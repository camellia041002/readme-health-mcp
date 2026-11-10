from __future__ import annotations

import asyncio
import hashlib
import json
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from db.database import get_connection
from services.health_connect_projection import (
    delete_projection,
    project_upsert,
    recompute_daily_summaries,
)
from services.retention import is_payload_expired


ENROLLMENT_TTL_SECONDS = 5 * 60
MAX_SYNC_RECORDS = 5_000
MAX_SYNC_PAYLOAD_BYTES = 8 * 1024 * 1024
MAX_SYNC_RUN_ID_LENGTH = 128
_SYNC_RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SYNC_RUN_KINDS = {"initial", "automatic", "incremental", "manual", "full"}

# SQLite permits concurrent readers but only one writer at a time.  A device
# can legitimately have the foreground sync and WorkManager submit batches at
# the same time, so serialize the complete ingestion transaction in this
# process.  The lock intentionally covers projection and audit writes too;
# releasing it before commit would reintroduce the race at BEGIN IMMEDIATE.
_CHANGE_BATCH_INGEST_LOCK = asyncio.Lock()


class DeviceSyncError(ValueError):
    pass


class BatchConflictError(DeviceSyncError):
    pass


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_utc(value: datetime | None = None) -> str:
    return (value or utc_now()).isoformat().replace("+00:00", "Z")


def hash_credential(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _run_metadata(payload: dict[str, Any], *, now: str) -> dict[str, Any] | None:
    """Validate and normalize optional run metadata for direct service callers too."""
    run_id = payload.get("run_id")
    if run_id is None:
        return None
    if not isinstance(run_id, str) or len(run_id) > MAX_SYNC_RUN_ID_LENGTH or not _SYNC_RUN_ID_PATTERN.fullmatch(run_id):
        raise DeviceSyncError("run_id must be a bounded UUID-like identifier")
    run_kind = payload.get("run_kind")
    if run_kind is not None and run_kind not in _SYNC_RUN_KINDS:
        raise DeviceSyncError("run_kind is invalid")
    if run_kind == "incremental":
        # Keep accepting the original contract spelling while presenting the
        # user-facing history with the clearer periodic-run label.
        run_kind = "automatic"
    run_started_at = payload.get("run_started_at")
    if run_started_at is None:
        run_started_at = now
    elif not isinstance(run_started_at, str):
        raise DeviceSyncError("run_started_at must be an ISO-8601 timestamp")
    else:
        try:
            parsed = datetime.fromisoformat(run_started_at.replace("Z", "+00:00"))
        except ValueError as exc:
            raise DeviceSyncError("run_started_at must be an ISO-8601 timestamp") from exc
        if parsed.tzinfo is None:
            raise DeviceSyncError("run_started_at must include a timezone")
    mode = str(payload.get("mode") or "changes")
    if run_kind is None:
        run_kind = "initial" if mode == "snapshot" else "automatic"
    for field in ("run_total_records", "run_processed_records", "run_duration_ms"):
        value = payload.get(field)
        if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value < 0):
            raise DeviceSyncError(f"{field} must be a nonnegative integer")
    run_current_record_type = payload.get("run_current_record_type")
    if run_current_record_type is not None and (
        not isinstance(run_current_record_type, str)
        or len(run_current_record_type) > 180
        or not re.fullmatch(r"^[A-Za-z0-9_.]+$", run_current_record_type)
    ):
        raise DeviceSyncError("run_current_record_type is invalid")
    run_completed = payload.get("run_completed", False)
    if run_completed is None:
        run_completed = False
    if not isinstance(run_completed, bool):
        raise DeviceSyncError("run_completed must be a boolean")
    return {
        "run_id": run_id,
        "run_kind": run_kind,
        "run_started_at": run_started_at,
        "run_total_records": payload.get("run_total_records"),
        "run_processed_records": payload.get("run_processed_records"),
        "run_completed": run_completed,
        "run_duration_ms": payload.get("run_duration_ms"),
        "run_current_record_type": run_current_record_type,
    }


