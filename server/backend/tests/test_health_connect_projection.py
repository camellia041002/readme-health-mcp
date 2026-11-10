from __future__ import annotations

import json
import pytest

from db.database import get_connection, init_db
from services.health_connect_projection import project_upsert


async def _seed_projection_batch(conn, batch_id: str) -> None:
    await conn.execute(
        """
        INSERT INTO ingest_batches (
          batch_id, source, received_at, processed_count, error_count,
          status, payload_json
        ) VALUES (?, 'device:test', '2026-08-23T00:00:00Z', 0, 0, 'processing', '{}')
        """,
        (batch_id,),
    )


@pytest.mark.asyncio
async def test_projection_deletion_routes_only_to_applicable_tables_and_cleans_sleep_children():
    await init_db()
    conn = await get_connection()
    try:
        for batch_id in (
            "projection:steps-1",
            "projection:hr-1",
            "projection:sleep-1",
            "projection:sleep-2",
            "projection:unknown-1",
        ):
            await _seed_projection_batch(conn, batch_id)
        await conn.commit()

        steps_sql: list[str] = []
        await conn.set_trace_callback(steps_sql.append)
        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="StepsRecord",
            record_id="steps-1",
            payload={
                "count": 42,
                "startTime": "2026-08-23T08:00:00Z",
                "endTime": "2026-08-23T09:00:00Z",
            },
            batch_id="projection:steps-1",
        )
        await conn.set_trace_callback(None)
        steps_sql_text = "\n".join(steps_sql).lower()
        assert "metric_intervals" in steps_sql_text
        assert "metric_points" not in steps_sql_text
        assert "sleep_sessions" not in steps_sql_text
        assert "sleep_stages" not in steps_sql_text
        assert "workouts" not in steps_sql_text
        steps_plan = await conn.execute_fetchall(
            """
            EXPLAIN QUERY PLAN
            SELECT DISTINCT substr(start_time, 1, 10)
            FROM metric_intervals
            WHERE record_key = ? OR (record_key >= ? AND record_key < ?)
            """,
            (
                "hc:dev-test:StepsRecord:steps-1",
                "hc:dev-test:StepsRecord:steps-1:",
                "hc:dev-test:StepsRecord:steps-1;",
            ),
        )
        assert any("SEARCH metric_intervals USING INDEX" in row[3] for row in steps_plan)

        heart_rate_sql: list[str] = []
        await conn.set_trace_callback(heart_rate_sql.append)
        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="HeartRateRecord",
            record_id="hr-1",
            payload={
                "samples": [{"time": "2026-08-23T08:00:00Z", "beatsPerMinute": 70}],
            },
            batch_id="projection:hr-1",
        )
        await conn.set_trace_callback(None)
        heart_rate_sql_text = "\n".join(heart_rate_sql).lower()
        assert "metric_points" in heart_rate_sql_text
        assert "metric_intervals" not in heart_rate_sql_text
        assert "sleep_sessions" not in heart_rate_sql_text
        assert "sleep_stages" not in heart_rate_sql_text
        assert "workouts" not in heart_rate_sql_text
        heart_rate_plan = await conn.execute_fetchall(
            """
            EXPLAIN QUERY PLAN
            SELECT DISTINCT substr(recorded_at, 1, 10)
            FROM metric_points
            WHERE record_key = ? OR (record_key >= ? AND record_key < ?)
            """,
            (
                "hc:dev-test:HeartRateRecord:hr-1",
                "hc:dev-test:HeartRateRecord:hr-1:",
                "hc:dev-test:HeartRateRecord:hr-1;",
            ),
        )
        assert any("SEARCH metric_points USING INDEX" in row[3] for row in heart_rate_plan)

        unknown_sql: list[str] = []
        await conn.set_trace_callback(unknown_sql.append)
        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="UnknownFutureRecord",
            record_id="unknown-1",
            payload={"time": "2026-08-23T08:00:00Z", "value": 1},
            batch_id="projection:unknown-1",
        )
        await conn.set_trace_callback(None)
        unknown_sql_text = "\n".join(unknown_sql).lower()
        assert not any(
            table in unknown_sql_text
            for table in ("metric_points", "metric_intervals", "sleep_sessions", "sleep_stages", "workouts")
        )

        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="SleepSessionRecord",
            record_id="sleep-1",
            payload={
                "startTime": "2026-08-22T22:00:00Z",
                "endTime": "2026-08-23T06:00:00Z",
                "stages": [
                    {
                        "stage": "deep",
                        "startTime": "2026-08-22T22:00:00Z",
                        "endTime": "2026-08-23T00:00:00Z",
                    },
                    {
                        "stage": "light",
                        "startTime": "2026-08-23T00:00:00Z",
                        "endTime": "2026-08-23T06:00:00Z",
                    },
                ],
            },
            batch_id="projection:sleep-1",
        )
        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="SleepSessionRecord",
            record_id="sleep-1",
            payload={
                "startTime": "2026-08-22T22:00:00Z",
                "endTime": "2026-08-23T06:00:00Z",
                "stages": [
                    {
                        "stage": "light",
                        "startTime": "2026-08-23T00:00:00Z",
                        "endTime": "2026-08-23T06:00:00Z",
                    }
                ],
            },
            batch_id="projection:sleep-2",
        )
        sleep_stage_count = await conn.execute_fetchall(
            "SELECT COUNT(*) FROM sleep_stages WHERE session_key = ?",
            ("hc:dev-test:SleepSessionRecord:sleep-1",),
        )
        assert sleep_stage_count[0][0] == 1
    finally:
        await conn.close()

@pytest.mark.asyncio
async def test_projects_readme_screen_time_with_app_identity():
    await init_db()
    conn = await get_connection()
    try:
        await _seed_projection_batch(conn, "projection:screen-test")
        await conn.commit()
        await project_upsert(
            conn,
            device_id="dev-test",
            record_type="ReadMeScreenTimeRecord",
            record_id="screen-2026-11-09-example",
            payload={
                "startTime": "2026-11-09T00:00:00+08:00",
                "endTime": "2026-11-09T18:00:00+08:00",
                "durationMinutes": 42.5,
                "appPackage": "com.example.reader",
                "appLabel": "示例",
            },
            batch_id="projection:screen-test",
        )
        await conn.commit()
        async with conn.execute(
            "SELECT metric_type, numeric_value, text_value, unit FROM metric_intervals WHERE metric_type = 'screen_time'"
        ) as cursor:
            row = await cursor.fetchone()
        assert row["numeric_value"] == 42.5
        assert row["unit"] == "min"
        assert json.loads(row["text_value"]) == {"app": "示例", "package": "com.example.reader"}
    finally:
        await conn.close()

