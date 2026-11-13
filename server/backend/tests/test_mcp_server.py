from __future__ import annotations

import asyncio
import os
import sqlite3
import sys
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
import mcp_server
import pytest
import uvicorn
from db.database import execute, init_db
from main import app
from mcp import StdioServerParameters
from mcp.client import Client
from mcp.client.stdio import stdio_client
from services.seed_data import seed_sample_health_data

BACKEND_ROOT = Path(__file__).resolve().parents[1]


@asynccontextmanager
async def run_backend_server(port: int):
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="on")
    server = uvicorn.Server(config)
    task = asyncio.create_task(server.serve())
    try:
        for _ in range(100):
            try:
                async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}", timeout=1.0) as client:
                    response = await client.get("/api/health")
                    if response.status_code == 200:
                        break
            except httpx.HTTPError:
                await asyncio.sleep(0.05)
        else:
            raise RuntimeError("Backend server did not start")
        yield f"http://127.0.0.1:{port}"
    finally:
        server.should_exit = True
        await task


@pytest.mark.asyncio
async def test_openapi_schema_hides_webhook_endpoint():
    await init_db()
    schema = app.openapi()
    assert "/api/webhook/health" not in schema["paths"]


@pytest.mark.asyncio
async def test_typed_catalog_uses_extended_timeout(monkeypatch):
    calls: list[tuple[str, float]] = []

    async def fake_post_query(sql: str, *, timeout_seconds: float = mcp_server.MCP_QUERY_TIMEOUT_SECONDS):
        calls.append((sql, timeout_seconds))
        return {"rows": []}

    monkeypatch.setattr(mcp_server, "_post_query", fake_post_query)

    await mcp_server.list_health_connect_record_types(limit=5)
    assert calls[-1][1] == mcp_server.MCP_CATALOG_TIMEOUT_SECONDS


def test_china_local_calendar_date_keeps_early_morning_record_on_same_day():
    expression = mcp_server._local_date_expression("?")
    with sqlite3.connect(":memory:") as connection:
        actual = connection.execute(
            f"SELECT {expression}",
            ("2026-11-09T00:59:00+08:00",),
        ).fetchone()[0]

    assert actual == "2026-11-09"
    assert "date(recorded_at, '+8 hours')" in mcp_server._date_filter(
        "recorded_at", "2026-11-09", "2026-11-09"
    )


def test_sqlite_utc_metadata_timestamp_is_returned_with_timezone_label():
    result = mcp_server._label_utc_metadata_timestamps(
        {
            "rows": [
                {
                    "updated_at": "2026-11-09 07:00:13",
                    "recorded_at": "2026-11-09T00:59:00+08:00",
                }
            ]
        }
    )

    assert result["rows"][0]["updated_at"] == "2026-11-09T07:00:13Z"
    assert result["rows"][0]["recorded_at"] == "2026-11-09T00:59:00+08:00"


def test_reader_audit_summary_keeps_useful_values_without_raw_rows():
    summary = mcp_server._bounded_result_summary(
        "metric_points",
        {
            "rows": [
                {"record_key": "secret-1", "numeric_value": 62, "unit": "bpm"},
                {"record_key": "secret-2", "numeric_value": 88, "unit": "bpm"},
            ]
        },
        metric_type="heart_rate",
    )

    assert summary == {
        "kind": "heart_rate",
        "count": 2,
        "unit": "bpm",
        "minimum": 62.0,
        "maximum": 88.0,
        "average": 75.0,
    }
    assert "record_key" not in str(summary)


def test_reader_audit_summary_keeps_sleep_times_and_duration():
    summary = mcp_server._bounded_result_summary(
        "sleep_sessions",
        {
            "rows": [
                {
                    "session_key": "secret-session",
                    "start_time": "2026-11-09T02:36:00+08:00",
                    "end_time": "2026-11-09T11:11:00+08:00",
                    "duration_minutes": 515,
                }
            ]
        },
    )

    assert summary == {
        "kind": "sleep",
        "count": 1,
        "sessions": [{
            "start": "2026-11-09T02:36:00+08:00",
            "end": "2026-11-09T11:11:00+08:00",
            "minutes": 515,
        }],
    }
    assert "session_key" not in str(summary)


