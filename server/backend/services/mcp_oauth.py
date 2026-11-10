"""Small, database-backed OAuth authorization server for remote MCP clients.

The SDK performs request parsing, PKCE verification, redirect validation, and
RFC 8414/RFC 9728 route generation. This provider stores only digests of
authorization codes, access tokens, and refresh tokens. Clients are public
PKCE clients (``token_endpoint_auth_method=none``); confidential DCR clients
are rejected because the SDK's provider protocol requires returning the raw
client secret from ``get_client``.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import socket
import time
import uuid
from typing import Any
from urllib.parse import urlencode, urlsplit

import httpx
from app_settings import get_settings
from pydantic import AnyUrl
from pydantic import PrivateAttr, ValidationError

from db.database import execute, fetch_all, fetch_one, get_connection
from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    AuthorizeError,
    OAuthAuthorizationServerProvider,
    RefreshToken,
    TokenError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

from .mcp_credentials import MCP_SCOPE_READ, authenticate_token

OAUTH_SCOPES = [MCP_SCOPE_READ]
OAUTH_CODE_TTL = 300
OAUTH_ACCESS_TTL = 3600
OAUTH_REFRESH_TTL = 30 * 24 * 3600
CIMD_CACHE_TTL = 60
CIMD_CACHE_MAX_ENTRIES = 256
CIMD_MAX_DOCUMENT_BYTES = 16 * 1024
CIMD_MAX_REDIRECTS = 3
CIMD_TIMEOUT_SECONDS = 3.0
CHATGPT_CIMD_CLIENT_ID = "https://chatgpt.com/oauth/client.json"
CHATGPT_CIMD_REDIRECT_URI = "https://chatgpt.com/connector_platform_oauth_redirect"
CONSENT_CSRF_HMAC_DOMAIN = b"healthquery-consent-csrf-v1\0"

OAUTH_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS mcp_oauth_clients (
  client_id TEXT PRIMARY KEY,
  metadata_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS mcp_oauth_requests (
  state_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  client_state TEXT,
  expires_at REAL NOT NULL,
  subject TEXT,
  consent_csrf_hash TEXT,
  consent_csrf_expires_at REAL,
  consent_csrf_consumed_at TEXT
);
CREATE TABLE IF NOT EXISTS mcp_oauth_codes (
  code_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  subject TEXT,
  expires_at REAL NOT NULL,
  consumed_at TEXT
);
CREATE TABLE IF NOT EXISTS mcp_oauth_tokens (
  token_hash TEXT PRIMARY KEY,
  token_kind TEXT NOT NULL CHECK (token_kind IN ('access', 'refresh')),
  client_id TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  subject TEXT,
  expires_at REAL,
  revoked_at TEXT,
  related_hash TEXT
);
CREATE INDEX IF NOT EXISTS idx_mcp_oauth_tokens_active
  ON mcp_oauth_tokens (token_kind, revoked_at, expires_at);
"""


def oauth_issuer() -> str:
    return os.getenv("HEALTHQUERY_MCP_ISSUER_URL", "http://localhost:8000/mcp").strip().rstrip("/")


def oauth_resource() -> str:
    return os.getenv("HEALTHQUERY_MCP_RESOURCE_URL", oauth_issuer()).strip().rstrip("/")


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _constant_hash_match(value: str, digest: str) -> bool:
    return hmac.compare_digest(_hash(value), digest)


def _consent_csrf_token(state: str) -> str:
    """Derive a stable per-state token so duplicate consent GETs cannot rotate it."""

    signing_secret = get_settings().session_secret
    if not signing_secret:
        raise RuntimeError("Consent CSRF signing secret is not configured")
    digest = hmac.new(
        signing_secret.encode("utf-8"),
        CONSENT_CSRF_HMAC_DOMAIN + state.encode("utf-8"),
        hashlib.sha256,
    ).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


