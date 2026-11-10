from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


class ConfigurationError(RuntimeError):
    """Raised when a public deployment would start with unsafe settings."""


def _csv(name: str, default: str = "") -> list[str]:
    return [value.strip() for value in os.getenv(name, default).split(",") if value.strip()]


def _read_secret_file(name: str) -> tuple[str | None, Path | None]:
    raw_path = os.getenv(name, "").strip()
    if not raw_path:
        return None, None
    path = Path(raw_path)
    try:
        value = path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise ConfigurationError(f"Unable to read {name}: {exc}") from exc
    if not value:
        raise ConfigurationError(f"{name} points to an empty secret file")
    return value, path


@dataclass(frozen=True)
class AppSettings:
    db_path: Path
    ingest_token: str
    read_token: str
    log_level: str
    cors_origins: list[str]
    environment: str
    public_url: str | None
    allowed_hosts: list[str]
    trusted_proxy_ips: list[str]
    admin_password_hash: str | None
    admin_password_hash_file: Path | None
    session_secret: str | None
    session_secret_file: Path | None
    session_max_age_seconds: int
    retention_days: int = 365

    @property
    def production(self) -> bool:
        return self.environment == "production"


def _is_placeholder_token(value: str, placeholder_prefix: str) -> bool:
    return value.startswith(placeholder_prefix)


def get_settings() -> AppSettings:
    db_path = Path(os.getenv("DB_PATH", "data/healthquery.db"))
    ingest_token = os.getenv("HEALTHQUERY_INGEST_TOKEN", "change-me-ingest")
    read_token = os.getenv("HEALTHQUERY_READ_TOKEN", "change-me-read")
    log_level = os.getenv("HEALTHQUERY_LOG_LEVEL", "INFO")
    cors_origins = _csv("HEALTHQUERY_CORS_ORIGINS")
    environment = os.getenv("HEALTHQUERY_ENV", "development").strip().lower()
    public_url = os.getenv("HEALTHQUERY_PUBLIC_URL", "").strip().rstrip("/") or None
    allowed_hosts = _csv("HEALTHQUERY_ALLOWED_HOSTS", "localhost,127.0.0.1,testserver")
    trusted_proxy_ips = _csv("HEALTHQUERY_TRUSTED_PROXY_IPS", "127.0.0.1,::1")
    admin_password_hash, admin_password_hash_file = _read_secret_file(
        "HEALTHQUERY_ADMIN_PASSWORD_HASH_FILE"
    )
    if admin_password_hash is None:
        admin_password_hash = os.getenv("HEALTHQUERY_ADMIN_PASSWORD_HASH", "").strip() or None
    session_secret, session_secret_file = _read_secret_file("HEALTHQUERY_SESSION_SECRET_FILE")
    if session_secret is None:
        session_secret = os.getenv("HEALTHQUERY_SESSION_SECRET", "").strip() or None
    session_max_age_seconds = int(os.getenv("HEALTHQUERY_SESSION_MAX_AGE_SECONDS", str(12 * 3600)))
    retention_days = int(os.getenv("HEALTHQUERY_RETENTION_DAYS", "365"))
    return AppSettings(
        db_path=db_path,
        ingest_token=ingest_token,
        read_token=read_token,
        log_level=log_level,
        cors_origins=cors_origins,
        environment=environment,
        public_url=public_url,
        allowed_hosts=allowed_hosts,
        trusted_proxy_ips=trusted_proxy_ips,
        admin_password_hash=admin_password_hash,
        admin_password_hash_file=admin_password_hash_file,
        session_secret=session_secret,
        session_secret_file=session_secret_file,
        session_max_age_seconds=session_max_age_seconds,
        retention_days=retention_days,
    )