def test_reader_audit_summary_keeps_screen_time_apps_and_total():
    summary = mcp_server._bounded_result_summary(
        "metric_intervals",
        {
            "rows": [
                {
                    "metric_type": "screen_time",
                    "numeric_value": 12.25,
                    "unit": "min",
                    "text_value": '{"app":"微信","package":"com.tencent.mm"}',
                },
                {
                    "metric_type": "screen_time",
                    "numeric_value": 3.0,
                    "unit": "min",
                    "text_value": '{"app":"相机","package":"com.oplus.camera"}',
                },
            ]
        },
        metric_type="screen_time",
    )

    assert summary["kind"] == "screen_time"
    assert summary["total_minutes"] == 15.2
    assert summary["values"] == [
        {"app": "微信", "minutes": 12.2},
        {"app": "相机", "minutes": 3.0},
    ]


def test_health_summary_normalizes_android_sleep_and_exercise_codes():
    rows, totals = mcp_server._summarize_sleep_stages([
        {"stage_type": 1, "duration_seconds": 600},
        {"stage_type": "4", "duration_seconds": 1_800},
        {"stage_type": 5, "duration_seconds": 1_200},
        {"stage_type": 6, "duration_seconds": 600},
        {"stage_type": 7, "duration_seconds": 300},
    ])

    assert [row["stage_type"] for row in rows] == ["awake", "light", "deep", "rem", "awake"]
    by_stage = {item["stage"]: item for item in totals}
    assert by_stage["awake"]["seconds"] == 900
    assert by_stage["light"]["percent"] == 40.0
    assert by_stage["deep"]["percent"] == 26.7
    assert by_stage["rem"]["percent"] == 13.3
    assert mcp_server._normalize_exercise_type(79) == "walking"
    assert mcp_server._normalize_exercise_type("56") == "running"
    assert mcp_server._normalize_exercise_type("yoga") == "yoga"