async def ensure_oauth_schema() -> None:
    conn = await get_connection()
    try:
        await conn.executescript(OAUTH_SCHEMA_SQL)
        columns = {
            row[1]
            for row in await (await conn.execute("PRAGMA table_info(mcp_oauth_requests)")).fetchall()
        }
        for name, column_type in (
            ("consent_csrf_hash", "TEXT"),
            ("consent_csrf_expires_at", "REAL"),
            ("consent_csrf_consumed_at", "TEXT"),
        ):
            if name not in columns:
                await conn.execute(f"ALTER TABLE mcp_oauth_requests ADD COLUMN {name} {column_type}")
        await conn.commit()
    finally:
        await conn.close()


def _client_from_row(row: dict[str, Any]) -> OAuthClientInformationFull:
    return OAuthClientInformationFull.model_validate(json.loads(row["metadata_json"]))


class _CIMDClientInformation(OAuthClientInformationFull):
    """OAuth client metadata loaded from a Client ID Metadata Document.

    The SDK's model parses redirect URLs as ``AnyUrl``. Keep the document's
    original strings as well so authorization requests are compared against the
    exact values published by the client (the SDK still performs the normal
    URL parsing and PKCE checks around this object).
    """

    _raw_redirect_uris: tuple[str, ...] = PrivateAttr(default=())

    def validate_redirect_uri(self, redirect_uri: AnyUrl | None) -> AnyUrl:
        from mcp.shared.auth import InvalidRedirectUriError

        if redirect_uri is not None:
            if str(redirect_uri) not in self._raw_redirect_uris:
                raise InvalidRedirectUriError(f"Redirect URI '{redirect_uri}' not registered for client")
            return redirect_uri
        if len(self._raw_redirect_uris) == 1:
            return AnyUrl(self._raw_redirect_uris[0])
        raise InvalidRedirectUriError(
            "redirect_uri must be specified unless the client has exactly one registered URI"
        )


_cimd_cache: dict[str, tuple[float, _CIMDClientInformation]] = {}


def _allow_test_localhost() -> bool:
    """Allow insecure loopback CIMD URLs only for an explicitly marked test."""

    return bool(os.getenv("PYTEST_CURRENT_TEST")) and os.getenv(
        "HEALTHQUERY_MCP_CIMD_ALLOW_TEST_LOCALHOST"
    ) == "1"


def _url_is_local_or_private(host: str) -> bool:
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_multicast
        or address.is_reserved
        or address.is_unspecified
    )


def _resolved_public_host(host: str) -> bool:
    """Reject names resolving to any private, loopback, or link-local address."""

    if _url_is_local_or_private(host):
        return False
    try:
        addresses = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except OSError:
        return False
    resolved = {entry[4][0] for entry in addresses if entry[4]}
    return bool(resolved) and not any(_url_is_local_or_private(address) for address in resolved)


def _validate_cimd_url(client_id: str, *, allow_localhost: bool = False) -> bool:
    try:
        parsed = urlsplit(client_id)
        host = parsed.hostname
    except ValueError:
        return False
    if parsed.scheme != "https" or not host or not parsed.path or parsed.path == "/":
        if not (
            allow_localhost
            and parsed.scheme == "http"
            and host in {"localhost", "127.0.0.1", "::1"}
            and parsed.path
            and parsed.path != "/"
        ):
            return False
    if parsed.username or parsed.password or parsed.fragment:
        return False
    if allow_localhost and host in {"localhost", "127.0.0.1", "::1"}:
        return True
    return _resolved_public_host(host)


