"""ASGI bearer authentication for the remote MCP endpoint."""

from __future__ import annotations

import contextvars
import json
import logging
import os
from collections.abc import Awaitable, Callable
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit

from mcp.server.auth.provider import AccessToken

from .mcp_credentials import MCPCredential, authenticate_token, credential_has_scope
from .mcp_oauth import oauth_issuer, oauth_provider, oauth_resource

CURRENT_MCP_CREDENTIAL: contextvars.ContextVar[MCPCredential | None] = contextvars.ContextVar(
    "current_mcp_credential", default=None
)

logger = logging.getLogger(__name__)


def _bearer_token(value: str) -> str | None:
    scheme, _, token = value.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def current_credential() -> MCPCredential:
    credential = CURRENT_MCP_CREDENTIAL.get()
    if credential is None:
        raise PermissionError("MCP authentication is required")
    return credential


def require_scope(scope: str) -> MCPCredential:
    credential = current_credential()
    if not credential_has_scope(credential, scope):
        raise PermissionError(f"MCP credential lacks required scope: {scope}")
    return credential


def _origin_allowed(scope: dict[str, Any]) -> bool:
    origin = _header(scope, "origin")
    if not origin:
        return True
    allowed = [value.strip().rstrip("/") for value in os.getenv("HEALTHQUERY_MCP_ALLOWED_ORIGINS", "").split(",") if value.strip()]
    parsed_resource = urlsplit(oauth_resource())
    same_origin = f"{parsed_resource.scheme}://{parsed_resource.netloc}".rstrip("/")
    return origin.rstrip("/") == same_origin or origin.rstrip("/") in allowed


def _origin_class(scope: dict[str, Any]) -> str:
    origins = _header_values(scope, "origin")
    if len(origins) > 1:
        return "other"
    origin = origins[0].strip() if origins else ""
    if not origin:
        return "missing"
    if origin.lower() == "null":
        return "null"
    parsed_resource = urlsplit(oauth_resource())
    canonical = f"{parsed_resource.scheme}://{parsed_resource.netloc}".rstrip("/")
    normalized = origin.rstrip("/").lower()
    if normalized == canonical.lower():
        return "canonical"
    if origin == "https://chatgpt.com":
        return "chatgpt_com"
    if origin == "https://chat.openai.com":
        return "chat_openai_com"
    return "other"


def _fetch_metadata_class(scope: dict[str, Any]) -> str:
    value = _header(scope, "sec-fetch-site").strip().lower()
    return value if value in {"same-origin", "same-site", "cross-site", "none"} else ("missing" if not value else "other")


def _log_consent_rejection(scope: dict[str, Any], rejected_check: str) -> None:
    """Emit only bounded categories; never headers, form fields, state, or secrets."""

    if not _is_exact_consent_post(scope):
        return
    logger.warning(
        "oauth_consent_diagnostic origin_class=%s fetch_metadata_class=%s rejected_check=%s",
        _origin_class(scope),
        _fetch_metadata_class(scope),
        rejected_check,
    )


def _header(scope: dict[str, Any], name: str) -> str:
    values = _header_values(scope, name)
    return values[0] if values else ""


def _header_values(scope: dict[str, Any], name: str) -> list[str]:
    needle = name.lower().encode("latin-1")
    return [value.decode("latin-1") for key, value in scope.get("headers", []) if key.lower() == needle]


