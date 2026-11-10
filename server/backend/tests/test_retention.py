from __future__ import annotations

import json
from datetime import datetime, timezone

import aiosqlite
import httpx
import pytest
from httpx import ASGITransport

from db.database import execute, fetch_one, init_db
from services.retention import is_payload_expired, prune_expired_health_data, record_event_time
from services.device_sync import process_change_batch
from services.device_sync import hash_credential
from main import app


NOW = datetime(2026, 11, 3, tzinfo=timezone.utc)


def test_record_event_time_uses_latest_supported_timestamp():
    payload = {
        "startTime": "2026-11-01T00:00:00Z",
        "endTime": "2026-11-01T01:00:00Z",
        "samples": [{"time": "2026-11-01T00:30:00Z"}],
    }
    assert record_event_time(payload) == datetime(2026, 11, 1, 1, tzinfo=timezone.utc)
    assert not is_payload_expired(payload, retention_days=365, now=NOW)
    assert is_payload_expired(
        {"time": "2024-01-01T00:00:00Z"},
        retention_days=365,
        now=NOW,
    )


@pytest.mark.asyncio
async def test_prune_removes_expired_raw_and_projected_rows_and_redacts_batches():
    await init_db()
    await execute(
        """
        INSERT INTO devices (
          device_id, name, credential_hash, credential_prefix, created_at
        ) VALUES ('dev-retention', 'Phone', 'hash', 'prefix', '2024-01-01T00:00:00Z')
        """
    )
    await execute(
        """
        INSERT INTO device_sync_batches (
          batch_id, device_id, protocol_version, mode, payload_sha256,
          payload_json, received_at, status
        ) VALUES ('batch-retention', 'dev-retention', 1, 'snapshot', 'sha', ?,
                  '2024-01-01T00:00:00Z', 'complete')
        """,
        (json.dumps({"records": [{"secret": "health-value"}]}),),
    )
    await execute(
        """
        INSERT INTO ingest_batches (
          batch_id, source, received_at, status, payload_json
        ) VALUES ('projection:batch-retention', 'device:dev-retention',
                  '2024-01-01T00:00:00Z', 'completed', ?)
        """,
        (json.dumps({"records": [{"secret": "health-value"}]}),),
    )
    old_payload = {"startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-01T01:00:00Z", "count": 100}
    await execute(
        """
        INSERT INTO health_connect_records (
          device_id, record_type, record_id, payload_json, first_seen_at,
          updated_at, last_batch_id
        ) VALUES ('dev-retention', 'StepsRecord', 'old-steps', ?,
                  '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'batch-retention')
        """,
        (json.dumps(old_payload),),
    )
    await execute(
        """
        INSERT INTO metric_intervals (
          record_key, batch_id, source, metric_type, start_time, end_time,
          numeric_value, unit, raw_json
        ) VALUES ('hc:dev-retention:StepsRecord:old-steps', 'projection:batch-retention',
                  'device:dev-retention', 'steps', '2024-01-01T00:00:00Z',
                  '2024-01-01T01:00:00Z', 100, 'count', ?)
        """,
        (json.dumps(old_payload),),
    )

    result = await prune_expired_health_data(retention_days=365, now=NOW, compact=True)

    assert result["health_records"] == 1
    assert await fetch_one("SELECT COUNT(*) AS count FROM health_connect_records") == {"count": 0}
    assert await fetch_one("SELECT COUNT(*) AS count FROM metric_intervals") == {"count": 0}
    assert await fetch_one("SELECT payload_json FROM device_sync_batches") == {"payload_json": "{}"}
    assert await fetch_one("SELECT payload_json FROM ingest_batches") == {"payload_json": "{}"}
    assert await fetch_one("PRAGMA secure_delete") == {"secure_delete": 1}


@pytest.mark.asyncio
async def test_ingest_does_not_persist_records_already_outside_retention():
    await init_db()
    await execute(
        """
        INSERT INTO devices (
          device_id, name, credential_hash, credential_prefix, created_at
        ) VALUES ('dev-old', 'Phone', 'hash-old', 'prefix-old', '2026-11-03T00:00:00Z')
        """
    )
    result = await process_change_batch(
        device_id="dev-old",
        payload={
            "batch_id": "batch-old",
            "protocol_version": 1,
            "mode": "snapshot",
            "records": [
                {
                    "operation": "upsert",
                    "record_type": "StepsRecord",
                    "record_id": "steps-old",
                    "payload": {
                        "startTime": "2020-01-01T00:00:00Z",
                        "endTime": "2020-01-01T01:00:00Z",
                        "count": 10,
                    },
                }
            ],
        },
    )
    assert result["accepted"] == 1
    assert result["upserted"] == 0
    assert await fetch_one("SELECT COUNT(*) AS count FROM health_connect_records") == {"count": 0}
    assert await fetch_one("SELECT payload_json FROM device_sync_batches") == {"payload_json": "{}"}


@pytest.mark.asyncio
async def test_locked_database_returns_retryable_503_without_partial_batch(monkeypatch, tmp_path):
    db_path = tmp_path / "busy.db"
    monkeypatch.setenv("DB_PATH", str(db_path))
    await init_db()
    device_token = "hqd_dev_busy.retry-secret"
    await execute(
        """
        INSERT INTO devices (
          device_id, name, credential_hash, credential_prefix, created_at
        ) VALUES ('dev-busy', 'Phone', ?, 'busy-prefix', '2026-11-03T00:00:00Z')
        """,
        (hash_credential(device_token),),
    )
    batch = {
        "batch_id": "batch-busy-001",
        "protocol_version": 1,
        "mode": "snapshot",
        "records": [
            {
                "operation": "upsert",
                "record_type": "StepsRecord",
                "record_id": "steps-busy",
                "payload": {
                    "startTime": "2026-11-02T00:00:00Z",
                    "endTime": "2026-11-02T01:00:00Z",
                    "count": 10,
                },
            }
        ],
    }
    blocker = await aiosqlite.connect(db_path.as_posix())
    await blocker.execute("PRAGMA busy_timeout=100")
    await blocker.execute("BEGIN EXCLUSIVE")
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="https://health.example") as client:
        busy = await client.post(
            "/api/device-sync/v1/changes",
            headers={"Authorization": f"Bearer {device_token}"},
            json=batch,
        )
        assert busy.status_code == 503
        assert busy.headers["retry-after"] == "5"
        await blocker.rollback()
        await blocker.close()
        assert await fetch_one("SELECT COUNT(*) AS count FROM device_sync_batches") == {"count": 0}

        accepted = await client.post(
            "/api/device-sync/v1/changes",
            headers={"Authorization": f"Bearer {device_token}"},
            json=batch,
        )
        replayed = await client.post(
            "/api/device-sync/v1/changes",
            headers={"Authorization": f"Bearer {device_token}"},
            json=batch,
        )
    assert accepted.status_code == 200
    assert replayed.status_code == 200
    assert replayed.json()["replayed"] is True
    assert await fetch_one("SELECT COUNT(*) AS count FROM health_connect_records") == {"count": 1}