async def _fetch_cimd_document(client_id: str) -> dict[str, Any]:
    """Fetch a bounded CIMD document with revalidated, same-host redirects."""

    current = client_id
    original_host = urlsplit(client_id).hostname
    timeout = httpx.Timeout(CIMD_TIMEOUT_SECONDS)
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, trust_env=False) as client:
        for _ in range(CIMD_MAX_REDIRECTS + 1):
            if not _validate_cimd_url(current, allow_localhost=_allow_test_localhost()):
                raise ValueError("CIMD URL is not a permitted public HTTPS URL")
            async with client.stream("GET", current) as response:
                if response.is_redirect:
                    location = response.headers.get("location")
                    if not location:
                        raise ValueError("CIMD redirect has no location")
                    redirected = str(httpx.URL(current).join(location))
                    if urlsplit(redirected).hostname != original_host:
                        raise ValueError("CIMD cross-host redirect is not permitted")
                    current = redirected
                    continue
                if response.status_code != 200:
                    raise ValueError("CIMD document fetch failed")
                content_length = response.headers.get("content-length")
                if content_length and int(content_length) > CIMD_MAX_DOCUMENT_BYTES:
                    raise ValueError("CIMD document is too large")
                chunks: list[bytes] = []
                size = 0
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > CIMD_MAX_DOCUMENT_BYTES:
                        raise ValueError("CIMD document is too large")
                    chunks.append(chunk)
            body = b"".join(chunks)
            document = json.loads(body)
            if not isinstance(document, dict):
                raise ValueError("CIMD document must be a JSON object")
            return document
    raise ValueError("Too many CIMD redirects")


def _built_in_cimd_document(client_id: str) -> dict[str, Any] | None:
    """Return the exact public ChatGPT profile used by the no-egress service."""

    if client_id != CHATGPT_CIMD_CLIENT_ID:
        return None
    return {
        "client_id": CHATGPT_CIMD_CLIENT_ID,
        "client_name": "ChatGPT",
        "redirect_uris": [CHATGPT_CIMD_REDIRECT_URI],
        "token_endpoint_auth_method": "none",
        "token_endpoint_auth_methods_supported": ["none"],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "scope": MCP_SCOPE_READ,
    }


async def _client_from_cimd(client_id: str) -> _CIMDClientInformation | None:
    now = time.monotonic()
    cached = _cimd_cache.get(client_id)
    if cached and cached[0] > now:
        return cached[1]
    if cached:
        _cimd_cache.pop(client_id, None)
    try:
        document = _built_in_cimd_document(client_id)
        if document is None:
            if not _validate_cimd_url(client_id, allow_localhost=_allow_test_localhost()):
                return None
            document = await _fetch_cimd_document(client_id)
        if not isinstance(document, dict):
            return None
        if not isinstance(document.get("client_id"), str) or document["client_id"] != client_id:
            return None
        if not isinstance(document.get("client_name"), str) or not document["client_name"].strip():
            return None
        redirect_uris = document.get("redirect_uris")
        if (
            not isinstance(redirect_uris, list)
            or not redirect_uris
            or not all(isinstance(uri, str) and uri and not urlsplit(uri).fragment for uri in redirect_uris)
        ):
            return None
        auth_methods = document.get("token_endpoint_auth_methods_supported")
        if auth_methods is not None:
            if (
                not isinstance(auth_methods, list)
                or not all(isinstance(method, str) for method in auth_methods)
                or "none" not in auth_methods
            ):
                return None
        elif document.get("token_endpoint_auth_method") != "none":
            return None
        if document.get("client_secret") not in (None, ""):
            return None
        grant_types = document.get("grant_types", ["authorization_code"])
        response_types = document.get("response_types", ["code"])
        scope = document.get("scope")
        if scope is None:
            document = {**document, "scope": MCP_SCOPE_READ}
        elif (
            not isinstance(scope, str)
            or not scope.strip()
            or not set(scope.split()).issubset(set(OAUTH_SCOPES))
        ):
            return None
        if not isinstance(grant_types, list) or "authorization_code" not in grant_types:
            return None
        if not isinstance(response_types, list) or "code" not in response_types:
            return None
        # During the CIMD transition ChatGPT publishes a plural capability
        # list and may advertise ``private_key_jwt`` as its legacy singular
        # preference. Select the supported intersection explicitly: this
        # authorization server is a public PKCE client and supports ``none``.
        client = _CIMDClientInformation.model_validate(
            {
                **document,
                "client_id": client_id,
                "client_secret": None,
                "token_endpoint_auth_method": "none",
            }
        )
        client._raw_redirect_uris = tuple(redirect_uris)
    except (ValueError, TypeError, ValidationError, httpx.HTTPError, json.JSONDecodeError, OSError):
        return None
    for key, (expires_at, _) in list(_cimd_cache.items()):
        if expires_at <= now:
            _cimd_cache.pop(key, None)
    while len(_cimd_cache) >= CIMD_CACHE_MAX_ENTRIES:
        oldest_key = min(_cimd_cache, key=lambda key: _cimd_cache[key][0])
        _cimd_cache.pop(oldest_key, None)
    _cimd_cache[client_id] = (now + CIMD_CACHE_TTL, client)
    return client


