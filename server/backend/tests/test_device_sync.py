from __future__ import annotations

import asyncio
import json
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from httpx import ASGITransport

from db.database import fetch_all, fetch_one, init_db
from main import app
from services.device_sync import process_change_batch


async def _authenticated_browser_client() -> httpx.AsyncClient:
    client = httpx.AsyncClient(
        transport=ASGITransport(app=app),
        base_url="https://health.example",
        headers={"Origin": "https://health.example"},
    )
    login = await client.post(
        "/api/auth/login",
        json={"password": "correct horse battery staple"},
    )
    assert login.status_code == 200
    return client


async def _enroll_device(client: httpx.AsyncClient, *, name: str = "Pixel") -> dict[str, str]:
    enrollment = await client.post(
        "/api/devices/enrollment-tokens",
        json={"label": name, "server_url": "https://health.example"},
    )
    assert enrollment.status_code == 201
    pairing = urlsplit(enrollment.json()["pairing_url"])
    assert pairing.scheme == "https"
    assert pairing.netloc == "health.example"
    assert pairing.path == "/pair/android"
    assert pairing.query == ""
    token = parse_qs(pairing.fragment)["token"][0]
    exchange = await client.post(
        "/api/device-enrollment/exchange",
        json={
            "token": token,
            "device_name": name,
            "device_model": "Pixel 10",
            "app_version": "0.1.0",
        },
    )
    assert exchange.status_code == 200
    return exchange.json()


@pytest.mark.asyncio
async def test_enrollment_is_browser_only_fragment_based_and_one_time():
    await init_db()
    unauthenticated = httpx.AsyncClient(
        transport=ASGITransport(app=app),
        base_url="https://health.example",
    )
    assert (await unauthenticated.get("/api/devices")).status_code == 401
    assert (
        await unauthenticated.post("/api/devices/enrollment-tokens", json={})
    ).status_code == 401
    await unauthenticated.aclose()

    client = await _authenticated_browser_client()
    insecure_public = await client.post(
        "/api/devices/enrollment-tokens",
        json={"server_url": "http://public.example"},
    )
    assert insecure_public.status_code == 422
    local_development = await client.post(
        "/api/devices/enrollment-tokens",
        json={"server_url": "http://100.100.10.20:3135"},
    )
    assert local_development.status_code == 201
    enrollment = await client.post(
        "/api/devices/enrollment-tokens",
        json={"label": "Phone", "server_url": "https://health.example"},
    )
    body = enrollment.json()
    assert enrollment.status_code == 201
    assert "token" not in body
    pairing = urlsplit(body["pairing_url"])
    assert pairing.query == ""
    token = parse_qs(pairing.fragment)["token"][0]

    exchange_body = {
        "token": token,
        "device_name": "Phone",
        "app_version": "1.0",
    }
    first, second = await asyncio.gather(
        client.post("/api/device-enrollment/exchange", json=exchange_body),
        client.post("/api/device-enrollment/exchange", json=exchange_body),
    )
    assert sorted([first.status_code, second.status_code]) == [200, 401]
    success = first if first.status_code == 200 else second
    assert success.json()["device_token"].startswith("hqd_dev_")
    stored = await fetch_one("SELECT COUNT(*) AS count FROM devices")
    assert stored == {"count": 1}
    await client.aclose()


