from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
from contextlib import asynccontextmanager

import httpx
import pytest
from db.database import execute, fetch_all, init_db
from fastapi import FastAPI
from mcp.server.auth.provider import AuthorizationParams
from mcp.shared.auth import (
    InvalidRedirectUriError,
    InvalidScopeError,
    OAuthClientInformationFull,
)
from pydantic import AnyUrl
from routers.mcp import create_mcp_app, metadata_router
from services import mcp_auth, mcp_oauth
from services.browser_auth import AUTH_STATE_KEY, SIGNING_SALT
from services.config_store import set_config_value
from services.mcp_oauth import oauth_provider
from services.seed_data import seed_sample_health_data


def test_consent_origin_diagnostic_uses_bounded_classes(monkeypatch):
    monkeypatch.setattr(mcp_auth, "oauth_resource", lambda: "https://health.example/mcp")

    def scope(origin=None, sec_fetch_site=None):
        headers = []
        if origin is not None:
            headers.append((b"origin", origin.encode("latin-1")))
        if sec_fetch_site is not None:
            headers.append((b"sec-fetch-site", sec_fetch_site.encode("latin-1")))
        return {"headers": headers}

    assert mcp_auth._origin_class(scope()) == "missing"
    assert mcp_auth._origin_class(scope("null")) == "null"
    assert mcp_auth._origin_class(scope("https://health.example")) == "canonical"
    assert mcp_auth._origin_class(scope("https://chatgpt.com")) == "chatgpt_com"
    assert mcp_auth._origin_class(scope("https://chat.openai.com")) == "chat_openai_com"
    assert mcp_auth._origin_class(scope("https://unexpected.example")) == "other"
    assert mcp_auth._fetch_metadata_class(scope()) == "missing"
    assert mcp_auth._fetch_metadata_class(scope(sec_fetch_site="same-origin")) == "same-origin"
    assert mcp_auth._fetch_metadata_class(scope(sec_fetch_site="cross-site")) == "cross-site"

    rejected_origins = [
        "http://chatgpt.com",
        "https://chatgpt.com:443",
        "https://chatgpt.com.",
        "https://sub.chatgpt.com",
        "https://chatgpt.com.evil.example",
        "https://user@chatgpt.com",
        "https://chatgpt.com/",
        "https://chatgpt.com, https://unexpected.example",
    ]
    for value in rejected_origins:
        assert mcp_auth._origin_class(scope(value)) == "other"
    duplicate = scope("https://chatgpt.com")
    duplicate["headers"].append((b"origin", b"https://unexpected.example"))
    assert mcp_auth._origin_class(duplicate) == "other"


def test_consent_rejection_log_contains_categories_only(monkeypatch, caplog):
    monkeypatch.setattr(mcp_auth, "oauth_resource", lambda: "https://health.example/mcp")
    secret_origin = "https://unexpected.example"
    secret_state = "state-must-never-be-logged"
    scope = {
        "method": "POST",
        "path": "/mcp/oauth/consent",
        "raw_path": b"/mcp/oauth/consent",
        "root_path": "/mcp",
        "query_string": b"state=redacted",
        "headers": [
            (b"origin", secret_origin.encode("ascii")),
            (b"sec-fetch-site", b"cross-site"),
            (b"x-secret-state", secret_state.encode("ascii")),
        ]
    }

    with caplog.at_level("WARNING"):
        mcp_auth._log_consent_rejection(scope, "origin")

    diagnostic = caplog.messages[-1]
    assert diagnostic == (
        "oauth_consent_diagnostic origin_class=other "
        "fetch_metadata_class=cross-site rejected_check=origin"
    )
    assert secret_origin not in diagnostic
    assert secret_state not in diagnostic