async def audit_access(
    *,
    actor_kind: str,
    actor_id: str | None,
    action: str,
    outcome: str,
    details: dict[str, Any] | None = None,
    conn=None,
) -> None:
    owns_connection = conn is None
    if owns_connection:
        conn = await get_connection()
    try:
        await conn.execute(
            """
            INSERT INTO access_audit (
              actor_kind, actor_id, action, outcome, occurred_at, details_json
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                actor_kind,
                actor_id,
                action,
                outcome,
                iso_utc(),
                canonical_json(details) if details else None,
            ),
        )
        if owns_connection:
            await conn.commit()
    finally:
        if owns_connection:
            await conn.close()


async def create_enrollment_token(
    *,
    label: str | None,
    ttl_seconds: int = ENROLLMENT_TTL_SECONDS,
) -> dict[str, str | None]:
    enrollment_id = f"enr_{uuid.uuid4().hex}"
    token = secrets.token_urlsafe(32)
    now = utc_now()
    expires_at = now + timedelta(seconds=ttl_seconds)
    conn = await get_connection()
    try:
        await conn.execute(
            """
            INSERT INTO device_enrollment_tokens (
              enrollment_id, token_hash, token_prefix, label, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                enrollment_id,
                hash_credential(token),
                token[:8],
                label,
                iso_utc(now),
                iso_utc(expires_at),
            ),
        )
        await audit_access(
            actor_kind="browser",
            actor_id=None,
            action="device.enrollment.create",
            outcome="success",
            details={"enrollment_id": enrollment_id, "label": label, "ttl_seconds": ttl_seconds},
            conn=conn,
        )
        await conn.commit()
    finally:
        await conn.close()
    return {
        "enrollment_id": enrollment_id,
        "token": token,
        "label": label,
        "created_at": iso_utc(now),
        "expires_at": iso_utc(expires_at),
    }


async def list_reader_access(limit: int = 50) -> list[dict[str, Any]]:
    """Return MCP read history with bounded value summaries and no raw rows or secrets."""
    bounded_limit = max(1, min(limit, 100))
    conn = await get_connection()
    try:
        async with conn.execute(
            """
            SELECT a.action, a.outcome, a.occurred_at, a.details_json,
                   c.name AS credential_name
            FROM access_audit AS a
            LEFT JOIN mcp_credentials AS c ON c.credential_id = a.actor_id
            WHERE a.actor_kind = 'mcp_read'
            ORDER BY a.occurred_at DESC, a.audit_id DESC
            LIMIT ?
            """,
            (bounded_limit,),
        ) as cursor:
            rows = await cursor.fetchall()
        events: list[dict[str, Any]] = []
        for row in rows:
            details = json.loads(row["details_json"] or "{}")
            if details.get("hide_from_reader_history") is True:
                continue
            tool = str(details.get("tool") or str(row["action"]).rsplit(".", 1)[-1])
            if tool in {"list_data_types", "describe_data_type", "list_health_connect_record_types", "get_data_coverage"}:
                continue
            stored_name = str(row["credential_name"] or details.get("reader_name") or "只读访问者")
            occurred_at = str(row["occurred_at"] or "")
            if occurred_at and "T" not in occurred_at:
                occurred_at = occurred_at.replace(" ", "T") + "Z"
            events.append(
                {
                    "reader_name": stored_name,
                    "tool": tool,
                    "data_types": [str(item) for item in details.get("data_types", [])][:20],
                    "start_date": details.get("start_date"),
                    "end_date": details.get("end_date"),
                    "returned_rows": details.get("returned_rows"),
                    "result_summary": details.get("result_summary"),
                    "outcome": str(row["outcome"]),
                    "occurred_at": occurred_at,
                }
            )
        return events
    finally:
        await conn.close()