@pytest.mark.asyncio
async def test_mcp_server_tools_read_from_backend_via_stdio():
    await init_db()
    await seed_sample_health_data()
    await execute(
        """
        INSERT INTO devices (
          device_id, name, platform, credential_hash, credential_prefix, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        ("device-test", "Test phone", "android", "hash-test", "cred-test", "2026-06-11T08:00:00Z"),
    )
    await execute(
        """
        INSERT INTO device_sync_batches (
          batch_id, device_id, protocol_version, mode, payload_sha256,
          payload_json, received_at, status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "batch-device-test",
            "device-test",
            1,
            "incremental",
            "sha-test",
            "{}",
            "2026-06-11T08:00:00Z",
            "completed",
        ),
    )
    await execute(
        """
        INSERT INTO health_connect_records (
          device_id, record_type, record_id, payload_json,
          first_seen_at, updated_at, last_batch_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "device-test",
            "android.health.connect.datatypes.HeartRateRecord",
            "record-1",
            '{"samples":[{"beatsPerMinute":61,"time":"2026-06-11T08:00:00Z"}],"nested":{"source":"watch"}}',
            "2026-06-11T08:00:00Z",
            "2026-06-11T08:00:00Z",
            "batch-device-test",
        ),
    )

    backend_port = 3146
    async with run_backend_server(backend_port):
        env = os.environ.copy()
        env["HEALTHQUERY_BASE_URL"] = f"http://127.0.0.1:{backend_port}"
        env["HEALTHQUERY_READ_TOKEN"] = "read-token"

        transport = StdioServerParameters(
            command=sys.executable,
            args=[str(BACKEND_ROOT / "mcp_server.py")],
            env=env,
            cwd=str(BACKEND_ROOT),
        )
        async with Client(stdio_client(transport)) as client:
            tools = await client.list_tools()
            tool_names = {tool.name for tool in tools.tools}
            assert {
                "list_data_types",
                "describe_data_type",
                "query_records",
                "list_workouts",
                "get_workout",
                "get_session_data",
                "get_health_summary",
                "aggregate",
                "get_data_coverage",
                "summarize_trends",
                "list_health_connect_record_types",
            }.issubset(tool_names)
            assert "query_sql" not in tool_names

            data_types = await client.call_tool("list_data_types", {})
            assert any(item["name"] == "workouts" for item in data_types.structured_content["data_types"])

            workouts = await client.call_tool("list_workouts", {"limit": 5})
            assert workouts.structured_content["row_count"] >= 1

            coverage = await client.call_tool(
                "get_data_coverage",
                {"data_type": "metric_points", "start_date": "2026-06-11", "end_date": "2026-06-11"},
            )
            assert coverage.structured_content["coverage"]["row_count"] >= 1
            assert coverage.structured_content["coverage"]["distinct_day_count"] == 1

            trend = await client.call_tool(
                "summarize_trends",
                {"data_type": "metric_points", "value_column": "numeric_value", "interval": "day"},
            )
            assert trend.structured_content["row_count"] == 1
            assert trend.structured_content["rows"][0]["bucket"] == "2026-06-11"

            raw_types = await client.call_tool("list_health_connect_record_types", {})
            assert raw_types.structured_content["rows"][0]["record_type"] == "android.health.connect.datatypes.HeartRateRecord"
            raw = await client.call_tool("query_records", {"data_type": "health_connect_records", "limit": 5})
            assert '"nested":{"source":"watch"}' in raw.structured_content["rows"][0]["payload_json"]

            summary = await client.call_tool(
                "get_health_summary",
                {"target_date": "2026-06-11", "sections": ["sleep", "vitals", "activity"]},
            )
            assert summary.structured_content["sleep"]["main_sleep"]["minutes"] == 470
            assert summary.structured_content["sleep"]["stage_totals"] == [
                {"stage": "light", "seconds": 6600, "minutes": 110.0, "percent": 100.0}
            ]
            heart_rate = next(
                item for item in summary.structured_content["vitals"]["metrics"]
                if item["metric"] == "heart_rate"
            )
            assert heart_rate["latest"] == 61
            activity = {
                item["metric"]: item for item in summary.structured_content["activity"]["metrics"]
            }
            assert activity["steps"]["total"] == 8420
            assert activity["distance"]["total"] == 6200
            assert activity["active_calories"]["total"] == 780
            workout_day = await client.call_tool(
                "get_health_summary",
                {"target_date": "2026-06-12", "sections": ["activity"]},
            )
            assert workout_day.structured_content["activity"]["workouts"][0]["activity"] == "walking"

            too_many = await client.call_tool(
                "query_records",
                {"data_type": "workouts", "limit": 501},
            )
            assert too_many.is_error


@pytest.mark.asyncio
async def test_mcp_catalog_labels_health_domains_and_raw_query_filters(monkeypatch):
    calls: list[str] = []

    async def fake_post_query(sql: str, *, timeout_seconds: float = mcp_server.MCP_QUERY_TIMEOUT_SECONDS):
        calls.append(sql)
        return {"rows": []}

    monkeypatch.setattr(mcp_server, "_post_query", fake_post_query)

    catalog = await mcp_server.list_data_types()
    by_name = {item["name"]: item for item in catalog["data_types"]}
    assert "training" in by_name["workouts"]["categories"]
    assert {"vitals", "body"}.issubset(by_name["metric_points"]["categories"])
    assert "sleep" in by_name["sleep_sessions"]["categories"]

    raw_description = await mcp_server.describe_data_type("health_connect_records")
    assert raw_description["raw_payload"] is True
    assert "Health Connect event" in raw_description["date_filter_semantics"]

    await mcp_server._query_records_impl(
        "health_connect_records",
        start_date="2026-06-11",
        end_date="2026-06-12",
        record_type="androidx.health.connect.client.records.HeartRateRecord",
        device_id="device-test",
    )
    sql = calls[-1]
    assert "record_type = 'androidx.health.connect.client.records.HeartRateRecord'" in sql
    assert "device_id = 'device-test'" in sql
    assert "deleted_at IS NULL" in sql
    assert "json_extract(payload_json, '$.startTime')" in sql
    assert "date('2026-06-11')" in sql

    await mcp_server.get_data_coverage("health_connect_records", start_date="2026-06-11")
    assert "COUNT(DISTINCT date(COALESCE(json_extract(payload_json, '$.startTime')" in calls[-1]
    assert "'+8 hours'" in calls[-1]

    await mcp_server.aggregate("metric_points", "avg", "numeric_value", interval="day")
    assert "strftime('%Y-%m-%d', recorded_at, '+8 hours')" in calls[-1]

    await mcp_server._query_records_impl("metric_points", metric_type="heart_rate")
    assert "metric_type = 'heart_rate'" in calls[-1]

    await mcp_server._query_records_impl(
        "health_connect_records",
        include_deleted=True,
    )
    assert "deleted_at IS NULL" not in calls[-1]

    with pytest.raises(ValueError, match="apply only to health_connect_records"):
        await mcp_server._query_records_impl("workouts", device_id="device-test")
