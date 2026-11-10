import os
import sqlite3
from contextlib import asynccontextmanager
from pathlib import Path

from app_settings import (
    get_settings,
    has_placeholder_ingest_token,
    has_placeholder_read_token,
    validate_settings,
)
from db.database import init_db
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from routers.browser_auth import router as browser_auth_router
from routers.devices import router as devices_router
from routers.health import router as health_router
from routers.mcp import mcp_app, mcp_exact_app
from routers.mcp import metadata_router as mcp_metadata_router
from routers.mcp_admin import router as mcp_admin_router
from routers.read_api import router as read_router
from routers.webhook import router as webhook_router
from services.http_security import PublicSecurityMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.routing import Route
from utils.logger import configure_logging, get_logger

configure_logging()
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting HealthQuery backend")
    settings = validate_settings()
    warn_on_placeholder_tokens(settings)
    os.umask(0o077)
    await init_db()
    for candidate in (settings.db_path, Path(f"{settings.db_path}-wal"), Path(f"{settings.db_path}-shm")):
        if candidate.exists():
            candidate.chmod(0o600)
    from services.browser_auth import ensure_browser_auth_state, prune_expired_sessions
    from services.retention import prune_expired_health_data

    await ensure_browser_auth_state()
    await prune_expired_sessions()
    retention = await prune_expired_health_data()
    logger.info("Applied health-data retention policy", extra={"counts": retention})
    async with mcp_app.lifespan(app):
        yield
    logger.info("Stopping HealthQuery backend")


startup_settings = validate_settings()
app = FastAPI(
    title="HealthQuery API",
    lifespan=lifespan,
    docs_url=None if startup_settings.production else "/docs",
    redoc_url=None if startup_settings.production else "/redoc",
    openapi_url=None if startup_settings.production else "/openapi.json",
)


@app.exception_handler(sqlite3.OperationalError)
async def sqlite_operational_error_handler(request, exc):  # type: ignore[no-untyped-def]
    message = str(exc).lower()
    if "locked" in message or "busy" in message:
        logger.warning("sqlite_busy path=%s", request.url.path)
        return JSONResponse(
            status_code=503,
            content={"detail": "Storage is temporarily busy; retry the same batch"},
            headers={"Retry-After": "5", "Cache-Control": "no-store"},
        )
    logger.error("sqlite_operational_error path=%s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={"detail": "Storage operation failed"},
        headers={"Cache-Control": "no-store"},
    )

app.add_middleware(PublicSecurityMiddleware)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=startup_settings.allowed_hosts)

if startup_settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=startup_settings.cors_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Webhook-Token"],
    )

app.include_router(health_router)
app.include_router(browser_auth_router)
app.include_router(devices_router)
app.include_router(mcp_admin_router)
app.include_router(mcp_metadata_router)
app.include_router(read_router)
app.include_router(webhook_router)
# A Starlette mount dispatches ``/mcp/`` but not the advertised exact
# protected-resource URL. Register the exact path before the mount/SPA routes.
app.router.routes.append(Route("/mcp", mcp_exact_app, methods=["GET", "POST", "DELETE"], include_in_schema=False))
app.mount("/mcp", mcp_app)

if startup_settings.production:
    async def disabled_api_documentation(request):  # type: ignore[no-untyped-def]
        raise StarletteHTTPException(status_code=404)

    app.router.routes.extend(
        [
            Route("/docs", disabled_api_documentation, methods=["GET"], include_in_schema=False),
            Route("/redoc", disabled_api_documentation, methods=["GET"], include_in_schema=False),
        ]
    )


class SpaStaticFiles(StaticFiles):
    """Serve the frontend entry point for browser routes without masking APIs."""

    async def get_response(self, path: str, scope):  # type: ignore[no-untyped-def]
        # Starlette's html=True fallback behavior changed to return index.html
        # directly, so reject unknown API paths before delegating to it.
        request_path = str(scope.get("path", path)).lstrip("/")
        if request_path.startswith("api/"):
            raise StarletteHTTPException(status_code=404)
        try:
            return await super().get_response(path, scope)
        except StarletteHTTPException as exc:
            if exc.status_code != 404 or path.startswith("api/") or "." in Path(path).name:
                raise
            return await super().get_response("index.html", scope)


frontend_dist = Path(os.getenv("FRONTEND_DIST_PATH", "../frontend/dist"))
if frontend_dist.is_dir():
    app.mount("/", SpaStaticFiles(directory=frontend_dist, html=True), name="frontend")


def warn_on_placeholder_tokens(settings=None) -> None:
    settings = settings or get_settings()
    if has_placeholder_ingest_token(settings):
        logger.warning("HEALTHQUERY_INGEST_TOKEN is still set to a placeholder value")
    if has_placeholder_read_token(settings):
        logger.warning("HEALTHQUERY_READ_TOKEN is still set to a placeholder value")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
