"""Browser-session protected MCP credential administration."""

from __future__ import annotations

from typing import Any

from app_settings import get_settings
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field, field_validator
from services.browser_auth import require_browser_session
from services.mcp_credentials import (
    SCOPE_ALIASES,
    SUPPORTED_SCOPES,
    create_credential,
    credential_public_dict,
    list_credentials,
    revoke_credential,
)

router = APIRouter(
    prefix="/api/mcp",
    tags=["mcp-admin"],
    dependencies=[Depends(require_browser_session)],
)


@router.get("/config")
async def get_mcp_config(request: Request) -> dict[str, Any]:
    """Return browser-safe connection details; no bearer secret is included."""
    settings = get_settings()
    base = settings.public_url or f"{request.url.scheme}://{request.headers.get('host', request.url.netloc)}"
    base = base.rstrip("/")
    endpoint = f"{base}/mcp"
    metadata = f"{base}/.well-known/oauth-protected-resource/mcp"
    return {
        "endpoint": endpoint,
        "url": endpoint,
        "mcp_url": endpoint,
        "transport": "streamable-http",
        "scopes_supported": ["health:read"],
        "protected_resource_metadata": metadata,
        "authorization_server_metadata": f"{base}/mcp/.well-known/oauth-authorization-server",
        "interactive_oauth": True,
        "auth": {"type": "bearer", "credential_management": "browser", "oauth": "authorization_code_pkce"},
    }


class CreateMCPCredentialRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=120)
    scopes: list[str] | None = None

    @field_validator("scopes")
    @classmethod
    def valid_scopes(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return value
        unknown = set(value) - SUPPORTED_SCOPES - set(SCOPE_ALIASES)
        if unknown:
            raise ValueError(f"Unsupported MCP scope(s): {', '.join(sorted(unknown))}")
        return value


@router.post("/credentials", status_code=status.HTTP_201_CREATED)
async def create_mcp_credential(body: CreateMCPCredentialRequest) -> dict[str, Any]:
    try:
        credential, token = await create_credential(body.name, body.scopes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {
        "credential": credential_public_dict(credential),
        # This field is intentionally only present in the creation response.
        "token": token,
        "warning": "Copy this token now; it cannot be shown again.",
    }


@router.get("/credentials")
async def get_mcp_credentials() -> dict[str, Any]:
    credentials = await list_credentials()
    return {"credentials": [credential_public_dict(credential) for credential in credentials]}


@router.post("/credentials/{credential_id}/revoke")
async def revoke_mcp_credential(credential_id: str) -> dict[str, Any]:
    if not await revoke_credential(credential_id):
        raise HTTPException(status_code=404, detail="MCP credential not found")
    return {"id": credential_id, "revoked": True}


@router.delete("/credentials/{credential_id}")
async def delete_mcp_credential(credential_id: str) -> dict[str, Any]:
    return await revoke_mcp_credential(credential_id)


__all__ = ["router"]