@pytest.mark.asyncio
async def test_lossless_sync_is_idempotent_and_tracks_updates_and_deletes():
    await init_db()
    client = await _authenticated_browser_client()
    enrolled = await _enroll_device(client)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    rich_payload = {
        "metadata": {
            "id": "hr-1",
            "dataOrigin": {"packageName": "com.vendor.watch"},
            "device": {"manufacturer": "Acme", "model": "Watch", "type": 2},
            "clientRecordId": "origin-hr-1",
            "clientRecordVersion": 7,
            "recordingMethod": 1,
        },
        "startTime": "2026-08-22T08:00:00Z",
        "endTime": "2026-08-22T08:01:00Z",
        "samples": [
            {"time": "2026-08-22T08:00:01Z", "beatsPerMinute": 62},
            {"time": "2026-08-22T08:00:31Z", "beatsPerMinute": 130},
        ],
        "unknownFutureField": {"nested": [1, True, None, "日本語"]},
    }
    batch = {
        "batch_id": "batch-stable-001",
        "protocol_version": 1,
        "mode": "snapshot",
        "next_change_token": "opaque-token-1",
        "app_version": "1.0",
        "records": [
            {
                "operation": "upsert",
                "record_type": "HeartRateRecord",
                "record_id": "hr-1",
                "payload": rich_payload,
            }
        ],
    }
    first = await client.post("/api/device-sync/v1/changes", headers=headers, json=batch)
    replay = await client.post("/api/device-sync/v1/changes", headers=headers, json=batch)
    assert first.status_code == 200
    assert first.json() == {
        "status": "success",
        "batch_id": "batch-stable-001",
        "accepted": 1,
        "upserted": 1,
        "deleted": 0,
        "errors": 0,
        "next_change_token": "opaque-token-1",
        "replayed": False,
    }
    assert replay.status_code == 200
    assert replay.json()["replayed"] is True
    assert (await fetch_one("SELECT COUNT(*) AS count FROM device_sync_batches")) == {"count": 1}
    record = await fetch_one(
        "SELECT payload_json, deleted_at FROM health_connect_records WHERE record_id = 'hr-1'"
    )
    assert json.loads(record["payload_json"]) == rich_payload
    assert record["deleted_at"] is None
    projected_samples = await fetch_all(
        "SELECT numeric_value, unit FROM metric_points WHERE metric_type = 'heart_rate' ORDER BY recorded_at"
    )
    assert projected_samples == [
        {"numeric_value": 62.0, "unit": "bpm"},
        {"numeric_value": 130.0, "unit": "bpm"},
    ]

    collision = {**batch, "records": [{**batch["records"][0], "record_id": "other"}]}
    conflict = await client.post("/api/device-sync/v1/changes", headers=headers, json=collision)
    assert conflict.status_code == 409

    updated_payload = {**rich_payload, "samples": [rich_payload["samples"][0]], "revision": 2}
    update = await client.post(
        "/api/device-sync/v1/changes",
        headers=headers,
        json={
            "batch_id": "batch-stable-002",
            "protocol_version": 1,
            "mode": "changes",
            "next_change_token": "opaque-token-2",
            "records": [
                {
                    "operation": "upsert",
                    "record_type": "HeartRateRecord",
                    "record_id": "hr-1",
                    "payload": updated_payload,
                }
            ],
        },
    )
    assert update.status_code == 200
    assert json.loads(
        (await fetch_one("SELECT payload_json FROM health_connect_records WHERE record_id = 'hr-1'"))[
            "payload_json"
        ]
    ) == updated_payload
    assert (await fetch_one("SELECT COUNT(*) AS count FROM metric_points WHERE metric_type = 'heart_rate'")) == {
        "count": 1
    }

    deletion = await client.post(
        "/api/device-sync/v1/changes",
        headers=headers,
        json={
            "batch_id": "batch-stable-003",
            "protocol_version": 1,
            "mode": "changes",
            "next_change_token": "opaque-token-3",
            "records": [
                {
                    "operation": "delete",
                    "record_type": "HeartRateRecord",
                    "record_id": "hr-1",
                    "payload": None,
                }
            ],
        },
    )
    assert deletion.status_code == 200
    deleted = await fetch_one(
        "SELECT payload_json, deleted_at FROM health_connect_records WHERE record_id = 'hr-1'"
    )
    assert json.loads(deleted["payload_json"]) == updated_payload
    assert deleted["deleted_at"] is not None
    assert (await fetch_one("SELECT COUNT(*) AS count FROM metric_points WHERE metric_type = 'heart_rate'")) == {
        "count": 0
    }
    device = await fetch_one(
        "SELECT last_change_token, last_successful_sync_at FROM devices WHERE device_id = ?",
        (enrolled["device_id"],),
    )
    assert device["last_change_token"] == "opaque-token-3"
    assert device["last_successful_sync_at"]
    await client.aclose()