async def _validate_embedded_consent_request(
    scope: dict[str, Any], receive: Callable[..., Awaitable[Any]]
) -> tuple[dict[str, Any], Callable[..., Awaitable[Any]]] | None:
    """Validate the one narrow opaque-origin OAuth consent exception."""

    origin_class = _origin_class(scope)
    fetch_site = _header(scope, "sec-fetch-site").strip().lower()
    if origin_class not in {"null", "missing", "chatgpt_com", "chat_openai_com"}:
        _log_consent_rejection(scope, "origin")
        return None
    # ChatGPT has used both an opaque/null Origin and its exact public origins
    # for this top-level consent POST.  A direct external-browser handoff can
    # also classify the navigation as ``none``.  Keep the exception limited to
    # those closed classes.  Explicit ChatGPT origins may legitimately arrive
    # as cross-site; an opaque/missing origin may not.  Every accepted request
    # still needs the one-time state-bound CSRF token, owner password, and
    # explicit approve decision below.
    allowed_fetch_sites = (
        {"", "same-origin", "same-site", "none", "cross-site"}
        if origin_class in {"chatgpt_com", "chat_openai_com"}
        else {"", "same-origin", "same-site", "none"}
    )
    if fetch_site not in allowed_fetch_sites:
        _log_consent_rejection(scope, "origin")
        return None
    chunks: list[bytes] = []
    size = 0
    while True:
        message = await receive()
        if message.get("type") != "http.request":
            _log_consent_rejection(scope, "state")
            return None
        chunk = message.get("body", b"")
        size += len(chunk)
        if size > 16 * 1024:
            _log_consent_rejection(scope, "state")
            return None
        chunks.append(chunk)
        if not message.get("more_body", False):
            break
    body = b"".join(chunks)
    try:
        params = dict(parse_qsl(body.decode("utf-8"), keep_blank_values=True))
    except UnicodeDecodeError:
        _log_consent_rejection(scope, "state")
        return None
    state = params.get("state", "")
    csrf_token = params.get("csrf_token", "")
    password = params.get("password", "")
    if params.get("decision") != "approve":
        _log_consent_rejection(scope, "decision")
        return None
    if not state:
        _log_consent_rejection(scope, "state")
        return None
    if not csrf_token:
        _log_consent_rejection(scope, "csrf")
        return None
    if not password:
        _log_consent_rejection(scope, "password")
        return None
    from .browser_auth import verify_admin_password

    if not await verify_admin_password(password):
        _log_consent_rejection(scope, "password")
        return None
    if not await oauth_provider.verify_consent_csrf(state, csrf_token):
        _log_consent_rejection(scope, "csrf")
        return None

    adjusted = dict(scope)
    adjusted["healthquery.embedded_consent_validated"] = True
    delivered = False

    async def replay() -> dict[str, Any]:
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    return adjusted, replay


def _base_path(scope: dict[str, Any]) -> str:
    root = str(scope.get("root_path", "")).rstrip("/")
    return root or ""


def _is_exact_consent_post(scope: dict[str, Any]) -> bool:
    if str(scope.get("method", "")).upper() != "POST":
        return False
    root = str(scope.get("root_path", "")).rstrip("/")
    path = str(scope.get("path", ""))
    if path not in {"/oauth/consent", f"{root}/oauth/consent"}:
        return False
    raw_path = scope.get("raw_path", b"")
    root_path = root.encode("ascii", errors="ignore")
    if raw_path not in {b"", b"/oauth/consent", root_path + b"/oauth/consent"}:
        return False
    # The current form intentionally has no action attribute, so browsers
    # submit back to the document URL and retain its state query string.
    # This helper only scopes diagnostics; it must not tighten the established
    # opaque-origin acceptance path during the temporary diagnostic stage.
    return True


def _resource_url(scope: dict[str, Any]) -> str:
    # The canonical resource is configured and validated at startup. Never
    # construct security metadata from attacker-controlled Host/proxy headers.
    return oauth_resource()


def protected_resource_metadata(scope: dict[str, Any]) -> dict[str, Any]:
    """RFC 9728 metadata for interactive OAuth and explicit bearer PATs."""
    metadata = {
        "resource": _resource_url(scope),
        "authorization_servers": [oauth_issuer()],
        "bearer_methods_supported": ["header"],
        "scopes_supported": ["health:read"],
        "resource_documentation": f"{_resource_url(scope)}/docs",
    }
    return metadata


def protected_resource_metadata_uri(scope: dict[str, Any]) -> str:
    """RFC 9728 well-known URI (with path insertion for mounted resources)."""
    resource = _resource_url(scope)
    parsed = urlsplit(resource)
    return f"{parsed.scheme}://{parsed.netloc}/.well-known/oauth-protected-resource{parsed.path or ''}"


def authorization_server_metadata() -> dict[str, Any]:
    issuer = oauth_issuer()
    return {
        "issuer": issuer,
        "authorization_endpoint": f"{issuer}/authorize",
        "token_endpoint": f"{issuer}/token",
        "registration_endpoint": f"{issuer}/register",
        "revocation_endpoint": f"{issuer}/revoke",
        "scopes_supported": ["health:read"],
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "refresh_token"],
        "token_endpoint_auth_methods_supported": ["none"],
        "code_challenge_methods_supported": ["S256"],
        "client_id_metadata_document_supported": True,
        "authorization_response_iss_parameter_supported": True,
    }