@pytest.mark.parametrize(
    ("method", "path", "raw_path", "query_string", "expected"),
    [
        ("POST", "/oauth/consent", b"/oauth/consent", b"", True),
        ("POST", "/oauth/consent", b"/mcp/oauth/consent", b"", True),
        ("POST", "/oauth/consent/", b"/oauth/consent/", b"", False),
        ("POST", "/OAuth/consent", b"/OAuth/consent", b"", False),
        ("POST", "/oauth/consent", b"/oauth/%63onsent", b"", False),
        ("POST", "//oauth/consent", b"//oauth/consent", b"", False),
        ("POST", "/oauth/consent", b"/oauth/consent", b"state=x", True),
        ("POST", "/oauth/consent;v=1", b"/oauth/consent;v=1", b"", False),
        ("HEAD", "/oauth/consent", b"/oauth/consent", b"", False),
        ("GET", "/oauth/consent", b"/oauth/consent", b"", False),
        ("PUT", "/oauth/consent", b"/oauth/consent", b"", False),
        ("POST", "/oauth/token", b"/oauth/token", b"", False),
        ("POST", "/tools", b"/tools", b"", False),
    ],
)
def test_consent_diagnostic_requires_exact_route(
    method, path, raw_path, query_string, expected
):
    assert mcp_auth._is_exact_consent_post(
        {
            "method": method,
            "path": path,
            "raw_path": raw_path,
            "query_string": query_string,
            "root_path": "/mcp" if raw_path.startswith(b"/mcp/") else "",
        }
    ) is expected


def test_consent_csrf_hmac_domain_is_separate_from_browser_session(monkeypatch):
    signing_secret = "test-domain-separation-" + "s" * 48
    monkeypatch.setenv("HEALTHQUERY_SESSION_SECRET", signing_secret)
    state = "fixed-test-oauth-state"
    consent_token = mcp_oauth._consent_csrf_token(state)
    browser_domain = SIGNING_SALT.encode("utf-8") + b"\0"
    browser_probe = base64.urlsafe_b64encode(
        hmac.new(
            signing_secret.encode("utf-8"),
            browser_domain + state.encode("utf-8"),
            hashlib.sha256,
        ).digest()
    ).decode("ascii").rstrip("=")
    assert mcp_oauth.CONSENT_CSRF_HMAC_DOMAIN != browser_domain
    assert consent_token != browser_probe


@asynccontextmanager
async def _app():
    mcp_app = create_mcp_app()

    @asynccontextmanager
    async def lifespan(app):
        async with mcp_app.lifespan(app):
            yield

    app = FastAPI(lifespan=lifespan)
    app.include_router(metadata_router)
    app.mount("/mcp", mcp_app)
    async with lifespan(app):
        yield app


@pytest.mark.asyncio
async def test_oauth_discovery_pkce_token_tool_refresh_and_revoke(monkeypatch):
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "http://testserver/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "http://testserver/mcp")
    await init_db()
    await seed_sample_health_data()
    token_columns = {row["name"] for row in await fetch_all("PRAGMA table_info(mcp_oauth_tokens)")}
    assert "token" not in token_columns
    assert "state" not in {row["name"] for row in await fetch_all("PRAGMA table_info(mcp_oauth_requests)")}
    async with _app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            metadata = await client.get("/mcp/.well-known/oauth-authorization-server")
            assert metadata.status_code == 200
            assert metadata.json()["code_challenge_methods_supported"] == ["S256"]
            assert metadata.json()["token_endpoint_auth_methods_supported"] == ["none"]
            assert metadata.json()["client_id_metadata_document_supported"] is True

            registration = await client.post(
                "/mcp/register",
                json={
                    "redirect_uris": ["http://127.0.0.1/callback"],
                    "token_endpoint_auth_method": "none",
                    "scope": "health:read",
                    "grant_types": ["authorization_code", "refresh_token"],
                    "response_types": ["code"],
                },
            )
            assert registration.status_code == 201
            client_info = registration.json()
            client_record = OAuthClientInformationFull.model_validate(client_info)

            verifier = secrets.token_urlsafe(32)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
            authorization_redirect = await oauth_provider.authorize(
                client_record,
                AuthorizationParams(
                    state="client-state",
                    scopes=["health:read"],
                    code_challenge=challenge,
                    redirect_uri=AnyUrl("http://127.0.0.1/callback"),
                    redirect_uri_provided_explicitly=True,
                    resource="http://testserver/mcp",
                ),
            )
            state = authorization_redirect.split("state=", 1)[1]
            callback = await oauth_provider.approve(state)
            assert "iss=http%3A%2F%2Ftestserver%2Fmcp" in callback
            code = callback.split("code=", 1)[1].split("&", 1)[0]
            token_response = await client.post(
                "/mcp/token",
                data={
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": "http://127.0.0.1/callback",
                    "client_id": client_record.client_id,
                    "code_verifier": verifier,
                },
            )
            assert token_response.status_code == 200
            tokens = token_response.json()
            headers = {"Authorization": f"Bearer {tokens['access_token']}", "Accept": "application/json, text/event-stream"}
            initialize = await client.post(
                "/mcp/",
                headers=headers,
                json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "oauth", "version": "1"}}},
            )
            assert initialize.status_code == 200
            call = await client.post(
                "/mcp/",
                headers=headers,
                json={"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "list_data_types", "arguments": {}}},
            )
            assert call.status_code == 200
            assert '"data_types"' in call.text

            refreshed = await client.post(
                "/mcp/token",
                data={"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"], "client_id": client_record.client_id},
            )
            assert refreshed.status_code == 200
            revoked = await client.post("/mcp/revoke", data={"token": refreshed.json()["access_token"], "client_id": client_record.client_id})
            assert revoked.status_code == 200