def validate_settings(settings: AppSettings | None = None) -> AppSettings:
    """Fail closed when public mode is missing any required security boundary."""

    settings = settings or get_settings()
    if settings.environment not in {"development", "test", "production"}:
        raise ConfigurationError("HEALTHQUERY_ENV must be development, test, or production")
    if not 300 <= settings.session_max_age_seconds <= 24 * 3600:
        raise ConfigurationError("HEALTHQUERY_SESSION_MAX_AGE_SECONDS must be between 300 and 86400")
    if not 30 <= settings.retention_days <= 3650:
        raise ConfigurationError("HEALTHQUERY_RETENTION_DAYS must be between 30 and 3650")
    if not settings.production:
        return settings

    errors: list[str] = []
    parsed_public = urlsplit(settings.public_url or "")
    if (
        parsed_public.scheme != "https"
        or not parsed_public.hostname
        or parsed_public.username
        or parsed_public.password
        or parsed_public.path not in {"", "/"}
        or parsed_public.query
        or parsed_public.fragment
    ):
        errors.append("HEALTHQUERY_PUBLIC_URL must be an HTTPS origin without a path")
    if not settings.allowed_hosts or any("*" in host for host in settings.allowed_hosts):
        errors.append("HEALTHQUERY_ALLOWED_HOSTS must contain explicit hosts")
    elif parsed_public.hostname and parsed_public.hostname not in settings.allowed_hosts:
        errors.append("HEALTHQUERY_ALLOWED_HOSTS must include the HEALTHQUERY_PUBLIC_URL host")
    if "*" in settings.cors_origins:
        errors.append("HEALTHQUERY_CORS_ORIGINS cannot contain * in production")
    for origin in settings.cors_origins:
        parsed = urlsplit(origin)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
        ):
            errors.append("HEALTHQUERY_CORS_ORIGINS must contain HTTPS origins only")
            break
    for proxy in settings.trusted_proxy_ips:
        try:
            ipaddress.ip_network(proxy, strict=False)
        except ValueError:
            errors.append("HEALTHQUERY_TRUSTED_PROXY_IPS must contain IP addresses or CIDRs only")
            break
    if has_placeholder_ingest_token(settings) or len(settings.ingest_token) < 32:
        errors.append("HEALTHQUERY_INGEST_TOKEN must be a non-placeholder secret of at least 32 characters")
    if has_placeholder_read_token(settings) or len(settings.read_token) < 32:
        errors.append("HEALTHQUERY_READ_TOKEN must be a non-placeholder secret of at least 32 characters")
    if settings.ingest_token == settings.read_token:
        errors.append("ingest and read tokens must be different")
    if not settings.admin_password_hash_file or not settings.admin_password_hash:
        errors.append("HEALTHQUERY_ADMIN_PASSWORD_HASH_FILE is required in production")
    elif not settings.admin_password_hash.startswith("$argon2"):
        errors.append("the administrator password secret must contain an Argon2 hash")
    if not settings.session_secret_file or not settings.session_secret:
        errors.append("HEALTHQUERY_SESSION_SECRET_FILE is required in production")
    elif len(settings.session_secret) < 43:
        errors.append("the session secret must contain at least 43 characters")

    issuer = os.getenv("HEALTHQUERY_MCP_ISSUER_URL", "").strip().rstrip("/")
    resource = os.getenv("HEALTHQUERY_MCP_RESOURCE_URL", "").strip().rstrip("/")
    expected_mcp = f"{settings.public_url}/mcp" if settings.public_url else ""
    if issuer != expected_mcp or resource != expected_mcp:
        errors.append("MCP issuer and resource URLs must both equal HEALTHQUERY_PUBLIC_URL + /mcp")
    if errors:
        raise ConfigurationError("Unsafe production configuration:\n- " + "\n- ".join(errors))
    return settings


def has_placeholder_ingest_token(settings: AppSettings) -> bool:
    return _is_placeholder_token(settings.ingest_token, "change-me-")


def has_placeholder_read_token(settings: AppSettings) -> bool:
    return _is_placeholder_token(settings.read_token, "change-me-")