class HealthQueryOAuthProvider(OAuthAuthorizationServerProvider[AuthorizationCode, RefreshToken, AccessToken]):
    async def consent_requires_inline_owner_auth(self, state: str) -> bool:
        """Return whether this unexpired state belongs to the exact ChatGPT client."""

        if not state:
            return False
        await ensure_oauth_schema()
        row = await fetch_one(
            """
            SELECT state_hash, client_id
            FROM mcp_oauth_requests
            WHERE state_hash = ? AND expires_at > ?
            """,
            (_hash(state), time.time()),
        )
        return bool(
            row
            and _constant_hash_match(state, row["state_hash"])
            and row["client_id"] == CHATGPT_CIMD_CLIENT_ID
        )

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        # CIMD client IDs are HTTPS URLs that point at self-hosted metadata. Keep
        # this path separate from DCR records so existing UUID clients continue
        # to work unchanged and no fetched document is persisted as a client.
        if client_id.lower().startswith(("https://", "http://")):
            return await _client_from_cimd(client_id)
        await ensure_oauth_schema()
        row = await fetch_one(
            "SELECT * FROM mcp_oauth_clients WHERE client_id = ? AND revoked_at IS NULL",
            (client_id,),
        )
        return _client_from_row(row) if row else None

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        await ensure_oauth_schema()
        if client_info.token_endpoint_auth_method not in (None, "none"):
            from mcp.server.auth.provider import RegistrationError

            raise RegistrationError(
                error="invalid_client_metadata",
                error_description="Only public PKCE clients (token_endpoint_auth_method=none) are supported",
            )
        requested = set((client_info.scope or "").split())
        if not requested.issubset(set(OAUTH_SCOPES)):
            from mcp.server.auth.provider import RegistrationError

            raise RegistrationError(error="invalid_client_metadata", error_description="Unsupported MCP scope")
        metadata = client_info.model_copy(update={"token_endpoint_auth_method": "none", "client_secret": None})
        await execute(
            "INSERT OR REPLACE INTO mcp_oauth_clients (client_id, metadata_json) VALUES (?, ?)",
            (client_info.client_id, metadata.model_dump_json()),
        )

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        if params.resource and params.resource.rstrip("/") != oauth_resource():
            raise AuthorizeError(error="invalid_target", error_description="Unsupported resource indicator")
        state = secrets.token_urlsafe(32)
        await ensure_oauth_schema()
        await execute(
            """
            INSERT INTO mcp_oauth_requests
              (state_hash, client_id, redirect_uri, code_challenge, scopes_json, resource, client_state, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                _hash(state),
                client.client_id,
                str(params.redirect_uri),
                params.code_challenge,
                json.dumps(params.scopes or [MCP_SCOPE_READ], separators=(",", ":")),
                params.resource or oauth_resource(),
                params.state,
                time.time() + OAUTH_CODE_TTL,
            ),
        )
        return f"{oauth_issuer()}/oauth/consent?{urlencode({'state': state})}"

    async def approve(self, state: str, subject: str = "browser") -> str:
        await ensure_oauth_schema()
        row = await fetch_one(
            "SELECT * FROM mcp_oauth_requests WHERE state_hash = ? AND expires_at > ?",
            (_hash(state), time.time()),
        )
        if not row or not _constant_hash_match(state, row["state_hash"]):
            raise ValueError("OAuth request is missing or expired")
        code = secrets.token_urlsafe(32)
        scopes = json.loads(row["scopes_json"])
        await execute(
            """
            INSERT INTO mcp_oauth_codes
              (code_hash, client_id, redirect_uri, code_challenge, scopes_json, resource, subject, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (_hash(code), row["client_id"], row["redirect_uri"], row["code_challenge"], json.dumps(scopes), row["resource"], subject, time.time() + OAUTH_CODE_TTL),
        )
        await execute("DELETE FROM mcp_oauth_requests WHERE state_hash = ?", (row["state_hash"],))
        return construct_redirect_uri(
            row["redirect_uri"],
            code=code,
            state=row["client_state"],
            iss=oauth_issuer(),
        )

    async def issue_consent_csrf(self, state: str) -> str | None:
        await ensure_oauth_schema()
        row = await fetch_one(
            "SELECT state_hash, expires_at FROM mcp_oauth_requests WHERE state_hash = ? AND expires_at > ?",
            (_hash(state), time.time()),
        )
        if not row or not _constant_hash_match(state, row["state_hash"]):
            return None
        token = _consent_csrf_token(state)
        expires_at = min(float(row["expires_at"]), time.time() + OAUTH_CODE_TTL)
        await execute(
            """
            UPDATE mcp_oauth_requests
            SET consent_csrf_hash = ?, consent_csrf_expires_at = ?, consent_csrf_consumed_at = NULL
            WHERE state_hash = ? AND expires_at > ?
            """,
            (_hash(token), expires_at, row["state_hash"], time.time()),
        )
        return token

    async def verify_consent_csrf(self, state: str, token: str) -> bool:
        if not state or not token:
            return False
        await ensure_oauth_schema()
        row = await fetch_one(
            """
            SELECT state_hash, consent_csrf_hash
            FROM mcp_oauth_requests
            WHERE state_hash = ? AND expires_at > ?
              AND consent_csrf_hash IS NOT NULL
              AND consent_csrf_expires_at > ?
              AND consent_csrf_consumed_at IS NULL
            """,
            (_hash(state), time.time(), time.time()),
        )
        return bool(
            row
            and _constant_hash_match(state, row["state_hash"])
            and _constant_hash_match(token, row["consent_csrf_hash"])
        )

    async def consume_consent_csrf(self, state: str, token: str) -> bool:
        if not await self.verify_consent_csrf(state, token):
            return False
        conn = await get_connection()
        try:
            cursor = await conn.execute(
                """
                UPDATE mcp_oauth_requests
                SET consent_csrf_consumed_at = CURRENT_TIMESTAMP
                WHERE state_hash = ? AND consent_csrf_hash = ?
                  AND expires_at > ? AND consent_csrf_expires_at > ?
                  AND consent_csrf_consumed_at IS NULL
                """,
                (_hash(state), _hash(token), time.time(), time.time()),
            )
            await conn.commit()
            return cursor.rowcount == 1
        finally:
            await conn.close()

    async def load_authorization_code(self, client: OAuthClientInformationFull, authorization_code: str) -> AuthorizationCode | None:
        await ensure_oauth_schema()
        row = await fetch_one(
            "SELECT * FROM mcp_oauth_codes WHERE code_hash = ? AND client_id = ? AND consumed_at IS NULL",
            (_hash(authorization_code), client.client_id),
        )
        if not row or not _constant_hash_match(authorization_code, row["code_hash"]):
            return None
        if float(row["expires_at"]) < time.time():
            return None
        return AuthorizationCode(
            code=authorization_code,
            scopes=json.loads(row["scopes_json"]),
            expires_at=float(row["expires_at"]),
            client_id=row["client_id"],
            code_challenge=row["code_challenge"],
            redirect_uri=AnyUrl(row["redirect_uri"]),
            redirect_uri_provided_explicitly=True,
            resource=row["resource"],
            subject=row["subject"],
        )

    async def exchange_authorization_code(self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode) -> OAuthToken:
        await ensure_oauth_schema()
        changed = await execute(
            "UPDATE mcp_oauth_codes SET consumed_at = CURRENT_TIMESTAMP WHERE code_hash = ? AND consumed_at IS NULL",
            (_hash(authorization_code.code),),
        )
        if changed != 1:
            raise TokenError(error="invalid_grant", error_description="Authorization code already used")
        return await self._issue_tokens(client.client_id, authorization_code.scopes, authorization_code.resource, authorization_code.subject)

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> RefreshToken | None:
        await ensure_oauth_schema()
        row = await fetch_one(
            "SELECT * FROM mcp_oauth_tokens WHERE token_hash = ? AND token_kind = 'refresh' AND client_id = ? AND revoked_at IS NULL",
            (_hash(refresh_token), client.client_id),
        )
        if not row or not _constant_hash_match(refresh_token, row["token_hash"]):
            return None
        if row["expires_at"] and float(row["expires_at"]) < time.time():
            return None
        return RefreshToken(token=refresh_token, client_id=row["client_id"], scopes=json.loads(row["scopes_json"]), expires_at=int(row["expires_at"]) if row["expires_at"] else None, subject=row["subject"])

    async def exchange_refresh_token(self, client: OAuthClientInformationFull, refresh_token: RefreshToken, scopes: list[str]) -> OAuthToken:
        await execute("UPDATE mcp_oauth_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ? AND revoked_at IS NULL", (_hash(refresh_token.token),))
        return await self._issue_tokens(client.client_id, scopes, None, refresh_token.subject)

    async def load_access_token(self, token: str) -> AccessToken | None:
        await ensure_oauth_schema()
        row = await fetch_one("SELECT * FROM mcp_oauth_tokens WHERE token_hash = ? AND token_kind = 'access' AND revoked_at IS NULL", (_hash(token),))
        if row and _constant_hash_match(token, row["token_hash"]):
            if row["expires_at"] and float(row["expires_at"]) < time.time():
                return None
            return AccessToken(token=token, client_id=row["client_id"], scopes=json.loads(row["scopes_json"]), expires_at=int(row["expires_at"]) if row["expires_at"] else None, resource=row["resource"], subject=row["subject"], claims={"iss": oauth_issuer()})

        # Keep independently revocable legacy PATs working through the same
        # official SDK verifier used by OAuth access tokens.  The credential
        # service performs the audit/counter update only after a hash match.
        credential = await authenticate_token(token)
        if credential is None:
            return None
        return AccessToken(
            token=token,
            client_id=f"pat:{credential.id}",
            scopes=sorted(credential.scopes),
            resource=oauth_resource(),
            subject=credential.id,
            claims={"iss": oauth_issuer(), "token_kind": "pat"},
        )

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        await ensure_oauth_schema()
        await execute("UPDATE mcp_oauth_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ? OR related_hash = ?", (_hash(token.token), _hash(token.token)))

    async def _issue_tokens(self, client_id: str, scopes: list[str], resource: str | None, subject: str | None) -> OAuthToken:
        access = secrets.token_urlsafe(32)
        refresh = secrets.token_urlsafe(32)
        access_hash = _hash(access)
        refresh_hash = _hash(refresh)
        now = time.time()
        await execute(
            "INSERT INTO mcp_oauth_tokens (token_hash, token_kind, client_id, scopes_json, resource, subject, expires_at, related_hash) VALUES (?, 'access', ?, ?, ?, ?, ?, ?)",
            (access_hash, client_id, json.dumps(scopes), resource or oauth_resource(), subject, now + OAUTH_ACCESS_TTL, refresh_hash),
        )
        await execute(
            "INSERT INTO mcp_oauth_tokens (token_hash, token_kind, client_id, scopes_json, resource, subject, expires_at, related_hash) VALUES (?, 'refresh', ?, ?, ?, ?, ?, ?)",
            (refresh_hash, client_id, json.dumps(scopes), resource or oauth_resource(), subject, now + OAUTH_REFRESH_TTL, access_hash),
        )
        return OAuthToken(access_token=access, expires_in=OAUTH_ACCESS_TTL, scope=" ".join(scopes), refresh_token=refresh)


oauth_provider = HealthQueryOAuthProvider()
