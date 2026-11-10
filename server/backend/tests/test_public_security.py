from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import httpx
import pytest
from httpx import ASGITransport

from app_settings import ConfigurationError, get_settings, validate_settings
from db.database import init_db
from main import app


PASSWORD = "correct horse battery staple"


def _production_env(monkeypatch, tmp_path) -> None:
    password_file = tmp_path / "admin-password-hash"
    password_file.write_text(
        "$argon2id$v=19$m=65536,t=3,p=4$8Zl/l0xKqO19PBMwqH4z1w$jRpnvyS0XDgXnxi6RjdYo1ZJ9IR3jsA/BcnyUt86E80",
        encoding="utf-8",
    )
    session_file = tmp_path / "session-secret"
    session_file.write_text("s" * 64, encoding="utf-8")
    monkeypatch.setenv("HEALTHQUERY_ENV", "production")
    monkeypatch.setenv("HEALTHQUERY_PUBLIC_URL", "https://health.example")
    monkeypatch.setenv("HEALTHQUERY_ALLOWED_HOSTS", "health.example")
    monkeypatch.setenv("HEALTHQUERY_CORS_ORIGINS", "https://health.example")
    monkeypatch.setenv("HEALTHQUERY_INGEST_TOKEN", "i" * 40)
    monkeypatch.setenv("HEALTHQUERY_READ_TOKEN", "r" * 40)
    monkeypatch.setenv("HEALTHQUERY_ADMIN_PASSWORD_HASH_FILE", str(password_file))
    monkeypatch.delenv("HEALTHQUERY_ADMIN_PASSWORD_HASH", raising=False)
    monkeypatch.setenv("HEALTHQUERY_SESSION_SECRET_FILE", str(session_file))
    monkeypatch.delenv("HEALTHQUERY_SESSION_SECRET", raising=False)
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "https://health.example/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "https://health.example/mcp")


def test_production_configuration_accepts_explicit_secrets(monkeypatch, tmp_path):
    _production_env(monkeypatch, tmp_path)
    assert validate_settings(get_settings()).production


@pytest.mark.parametrize(
    "name,value,match",
    [
        ("HEALTHQUERY_ALLOWED_HOSTS", "*", "ALLOWED_HOSTS"),
        ("HEALTHQUERY_INGEST_TOKEN", "change-me-ingest", "INGEST_TOKEN"),
        ("HEALTHQUERY_READ_TOKEN", "change-me-read", "READ_TOKEN"),
    ],
)
def test_production_configuration_rejects_wildcards_and_placeholders(
    monkeypatch, tmp_path, name, value, match
):
    _production_env(monkeypatch, tmp_path)
    monkeypatch.setenv(name, value)
    with pytest.raises(ConfigurationError, match=match):
        validate_settings(get_settings())


def test_production_configuration_requires_secret_files(monkeypatch, tmp_path):
    _production_env(monkeypatch, tmp_path)
    monkeypatch.delenv("HEALTHQUERY_ADMIN_PASSWORD_HASH_FILE")
    monkeypatch.delenv("HEALTHQUERY_SESSION_SECRET_FILE")
    with pytest.raises(ConfigurationError, match="required in production"):
        validate_settings(get_settings())


