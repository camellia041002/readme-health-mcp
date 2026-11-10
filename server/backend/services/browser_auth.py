from __future__ import annotations

import hashlib
import secrets
import time
from dataclasses import dataclass

from app_settings import get_settings
from db.database import execute, fetch_one
from fastapi import HTTPException, Request, Response, status
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer
from pwdlib import PasswordHash

from services.config_store import get_config_value, set_config_value
from services.http_security import client_ip, request_origin_allowed

AUTH_STATE_KEY = "_browser_auth_state"
LEGACY_COOKIE_NAME = "healthquery_session"
PRODUCTION_COOKIE_NAME = "__Host-healthquery_session"
SIGNING_SALT = "healthquery-browser-session-v2"
PASSWORD_HASH = PasswordHash.recommended()


@dataclass(frozen=True)
class BrowserAuthState:
    # ``enrolled`` is retained as a compatibility field for older config rows;
    # password configuration is now the complete browser-auth enrollment.
    enrolled: bool
    fallback_session_secret: str


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _cookie_name() -> str:
    return PRODUCTION_COOKIE_NAME if get_settings().production else LEGACY_COOKIE_NAME


def _session_max_age() -> int:
    return get_settings().session_max_age_seconds


async def load_browser_auth_state() -> BrowserAuthState:
    raw = await get_config_value(AUTH_STATE_KEY, default={})
    raw = raw if isinstance(raw, dict) else {}
    return BrowserAuthState(
        enrolled=bool(raw.get("enrolled", False)),
        fallback_session_secret=str(raw.get("session_secret", "")),
    )


async def ensure_browser_auth_state() -> BrowserAuthState:
    state = await load_browser_auth_state()
    if get_settings().production:
        # Production sessions always use the Docker-provided signing secret.
        # Leave legacy config JSON untouched; extra keys are intentionally
        # ignored and require no migration.
        return BrowserAuthState(enrolled=state.enrolled, fallback_session_secret="")
    if state.fallback_session_secret:
        return state
    state = BrowserAuthState(
        enrolled=state.enrolled,
        fallback_session_secret=state.fallback_session_secret or secrets.token_urlsafe(48),
    )
    payload: dict[str, object] = {
        "enrolled": state.enrolled,
        # Public deployments sign with a Docker secret. This fallback keeps
        # local development usable and migrates existing databases safely.
        "session_secret": state.fallback_session_secret,
    }
    await set_config_value(
        AUTH_STATE_KEY,
        payload,
    )
    return state


async def verify_admin_password(password: str) -> bool:
    configured_hash = get_settings().admin_password_hash
    if not configured_hash:
        return False
    try:
        return PASSWORD_HASH.verify(password, configured_hash)
    except (ValueError, TypeError):
        return False


async def require_same_origin(request: Request) -> None:
    embedded_consent = request.scope.get("healthquery.embedded_consent_validated") is True
    if (
        request.method.upper() in {"POST", "PUT", "PATCH", "DELETE"}
        and not embedded_consent
        and not request_origin_allowed(request.scope)
    ):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Cross-origin request rejected")


async def audit_browser_event(
    request: Request,
    event_type: str,
    success: bool,
    detail: str | None = None,
) -> None:
    await execute(
        """
        INSERT INTO security_audit_events (event_type, success, client_ip, detail)
        VALUES (?, ?, ?, ?)
        """,
        (event_type, int(success), client_ip(request.scope), detail),
    )


async def _signing_secret() -> str:
    settings = get_settings()
    if settings.session_secret:
        return settings.session_secret
    return (await ensure_browser_auth_state()).fallback_session_secret


async def _serializer() -> URLSafeTimedSerializer:
    return URLSafeTimedSerializer(await _signing_secret(), salt=SIGNING_SALT)


async def _session_payload(request: Request) -> dict[str, object] | None:
    token = request.cookies.get(_cookie_name())
    if not token:
        return None
    try:
        payload = (await _serializer()).loads(token, max_age=_session_max_age())
    except (BadSignature, SignatureExpired):
        return None
    return payload if isinstance(payload, dict) else None


async def session_is_valid(request: Request) -> bool:
    payload = await _session_payload(request)
    session_id = str(payload.get("session_id", "")) if payload else ""
    if not session_id:
        return False
    now = time.time()
    row = await fetch_one(
        """
        SELECT expires_at, revoked_at, last_seen_at
        FROM browser_sessions
        WHERE session_hash = ?
        """,
        (_digest(session_id),),
    )
    if not row or row["revoked_at"] is not None or float(row["expires_at"]) <= now:
        return False
    if now - float(row["last_seen_at"]) >= 300:
        await execute(
            "UPDATE browser_sessions SET last_seen_at = ? WHERE session_hash = ?",
            (now, _digest(session_id)),
        )
    return True


async def require_browser_session(request: Request) -> None:
    if not await session_is_valid(request):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authenticated")
    await require_same_origin(request)


async def issue_session_cookie(request: Request, response: Response) -> None:
    now = time.time()
    session_id = secrets.token_urlsafe(32)
    max_age = _session_max_age()
    user_agent = request.headers.get("user-agent", "")
    await execute(
        """
        INSERT INTO browser_sessions (
          session_hash, issued_at, expires_at, last_seen_at, client_ip, user_agent_hash
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        (
            _digest(session_id),
            now,
            now + max_age,
            now,
            client_ip(request.scope),
            _digest(user_agent) if user_agent else None,
        ),
    )
    token = (await _serializer()).dumps({"session_id": session_id, "issued_at": int(now)})
    settings = get_settings()
    response.set_cookie(
        key=_cookie_name(),
        value=token,
        max_age=max_age,
        httponly=True,
        secure=settings.production or request.url.scheme == "https",
        samesite="strict",
        path="/",
    )


async def revoke_session(request: Request, response: Response) -> None:
    payload = await _session_payload(request)
    session_id = str(payload.get("session_id", "")) if payload else ""
    if session_id:
        await execute(
            "UPDATE browser_sessions SET revoked_at = ? WHERE session_hash = ? AND revoked_at IS NULL",
            (time.time(), _digest(session_id)),
        )
    response.delete_cookie(
        key=_cookie_name(),
        path="/",
        secure=get_settings().production,
        httponly=True,
        samesite="strict",
    )


async def prune_expired_sessions() -> None:
    await execute("DELETE FROM browser_sessions WHERE expires_at < ?", (time.time() - 24 * 3600,))
    await execute("DELETE FROM security_audit_events WHERE occurred_at < datetime('now', '-90 days')")


__all__ = [
    "audit_browser_event",
    "ensure_browser_auth_state",
    "issue_session_cookie",
    "prune_expired_sessions",
    "require_browser_session",
    "require_same_origin",
    "revoke_session",
    "session_is_valid",
    "verify_admin_password",
]
