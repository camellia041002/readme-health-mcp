from __future__ import annotations

import pytest

from db.database import execute, init_db
from services.safe_sql import SafeSqlTimeoutError, execute_safe_select


@pytest.mark.asyncio
async def test_safe_sql_caps_rows_bytes_and_serializes_blobs():
    await init_db()
    await execute("CREATE TABLE safe_sql_values (id INTEGER, payload BLOB)")
    for value in range(5):
        await execute("INSERT INTO safe_sql_values VALUES (?, ?)", (value, bytes([value])))
    result = await execute_safe_select(
        "SELECT id, payload FROM safe_sql_values ORDER BY id",
        max_rows=3,
        max_bytes=10_000,
    )
    assert result.truncated is True
    assert len(result.rows) == 3
    assert result.rows[0]["payload"] == {"encoding": "base64", "data": "AA=="}


@pytest.mark.asyncio
async def test_safe_sql_interrupts_runaway_recursive_query():
    await init_db()
    with pytest.raises(SafeSqlTimeoutError):
        await execute_safe_select(
            """
            WITH RECURSIVE forever AS (
              SELECT 1 AS value
              UNION ALL
              SELECT value + 1 FROM forever
            )
            SELECT SUM(value) FROM forever
            """,
            timeout_seconds=0.001,
        )