def test_production_import_disables_interactive_api_docs(monkeypatch, tmp_path):
    _production_env(monkeypatch, tmp_path)
    child_env = os.environ.copy()
    child = subprocess.run(
        [
            sys.executable,
            "-c",
            "import main; assert main.app.docs_url is None; assert main.app.redoc_url is None; assert main.app.openapi_url is None",
        ],
        cwd=str(Path(__file__).resolve().parents[1]),
        env=child_env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert child.returncode == 0, child.stderr


@pytest.mark.asyncio
async def test_browser_login_is_password_only_and_production_cookie_is_host_only(monkeypatch):
    await init_db()
    monkeypatch.setenv("HEALTHQUERY_ENV", "production")
    monkeypatch.setenv("HEALTHQUERY_PUBLIC_URL", "https://health.example")
    monkeypatch.setenv("HEALTHQUERY_SESSION_SECRET", "s" * 64)
    monkeypatch.delenv("HEALTHQUERY_SESSION_SECRET_FILE", raising=False)
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="https://health.example",
        headers={"Origin": "https://health.example"},
    ) as client:
        legacy = await client.get("/api/auth/setup")
        assert legacy.status_code in {404, 405}
        assert "secret" not in legacy.text
        wrong = await client.post("/api/auth/login", json={"password": "wrong password"})
        assert wrong.status_code == 401
        login = await client.post("/api/auth/login", json={"password": PASSWORD})
        assert login.status_code == 200
        set_cookie = login.headers["set-cookie"]
        assert set_cookie.startswith("__Host-healthquery_session=")
        assert "Path=/" in set_cookie
        assert "Secure" in set_cookie
        assert "HttpOnly" in set_cookie
        assert "SameSite=strict" in set_cookie

        await client.post("/api/auth/logout")
        stale = await client.get("/api/health/status")
        assert stale.status_code == 401

        login_wrong = await client.post("/api/auth/login", json={"password": "wrong password"})
        assert login_wrong.status_code == 401
        login = await client.post("/api/auth/login", json={"password": PASSWORD})
        assert login.status_code == 200
        mcp_config = await client.get(
            "/api/mcp/config",
            headers={"X-Forwarded-Host": "attacker.example", "X-Forwarded-Proto": "http"},
        )
        assert mcp_config.json()["endpoint"] == "https://health.example/mcp"
        enrollment = await client.post(
            "/api/devices/enrollment-tokens",
            json={"server_url": "https://attacker.example"},
        )
        assert enrollment.status_code == 201
        assert enrollment.json()["pairing_url"].startswith("https://health.example/pair/android#token=")


@pytest.mark.asyncio
async def test_cross_origin_post_is_rejected_before_auth(monkeypatch):
    await init_db()
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="https://health.example",
        headers={"Origin": "https://attacker.example"},
    ) as client:
        response = await client.post("/api/auth/login", json={"password": PASSWORD})
    assert response.status_code == 403
    assert "Cross-origin" in response.text


@pytest.mark.asyncio
async def test_trusted_host_and_security_headers(monkeypatch):
    await init_db()
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        rejected = await client.get("/api/health", headers={"Host": "attacker.example"})
        assert rejected.status_code == 400
        response = await client.get("/api/health", headers={"Host": "test"})
    assert response.status_code == 200
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["referrer-policy"] == "no-referrer"
    assert response.headers["cross-origin-opener-policy"] == "same-origin"


@pytest.mark.asyncio
async def test_health_device_and_mcp_data_are_unauthenticated_401():
    await init_db()
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        assert (await client.get("/api/health/status")).status_code == 401
        assert (await client.get("/api/devices")).status_code == 401
        mcp = await client.post(
            "/mcp",
            json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        )
    assert mcp.status_code == 401


@pytest.mark.asyncio
async def test_mcp_metadata_uses_canonical_resource_not_host(monkeypatch):
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "https://health.example/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "https://health.example/mcp")
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="https://test") as client:
        first = await client.get(
            "/.well-known/oauth-protected-resource",
            headers={"Host": "test"},
        )
        second = await client.get(
            "/.well-known/oauth-protected-resource",
            headers={"Host": "health.example"},
        )
    assert first.status_code == second.status_code == 200
    assert first.json()["resource"] == second.json()["resource"] == "https://health.example/mcp"


@pytest.mark.asyncio
async def test_rate_and_body_limits(monkeypatch):
    await init_db()
    transport = ASGITransport(app=app, client=("198.51.100.77", 4321))
    async with httpx.AsyncClient(
        transport=transport,
        base_url="https://health.example",
        headers={"Origin": "https://health.example"},
    ) as client:
        responses = [
            await client.post("/api/auth/login", json={"password": "wrong password"})
            for _ in range(4)
        ]
        assert [response.status_code for response in responses] == [401, 401, 401, 401]
        oversized = await client.post(
            "/api/auth/login",
            content=b"{}",
            headers={"Content-Length": str(2 * 1024 * 1024 + 1)},
        )
    assert oversized.status_code == 413
