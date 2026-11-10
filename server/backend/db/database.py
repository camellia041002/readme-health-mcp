from __future__ import annotations

import asyncio
import threading
from typing import Any

import aiosqlite

from app_settings import get_settings
from db.schema_migrations import CURRENT_SCHEMA_VERSION, pending_migrations

SCHEMA_META_SQL = """
CREATE TABLE IF NOT EXISTS schema_meta (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  schema_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT OR IGNORE INTO schema_meta (id, schema_version) VALUES (1, 0);
"""

# Changing SQLite's journal mode is a database-wide operation.  Running the
# PRAGMA for every short-lived request connection lets concurrent connections
# contend for that operation (and can surface ``database is locked`` during a
# busy ingestion).  Keep the one-time setup process-local; the application
# lifespan calls ``init_db`` before serving requests, while this also preserves
# the safe behaviour for callers that open a connection directly.
_WAL_SETUP_LOCK = threading.Lock()
_WAL_INITIALIZED_PATHS: set[str] = set()


async def get_connection() -> aiosqlite.Connection:
    settings = get_settings()
    settings.db_path.parent.mkdir(parents=True, exist_ok=True)
    db_path = settings.db_path.resolve().as_posix()
    setup_lock_acquired = False
    if db_path not in _WAL_INITIALIZED_PATHS:
        # Acquire the process-wide lock in a worker thread so a second event
        # loop (or another request on this loop) waits without blocking the
        # event loop while the first connection performs async SQLite work.
        await asyncio.to_thread(_WAL_SETUP_LOCK.acquire)
        setup_lock_acquired = True

    conn = None
    try:
        conn = await aiosqlite.connect(settings.db_path.as_posix())
        conn.row_factory = aiosqlite.Row
        # Set the timeout before the database-wide WAL transition so a
        # startup connection waits for a brief in-flight writer rather than
        # failing immediately with ``database is locked``.
        await conn.execute("PRAGMA busy_timeout=5000;")
        await conn.execute("PRAGMA synchronous=NORMAL;")
        await conn.execute("PRAGMA foreign_keys=ON;")
        # Overwrite deleted SQLite cells so expired health values are not left
        # recoverable in ordinary database pages between retention compactions.
        await conn.execute("PRAGMA secure_delete=ON;")
        if db_path not in _WAL_INITIALIZED_PATHS:
            cursor = await conn.execute("PRAGMA journal_mode=WAL;")
            try:
                row = await cursor.fetchone()
            finally:
                await cursor.close()
            if not row or str(row[0]).lower() != "wal":
                raise RuntimeError("SQLite did not enable WAL journal mode")
            # journal_mode changes are persisted by SQLite, but committing
            # here makes the initialization boundary explicit before other
            # connections are allowed to skip the PRAGMA.
            await conn.commit()
            _WAL_INITIALIZED_PATHS.add(db_path)
        return conn
    except Exception:
        if conn is not None:
            await conn.close()
        raise
    finally:
        if setup_lock_acquired:
            _WAL_SETUP_LOCK.release()


async def _bootstrap_schema_meta(conn: aiosqlite.Connection) -> None:
    await conn.executescript(SCHEMA_META_SQL)
    await conn.commit()


async def _get_schema_version(conn: aiosqlite.Connection) -> int:
    async with conn.execute("SELECT schema_version FROM schema_meta WHERE id = 1") as cursor:
        row = await cursor.fetchone()
        return int(row["schema_version"]) if row else 0


async def _set_schema_version(conn: aiosqlite.Connection, version: int) -> None:
    await conn.execute(
        """
        UPDATE schema_meta
        SET schema_version = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = 1
        """,
        (version,),
    )


async def init_db() -> None:
    conn = await get_connection()
    try:
        await _bootstrap_schema_meta(conn)
        current_version = await _get_schema_version(conn)
        for version, path in pending_migrations(current_version):
            migration_sql = path.read_text(encoding="utf-8")
            await conn.executescript(migration_sql)
            await _set_schema_version(conn, version)
            await conn.commit()
        if current_version < CURRENT_SCHEMA_VERSION:
            await _set_schema_version(conn, CURRENT_SCHEMA_VERSION)
            await conn.commit()
        await conn.commit()
    finally:
        await conn.close()


async def fetch_one(query: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
    conn = await get_connection()
    try:
        async with conn.execute(query, params) as cursor:
            row = await cursor.fetchone()
            return dict(row) if row else None
    finally:
        await conn.close()


async def fetch_all(query: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    conn = await get_connection()
    try:
        async with conn.execute(query, params) as cursor:
            rows = await cursor.fetchall()
            return [dict(row) for row in rows]
    finally:
        await conn.close()


async def execute(query: str, params: tuple[Any, ...] = ()) -> int:
    conn = await get_connection()
    try:
        cursor = await conn.execute(query, params)
        await conn.commit()
        return cursor.rowcount
    finally:
        await conn.close()
