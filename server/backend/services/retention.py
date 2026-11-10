from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Any

from app_settings import get_settings
from db.database import get_connection
from services.health_connect_projection import delete_projection


def _parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


def record_event_time(payload: dict[str, Any]) -> datetime | None:
    """Return the latest event time carried by a supported Health Connect record."""

    candidates = [
        _parse_timestamp(payload.get("endTime")),
        _parse_timestamp(payload.get("time")),
        _parse_timestamp(payload.get("startTime")),
    ]
    samples = payload.get("samples")
    if isinstance(samples, list):
        candidates.extend(
            _parse_timestamp(sample.get("time"))
            for sample in samples
            if isinstance(sample, dict)
        )
    valid = [value for value in candidates if value is not None]
    return max(valid) if valid else None


def is_payload_expired(
    payload: dict[str, Any],
    *,
    retention_days: int | None = None,
    now: datetime | None = None,
) -> bool:
    event_time = record_event_time(payload)
    if event_time is None:
        return False
    days = retention_days if retention_days is not None else get_settings().retention_days
    reference = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    return event_time < reference - timedelta(days=days)


async def prune_expired_health_data(
    *,
    retention_days: int | None = None,
    now: datetime | None = None,
    compact: bool = False,
) -> dict[str, int]:
    """Remove raw and projected health data outside the rolling retention window."""

    days = retention_days if retention_days is not None else get_settings().retention_days
    reference = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    cutoff = reference - timedelta(days=days)
    cutoff_day = cutoff.date().isoformat()
    conn = await get_connection()
    removed_records = 0
    removed_projection_rows = 0
    direct_projection_rows = 0

    async def table_exists(name: str) -> bool:
        async with conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            (name,),
        ) as cursor:
            return await cursor.fetchone() is not None

    async def delete_if_present(table: str, column: str) -> int:
        if not await table_exists(table):
            return 0
        cursor = await conn.execute(f"DELETE FROM {table} WHERE {column} < ?", (cutoff_day,))
        return max(cursor.rowcount, 0)
    try:
        await conn.execute("BEGIN IMMEDIATE")
        async with conn.execute(
            """
            SELECT device_id, record_type, record_id, payload_json
            FROM health_connect_records
            WHERE payload_json IS NOT NULL
            """
        ) as cursor:
            records = await cursor.fetchall()
        for row in records:
            try:
                payload = json.loads(row["payload_json"])
            except (TypeError, json.JSONDecodeError):
                continue
            if not isinstance(payload, dict) or not is_payload_expired(
                payload,
                retention_days=days,
                now=reference,
            ):
                continue
            before = conn.total_changes
            await delete_projection(
                conn,
                device_id=str(row["device_id"]),
                record_type=str(row["record_type"]),
                record_id=str(row["record_id"]),
            )
            removed_projection_rows += conn.total_changes - before
            await conn.execute(
                "DELETE FROM health_connect_records WHERE device_id = ? AND record_type = ? AND record_id = ?",
                (row["device_id"], row["record_type"], row["record_id"]),
            )
            removed_records += 1

        for table, column in (
            ("metric_points", "substr(recorded_at, 1, 10)"),
            ("metric_intervals", "substr(start_time, 1, 10)"),
            ("sleep_stages", "substr(start_time, 1, 10)"),
            ("sleep_sessions", "substr(start_time, 1, 10)"),
            ("workouts", "substr(start_time, 1, 10)"),
        ):
            direct_projection_rows += await delete_if_present(table, column)
        daily = await conn.execute("DELETE FROM daily_summaries WHERE summary_date < ?", (cutoff_day,))
        audit_rows = 0
        for table, column in (
            ("access_audit", "substr(occurred_at, 1, 10)"),
            ("security_audit_events", "substr(occurred_at, 1, 10)"),
            ("device_enrollment_tokens", "substr(expires_at, 1, 10)"),
            ("device_sync_runs", "substr(started_at, 1, 10)"),
            ("timeline_events", "substr(event_time, 1, 10)"),
        ):
            audit_rows += await delete_if_present(table, column)
        # Batch bodies are not needed for idempotency; retain only bounded metadata and hashes.
        redacted_device_batches = await conn.execute(
            "UPDATE device_sync_batches SET payload_json = '{}' WHERE payload_json != '{}'"
        )
        redacted_ingest_batches = await conn.execute(
            """
            UPDATE ingest_batches
            SET payload_json = '{}'
            WHERE payload_json != '{}'
            """
        )
        await conn.commit()
        if compact:
            checkpoint = await conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            await checkpoint.fetchall()
            await checkpoint.close()
            vacuum = await conn.execute("VACUUM")
            await vacuum.close()
        return {
            "health_records": removed_records,
            "projection_rows": removed_projection_rows + direct_projection_rows,
            "daily_summaries": max(daily.rowcount, 0),
            "audit_and_import_rows": audit_rows,
            "redacted_device_batches": max(redacted_device_batches.rowcount, 0),
            "redacted_ingest_batches": max(redacted_ingest_batches.rowcount, 0),
        }
    except Exception:
        await conn.rollback()
        raise
    finally:
        await conn.close()