@pytest.mark.asyncio
async def test_authorization_server_discovery_supports_path_insertion_and_compatibility(monkeypatch):
    issuer = "https://health.example.com/mcp"
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", issuer)
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", issuer)

    async with _app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url=issuer.removesuffix("/mcp")) as client:
            for path in (
                "/.well-known/oauth-authorization-server/mcp",
                "/mcp/.well-known/oauth-authorization-server",
            ):
                metadata = await client.get(path)
                assert metadata.status_code == 200
                assert metadata.headers["content-type"].split(";", 1)[0] == "application/json"
                payload = metadata.json()
                assert payload["issuer"] == issuer
                assert payload["authorization_endpoint"] == f"{issuer}/authorize"
                assert payload["token_endpoint"] == f"{issuer}/token"
                assert payload["registration_endpoint"] == f"{issuer}/register"
                assert payload["revocation_endpoint"] == f"{issuer}/revoke"


@pytest.mark.asyncio
async def test_cimd_loads_public_metadata_and_requires_exact_redirect(monkeypatch):
    mcp_oauth._cimd_cache.clear()
    client_id = "https://example.com/oauth/client-metadata.json"
    # Keep the unit test independent of local DNS/Fake-IP behavior; the
    # dedicated fetch tests cover public-address and SSRF validation.
    monkeypatch.setattr(mcp_oauth, "_resolved_public_host", lambda _: True)

    async def fake_fetch(_):
        return {
            "client_id": client_id,
            "client_name": "Example client",
            "redirect_uris": ["http://127.0.0.1:43123/callback"],
            "grant_types": ["authorization_code"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "none",
        }

    monkeypatch.setattr(
        mcp_oauth,
        "_fetch_cimd_document",
        fake_fetch,
    )
    client = await oauth_provider.get_client(client_id)
    assert client is not None
    assert client.client_id == client_id
    assert client.client_secret is None
    assert client.validate_scope("health:read") == ["health:read"]
    with pytest.raises(InvalidScopeError):
        client.validate_scope("health:sql")
    assert str(client.validate_redirect_uri(AnyUrl("http://127.0.0.1:43123/callback"))) == "http://127.0.0.1:43123/callback"
    with pytest.raises(InvalidRedirectUriError):
        client.validate_redirect_uri(AnyUrl("http://127.0.0.1:43123/callback/"))


@pytest.mark.asyncio
async def test_cimd_selects_public_pkce_from_plural_auth_methods(monkeypatch):
    mcp_oauth._cimd_cache.clear()
    client_id = "https://client.example/oauth/chatgpt-shaped.json"
    monkeypatch.setattr(mcp_oauth, "_resolved_public_host", lambda _: True)

    async def fake_fetch(_):
        return {
            "client_id": client_id,
            "client_name": "ChatGPT",
            "redirect_uris": ["https://chatgpt.com/connector_platform_oauth_redirect"],
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "private_key_jwt",
            "token_endpoint_auth_methods_supported": ["none", "private_key_jwt"],
        }

    monkeypatch.setattr(mcp_oauth, "_fetch_cimd_document", fake_fetch)
    client = await mcp_oauth.oauth_provider.get_client(client_id)
    assert client is not None
    assert client.token_endpoint_auth_method == "none"
    assert client.client_secret is None
    assert client.validate_scope("health:read") == ["health:read"]


@pytest.mark.asyncio
async def test_builtin_chatgpt_cimd_is_exact_public_profile_without_network(monkeypatch):
    mcp_oauth._cimd_cache.clear()

    async def fail_fetch(_):
        raise AssertionError("the exact built-in ChatGPT profile must not use outbound network")

    monkeypatch.setattr(mcp_oauth, "_fetch_cimd_document", fail_fetch)
    client = await mcp_oauth.oauth_provider.get_client(mcp_oauth.CHATGPT_CIMD_CLIENT_ID)

    assert client is not None
    assert client.client_id == mcp_oauth.CHATGPT_CIMD_CLIENT_ID
    assert client.token_endpoint_auth_method == "none"
    assert client.client_secret is None
    assert client.validate_scope("health:read") == ["health:read"]
    with pytest.raises(InvalidScopeError):
        client.validate_scope("health:read private_key_jwt")
    assert str(client.validate_redirect_uri(AnyUrl(mcp_oauth.CHATGPT_CIMD_REDIRECT_URI))) == mcp_oauth.CHATGPT_CIMD_REDIRECT_URI
    with pytest.raises(InvalidRedirectUriError):
        client.validate_redirect_uri(AnyUrl(mcp_oauth.CHATGPT_CIMD_REDIRECT_URI + "/extra"))

    profile = mcp_oauth._built_in_cimd_document(mcp_oauth.CHATGPT_CIMD_CLIENT_ID)
    assert profile is not None
    assert profile["token_endpoint_auth_methods_supported"] == ["none"]
    assert "client_secret" not in profile
    assert "private_key_jwt" not in profile["token_endpoint_auth_methods_supported"]


@pytest.mark.asyncio
async def test_builtin_chatgpt_cimd_rejects_near_match_client_id(monkeypatch):
    mcp_oauth._cimd_cache.clear()
    fetched = []
    monkeypatch.setattr(mcp_oauth, "_resolved_public_host", lambda _: True)

    async def fake_fetch(client_id):
        fetched.append(client_id)
        raise ValueError("not available")

    monkeypatch.setattr(mcp_oauth, "_fetch_cimd_document", fake_fetch)
    near_match = mcp_oauth.CHATGPT_CIMD_CLIENT_ID + "?copy=1"
    assert mcp_oauth._built_in_cimd_document(near_match) is None
    assert await mcp_oauth.oauth_provider.get_client(near_match) is None
    assert fetched == [near_match]


@pytest.mark.asyncio
async def test_cimd_rejects_mismatch_and_ssrf_urls(monkeypatch):
    mcp_oauth._cimd_cache.clear()
    client_id = "https://example.com/oauth/client-metadata.json"

    async def fake_fetch(_):
        return {
            "client_id": "https://attacker.example/client.json",
            "client_name": "Wrong identity",
            "redirect_uris": ["http://127.0.0.1/callback"],
            "token_endpoint_auth_method": "none",
        }

    monkeypatch.setattr(
        mcp_oauth,
        "_fetch_cimd_document",
        fake_fetch,
    )
    assert await oauth_provider.get_client(client_id) is None
    assert await oauth_provider.get_client("http://127.0.0.1/client.json") is None
    assert await oauth_provider.get_client("https://user:pass@example.com/client.json") is None
    assert await oauth_provider.get_client("https://example.com/client.json#fragment") is None


@pytest.mark.asyncio
async def test_cimd_fetch_handles_redirects_and_bounded_body(monkeypatch):
    monkeypatch.setattr(mcp_oauth, "_resolved_public_host", lambda _: True)
    real_async_client = httpx.AsyncClient
    requests: list[str] = []

    async def handler(request):
        requests.append(str(request.url))
        if request.url.path == "/start":
            return httpx.Response(302, headers={"location": "/client.json"}, request=request)
        return httpx.Response(200, content=b'{"ok": true}', request=request)

    transport = httpx.MockTransport(handler)

    def make_client(*args, **kwargs):
        return real_async_client(*args, transport=transport, **kwargs)

    monkeypatch.setattr(mcp_oauth.httpx, "AsyncClient", make_client)
    assert await mcp_oauth._fetch_cimd_document("https://example.com/start") == {"ok": True}
    assert requests == ["https://example.com/start", "https://example.com/client.json"]

    async def cross_host_handler(request):
        return httpx.Response(
            302,
            headers={"location": "https://other.example/client.json"},
            request=request,
        )

    monkeypatch.setattr(mcp_oauth.httpx, "AsyncClient", lambda *args, **kwargs: real_async_client(
        *args, transport=httpx.MockTransport(cross_host_handler), **kwargs
    ))
    with pytest.raises(ValueError, match="cross-host"):
        await mcp_oauth._fetch_cimd_document("https://example.com/start")

    async def oversized_handler(request):
        return httpx.Response(
            200,
            content=b"x" * (mcp_oauth.CIMD_MAX_DOCUMENT_BYTES + 1),
            request=request,
        )

    monkeypatch.setattr(mcp_oauth.httpx, "AsyncClient", lambda *args, **kwargs: real_async_client(
        *args, transport=httpx.MockTransport(oversized_handler), **kwargs
    ))
    with pytest.raises(ValueError, match="too large"):
        await mcp_oauth._fetch_cimd_document("https://example.com/large.json")


@pytest.mark.asyncio
async def test_oauth_consent_inline_password_preserves_state(monkeypatch):
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "http://testserver/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "http://testserver/mcp")
    await init_db()
    await set_config_value(
        AUTH_STATE_KEY,
        {"enrolled": True, "session_secret": secrets.token_urlsafe(48)},
    )
    async with _app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            registration = await client.post(
                "/mcp/register",
                json={"redirect_uris": ["http://127.0.0.1/callback"], "token_endpoint_auth_method": "none"},
            )
            assert registration.status_code == 201
            client_record = OAuthClientInformationFull.model_validate(registration.json())
            verifier = secrets.token_urlsafe(32)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
            authorization_redirect = await oauth_provider.authorize(
                client_record,
                AuthorizationParams(
                    state="client-state",
                    scopes=["health:read"],
                    code_challenge=challenge,
                    redirect_uri=AnyUrl("http://127.0.0.1/callback"),
                    redirect_uri_provided_explicitly=True,
                    resource="http://testserver/mcp",
                ),
            )
            state = authorization_redirect.split("state=", 1)[1]
            consent = await client.get(f"/mcp/oauth/consent?state={state}")
            assert consent.status_code == 200
            assert "Owner password" in consent.text
            assert "Authenticator" not in consent.text
            assert "TOTP" not in consent.text

            logged_in = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "password": "correct horse battery staple",
                },
                headers={"Origin": "http://testserver"},
                follow_redirects=False,
            )
            assert logged_in.status_code == 303
            assert "healthquery_session=" in logged_in.headers["set-cookie"]
            assert logged_in.headers["location"].startswith("/mcp/oauth/consent?state=")

            consent = await client.get(logged_in.headers["location"])
            assert consent.status_code == 200
            assert "Allow read access" in consent.text
            approved = await client.post(
                "/mcp/oauth/consent",
                data={"state": state, "decision": "approve"},
                headers={"Origin": "http://testserver"},
                follow_redirects=False,
            )
            assert approved.status_code == 302
            assert "code=" in approved.headers["location"]


