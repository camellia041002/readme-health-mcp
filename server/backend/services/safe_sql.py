from __future__ import annotations

import base64
import json
import sqlite3
import time
from dataclasses import dataclass
from typing import Any

from db.database import get_connection
from services.sql_guard import validate_read_only_sql


DEFAULT_MAX_ROWS = 1_000
DEFAULT_MAX_BYTES = 1_000_000
DEFAULT_TIMEOUT_SECONDS = 2.0


class SafeSqlTimeoutError(TimeoutError):
    pass


@dataclass(frozen=True)
class SafeSqlResult:
    sql: str
    rows: list[dict[str, Any]]
    byte_count: int
    truncated: bool

    def as_dict(self) -> dict[str, Any]:
        return {
            "sql": self.sql,
            "row_count": len(self.rows),
            "returned_row_count": len(self.rows),
            "byte_count": self.byte_count,
            "truncated": self.truncated,
            "rows": self.rows,
        }


def _json_safe(value: Any) -> Any:
    if isinstance(value, bytes):
        return {"encoding": "base64", "data": base64.b64encode(value).decode("ascii")}
    return value


def _cap_rows(
    rows: list[dict[str, Any]],
    *,
    max_rows: int,
    max_bytes: int,
) -> tuple[list[dict[str, Any]], int, bool]:
    capped: list[dict[str, Any]] = []
    byte_count = len("[]")
    for raw_row in rows:
        row = {key: _json_safe(value) for key, value in raw_row.items()}
        encoded = json.dumps(row, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        overhead = 1 if capped else 0
        if len(capped) >= max_rows or byte_count + overhead + len(encoded) > max_bytes:
            return capped, byte_count, True
        capped.append(row)
        byte_count += overhead + len(encoded)
    return capped, byte_count, False


async def execute_safe_select(
    sql: str,
    *,
    max_rows: int = DEFAULT_MAX_ROWS,
    max_bytes: int = DEFAULT_MAX_BYTES,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
) -> SafeSqlResult:
    if max_rows < 1 or max_bytes < 2 or timeout_seconds <= 0:
        raise ValueError("SQL execution limits must be positive")
    guard = validate_read_only_sql(sql)
    deadline = time.monotonic() + timeout_seconds
    conn = await get_connection()
    try:
        await conn.set_progress_handler(lambda: 1 if time.monotonic() >= deadline else 0, 1_000)
        limited_sql = f"SELECT * FROM ({guard.normalized_sql}) LIMIT {max_rows + 1}"
        try:
            async with conn.execute(limited_sql) as cursor:
                fetched = [dict(row) for row in await cursor.fetchall()]
        except sqlite3.OperationalError as exc:
            if "interrupted" in str(exc).lower() or time.monotonic() >= deadline:
                raise SafeSqlTimeoutError(
                    f"SQL query exceeded the {timeout_seconds:g} second execution limit"
                ) from exc
            raise
    finally:
        await conn.set_progress_handler(None, 0)
        await conn.close()
    db_truncated = len(fetched) > max_rows
    if db_truncated:
        fetched = fetched[:max_rows]
    capped, byte_count, byte_truncated = _cap_rows(
        fetched,
        max_rows=max_rows,
        max_bytes=max_bytes,
    )
    return SafeSqlResult(
        sql=guard.normalized_sql,
        rows=capped,
        byte_count=byte_count,
        truncated=db_truncated or byte_truncated,
    )
