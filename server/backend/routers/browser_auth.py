from __future__ import annotations

from app_settings import get_settings
from fastapi import APIRouter, HTTPException, Request, Response, status
from pydantic import BaseModel, Field
from services.browser_auth import (
    audit_browser_event,
    ensure_browser_auth_state,
    issue_session_cookie,
    require_browser_session,
    require_same_origin,
    revoke_session,
    session_is_valid,
    verify_admin_password,
)

router = APIRouter(prefix="/api/auth", tags=["browser-auth"], include_in_schema=False)


class PasswordRequest(BaseModel):
    password: str = Field(..., min_length=12, max_length=1024)


@router.get("/status")
async def auth_status() -> dict[str, bool]:
    state = await ensure_browser_auth_state()
    password_configured = bool(get_settings().admin_password_hash)
    return {
        # ``enrolled`` is retained for clients that consumed the old status
        # shape. Password configuration is now the complete browser-auth
        # enrollment; the legacy flag is retained only for response shape compatibility.
        "enrolled": state.enrolled or password_configured,
        "password_configured": password_configured,
    }


@router.get("/me")
async def auth_me(request: Request) -> dict[str, bool]:
    return {"authenticated": await session_is_valid(request)}


@router.post("/login")
async def auth_login(body: PasswordRequest, request: Request, response: Response) -> dict[str, bool]:
    await require_same_origin(request)
    await ensure_browser_auth_state()
    if not await verify_admin_password(body.password):
        await audit_browser_event(request, "browser_login", False)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid owner credentials")
    await issue_session_cookie(request, response)
    await audit_browser_event(request, "browser_login", True)
    return {"authenticated": True}


@router.post("/logout")
async def auth_logout(request: Request, response: Response) -> dict[str, str]:
    await require_browser_session(request)
    await revoke_session(request, response)
    await audit_browser_event(request, "browser_logout", True)
    return {"status": "ok"}