@pytest.mark.asyncio
async def test_chatgpt_consent_keeps_inline_owner_auth_with_valid_session(monkeypatch):
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "http://testserver/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "http://testserver/mcp")
    await init_db()
    session_secret = secrets.token_urlsafe(48)
    await set_config_value(
        AUTH_STATE_KEY,
        {"enrolled": True, "session_secret": session_secret},
    )
    async with _app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            ordinary = await client.post(
                "/mcp/register",
                json={"redirect_uris": ["http://127.0.0.1/callback"], "token_endpoint_auth_method": "none"},
            )
            assert ordinary.status_code == 201
            ordinary_client = OAuthClientInformationFull.model_validate(ordinary.json())
            verifier = secrets.token_urlsafe(32)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")

            ordinary_redirect = await oauth_provider.authorize(
                ordinary_client,
                AuthorizationParams(
                    state="ordinary-client-state",
                    scopes=["health:read"],
                    code_challenge=challenge,
                    redirect_uri=AnyUrl("http://127.0.0.1/callback"),
                    redirect_uri_provided_explicitly=True,
                    resource="http://testserver/mcp",
                ),
            )
            ordinary_state = ordinary_redirect.split("state=", 1)[1]
            logged_in = await client.post(
                "/mcp/oauth/consent",
                data={"state": ordinary_state, "password": "correct horse battery staple"},
                headers={"Origin": "http://testserver"},
                follow_redirects=False,
            )
            assert logged_in.status_code == 303

            ordinary_consent = await client.get(logged_in.headers["location"])
            assert ordinary_consent.status_code == 200
            assert "Allow read access" in ordinary_consent.text
            assert "Owner password" not in ordinary_consent.text
            assert 'name="csrf_token"' not in ordinary_consent.text

            chatgpt_client = await oauth_provider.get_client(mcp_oauth.CHATGPT_CIMD_CLIENT_ID)
            assert chatgpt_client is not None
            chatgpt_redirect = await oauth_provider.authorize(
                chatgpt_client,
                AuthorizationParams(
                    state="chatgpt-client-state",
                    scopes=["health:read"],
                    code_challenge=challenge,
                    redirect_uri=AnyUrl(mcp_oauth.CHATGPT_CIMD_REDIRECT_URI),
                    redirect_uri_provided_explicitly=True,
                    resource="http://testserver/mcp",
                ),
            )
            chatgpt_state = chatgpt_redirect.split("state=", 1)[1]
            assert await oauth_provider.consent_requires_inline_owner_auth(chatgpt_state) is True
            assert await oauth_provider.consent_requires_inline_owner_auth(ordinary_state) is False

            chatgpt_consent = await client.get(f"/mcp/oauth/consent?state={chatgpt_state}")
            assert chatgpt_consent.status_code == 200
            assert "Owner password" in chatgpt_consent.text
            assert 'name="csrf_token"' in chatgpt_consent.text
            assert '<form method="post">' in chatgpt_consent.text
            assert "action=" not in chatgpt_consent.text
            assert mcp_auth._is_exact_consent_post(
                {
                    "method": "POST",
                    "path": "/mcp/oauth/consent",
                    "raw_path": b"/mcp/oauth/consent",
                    "query_string": f"state={chatgpt_state}".encode(),
                    "root_path": "/mcp",
                }
            ) is True
            assert session_secret not in chatgpt_consent.text
            assert "healthquery_session" not in chatgpt_consent.text

            match = re.search(r'name="csrf_token" value="([^"]+)"', chatgpt_consent.text)
            assert match is not None
            approved = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": chatgpt_state,
                    "csrf_token": match.group(1),
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Origin": "null"},
                follow_redirects=False,
            )
            assert approved.status_code == 302
            assert "code=" in approved.headers["location"]