@pytest.mark.asyncio
async def test_concurrent_batches_are_serialized_and_replayed_idempotently():
    await init_db()
    client = await _authenticated_browser_client()
    enrolled = await _enroll_device(client)

    def payload(batch_id: str, record_id: str, count: int) -> dict:
        return {
            "batch_id": batch_id,
            "protocol_version": 1,
            "mode": "changes",
            "next_change_token": batch_id,
            "records": [
                {
                    "operation": "upsert",
                    "record_type": "StepsRecord",
                    "record_id": record_id,
                    "payload": {
                        "count": count,
                        "startTime": "2026-08-22T08:00:00Z",
                        "endTime": "2026-08-22T09:00:00Z",
                        "metadata": {"id": record_id},
                    },
                }
            ],
        }

    first_batch = payload("batch-concurrent-001", "steps-concurrent-001", 100)
    second_batch = payload("batch-concurrent-002", "steps-concurrent-002", 200)
    results = await asyncio.gather(
        process_change_batch(device_id=enrolled["device_id"], payload=first_batch),
        process_change_batch(device_id=enrolled["device_id"], payload=first_batch),
        process_change_batch(device_id=enrolled["device_id"], payload=second_batch),
    )

    first_results = [result for result in results if result["batch_id"] == first_batch["batch_id"]]
    second_results = [result for result in results if result["batch_id"] == second_batch["batch_id"]]
    assert sorted(result["replayed"] for result in first_results) == [False, True]
    assert second_results == [
        {
            "status": "success",
            "batch_id": "batch-concurrent-002",
            "accepted": 1,
            "upserted": 1,
            "deleted": 0,
            "errors": 0,
            "next_change_token": "batch-concurrent-002",
            "replayed": False,
        }
    ]
    assert await fetch_one("SELECT COUNT(*) AS count FROM device_sync_batches") == {"count": 2}
    assert await fetch_one("SELECT COUNT(*) AS count FROM health_connect_records") == {"count": 2}
    await client.aclose()


@pytest.mark.asyncio
async def test_large_heart_rate_record_keeps_projection_payload_per_sample():
    await init_db()
    client = await _authenticated_browser_client()
    enrolled = await _enroll_device(client)
    samples = [
        {
            "time": f"2026-08-22T08:{index // 60:02d}:{index % 60:02d}Z",
            "beatsPerMinute": 60 + index % 40,
        }
        for index in range(1_000)
    ]
    payload = {
        "batch_id": "batch-large-heart-rate",
        "protocol_version": 1,
        "mode": "snapshot",
        "records": [
            {
                "operation": "upsert",
                "record_type": "HeartRateRecord",
                "record_id": "hr-large",
                "payload": {
                    "metadata": {"id": "hr-large", "source": "watch"},
                    "startTime": samples[0]["time"],
                    "endTime": samples[-1]["time"],
                    "samples": samples,
                },
            }
        ],
    }

    result = await process_change_batch(device_id=enrolled["device_id"], payload=payload)

    assert result["upserted"] == 1
    assert await fetch_one("SELECT COUNT(*) AS count FROM metric_points") == {"count": 1_000}
    raw_record = await fetch_one(
        "SELECT payload_json FROM health_connect_records WHERE record_id = 'hr-large'"
    )
    assert len(json.loads(raw_record["payload_json"])["samples"]) == 1_000
    projected = await fetch_one(
        "SELECT raw_json FROM metric_points ORDER BY recorded_at LIMIT 1"
    )
    assert json.loads(projected["raw_json"]) == samples[0]
    assert all(
        len(json.loads(row["raw_json"]).get("samples", [])) == 0
        for row in await fetch_all("SELECT raw_json FROM metric_points")
    )
    await client.aclose()


@pytest.mark.asyncio
async def test_device_revocation_and_sync_inspection():
    await init_db()
    client = await _authenticated_browser_client()
    enrolled = await _enroll_device(client)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    sync = await client.post(
        "/api/device-sync/v1/changes",
        headers=headers,
        json={
            "batch_id": "batch-inspection",
            "protocol_version": 1,
            "mode": "snapshot",
            "records": [
                {
                    "operation": "upsert",
                    "record_type": "StepsRecord",
                    "record_id": "steps-1",
                    "payload": {
                        "count": 1234,
                        "startTime": "2026-08-22T08:00:00Z",
                        "endTime": "2026-08-22T09:00:00Z",
                        "metadata": {"id": "steps-1"},
                    },
                }
            ],
        },
    )
    assert sync.status_code == 200
    assert await fetch_one("SELECT steps FROM daily_summaries") == {"steps": 1234}
    inspection = await client.get("/api/sync/inspection")
    assert inspection.status_code == 200
    assert inspection.json()["data_types"][0]["record_type"] == "StepsRecord"
    assert inspection.json()["batches"][0]["batch_id"] == "batch-inspection"
    assert any(entry["action"] == "device.sync" for entry in inspection.json()["audit"])

    revoked = await client.delete(f"/api/devices/{enrolled['device_id']}")
    assert revoked.status_code == 200
    rejected = await client.post(
        "/api/device-sync/v1/changes",
        headers=headers,
        json={
            "batch_id": "batch-after-revoke",
            "protocol_version": 1,
            "mode": "changes",
            "records": [],
        },
    )
    assert rejected.status_code == 401
    devices = (await client.get("/api/devices")).json()["devices"]
    assert devices[0]["revoked_at"] is not None
    assert "credential_hash" not in devices[0]
    await client.aclose()


