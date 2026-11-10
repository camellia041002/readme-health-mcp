from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
import pytest
from fastapi import FastAPI
from starlette.routing import Route

from db.database import fetch_one, init_db
from routers.mcp import MCPExactPathApp, create_mcp_app
from services.mcp_credentials import authenticate_token, create_credential, revoke_credential
from services.seed_data import seed_sample_health_data


@asynccontextmanager
async def _mounted_app():
    mcp_app = create_mcp_app()

    @asynccontextmanager
    async def lifespan(app):
        async with mcp_app.lifespan(app):
            yield

    app = FastAPI(lifespan=lifespan)
    app.router.routes.append(
        Route("/mcp", MCPExactPathApp(mcp_app), methods=["GET", "POST", "DELETE"])
    )
    app.mount("/mcp", mcp_app)
    async with lifespan(app):
        yield app


@pytest.mark.asyncio
async def test_remote_mcp_current_and_legacy_protocols_with_auth_discovery(monkeypatch):
    monkeypatch.setenv("HEALTHQUERY_ALLOWED_HOSTS", "example.test")
    monkeypatch.setenv("HEALTHQUERY_MCP_ISSUER_URL", "https://example.test/mcp")
    monkeypatch.setenv("HEALTHQUERY_MCP_RESOURCE_URL", "https://example.test/mcp")
    await init_db()
    await seed_sample_health_data()
    credential, token = await create_credential("remote-test")
    async with _mounted_app() as app:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="https://example.test") as client:
            metadata = await client.get("/mcp/.well-known/oauth-protected-resource")
            assert metadata.status_code == 200
            assert metadata.json()["resource"] == "https://example.test/mcp"

            unauthenticated = await client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
            assert unauthenticated.status_code == 401
            assert "resource_metadata" in unauthenticated.headers["www-authenticate"]

            auth_headers = {"Authorization": f"Bearer {token}", "Accept": "application/json, text/event-stream"}

            current_meta = {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1"},
                "io.modelcontextprotocol/clientCapabilities": {},
            }
            current_headers = {
                **auth_headers,
                "MCP-Protocol-Version": "2026-07-28",
                "Mcp-Method": "server/discover",
            }
            discovered = await client.post(
                "/mcp",
                headers=current_headers,
                json={"jsonrpc": "2.0", "id": "discover", "method": "server/discover", "params": {"_meta": current_meta}},
            )
            assert discovered.status_code == 200
            assert "2026-07-28" in discovered.json()["result"]["supportedVersions"]

            list_headers = {**current_headers, "Mcp-Method": "tools/list"}
            listed = await client.post(
                "/mcp",
                headers=list_headers,
                json={"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {"_meta": current_meta}},
            )
            assert "Mcp-Session-Id" not in listed.headers
            names = {item["name"] for item in listed.json()["result"]["tools"]}
            assert {"list_data_types", "query_records"}.issubset(names)
            assert "query_sql" not in names

            call_headers = {**current_headers, "Mcp-Method": "tools/call", "Mcp-Name": "list_workouts"}
            called = await client.post(
                "/mcp/",
                headers=call_headers,
                json={"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "list_workouts", "arguments": {"limit": 5}, "_meta": current_meta}},
            )
            assert called.status_code == 200
            assert '"row_count":1' in called.text

            # The same endpoint remains compatible with handshake-era clients.
            initialize = await client.post(
                "/mcp/",
                headers=auth_headers,
                json={"jsonrpc": "2.0", "id": 10, "method": "initialize", "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}},
            )
            assert initialize.status_code == 200
            assert initialize.json()["result"]["serverInfo"]["name"] == "HealthQueryDirect"
    assert (await fetch_one("SELECT access_count FROM mcp_credentials WHERE credential_id = ?", (credential.id,)))["access_count"] >= 2


@pytest.mark.asyncio
async def test_mcp_credential_hashing_and_revoke():
    await init_db()
    credential, token = await create_credential("hash-test")
    row = await fetch_one("SELECT token_hash FROM mcp_credentials WHERE credential_id = ?", (credential.id,))
    assert row and token not in row["token_hash"]
    assert await authenticate_token(token)
    assert await revoke_credential(credential.id)
    assert await authenticate_token(token) is None