async def exchange_enrollment_token(
    *,
    token: str,
    device_name: str,
    app_version: str | None,
    platform: str,
    device_model: str | None,
) -> dict[str, str] | None:
    now = iso_utc()
    token_hash = hash_credential(token)
    device_id = f"dev_{uuid.uuid4().hex}"
    device_secret = secrets.token_urlsafe(48)
    device_token = f"hqd_{device_id}.{device_secret}"
    conn = await get_connection()
    try:
        await conn.execute("BEGIN IMMEDIATE")
        cursor = await conn.execute(
            """
            UPDATE device_enrollment_tokens
            SET consumed_at = ?
            WHERE token_hash = ?
              AND consumed_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            RETURNING enrollment_id
            """,
            (now, token_hash, now),
        )
        consumed = await cursor.fetchone()
        if consumed is None:
            await conn.rollback()
            await audit_access(
                actor_kind="enrollment",
                actor_id=None,
                action="device.enrollment.exchange",
                outcome="rejected",
                details={"reason": "invalid_unavailable_or_expired"},
            )
            return None
        await conn.execute(
            """
            INSERT INTO devices (
              device_id, name, platform, device_model, app_version,
              credential_hash, credential_prefix, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                device_id,
                device_name,
                platform,
                device_model,
                app_version,
                hash_credential(device_token),
                device_token[:16],
                now,
            ),
        )
        await audit_access(
            actor_kind="device",
            actor_id=device_id,
            action="device.enrollment.exchange",
            outcome="success",
            details={"enrollment_id": consumed["enrollment_id"]},
            conn=conn,
        )
        await conn.commit()
    except Exception:
        await conn.rollback()
        raise
    finally:
        await conn.close()
    return {
        "device_id": device_id,
        "device_token": device_token,
        "credential": device_token,
        "sync_path": "/api/device-sync/v1/changes",
        "created_at": now,
    }


async def authenticate_device(token: str) -> dict[str, Any] | None:
    conn = await get_connection()
    try:
        async with conn.execute(
            """
            SELECT device_id, name, platform, device_model, app_version,
                   created_at, last_seen_at, last_successful_sync_at, last_change_token
            FROM devices
            WHERE credential_hash = ? AND revoked_at IS NULL
            LIMIT 1
            """,
            (hash_credential(token),),
        ) as cursor:
            row = await cursor.fetchone()
            return dict(row) if row else None
    finally:
        await conn.close()


def _batch_result(row: dict[str, Any], *, replayed: bool) -> dict[str, Any]:
    return {
        "status": "success" if row["status"] == "complete" else row["status"],
        "batch_id": row["batch_id"],
        "accepted": row["accepted_count"],
        "upserted": row["upserted_count"],
        "deleted": row["deleted_count"],
        "errors": row["error_count"],
        "next_change_token": row["next_change_token"],
        "replayed": replayed,
    }


async def process_change_batch(*, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    async with _CHANGE_BATCH_INGEST_LOCK:
        return await _process_change_batch_unlocked(device_id=device_id, payload=payload)


async def _process_change_batch_unlocked(*, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    records = payload.get("records")
    if not isinstance(records, list):
        raise DeviceSyncError("records must be a list")
    if len(records) > MAX_SYNC_RECORDS:
        raise DeviceSyncError(f"batch exceeds the {MAX_SYNC_RECORDS} record limit")
    raw_payload = canonical_json(payload)
    payload_bytes = len(raw_payload.encode("utf-8"))
    if payload_bytes > MAX_SYNC_PAYLOAD_BYTES:
        raise DeviceSyncError(f"batch exceeds the {MAX_SYNC_PAYLOAD_BYTES} byte limit")
    payload_sha256 = hashlib.sha256(raw_payload.encode("utf-8")).hexdigest()
    batch_id = str(payload["batch_id"])
    now = iso_utc()
    run = _run_metadata(payload, now=now)
    conn = await get_connection()
    try:
        await conn.execute("BEGIN IMMEDIATE")
        async with conn.execute(
            "SELECT * FROM device_sync_batches WHERE batch_id = ?",
            (batch_id,),
        ) as cursor:
            existing_row = await cursor.fetchone()
        if existing_row is not None:
            existing = dict(existing_row)
            if existing["device_id"] != device_id or existing["payload_sha256"] != payload_sha256:
                await conn.rollback()
                raise BatchConflictError("batch_id was already used with different content")
            await conn.rollback()
            return _batch_result(existing, replayed=True)

        await conn.execute(
            """
            INSERT INTO device_sync_batches (
              batch_id, device_id, protocol_version, mode, payload_sha256,
              payload_json, received_at, status, next_change_token
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'processing', ?)
            """,
            (
                batch_id,
                device_id,
                int(payload["protocol_version"]),
                str(payload["mode"]),
                payload_sha256,
                raw_payload,
                now,
                payload.get("next_change_token"),
            ),
        )
        projection_batch_id = f"projection:{batch_id}"
        await conn.execute(
            """
            INSERT INTO ingest_batches (
              batch_id, source, received_at, processed_count, error_count,
              status, payload_json, notes
            ) VALUES (?, ?, ?, 0, 0, 'processing', ?, ?)
            """,
            (
                projection_batch_id,
                f"device:{device_id}",
                now,
                raw_payload,
                "Lossless Health Connect device sync projection",
            ),
        )
        if run is not None:
            # A run row is created in the same transaction as its first batch.
            # Subsequent batches keep the original start/kind metadata while
            # their counters are applied only after this new batch completes.
            await conn.execute(
                """
                INSERT INTO device_sync_runs (
                  device_id, run_id, started_at, status, kind,
                  total_records, current_record_type
                ) VALUES (?, ?, ?, 'running', ?, ?, ?)
                ON CONFLICT(device_id, run_id) DO NOTHING
                """,
                (
                    device_id,
                    run["run_id"],
                    run["run_started_at"],
                    run["run_kind"],
                    run["run_total_records"],
                    run["run_current_record_type"],
                ),
            )
        upserted = 0
        deleted = 0
        retention_skipped = 0
        projection_errors: list[dict[str, str]] = []
        touched_dates: set[str] = set()
        for envelope in records:
            operation = envelope["operation"]
            record_type = envelope["record_type"]
            record_id = envelope["record_id"]
            if operation == "upsert":
                if is_payload_expired(envelope["payload"]):
                    touched_dates.update(
                        await delete_projection(
                            conn,
                            device_id=device_id,
                            record_type=record_type,
                            record_id=record_id,
                        )
                    )
                    await conn.execute(
                        """
                        DELETE FROM health_connect_records
                        WHERE device_id = ? AND record_type = ? AND record_id = ?
                        """,
                        (device_id, record_type, record_id),
                    )
                    retention_skipped += 1
                    continue
                record_payload = canonical_json(envelope["payload"])
                await conn.execute(
                    """
                    INSERT INTO health_connect_records (
                      device_id, record_type, record_id, payload_json,
                      first_seen_at, updated_at, deleted_at, last_batch_id
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                    ON CONFLICT(device_id, record_type, record_id) DO UPDATE SET
                      payload_json = excluded.payload_json,
                      updated_at = excluded.updated_at,
                      deleted_at = NULL,
                      last_batch_id = excluded.last_batch_id
                    """,
                    (device_id, record_type, record_id, record_payload, now, now, batch_id),
                )
                upserted += 1
                try:
                    touched_dates.update(
                        await project_upsert(
                            conn,
                            device_id=device_id,
                            record_type=record_type,
                            record_id=record_id,
                            payload=envelope["payload"],
                            batch_id=projection_batch_id,
                        )
                    )
                except Exception as exc:
                    projection_errors.append(
                        {"record_type": record_type, "record_id": record_id, "reason": str(exc)}
                    )
            else:
                matched_unknown_type = False
                projection_types = [record_type]
                if record_type == "unknown":
                    async with conn.execute(
                        """
                        SELECT record_type FROM health_connect_records
                        WHERE device_id = ? AND record_id = ? AND deleted_at IS NULL
                        """,
                        (device_id, record_id),
                    ) as cursor:
                        projection_types = [str(row["record_type"]) for row in await cursor.fetchall()]
                    cursor = await conn.execute(
                        """
                        UPDATE health_connect_records
                        SET updated_at = ?, deleted_at = ?, last_batch_id = ?
                        WHERE device_id = ? AND record_id = ? AND deleted_at IS NULL
                        """,
                        (now, now, batch_id, device_id, record_id),
                    )
                    matched_unknown_type = cursor.rowcount > 0
                if not matched_unknown_type:
                    await conn.execute(
                        """
                        INSERT INTO health_connect_records (
                          device_id, record_type, record_id, payload_json,
                          first_seen_at, updated_at, deleted_at, last_batch_id
                        ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?)
                        ON CONFLICT(device_id, record_type, record_id) DO UPDATE SET
                          updated_at = excluded.updated_at,
                          deleted_at = excluded.deleted_at,
                          last_batch_id = excluded.last_batch_id
                        """,
                        (device_id, record_type, record_id, now, now, now, batch_id),
                    )
                deleted += 1
                for projected_type in projection_types:
                    try:
                        touched_dates.update(
                            await delete_projection(
                                conn,
                                device_id=device_id,
                                record_type=projected_type,
                                record_id=record_id,
                            )
                        )
                    except Exception as exc:
                        projection_errors.append(
                            {"record_type": projected_type, "record_id": record_id, "reason": str(exc)}
                        )

        await recompute_daily_summaries(conn, touched_dates)

        next_change_token = payload.get("next_change_token")
        await conn.execute(
            """
            UPDATE device_sync_batches
            SET completed_at = ?, status = 'complete', accepted_count = ?,
                upserted_count = ?, deleted_count = ?, error_count = ?, error_json = ?
                , payload_json = '{}'
            WHERE batch_id = ?
            """,
            (
                now,
                len(records),
                upserted,
                deleted,
                len(projection_errors),
                canonical_json(projection_errors) if projection_errors else None,
                batch_id,
            ),
        )
        await conn.execute(
            """
            UPDATE ingest_batches
            SET processed_count = ?, error_count = ?, status = ?, notes = ?, payload_json = '{}'
            WHERE batch_id = ?
            """,
            (
                len(records),
                len(projection_errors),
                "completed" if not projection_errors else "completed_with_projection_errors",
                canonical_json(projection_errors) if projection_errors else "Lossless Health Connect device sync projection",
                projection_batch_id,
            ),
        )
        await conn.execute(
            """
            UPDATE devices
            SET last_seen_at = ?, last_successful_sync_at = ?,
                last_change_token = ?, app_version = COALESCE(?, app_version),
                last_error = NULL
            WHERE device_id = ? AND revoked_at IS NULL
            """,
            (now, now, next_change_token, payload.get("app_version"), device_id),
        )
        if run is not None:
            # This update is reached only for a newly inserted batch. Replay
            # returns above before touching the run, so accepted operations,
            # batch count, and errors cannot be double counted.
            await conn.execute(
                """
                UPDATE device_sync_runs
                SET total_records = COALESCE(total_records, ?),
                    processed_records = CASE
                      WHEN ? IS NULL THEN processed_records + ?
                      ELSE MAX(processed_records, ?)
                    END,
                    accepted_operations = accepted_operations + ?,
                    batch_count = batch_count + 1,
                    error_count = error_count + ?,
                    duration_ms = COALESCE(?, duration_ms),
                    current_record_type = COALESCE(?, current_record_type),
                    completed_at = CASE WHEN ? THEN ? ELSE completed_at END,
                    status = CASE WHEN ? THEN 'completed' ELSE status END
                WHERE device_id = ? AND run_id = ?
                """,
                (
                    run["run_total_records"],
                    run["run_processed_records"],
                    len(records),
                    run["run_processed_records"],
                    len(records),
                    len(projection_errors),
                    run["run_duration_ms"],
                    run["run_current_record_type"],
                    1 if run["run_completed"] else 0,
                    now,
                    1 if run["run_completed"] else 0,
                    device_id,
                    run["run_id"],
                ),
            )
        await audit_access(
            actor_kind="device",
            actor_id=device_id,
            action="device.sync",
            outcome="success",
            details={
                "batch_id": batch_id,
                "mode": payload["mode"],
                "accepted": len(records),
                "payload_bytes": payload_bytes,
                "retention_skipped": retention_skipped,
            },
            conn=conn,
        )
        await conn.commit()
        return {
            "status": "success",
            "batch_id": batch_id,
            "accepted": len(records),
            "upserted": upserted,
            "deleted": deleted,
            "errors": len(projection_errors),
            "next_change_token": next_change_token,
            "replayed": False,
        }
    except Exception:
        await conn.rollback()
        raise
    finally:
        await conn.close()


async def list_devices() -> list[dict[str, Any]]:
    conn = await get_connection()
    try:
        async with conn.execute(
            """
            SELECT device_id, name, platform, device_model, app_version,
                   credential_prefix, created_at, last_seen_at,
                   last_successful_sync_at, last_change_token, last_error, revoked_at
            FROM devices
            ORDER BY created_at DESC
            """
        ) as cursor:
            return [dict(row) for row in await cursor.fetchall()]
    finally:
        await conn.close()


async def revoke_device(device_id: str) -> bool:
    now = iso_utc()
    conn = await get_connection()
    try:
        cursor = await conn.execute(
            "UPDATE devices SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL",
            (now, device_id),
        )
        revoked = cursor.rowcount > 0
        if revoked:
            await audit_access(
                actor_kind="browser",
                actor_id=None,
                action="device.revoke",
                outcome="success",
                details={"device_id": device_id},
                conn=conn,
            )
        await conn.commit()
        return revoked
    finally:
        await conn.close()


async def list_sync_runs(*, limit: int = 50, device_id: str | None = None) -> list[dict[str, Any]]:
    """Return bounded run summaries, optionally scoped to one device."""
    conn = await get_connection()
    try:
        bounded_limit = max(1, min(limit, 200))
        if device_id:
            query = """
                SELECT device_id, run_id, started_at, completed_at, status, kind,
                       total_records, processed_records, accepted_operations,
                       batch_count, error_count, duration_ms, current_record_type
                FROM device_sync_runs
                WHERE device_id = ?
                ORDER BY started_at DESC, run_id DESC
                LIMIT ?
            """
            params = (device_id, bounded_limit)
        else:
            query = """
                SELECT device_id, run_id, started_at, completed_at, status, kind,
                       total_records, processed_records, accepted_operations,
                       batch_count, error_count, duration_ms, current_record_type
                FROM device_sync_runs
                ORDER BY started_at DESC, device_id ASC, run_id DESC
                LIMIT ?
            """
            params = (bounded_limit,)
        async with conn.execute(query, params) as cursor:
            return [dict(row) for row in await cursor.fetchall()]
    finally:
        await conn.close()


async def get_device_sync_history(*, device_id: str, limit: int = 50) -> list[dict[str, Any]]:
    """Device-authenticated history; device_id is always supplied by auth."""
    return await list_sync_runs(limit=limit, device_id=device_id)


async def get_sync_inspection(
    *,
    batch_limit: int = 50,
    record_limit: int = 100,
    record_type: str | None = None,
) -> dict[str, Any]:
    conn = await get_connection()
    try:
        async with conn.execute(
            """
            SELECT record_type,
                   COUNT(*) AS total_count,
                   SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END) AS active_count,
                   SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END) AS deleted_count,
                   MIN(COALESCE(
                       json_extract(payload_json, '$.startTime'),
                       json_extract(payload_json, '$.time'),
                       json_extract(payload_json, '$.metadata.lastModifiedTime'),
                       first_seen_at
                   )) AS earliest_record_at,
                   MAX(COALESCE(
                       json_extract(payload_json, '$.endTime'),
                       json_extract(payload_json, '$.time'),
                       json_extract(payload_json, '$.startTime'),
                       json_extract(payload_json, '$.metadata.lastModifiedTime'),
                       updated_at
                   )) AS latest_record_at,
                   MAX(updated_at) AS last_synced_at
            FROM health_connect_records
            GROUP BY record_type
            ORDER BY active_count DESC, record_type
            """
        ) as cursor:
            data_types = [dict(row) for row in await cursor.fetchall()]
        async with conn.execute(
            """
            SELECT batch_id, device_id, protocol_version, mode, received_at,
                   completed_at, status, accepted_count, upserted_count,
                   deleted_count, error_count, next_change_token, error_json
            FROM device_sync_batches
            ORDER BY received_at DESC
            LIMIT ?
            """,
            (max(1, min(batch_limit, 200)),),
        ) as cursor:
            batches = [dict(row) for row in await cursor.fetchall()]
        bounded_run_limit = max(1, min(batch_limit, 200))
        async with conn.execute(
            """
            SELECT device_id, run_id, started_at, completed_at, status, kind,
                   total_records, processed_records, accepted_operations,
                   batch_count, error_count, duration_ms, current_record_type
            FROM device_sync_runs
            ORDER BY started_at DESC, device_id ASC, run_id DESC
            LIMIT ?
            """,
            (bounded_run_limit,),
        ) as cursor:
            runs = [dict(row) for row in await cursor.fetchall()]
        record_query = """
            SELECT device_id, record_type, record_id, payload_json,
                   first_seen_at, updated_at, deleted_at, last_batch_id
            FROM health_connect_records
        """
        record_params: tuple[Any, ...]
        if record_type:
            record_query += " WHERE record_type = ?"
            record_params = (record_type, max(1, min(record_limit, 500)))
        else:
            record_params = (max(1, min(record_limit, 500)),)
        record_query += """
            ORDER BY updated_at DESC
            LIMIT ?
        """
        async with conn.execute(record_query, record_params) as cursor:
            records = [dict(row) for row in await cursor.fetchall()]
        errors = [
            batch
            for batch in batches
            if int(batch.get("error_count") or 0) > 0 or batch.get("error_json")
        ]
        async with conn.execute(
            """
            SELECT actor_kind, actor_id, action, outcome, occurred_at, details_json
            FROM access_audit
            ORDER BY occurred_at DESC, audit_id DESC
            LIMIT 100
            """
        ) as cursor:
            audit = [dict(row) for row in await cursor.fetchall()]
        return {
            "summary": {
                "records": sum(int(row["total_count"] or 0) for row in data_types),
                "batches": len(batches),
                "errors": len(errors),
                "data_types": len(data_types),
                "runs": len(runs),
            },
            "data_types": data_types,
            "records": records,
            "batches": batches,
            "runs": runs,
            "errors": errors,
            "audit": audit,
        }
    finally:
        await conn.close()
