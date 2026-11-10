"""Public HTTP boundary adapted from Starlette middleware and limits' fixed-window API."""

from __future__ import annotations

import ipaddress
import json
import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any
from urllib.parse import urlsplit

from app_settings import get_settings
from limits import parse
from limits.storage import MemoryStorage
from limits.strategies import FixedWindowRateLimiter

logger = logging.getLogger(__name__)

ASGIApp = Callable[
    [dict[str, Any], Callable[[], Awaitable[dict[str, Any]]], Callable[[dict[str, Any]], Awaitable[None]]],
    Awaitable[None],
]

_RATE_RULES = {
    ("POST", "/api/auth/login"): "5/minute",
    ("POST", "/api/device-enrollment/exchange"): "10/minute",
    ("POST", "/mcp/register"): "20/minute",
    ("POST", "/mcp/token"): "30/minute",
    ("POST", "/mcp/oauth/consent"): "10/minute",
    ("POST", "/mcp"): "120/minute",
    ("POST", "/mcp/"): "120/minute",
    ("POST", "/api/webhook/health"): "60/minute",
    ("POST", "/api/device-sync/v1/changes"): "120/minute",
    ("GET", "/api/device-sync/v1/reader-access"): "30/minute",
}
_PARSED_RATE_RULES = {key: parse(value) for key, value in _RATE_RULES.items()}
_RATE_STORAGE = MemoryStorage()
_RATE_LIMITER = FixedWindowRateLimiter(_RATE_STORAGE)


def _header(scope: dict[str, Any], name: str) -> str:
    needle = name.lower().encode("latin-1")
    for key, value in scope.get("headers", []):
        if key.lower() == needle:
            return value.decode("latin-1")
    return ""


def _peer_is_trusted(scope: dict[str, Any]) -> bool:
    peer = (scope.get("client") or ("", 0))[0]
    if not peer:
        return False
    try:
        address = ipaddress.ip_address(peer)
    except ValueError:
        return False
    for raw in get_settings().trusted_proxy_ips:
        try:
            if address in ipaddress.ip_network(raw, strict=False):
                return True
        except ValueError:
            continue
    return False


def client_ip(scope: dict[str, Any]) -> str:
    if _peer_is_trusted(scope):
        forwarded = _header(scope, "x-forwarded-for").split(",", 1)[0].strip()
        if forwarded:
            try:
                return str(ipaddress.ip_address(forwarded))
            except ValueError:
                pass
    return str((scope.get("client") or ("unknown", 0))[0])


async def _json_response(
    send: Callable[[dict[str, Any]], Awaitable[None]],
    status_code: int,
    detail: str,
    *,
    headers: list[tuple[bytes, bytes]] | None = None,
) -> None:
    body = json.dumps({"detail": detail}, separators=(",", ":")).encode("utf-8")
    response_headers = [
        (b"content-type", b"application/json"),
        (b"content-length", str(len(body)).encode("ascii")),
        (b"cache-control", b"no-store"),
    ]
    response_headers.extend(headers or [])
    await send({"type": "http.response.start", "status": status_code, "headers": response_headers})
    await send({"type": "http.response.body", "body": body})


def request_origin_allowed(scope: dict[str, Any]) -> bool:
    """Validate unsafe browser requests against the configured canonical origin."""

    settings = get_settings()
    public_origin = settings.public_url
    if not public_origin:
        scheme = str(scope.get("scheme", "http"))
        host = _header(scope, "host")
        public_origin = f"{scheme}://{host}" if host else None
    if not public_origin:
        return False
    expected = public_origin.rstrip("/")
    origin = _header(scope, "origin").rstrip("/")
    if origin:
        return origin == expected
    referer = _header(scope, "referer")
    if referer:
        parsed = urlsplit(referer)
        return f"{parsed.scheme}://{parsed.netloc}".rstrip("/") == expected
    return _header(scope, "sec-fetch-site").lower() == "same-origin"


class PublicSecurityMiddleware:
    """Apply bounded requests, brute-force protection, and browser security headers."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: dict[str, Any], receive: Callable[..., Awaitable[Any]], send: Callable[..., Awaitable[None]]) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        method = str(scope.get("method", "GET")).upper()
        path = str(scope.get("path", ""))
        rate = _PARSED_RATE_RULES.get((method, path))
        key = f"{method}:{path}:{client_ip(scope)}"
        if rate is not None and not _RATE_LIMITER.hit(rate, key):
            stats = _RATE_LIMITER.get_window_stats(rate, key)
            retry_after = max(1, int(stats.reset_time - time.time()))
            logger.warning("security_rate_limit path=%s client=%s", path, client_ip(scope))
            await _json_response(
                send,
                429,
                "Too many requests",
                headers=[(b"retry-after", str(retry_after).encode("ascii"))],
            )
            return

        sync_path = path.startswith("/api/device-sync/") or path == "/api/webhook/health"
        body_limit = 64 * 1024 * 1024 if sync_path else 2 * 1024 * 1024
        content_length = _header(scope, "content-length")
        if content_length:
            try:
                if int(content_length) > body_limit:
                    await _json_response(send, 413, "Request body too large")
                    return
            except ValueError:
                await _json_response(send, 400, "Invalid Content-Length")
                return

        consumed = 0
        response_started = False

        class RequestTooLarge(Exception):
            pass

        async def limited_receive() -> dict[str, Any]:
            nonlocal consumed
            message = await receive()
            if message.get("type") == "http.request":
                consumed += len(message.get("body", b""))
                if consumed > body_limit:
                    raise RequestTooLarge
            return message

        security_headers = [
            (b"x-content-type-options", b"nosniff"),
            (b"x-frame-options", b"DENY"),
            (b"referrer-policy", b"no-referrer"),
            (b"permissions-policy", b"camera=(), microphone=(), geolocation=()"),
            (b"cross-origin-opener-policy", b"same-origin"),
            (
                b"content-security-policy",
                (
                    b"default-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "
                    b"script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                    b"connect-src 'self'"
                ),
            ),
        ]
        if get_settings().production:
            security_headers.append((b"strict-transport-security", b"max-age=63072000; includeSubDomains"))

        async def security_send(message: dict[str, Any]) -> None:
            nonlocal response_started
            if message.get("type") == "http.response.start":
                response_started = True
                headers = list(message.get("headers", []))
                present = {key.lower() for key, _ in headers}
                headers.extend((key, value) for key, value in security_headers if key not in present)
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, limited_receive, security_send)
        except RequestTooLarge:
            if not response_started:
                await _json_response(send, 413, "Request body too large")
            else:
                logger.warning("request_body_limit_after_response path=%s", path)


__all__ = ["PublicSecurityMiddleware", "client_ip", "request_origin_allowed"]