@pytest.mark.asyncio
async def test_embedded_consent_requires_state_csrf_password_and_explicit_approve(monkeypatch, caplog):
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "http://testserver/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "http://testserver/mcp")
    await init_db()
    await set_config_value(
        AUTH_STATE_KEY,
        {"enrolled": True, "session_secret": secrets.token_urlsafe(48)},
    )
    async with _app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            registration = await client.post(
                "/mcp/register",
                json={"redirect_uris": ["http://127.0.0.1/callback"], "token_endpoint_auth_method": "none"},
            )
            client_record = OAuthClientInformationFull.model_validate(registration.json())
            verifier = secrets.token_urlsafe(32)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")

            async def new_state() -> tuple[str, str]:
                authorization_redirect = await oauth_provider.authorize(
                    client_record,
                    AuthorizationParams(
                        state="client-state",
                        scopes=["health:read"],
                        code_challenge=challenge,
                        redirect_uri=AnyUrl("http://127.0.0.1/callback"),
                        redirect_uri_provided_explicitly=True,
                        resource="http://testserver/mcp",
                    ),
                )
                state = authorization_redirect.split("state=", 1)[1]
                consent = await client.get(f"/mcp/oauth/consent?state={state}")
                match = re.search(r'name="csrf_token" value="([^"]+)"', consent.text)
                assert match is not None
                repeated_consent = await client.get(f"/mcp/oauth/consent?state={state}")
                repeated_match = re.search(r'name="csrf_token" value="([^"]+)"', repeated_consent.text)
                assert repeated_match is not None
                assert repeated_match.group(1) == match.group(1)
                return state, repeated_match.group(1)

            state, csrf_token = await new_state()
            common = {
                "state": state,
                "csrf_token": csrf_token,
                "password": "correct horse battery staple",
                "decision": "approve",
            }
            headers = {"Origin": "null", "Sec-Fetch-Site": "same-origin"}

            missing_decision = await client.post(
                "/mcp/oauth/consent",
                data={key: value for key, value in common.items() if key != "decision"},
                headers=headers,
            )
            assert missing_decision.status_code == 403

            other_origin = await client.post(
                "/mcp/oauth/consent",
                data=common,
                headers={"Origin": "https://unexpected.example", "Sec-Fetch-Site": "same-origin"},
            )
            assert other_origin.status_code == 403

            wrong_password = await client.post(
                "/mcp/oauth/consent",
                data={**common, "password": "definitely-wrong"},
                headers=headers,
            )
            assert wrong_password.status_code == 403

            approved = await client.post(
                "/mcp/oauth/consent",
                data=common,
                headers=headers,
                follow_redirects=False,
            )
            assert approved.status_code == 302
            assert "code=" in approved.headers["location"]

            consumed_get = await client.get(f"/mcp/oauth/consent?state={state}")
            assert consumed_get.status_code == 400
            assert 'name="csrf_token"' not in consumed_get.text

            reused = await client.post(
                "/mcp/oauth/consent",
                data=common,
                headers=headers,
            )
            assert reused.status_code == 403

            state, csrf_token = await new_state()
            missing_origin = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "csrf_token": csrf_token,
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Sec-Fetch-Site": "same-origin"},
                follow_redirects=False,
            )
            assert missing_origin.status_code == 302

            state, csrf_token = await new_state()
            missing_fetch_metadata = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "csrf_token": csrf_token,
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Origin": "null"},
                follow_redirects=False,
            )
            assert missing_fetch_metadata.status_code == 302

            state, csrf_token = await new_state()
            external_browser_navigation = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "csrf_token": csrf_token,
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Sec-Fetch-Site": "none"},
                follow_redirects=False,
            )
            assert external_browser_navigation.status_code == 302

            state, csrf_token = await new_state()
            exact_chatgpt_origin = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "csrf_token": csrf_token,
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Origin": "https://chatgpt.com", "Sec-Fetch-Site": "cross-site"},
                follow_redirects=False,
            )
            assert exact_chatgpt_origin.status_code == 302

            state, csrf_token = await new_state()
            explicit_cross_site = await client.post(
                "/mcp/oauth/consent",
                data={
                    "state": state,
                    "csrf_token": csrf_token,
                    "password": "correct horse battery staple",
                    "decision": "approve",
                },
                headers={"Origin": "null", "Sec-Fetch-Site": "cross-site"},
                follow_redirects=False,
            )
            assert explicit_cross_site.status_code == 403

            expired_state, _ = await new_state()
            await execute(
                "UPDATE mcp_oauth_requests SET expires_at = 0 WHERE state_hash = ?",
                (hashlib.sha256(expired_state.encode("utf-8")).hexdigest(),),
            )
            expired_get = await client.get(f"/mcp/oauth/consent?state={expired_state}")
            assert expired_get.status_code == 400
            assert 'name="csrf_token"' not in expired_get.text

            other_route = await client.post(
                "/mcp/",
                headers={"Origin": "null", "Sec-Fetch-Site": "same-origin"},
                follow_redirects=False,
            )
            assert other_route.status_code == 403

    diagnostics = [message for message in caplog.messages if message.startswith("oauth_consent_diagnostic ")]
    assert any("rejected_check=decision" in message for message in diagnostics)
    assert any("origin_class=other" in message and "rejected_check=origin" in message for message in diagnostics)
    assert any("rejected_check=password" in message for message in diagnostics)
    assert any("rejected_check=csrf" in message for message in diagnostics)
    assert any("fetch_metadata_class=cross-site rejected_check=origin" in message for message in diagnostics)
