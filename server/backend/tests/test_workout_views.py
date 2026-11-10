from __future__ import annotations

import json

import pytest

from db.database import get_connection, init_db
from services.workout_views import get_activity_training_view, get_workout_detail


@pytest.mark.asyncio
async def test_health_connect_workout_detail_correlates_lossless_session_metrics():
    await init_db()
    workout_key = "hc:device-test:ExerciseSessionRecord:session-1"
    start = "2026-08-23T08:00:00Z"
    end = "2026-08-23T09:00:00Z"
    origin = {"packageName": "com.example.watch"}
    session = {
        "exerciseType": 37,
        "title": "Forest ridge",
        "notes": "Steady effort",
        "startTime": start,
        "endTime": end,
        "metadata": {"dataOrigin": origin, "recordingMethod": 1, "device": {"manufacturer": "Example", "model": "Trail"}},
        "laps": [],
        "segments": [],
        "exerciseRouteResult": {
            "exerciseRoute": {
                "route": [
                    {"latitude": 48.1, "longitude": 2.1, "time": start},
                    {"latitude": 48.2, "longitude": 2.2, "time": end},
                ]
            }
        },
    }
    distance = {
        "startTime": start,
        "endTime": end,
        "distance": {"value": 5.0, "type": "KILOMETERS"},
        "metadata": {"dataOrigin": origin},
    }
    heart_rate = {
        "startTime": start,
        "endTime": end,
        "samples": [
            {"time": "2026-08-23T08:05:00Z", "beatsPerMinute": 140},
            {"time": "2026-08-23T08:30:00Z", "beatsPerMinute": 150},
            {"time": "2026-08-23T08:55:00Z", "beatsPerMinute": 160},
        ],
        "metadata": {"dataOrigin": origin},
    }
    cadence = {
        "startTime": start,
        "endTime": end,
        "samples": [
            {"time": "2026-08-23T08:15:00Z", "rate": 110},
            {"time": "2026-08-23T08:45:00Z", "rate": 120},
        ],
        "metadata": {"dataOrigin": origin},
    }

    conn = await get_connection()
    try:
        await conn.execute(
            "INSERT INTO ingest_batches (batch_id, source, payload_json, status) VALUES ('projection-batch', 'health_connect', '{}', 'completed')"
        )
        await conn.execute(
            """
            INSERT INTO devices (
              device_id, name, credential_hash, credential_prefix, created_at
            ) VALUES ('device-test', 'Test watch', 'hash', 'prefix', ?)
            """,
            (start,),
        )
        await conn.execute(
            """
            INSERT INTO device_sync_batches (
              batch_id, device_id, protocol_version, mode, payload_sha256,
              payload_json, received_at, completed_at, status
            ) VALUES ('sync-batch', 'device-test', 1, 'snapshot', 'sha', '{}', ?, ?, 'completed')
            """,
            (start, end),
        )
        await conn.execute(
            """
            INSERT INTO workouts (
              workout_key, batch_id, source, activity_type, start_time, end_time,
              duration_minutes, calories, avg_hr, raw_json
            ) VALUES (?, 'projection-batch', 'health_connect', '37', ?, ?, 60, NULL, NULL, ?)
            """,
            (workout_key, start, end, json.dumps(session)),
        )
        for record_id, record_type, payload in (
            ("distance-1", "androidx.health.connect.client.records.DistanceRecord", distance),
            ("heart-1", "androidx.health.connect.client.records.HeartRateRecord", heart_rate),
            ("cadence-1", "androidx.health.connect.client.records.StepsCadenceRecord", cadence),
        ):
            await conn.execute(
                """
                INSERT INTO health_connect_records (
                  device_id, record_type, record_id, payload_json,
                  first_seen_at, updated_at, last_batch_id
                ) VALUES ('device-test', ?, ?, ?, ?, ?, 'sync-batch')
                """,
                (record_type, record_id, json.dumps(payload), start, end),
            )
        await conn.commit()
    finally:
        await conn.close()

    activity = await get_activity_training_view()
    assert activity["history_total"] == 1
    assert activity["workouts"][0]["activity_type"] == "hiking"
    assert activity["workouts"][0]["activity_label"] == "Hike"
    assert activity["sport_totals"][0]["duration_minutes"] == 60

    detail = await get_workout_detail(workout_key)
    assert detail is not None
    assert detail["session"]["title"] == "Forest ridge"
    assert detail["notes"] == "Steady effort"
    assert detail["totals"]["distance_meters"] == 5_000
    assert detail["totals"]["avg_hr"] == 150
    assert detail["totals"]["max_hr"] == 160
    assert detail["totals"]["avg_cadence"] == 115
    assert len(detail["series"]["heart_rate"]) == 3
    assert detail["route"]["status"] == "available"
    assert len(detail["route"]["points"]) == 2


@pytest.mark.asyncio
async def test_workout_detail_returns_none_for_unknown_workout():
    await init_db()
    assert await get_workout_detail("missing") is None
