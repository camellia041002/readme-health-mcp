"""Storage and verification for revocable remote MCP credentials.

The secret is returned only by :func:`create_credential`; the database stores
only a SHA-256 digest.  These credentials are intentionally separate from the
legacy ``HEALTHQUERY_READ_TOKEN`` used by the stdio subprocess.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

from db.database import execute, fetch_all, fetch_one, get_connection

MCP_SCOPE_READ = "health:read"
SUPPORTED_SCOPES = frozenset({MCP_SCOPE_READ})
SCOPE_ALIASES = {"read": MCP_SCOPE_READ}
DEFAULT_SCOPES = frozenset({MCP_SCOPE_READ})
TOKEN_PREFIX = "hqmcp_"


@dataclass(frozen=True)
class MCPCredential:
    id: str
    name: str
    token_prefix: str
    scopes: frozenset[str]
    created_at: str
    last_used_at: str | None
    last_used_ip: str | None
    access_count: int
    revoked_at: str | None

    @property
    def revoked(self) -> bool:
        return self.revoked_at is not None


@dataclass(frozen=True)
class AuthenticatedMCPCredential:
    credential: MCPCredential
    token: str


CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS mcp_credentials (
  credential_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_prefix TEXT NOT NULL UNIQUE,
  token_hash TEXT NOT NULL UNIQUE,
  scopes_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TEXT,
  last_used_ip TEXT,
  access_count INTEGER NOT NULL DEFAULT 0,
  revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_mcp_credentials_active
  ON mcp_credentials (revoked_at, created_at);
"""


async def ensure_mcp_credentials_table() -> None:
    """Create the table defensively for installations before migration 007."""
    conn = await get_connection()
    try:
        await conn.executescript(CREATE_TABLE_SQL)
        await conn.commit()
    finally:
        await conn.close()


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _normalise_scopes(scopes: Iterable[str] | None) -> frozenset[str]:
    values = frozenset(
        SCOPE_ALIASES.get(str(scope).strip(), str(scope).strip())
        for scope in (scopes or DEFAULT_SCOPES)
        if str(scope).strip()
    )
    unknown = values - SUPPORTED_SCOPES
    if unknown:
        raise ValueError(f"Unsupported MCP scope(s): {', '.join(sorted(unknown))}")
    if MCP_SCOPE_READ not in values:
        raise ValueError(f"{MCP_SCOPE_READ} is required")
    return values


def _credential_from_row(row: dict[str, Any]) -> MCPCredential:
    try:
        scopes = frozenset(str(value) for value in json.loads(row["scopes_json"]))
    except (TypeError, ValueError, json.JSONDecodeError):
        scopes = frozenset()
    return MCPCredential(
        id=str(row["credential_id"]),
        name=str(row["name"]),
        token_prefix=str(row.get("token_prefix") or ""),
        scopes=scopes,
        created_at=str(row["created_at"]),
        last_used_at=row.get("last_used_at"),
        last_used_ip=row.get("last_used_ip"),
        access_count=int(row.get("access_count") or 0),
        revoked_at=row.get("revoked_at"),
    )


async def create_credential(name: str, scopes: Iterable[str] | None = None) -> tuple[MCPCredential, str]:
    await ensure_mcp_credentials_table()
    clean_name = name.strip()
    if not clean_name:
        raise ValueError("Credential name is required")
    if len(clean_name) > 120:
        raise ValueError("Credential name is too long")
    normalised_scopes = _normalise_scopes(scopes)
    credential_id = str(uuid.uuid4())
    secret = secrets.token_urlsafe(32)
    token = f"{TOKEN_PREFIX}{credential_id.replace('-', '')[:12]}_{secret}"
    token_prefix = token[: len(TOKEN_PREFIX) + 12]
    await execute(
        """
        INSERT INTO mcp_credentials
          (credential_id, name, token_prefix, token_hash, scopes_json)
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            credential_id,
            clean_name,
            token_prefix,
            _digest(token),
            json.dumps(sorted(normalised_scopes), separators=(",", ":")),
        ),
    )
    row = await fetch_one("SELECT * FROM mcp_credentials WHERE credential_id = ?", (credential_id,))
    if not row:
        raise RuntimeError("MCP credential was not persisted")
    return _credential_from_row(row), token


async def list_credentials(*, include_revoked: bool = True) -> list[MCPCredential]:
    await ensure_mcp_credentials_table()
    query = "SELECT * FROM mcp_credentials"
    params: tuple[Any, ...] = ()
    if not include_revoked:
        query += " WHERE revoked_at IS NULL"
    query += " ORDER BY created_at DESC, credential_id DESC"
    return [_credential_from_row(row) for row in await fetch_all(query, params)]


async def revoke_credential(credential_id: str) -> bool:
    await ensure_mcp_credentials_table()
    changed = await execute(
        """
        UPDATE mcp_credentials
        SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
        WHERE credential_id = ?
        """,
        (credential_id,),
    )
    return changed > 0


async def authenticate_token(token: str, *, client_ip: str | None = None) -> MCPCredential | None:
    """Verify a PAT and atomically update its audit counters."""
    await ensure_mcp_credentials_table()
    if not token or len(token) > 512:
        return None
    digest = _digest(token)
    row = await fetch_one(
        "SELECT * FROM mcp_credentials WHERE token_hash = ? AND revoked_at IS NULL",
        (digest,),
    )
    if not row or not hmac.compare_digest(str(row.get("token_hash", "")), digest):
        return None
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    await execute(
        """
        UPDATE mcp_credentials
        SET last_used_at = ?, last_used_ip = ?, access_count = access_count + 1
        WHERE credential_id = ? AND revoked_at IS NULL
        """,
        (now, client_ip, row["credential_id"]),
    )
    # ``access_audit`` is the shared audit ledger used by device and API
    # access. Keep the credential's denormalised counters for the admin list,
    # while recording every successful request in the common ledger.
    try:
        await execute(
            """
            INSERT INTO access_audit
              (actor_kind, actor_id, action, outcome, occurred_at, details_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                "mcp",
                str(row["credential_id"]),
                "mcp.request",
                "success",
                now,
                json.dumps({"token_prefix": row.get("token_prefix"), "client_ip": client_ip}, separators=(",", ":")),
            ),
        )
    except Exception:
        # A pre-005 database can still serve a credential; migration 005 adds
        # the shared ledger and normal installations will always audit here.
        pass
    updated = dict(row)
    updated["last_used_at"] = now
    updated["last_used_ip"] = client_ip
    updated["access_count"] = int(row.get("access_count") or 0) + 1
    return _credential_from_row(updated)


def credential_has_scope(credential: MCPCredential, scope: str) -> bool:
    return scope in credential.scopes


def credential_public_dict(credential: MCPCredential) -> dict[str, Any]:
    return {
        "id": credential.id,
        "name": credential.name,
        "token_prefix": credential.token_prefix,
        "scopes": sorted(credential.scopes),
        "created_at": credential.created_at,
        "last_used_at": credential.last_used_at,
        "last_used_ip": credential.last_used_ip,
        "access_count": credential.access_count,
        "revoked_at": credential.revoked_at,
        "revoked": credential.revoked,
    }
