from __future__ import annotations

import asyncio
import threading

import aiosqlite
import pytest

from db import database
from db.database import get_connection, init_db

pytestmark = pytest.mark.asyncio


async def _pragma_value(conn: aiosqlite.Connection, name: str):
    async with conn.execute(f"PRAGMA {name}") as cursor:
        row = await cursor.fetchone()
    return row[0]


async def test_init_db_establishes_wal_and_connection_pragmas():
    await init_db()

    conn = await get_connection()
    try:
        assert (await _pragma_value(conn, "journal_mode")).lower() == "wal"
        assert await _pragma_value(conn, "synchronous") == 1
        assert await _pragma_value(conn, "foreign_keys") == 1
        assert await _pragma_value(conn, "busy_timeout") == 5000
    finally:
        await conn.close()


async def test_concurrent_connections_initialize_wal_once(monkeypatch):
    journal_mode_calls = 0
    calls_lock = threading.Lock()
    original_execute = aiosqlite.Connection.execute

    def tracking_execute(self, query, *args, **kwargs):
        nonlocal journal_mode_calls
        if query.strip().lower() == "pragma journal_mode=wal;":
            with calls_lock:
                journal_mode_calls += 1
        return original_execute(self, query, *args, **kwargs)

    monkeypatch.setattr(aiosqlite.Connection, "execute", tracking_execute)

    connections = await asyncio.gather(*(get_connection() for _ in range(16)))
    try:
        assert journal_mode_calls == 1
        modes = await asyncio.gather(*(_pragma_value(conn, "journal_mode") for conn in connections))
        assert modes == ["wal"] * len(connections)
    finally:
        await asyncio.gather(*(conn.close() for conn in connections))


async def test_init_db_can_be_called_after_concurrent_connection_setup():
    connections = await asyncio.gather(*(get_connection() for _ in range(4)))
    await asyncio.gather(*(conn.close() for conn in connections))

    await init_db()

    conn = await get_connection()
    try:
        async with conn.execute("SELECT schema_version FROM schema_meta WHERE id = 1") as cursor:
            row = await cursor.fetchone()
        assert row[0] == database.CURRENT_SCHEMA_VERSION
        assert (await _pragma_value(conn, "journal_mode")).lower() == "wal"
    finally:
        await conn.close()