async def send_json(send: Callable[..., Awaitable[None]], status: int, payload: dict[str, Any], headers: list[tuple[bytes, bytes]] | None = None) -> None:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    response_headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode("ascii"))]
    if headers:
        response_headers.extend(headers)
    await send({"type": "http.response.start", "status": status, "headers": response_headers})
    await send({"type": "http.response.body", "body": body})


async def _normalize_public_revocation_request(
    scope: dict[str, Any], receive: Callable[..., Awaitable[Any]]
) -> tuple[dict[str, Any], Callable[..., Awaitable[Any]]]:
    """Work around MCP SDK 2.0 requiring an optional public-client field.

    RFC 7009 public clients send ``client_id`` without a client secret. The
    SDK's request model types ``client_secret`` as optional but omits its
    default, which makes Pydantic reject a standards-shaped request. Supplying
    an empty value preserves the SDK's normal client/token validation.
    """
    chunks: list[bytes] = []
    while True:
        message = await receive()
        if message.get("type") != "http.request":
            return scope, receive
        chunks.append(message.get("body", b""))
        if not message.get("more_body", False):
            break
    body = b"".join(chunks)
    params = parse_qsl(body.decode("utf-8"), keep_blank_values=True)
    if any(key == "client_id" for key, _ in params) and not any(key == "client_secret" for key, _ in params):
        params.append(("client_secret", ""))
        body = urlencode(params).encode("utf-8")
        adjusted = dict(scope)
        adjusted["headers"] = [
            (key, str(len(body)).encode("ascii")) if key.lower() == b"content-length" else (key, value)
            for key, value in scope.get("headers", [])
        ]
        scope = adjusted

    delivered = False

    async def replay() -> dict[str, Any]:
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    return scope, replay


class MCPBearerMiddleware:
    """Authenticate all Streamable HTTP requests and expose auth metadata."""

    def __init__(self, app: Any):
        self.app = app
        # The MCP SDK's parent application must be included in the host
        # application's lifespan; expose it through the wrapper for the
        # integration snippet and for Starlette lifespan composition.
        self.lifespan = getattr(app, "lifespan", None)
        if self.lifespan is None and getattr(app, "router", None) is not None:
            self.lifespan = app.router.lifespan_context

    async def __call__(self, scope: dict[str, Any], receive: Callable[..., Awaitable[Any]], send: Callable[..., Awaitable[None]]) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        path = str(scope.get("path", ""))
        if (
            path.endswith("/.well-known/oauth-protected-resource")
            or path == "/.well-known/oauth-protected-resource"
            or path.startswith("/.well-known/oauth-protected-resource/")
        ):
            await send_json(send, 200, protected_resource_metadata(scope))
            return
        if path.endswith("/.well-known/oauth-authorization-server") or path == "/.well-known/oauth-authorization-server":
            await send_json(send, 200, authorization_server_metadata())
            return
        consent_post = scope.get("method") == "POST" and path.endswith("/oauth/consent")
        if not _origin_allowed(scope) or (consent_post and _origin_class(scope) == "missing"):
            embedded = await _validate_embedded_consent_request(scope, receive) if consent_post else None
            if embedded is None:
                await send_json(send, 403, {"detail": "Invalid Origin"})
                return
            scope, receive = embedded

        if scope.get("method") == "POST" and path.endswith("/revoke"):
            scope, receive = await _normalize_public_revocation_request(scope, receive)

        # The official SDK verifier handles both OAuth access tokens and the
        # legacy hashed PATs (the provider falls back to authenticate_token).
        # Keep this wrapper focused on metadata, Origin, and mounted-path
        # normalization so PAT audit counters are updated exactly once.
        inner_scope = scope
        if path in {"/mcp", "/mcp/"}:
            inner_scope = dict(scope)
            inner_scope["path"] = "/"
            inner_scope["raw_path"] = b"/"
        await self.app(inner_scope, receive, send)


class MCPTokenVerifier:
    """Official SDK verifier adapter for OAuth access tokens and legacy PATs."""

    async def verify_token(self, token: str) -> AccessToken | None:
        oauth_token = await oauth_provider.load_access_token(token)
        if oauth_token is not None:
            return oauth_token
        credential = await authenticate_token(token)
        if credential is None:
            return None
        return AccessToken(
            token=token,
            client_id=f"pat:{credential.id}",
            scopes=sorted(credential.scopes),
            resource=oauth_resource(),
            subject=credential.id,
            claims={"iss": oauth_resource()},
        )
