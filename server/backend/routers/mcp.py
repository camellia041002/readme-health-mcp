"""Mountable remote MCP Streamable HTTP application.

Integration in ``main.py`` mounts the official MCP Python SDK's dual-era
Streamable HTTP app::

    from routers.mcp import mcp_app
    app.mount("/mcp", mcp_app)
    # Run ``mcp_app.lifespan`` from the parent lifespan.

The mounted endpoint is then ``/mcp/`` (clients commonly use ``/mcp`` and
follow the normal trailing-slash redirect). Admin credential management is a
separate FastAPI router in :mod:`routers.mcp_admin`.
"""

from __future__ import annotations

import os
from collections.abc import Awaitable, Callable
from typing import Any

from app_settings import get_settings
from fastapi import APIRouter, Request
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from mcp_server import mcp
from services.mcp_auth import (
    MCPBearerMiddleware,
    authorization_server_metadata,
    protected_resource_metadata,
)


def create_mcp_app(server: MCPServer[Any] = mcp) -> Any:
    """Create an authenticated Streamable HTTP ASGI app for mounting.

    The official SDK serves both the stateless 2026-07-28 protocol and the
    2025 handshake era on one endpoint. The inner path is ``/`` so a parent
    can mount it at exactly ``/mcp``.
    """
    settings = get_settings()
    transport_hosts = list(settings.allowed_hosts)
    transport_hosts.extend(f"{host}:*" for host in settings.allowed_hosts if ":" not in host)
    transport_origins = [
        origin.strip().rstrip("/")
        for origin in os.getenv("HEALTHQUERY_MCP_ALLOWED_ORIGINS", "").split(",")
        if origin.strip()
    ]
    if settings.public_url and settings.public_url not in transport_origins:
        transport_origins.append(settings.public_url)
    inner = server.streamable_http_app(
        streamable_http_path="/",
        json_response=True,
        stateless_http=True,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=transport_hosts,
            allowed_origins=transport_origins,
        ),
    )
    return MCPBearerMiddleware(inner)


mcp_app = create_mcp_app()
mcp_asgi_app = mcp_app


class MCPExactPathApp:
    """Expose the mounted MCP app at the advertised path without a redirect.

    Starlette mounts only dispatch ``/mcp/`` to a sub-application. An exact
    ``/mcp`` request can otherwise fall through to the SPA and become a 405,
    which breaks clients that correctly use the protected resource URL as-is.
    Rewrite the exact route to the same scope a ``/mcp`` mount produces.
    """

    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(
        self,
        scope: dict[str, Any],
        receive: Callable[[], Awaitable[dict[str, Any]]],
        send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        inner_scope = dict(scope)
        inner_scope["root_path"] = f"{scope.get('root_path', '')}/mcp"
        inner_scope["path"] = "/"
        inner_scope["raw_path"] = b"/"
        await self.app(inner_scope, receive, send)


mcp_exact_app = MCPExactPathApp(mcp_app)

# RFC 9728's path-insertion form is rooted at the host authority rather than
# under the mounted MCP path. Export these tiny routes so the host app can
# include them alongside ``mcp_app`` for clients that implement that form.
metadata_router = APIRouter(include_in_schema=False)


@metadata_router.get("/.well-known/oauth-protected-resource/mcp")
@metadata_router.get("/.well-known/oauth-protected-resource")
async def mcp_protected_resource_metadata(request: Request) -> dict[str, Any]:
    return protected_resource_metadata(request.scope)


@metadata_router.get("/.well-known/oauth-authorization-server/mcp")
@metadata_router.get("/mcp/.well-known/oauth-authorization-server")
async def mcp_authorization_server_metadata() -> dict[str, Any]:
    """Expose RFC 8414 discovery for mounted and path-insertion clients."""
    return authorization_server_metadata()

__all__ = ["MCPExactPathApp", "create_mcp_app", "mcp_app", "mcp_asgi_app", "mcp_exact_app", "metadata_router"]