@pytest.mark.asyncio
async def test_sync_run_lifecycle_replay_isolation_and_legacy_request():
    await init_db()
    browser = await _authenticated_browser_client()
    first_device = await _enroll_device(browser, name="First")
    second_device = await _enroll_device(browser, name="Second")
    first_headers = {"Authorization": f"Bearer {first_device['device_token']}"}
    second_headers = {"Authorization": f"Bearer {second_device['device_token']}"}

    def batch(batch_id: str, record_id: str, **run_metadata) -> dict:
        return {
            "batch_id": batch_id,
            "protocol_version": 1,
            "mode": "changes",
            "records": [
                {
                    "operation": "upsert",
                    "record_type": "StepsRecord",
                    "record_id": record_id,
                    "payload": {
                        "count": 10,
                        "startTime": "2026-08-22T08:00:00Z",
                        "endTime": "2026-08-22T09:00:00Z",
                        "metadata": {"id": record_id},
                    },
                }
            ],
            **run_metadata,
        }

    first = batch(
        "run-batch-1",
        "run-record-1",
        run_id="run-lifecycle-1",
        run_kind="manual",
        run_started_at="2026-08-22T08:00:00Z",
        run_total_records=2,
        run_processed_records=1,
        run_current_record_type="StepsRecord",
    )
    response = await browser.post("/api/device-sync/v1/changes", headers=first_headers, json=first)
    assert response.status_code == 200
    replay = await browser.post("/api/device-sync/v1/changes", headers=first_headers, json=first)
    assert replay.status_code == 200 and replay.json()["replayed"] is True
    second = batch(
        "run-batch-2",
        "run-record-2",
        run_id="run-lifecycle-1",
        run_kind="manual",
        run_started_at="2026-08-22T08:00:00Z",
        run_total_records=2,
        run_processed_records=2,
        run_completed=True,
        run_duration_ms=321,
    )
    response = await browser.post("/api/device-sync/v1/changes", headers=first_headers, json=second)
    assert response.status_code == 200
    run = await fetch_one(
        "SELECT * FROM device_sync_runs WHERE device_id = ? AND run_id = ?",
        (first_device["device_id"], "run-lifecycle-1"),
    )
    assert run["status"] == "completed"
    assert run["kind"] == "manual"
    assert run["total_records"] == 2
    assert run["processed_records"] == 2
    assert run["accepted_operations"] == 2
    assert run["batch_count"] == 2
    assert run["duration_ms"] == 321
    assert run["completed_at"]

    # A legacy protocol-1 envelope remains valid and does not invent a run.
    legacy = await browser.post(
        "/api/device-sync/v1/changes",
        headers=second_headers,
        json=batch("legacy-batch", "legacy-record"),
    )
    assert legacy.status_code == 200
    assert await fetch_one(
        "SELECT COUNT(*) AS count FROM device_sync_runs WHERE device_id = ?",
        (second_device["device_id"],),
    ) == {"count": 0}

    unauthenticated = httpx.AsyncClient(transport=ASGITransport(app=app), base_url="https://health.example")
    assert (await unauthenticated.get("/api/sync/runs")).status_code == 401
    assert (await unauthenticated.get("/api/device-sync/v1/history")).status_code == 401
    await unauthenticated.aclose()
    browser_runs = await browser.get("/api/sync/runs")
    assert browser_runs.status_code == 200
    assert [item["run_id"] for item in browser_runs.json()["runs"]] == ["run-lifecycle-1"]
    own_history = await browser.get("/api/device-sync/v1/history", headers=first_headers)
    assert own_history.status_code == 200
    assert [item["run_id"] for item in own_history.json()["runs"]] == ["run-lifecycle-1"]
    other_history = await browser.get("/api/device-sync/v1/history", headers=second_headers)
    assert other_history.status_code == 200
    assert other_history.json()["runs"] == []
    inspection = await browser.get("/api/sync/inspection")
    assert inspection.status_code == 200
    assert inspection.json()["runs"][0]["run_id"] == "run-lifecycle-1"
    await browser.aclose()
