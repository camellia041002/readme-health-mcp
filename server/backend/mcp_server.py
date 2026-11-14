"""HealthQuery's read-only MCP server.

The same ``mcp`` object powers the legacy stdio subprocess and the remote
Streamable HTTP app exported by :mod:`routers.mcp`.  Stdio authenticates to
the local HealthQuery API with ``HEALTHQUERY_READ_TOKEN``; HTTP credentials
are verified by the mount middleware before a tool is invoked.
"""

from __future__ import annotations

import html
import inspect
import json
import re
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import date, datetime
from pathlib import Path
from typing import Any
from functools import wraps
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

from fastapi.responses import HTMLResponse, RedirectResponse
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import (
    AuthSettings,
    ClientRegistrationOptions,
    RevocationOptions,
)
from mcp.server.mcpserver import MCPServer
from starlette.requests import Request
from starlette.responses import Response

_PACKAGE_PARENT = Path(__file__).resolve().parent.parent
if str(_PACKAGE_PARENT) not in sys.path:
    sys.path.insert(0, str(_PACKAGE_PARENT))

from services.browser_auth import (
    audit_browser_event,
    issue_session_cookie,
    require_same_origin,
    session_is_valid,
    verify_admin_password,
)
from services.mcp_auth import (
    CURRENT_MCP_CREDENTIAL,
)
from services.device_sync import audit_access
from services.mcp_oauth import (
    OAUTH_SCOPES,
    oauth_issuer,
    oauth_provider,
    oauth_resource,
)
from services.safe_sql import SafeSqlTimeoutError, execute_safe_select
from services.sql_guard import SqlGuardError

from healthquery_client import AsyncHealthQueryClient

MAX_TOOL_ROWS = 500
MCP_QUERY_TIMEOUT_SECONDS = 2.0
MCP_CATALOG_TIMEOUT_SECONDS = 5.0

DATA_TYPES: dict[str, dict[str, Any]] = {
    "daily_summaries": {
        "label": "Daily summaries",
        "categories": ["activity", "sleep"],
        "table": "daily_summaries",
        "time_column": "summary_date",
        "columns": ["summary_date", "steps", "active_minutes", "sleep_minutes", "workouts", "updated_at"],
        "value_columns": ["steps", "active_minutes", "sleep_minutes", "workouts"],
        "description": "One row per date with steps, active minutes, sleep minutes, and workout count.",
    },
    "metric_points": {
        "label": "Point metrics",
        "categories": ["vitals", "body"],
        "table": "metric_points",
        "time_column": "recorded_at",
        "columns": ["record_key", "source", "metric_type", "recorded_at", "numeric_value", "text_value", "unit", "updated_at"],
        "value_columns": ["numeric_value"],
        "description": "Point-in-time measurements such as heart rate, weight, glucose, and oxygen saturation.",
    },
    "metric_intervals": {
        "label": "Interval metrics",
        "categories": ["activity", "hydration", "mindfulness", "digital_wellbeing", "cycle"],
        "table": "metric_intervals",
        "time_column": "start_time",
        "columns": ["record_key", "source", "metric_type", "start_time", "end_time", "numeric_value", "text_value", "unit", "updated_at"],
        "value_columns": ["numeric_value"],
        "description": "Metrics measured over intervals, including steps, distance, calories, hydration, mindfulness, menstrual periods, and Android screen time.",
    },
    "sleep_sessions": {
        "label": "Sleep sessions",
        "categories": ["sleep"],
        "table": "sleep_sessions",
        "time_column": "start_time",
        "columns": ["session_key", "source", "start_time", "end_time", "duration_minutes", "efficiency_pct", "updated_at"],
        "value_columns": ["duration_minutes", "efficiency_pct"],
        "description": "Sleep session summaries. Use get_session_data for stage rows.",
    },
    "sleep_stages": {
        "label": "Sleep stages",
        "categories": ["sleep"],
        "table": "sleep_stages",
        "time_column": "start_time",
        "columns": ["stage_key", "session_key", "source", "stage_type", "start_time", "end_time", "duration_seconds", "updated_at"],
        "value_columns": ["duration_seconds"],
        "description": "Stages belonging to sleep sessions.",
    },
    "workouts": {
        "label": "Workouts",
        "categories": ["training"],
        "table": "workouts",
        "time_column": "start_time",
        "columns": ["workout_key", "source", "activity_type", "start_time", "end_time", "duration_minutes", "calories", "avg_hr", "updated_at"],
        "value_columns": ["duration_minutes", "calories", "avg_hr"],
        "description": "Exercise sessions with activity type, duration, calories, and average heart rate.",
    },
    "health_connect_records": {
        "label": "Health Connect records",
        "categories": ["raw", "activity", "sleep", "vitals", "body", "nutrition", "hydration", "mindfulness", "cycle"],
        "table": "health_connect_records",
        "time_column": "updated_at",
        "columns": ["device_id", "record_type", "record_id", "payload_json", "first_seen_at", "updated_at", "deleted_at", "last_batch_id"],
        "value_columns": [],
        "description": "Lossless raw records synchronized from Android Health Connect devices.",
    },
    "device_sync_batches": {
        "label": "Device sync batches",
        "categories": ["sync"],
        "table": "device_sync_batches",
        "time_column": "received_at",
        "columns": ["batch_id", "device_id", "protocol_version", "mode", "payload_sha256", "received_at", "completed_at", "status", "accepted_count", "upserted_count", "deleted_count", "error_count", "next_change_token", "error_json"],
        "value_columns": ["accepted_count", "upserted_count", "deleted_count", "error_count"],
        "description": "Read-only synchronization batch status and counts.",
    },
}

_ALIAS_TO_TYPE = {
    "metrics": "metric_points",
    "points": "metric_points",
    "intervals": "metric_intervals",
    "sleep": "sleep_sessions",
    "exercise": "workouts",
    "workout": "workouts",
}

HEALTH_SUMMARY_SECTIONS = ("sleep", "vitals", "activity", "cycle", "screen_time")

SLEEP_STAGE_NAMES = {
    "0": "unknown",
    "1": "awake",
    "2": "sleeping",
    "3": "awake",
    "4": "light",
    "5": "deep",
    "6": "rem",
    "7": "awake",
    "unknown": "unknown",
    "awake": "awake",
    "awake_in_bed": "awake",
    "awake_out_of_bed": "awake",
    "out_of_bed": "awake",
    "sleeping": "sleeping",
    "light": "light",
    "sleeping_light": "light",
    "deep": "deep",
    "sleeping_deep": "deep",
    "rem": "rem",
    "sleeping_rem": "rem",
}

EXERCISE_TYPE_NAMES = {
    "0": "other_workout", "2": "badminton", "4": "baseball", "5": "basketball",
    "8": "biking", "9": "biking_stationary", "10": "boot_camp", "11": "boxing",
    "13": "calisthenics", "14": "cricket", "16": "dancing", "25": "elliptical",
    "26": "exercise_class", "27": "fencing", "28": "football_american",
    "29": "football_australian", "31": "frisbee_disc", "32": "golf",
    "33": "guided_breathing", "34": "gymnastics", "35": "handball",
    "36": "high_intensity_interval_training", "37": "hiking", "38": "ice_hockey",
    "39": "ice_skating", "44": "martial_arts", "46": "paddling",
    "47": "paragliding", "48": "pilates", "50": "racquetball", "51": "rock_climbing",
    "52": "roller_hockey", "53": "rowing", "54": "rowing_machine", "55": "rugby",
    "56": "running", "57": "running_treadmill", "58": "sailing", "59": "scuba_diving",
    "60": "skating", "61": "skiing", "62": "snowboarding", "63": "snowshoeing",
    "64": "soccer", "65": "softball", "66": "squash", "68": "stair_climbing",
    "69": "stair_climbing_machine", "70": "strength_training", "71": "stretching",
    "72": "surfing", "73": "swimming_open_water", "74": "swimming_pool",
    "75": "table_tennis", "76": "tennis", "78": "volleyball", "79": "walking",
    "80": "water_polo", "81": "weightlifting", "82": "wheelchair", "83": "yoga",
}


def _normalize_sleep_stage(value: object) -> str:
    key = str(value if value is not None else "unknown").strip().lower().replace("-", "_").replace(" ", "_")
    return SLEEP_STAGE_NAMES.get(key, key or "unknown")


def _normalize_exercise_type(value: object) -> str:
    key = str(value if value is not None else "other_workout").strip().lower().replace("-", "_").replace(" ", "_")
    return EXERCISE_TYPE_NAMES.get(key, key or "other_workout")


def _resolve_type(data_type: str) -> tuple[str, dict[str, Any]]:
    key = _ALIAS_TO_TYPE.get(data_type.strip().lower(), data_type.strip().lower())
    try:
        return key, DATA_TYPES[key]
    except KeyError as exc:
        raise ValueError(f"Unknown data_type: {data_type}") from exc


def _parse_date(value: date | str | None) -> date | None:
    if value is None or isinstance(value, date):
        return value
    clean = value.strip()
    return date.fromisoformat(clean) if clean else None


LOCAL_TIME_ZONE_LABEL = "Asia/Shanghai"
LOCAL_TIME_SQL_MODIFIER = "+8 hours"
UTC_METADATA_TIMESTAMP_FIELDS = {
    "created_at", "updated_at", "first_seen_at", "deleted_at", "received_at",
    "expires_at", "revoked_at", "consumed_at", "last_used_at",
}
NAIVE_SQLITE_TIMESTAMP_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$"
)


def _local_date_expression(column: str) -> str:
    """Return a SQLite expression for the China-local calendar date."""
    return f"date({column}, '{LOCAL_TIME_SQL_MODIFIER}')"


def _local_bucket_expression(column: str, format_value: str) -> str:
    """Return a SQLite expression for a China-local calendar bucket."""
    return f"strftime('{format_value}', {column}, '{LOCAL_TIME_SQL_MODIFIER}')"


def _label_utc_metadata_timestamps(result: dict[str, Any]) -> dict[str, Any]:
    """Make SQLite UTC metadata timestamps unambiguous to remote readers."""
    for row in result.get("rows", []):
        if not isinstance(row, dict):
            continue
        for field in UTC_METADATA_TIMESTAMP_FIELDS:
            value = row.get(field)
            if isinstance(value, str) and NAIVE_SQLITE_TIMESTAMP_PATTERN.fullmatch(value):
                row[field] = f"{value.replace(' ', 'T')}Z"
    return result


def _date_filter(column: str, start_date: date | str | None, end_date: date | str | None) -> str:
    start = _parse_date(start_date)
    end = _parse_date(end_date)
    clauses: list[str] = []
    local_date = _local_date_expression(column)
    if start:
        clauses.append(f"{local_date} >= date('{start.isoformat()}')")
    if end:
        clauses.append(f"{local_date} <= date('{end.isoformat()}')")
    return " AND ".join(clauses) or "1 = 1"


def _sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _limit(value: int) -> int:
    if value < 1 or value > MAX_TOOL_ROWS:
        raise ValueError(f"limit must be between 1 and {MAX_TOOL_ROWS}")
    return value


def _raw_record_time_expression() -> str:
    """Use Health Connect event time for raw date filtering when available.

    Sync timestamps are useful for freshness, but they are not the time at
    which a workout, sleep session, or vital was recorded.  Unknown future
    record shapes fall back to ``updated_at`` so no raw record disappears.
    """
    return (
        "COALESCE(json_extract(payload_json, '$.startTime'), "
        "json_extract(payload_json, '$.time'), "
        "json_extract(payload_json, '$.endTime'), updated_at)"
    )


@asynccontextmanager
async def _client() -> AsyncIterator[AsyncHealthQueryClient]:
    async with AsyncHealthQueryClient() as client:
        yield client


async def _post_query(sql: str, *, timeout_seconds: float = MCP_QUERY_TIMEOUT_SECONDS) -> dict[str, Any]:
    """Use local bounded execution for HTTP MCP, legacy API for stdio."""
    if CURRENT_MCP_CREDENTIAL.get() is not None or get_access_token() is not None:
        try:
            result = await execute_safe_select(
                sql,
                max_rows=500,
                max_bytes=1_000_000,
                timeout_seconds=timeout_seconds,
            )
        except (SqlGuardError, SafeSqlTimeoutError) as exc:
            raise ValueError(str(exc)) from exc
        return _label_utc_metadata_timestamps(result.as_dict())
    async with _client() as client:
        return _label_utc_metadata_timestamps((await client.post_query(sql)).model_dump())


async def _audit_read(
    tool: str,
    *,
    data_types: list[str],
    start_date: date | str | None = None,
    end_date: date | str | None = None,
    returned_rows: int | None = None,
    result_summary: dict[str, Any] | None = None,
    outcome: str = "success",
) -> None:
    """Record a bounded user-facing result summary without raw health rows or secrets."""
    credential = CURRENT_MCP_CREDENTIAL.get()
    token = get_access_token()
    # Legacy stdio/local test calls have no remote reader identity and must
    # not create a misleading anonymous audit event.
    if credential is None and token is None:
        return
    actor_id = credential.id if credential is not None else str(
        getattr(token, "subject", None) or getattr(token, "client_id", None) or "remote"
    )
    details: dict[str, Any] = {
        "reader_name": credential.name if credential is not None else "OAuth reader",
        "tool": tool,
        "data_types": sorted(set(data_types)),
        "start_date": _parse_date(start_date).isoformat() if _parse_date(start_date) else None,
        "end_date": _parse_date(end_date).isoformat() if _parse_date(end_date) else None,
    }
    if returned_rows is not None:
        details["returned_rows"] = max(0, int(returned_rows))
    if result_summary:
        details["result_summary"] = result_summary
    await audit_access(
        actor_kind="mcp_read",
        actor_id=actor_id,
        action=f"mcp.tool.{tool}",
        outcome=outcome,
        details=details,
    )


def _bounded_result_summary(
    data_type: str,
    result: dict[str, Any],
    *,
    metric_type: str | None = None,
) -> dict[str, Any] | None:
    """Reduce a tool result to the exact useful values shown in access history."""
    rows = [row for row in result.get("rows", []) if isinstance(row, dict)][:20]
    if not rows:
        return {"kind": data_type, "count": 0}
    if data_type == "sleep_sessions":
        return {
            "kind": "sleep",
            "count": len(rows),
            "sessions": [
                {"start": row.get("start_time"), "end": row.get("end_time"), "minutes": row.get("duration_minutes")}
                for row in rows[:6]
            ],
        }
    if data_type == "daily_summaries":
        return {
            "kind": "daily",
            "count": len(rows),
            "values": [
                {
                    "date": row.get("summary_date") or row.get("bucket"),
                    "steps": row.get("steps") if row.get("steps") is not None else row.get("total"),
                    "sleep_minutes": row.get("sleep_minutes"),
                }
                for row in rows[:14]
            ],
        }
    if data_type in {"metric_points", "metric_intervals"}:
        observed_types = {str(row.get("metric_type")) for row in rows if row.get("metric_type")}
        effective_kind = metric_type or (next(iter(observed_types)) if len(observed_types) == 1 else data_type)
        numeric = [float(row["numeric_value"]) for row in rows if isinstance(row.get("numeric_value"), (int, float))]
        summary: dict[str, Any] = {
            "kind": effective_kind,
            "count": len(rows),
            "unit": next((row.get("unit") for row in rows if row.get("unit")), None),
        }
        if effective_kind == "screen_time":
            values: list[dict[str, Any]] = []
            for row in rows:
                if not isinstance(row.get("numeric_value"), (int, float)):
                    continue
                text_value = row.get("text_value")
                app: dict[str, Any] = {}
                if isinstance(text_value, str):
                    try:
                        decoded = json.loads(text_value)
                        if isinstance(decoded, dict):
                            app = decoded
                    except json.JSONDecodeError:
                        pass
                values.append({
                    "app": app.get("app") or app.get("package") or "未知应用",
                    "minutes": round(float(row["numeric_value"]), 1),
                })
            summary["values"] = values[:20]
            summary["total_minutes"] = round(sum(item["minutes"] for item in values), 1)
        if numeric:
            summary.update({"minimum": min(numeric), "maximum": max(numeric), "average": round(sum(numeric) / len(numeric), 1)})
        return summary
    return {"kind": data_type, "count": len(rows)}


def _audit_read_failures(tool: str, fixed_data_types: list[str] | None = None):
    """Audit rejected/failed remote reads while preserving the original tool error."""
    def decorate(function):
        signature = inspect.signature(function)

        @wraps(function)
        async def wrapped(*args, **kwargs):
            try:
                return await function(*args, **kwargs)
            except Exception:
                bound = signature.bind_partial(*args, **kwargs)
                supplied_type = bound.arguments.get("data_type")
                data_types = fixed_data_types or ([str(supplied_type)] if supplied_type else [])
                await _audit_read(
                    tool,
                    data_types=data_types,
                    start_date=bound.arguments.get("start_date"),
                    end_date=bound.arguments.get("end_date"),
                    outcome="failure",
                )
                raise

        return wrapped
    return decorate


mcp = MCPServer(
    "HealthQueryDirect",
    instructions=(
        "Read-only HealthQuery data access. Tools expose locally stored health "
        "records and safe aggregate/query output. Prefer get_health_summary for ordinary "
        "daily questions so sleep stages, vital summaries, activity totals, workouts, and "
        "optional screen time arrive in one call. Use low-level tools only for drill-down. "
        "No health-data mutation tools are available."
    ),
    auth=AuthSettings(
        issuer_url=oauth_issuer(),
        resource_server_url=oauth_resource(),
        required_scopes=["health:read"],
        client_registration_options=ClientRegistrationOptions(
            enabled=True,
            valid_scopes=OAUTH_SCOPES,
            default_scopes=["health:read"],
        ),
        revocation_options=RevocationOptions(enabled=True),
    ),
    auth_server_provider=oauth_provider,
)


@mcp.tool()
@_audit_read_failures("list_data_types", list(DATA_TYPES))
async def list_data_types() -> dict[str, object]:
    """List health data collections available to read."""
    response = {
        "data_types": [
            {
                "name": name,
                "label": spec["label"],
                "categories": spec["categories"],
                "raw_payload": name == "health_connect_records",
                "description": spec["description"],
                "time_column": spec["time_column"],
                "columns": spec["columns"],
                "value_columns": spec["value_columns"],
            }
            for name, spec in DATA_TYPES.items()
        ]
    }
    await _audit_read("list_data_types", data_types=list(DATA_TYPES), returned_rows=len(DATA_TYPES))
    return response


@mcp.tool()
@_audit_read_failures("describe_data_type")
async def describe_data_type(data_type: str) -> dict[str, object]:
    """Describe the columns and time semantics for one collection."""
    name, spec = _resolve_type(data_type)
    response = {
        "name": name,
        "label": spec["label"],
        "categories": spec["categories"],
        "description": spec["description"],
        "table": spec["table"],
        "time_column": spec["time_column"],
        "columns": spec["columns"],
        "value_columns": spec["value_columns"],
        "raw_payload": name == "health_connect_records",
        "date_filter_semantics": (
            f"Health Connect event start/time/end when present; updated_at for unknown shapes; calendar dates use {LOCAL_TIME_ZONE_LABEL}"
            if name == "health_connect_records"
            else f"{_local_date_expression(spec['time_column'])} ({LOCAL_TIME_ZONE_LABEL})"
        ),
    }
    await _audit_read("describe_data_type", data_types=[name], returned_rows=1)
    return response


@mcp.tool()
@_audit_read_failures("query_records")
async def query_records(
    data_type: str,
    start_date: str | None = None,
    end_date: str | None = None,
    limit: int = 100,
    descending: bool = True,
    record_type: str | None = None,
    device_id: str | None = None,
    include_deleted: bool = False,
    metric_type: str | None = None,
) -> dict[str, object]:
    """Read bounded records from a named health collection and date range.

    ``record_type``, ``device_id``, and ``include_deleted`` apply only to the
    lossless ``health_connect_records`` collection. ``metric_type`` applies to
    normalized point/interval metrics. Raw tombstones are hidden by default
    while remaining explicitly discoverable for audit/reconciliation.
    """
    return await _query_records_impl(
        data_type,
        start_date,
        end_date,
        limit,
        descending,
        record_type=record_type,
        device_id=device_id,
        include_deleted=include_deleted,
        metric_type=metric_type,
    )


async def _query_records_impl(
    data_type: str,
    start_date: str | None = None,
    end_date: str | None = None,
    limit: int = 100,
    descending: bool = True,
    *,
    record_type: str | None = None,
    device_id: str | None = None,
    include_deleted: bool = False,
    metric_type: str | None = None,
    audit_tool: str = "query_records",
) -> dict[str, object]:
    name, spec = _resolve_type(data_type)
    raw_filters_requested = record_type is not None or device_id is not None or include_deleted
    if raw_filters_requested and name != "health_connect_records":
        raise ValueError("record_type, device_id, and include_deleted apply only to health_connect_records")
    if metric_type is not None and name not in {"metric_points", "metric_intervals"}:
        raise ValueError("metric_type applies only to metric_points or metric_intervals")
    if record_type is not None and not record_type.strip():
        raise ValueError("record_type must not be empty")
    if device_id is not None and not device_id.strip():
        raise ValueError("device_id must not be empty")
    if metric_type is not None and not metric_type.strip():
        raise ValueError("metric_type must not be empty")
    bounded_limit = _limit(limit)
    order = "DESC" if descending else "ASC"
    time_expression = _raw_record_time_expression() if name == "health_connect_records" else spec["time_column"]
    clauses = [_date_filter(time_expression, start_date, end_date)]
    if name == "health_connect_records":
        if record_type is not None:
            clauses.append(f"record_type = {_sql_quote(record_type.strip())}")
        if device_id is not None:
            clauses.append(f"device_id = {_sql_quote(device_id.strip())}")
        if not include_deleted:
            clauses.append("deleted_at IS NULL")
    if metric_type is not None:
        clauses.append(f"metric_type = {_sql_quote(metric_type.strip())}")
    sql = (
        f"SELECT {', '.join(spec['columns'])} FROM {spec['table']} "
        f"WHERE {' AND '.join(clauses)} "
        f"ORDER BY {time_expression} {order} LIMIT {bounded_limit}"
    )
    result = await _post_query(sql)
    response = {
        "data_type": name,
        "start_date": _parse_date(start_date).isoformat() if _parse_date(start_date) else None,
        "end_date": _parse_date(end_date).isoformat() if _parse_date(end_date) else None,
        "limit": bounded_limit,
        "record_type": record_type.strip() if record_type is not None else None,
        "device_id": device_id.strip() if device_id is not None else None,
        "include_deleted": include_deleted if name == "health_connect_records" else None,
        "metric_type": metric_type.strip() if metric_type is not None else None,
        **result,
    }
    await _audit_read(
        audit_tool,
        data_types=[name],
        start_date=start_date,
        end_date=end_date,
        returned_rows=len(result.get("rows", [])),
        result_summary=_bounded_result_summary(name, result, metric_type=metric_type),
    )
    return response


@mcp.tool()
@_audit_read_failures("list_workouts", ["workouts"])
async def list_workouts(
    start_date: str | None = None,
    end_date: str | None = None,
    limit: int = 100,
) -> dict[str, object]:
    """List bounded workout sessions."""
    result = await _query_records_impl(
        "workouts", start_date=start_date, end_date=end_date, limit=limit, audit_tool="list_workouts"
    )
    for row in result.get("rows", []):
        if isinstance(row, dict):
            row["activity_type_raw"] = row.get("activity_type")
            row["activity_type"] = _normalize_exercise_type(row.get("activity_type"))
    return result


@mcp.tool()
@_audit_read_failures("get_workout", ["workouts"])
async def get_workout(workout_key: str) -> dict[str, object]:
    """Fetch one workout by its stable record key."""
    key = workout_key.strip()
    if not key:
        raise ValueError("workout_key is required")
    result = await _post_query(f"SELECT * FROM workouts WHERE workout_key = {_sql_quote(key)} LIMIT 1")
    for row in result.get("rows", []):
        if isinstance(row, dict):
            row["activity_type_raw"] = row.get("activity_type")
            row["activity_type"] = _normalize_exercise_type(row.get("activity_type"))
    await _audit_read("get_workout", data_types=["workouts"], returned_rows=len(result.get("rows", [])))
    return {"workout_key": key, **result}


def _summarize_sleep_stages(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, object]]]:
    normalized_rows: list[dict[str, Any]] = []
    stage_totals: dict[str, int] = {}
    for original in rows:
        row = dict(original)
        row["stage_type_raw"] = row.get("stage_type")
        row["stage_type"] = _normalize_sleep_stage(row.get("stage_type"))
        seconds = max(0, int(float(row.get("duration_seconds") or 0)))
        row["duration_seconds"] = seconds
        stage_totals[row["stage_type"]] = stage_totals.get(row["stage_type"], 0) + seconds
        normalized_rows.append(row)
    total_seconds = sum(stage_totals.values())
    order = {name: index for index, name in enumerate(("deep", "light", "rem", "awake", "sleeping", "unknown"))}
    totals = [
        {
            "stage": stage,
            "seconds": seconds,
            "minutes": round(seconds / 60, 1),
            "percent": round(seconds * 100 / total_seconds, 1) if total_seconds else 0.0,
        }
        for stage, seconds in sorted(stage_totals.items(), key=lambda item: (order.get(item[0], 99), item[0]))
    ]
    return normalized_rows, totals


@mcp.tool()
@_audit_read_failures("get_session_data", ["sleep_sessions", "sleep_stages"])
async def get_session_data(session_key: str) -> dict[str, object]:
    """Fetch one sleep session and its stage rows."""
    key = session_key.strip()
    if not key:
        raise ValueError("session_key is required")
    session = await _post_query(f"SELECT * FROM sleep_sessions WHERE session_key = {_sql_quote(key)} LIMIT 1")
    stages = await _post_query(f"SELECT * FROM sleep_stages WHERE session_key = {_sql_quote(key)} ORDER BY start_time ASC LIMIT {MAX_TOOL_ROWS}")
    stage_rows = [row for row in stages.get("rows", []) if isinstance(row, dict)]
    normalized_stage_rows, stage_totals = _summarize_sleep_stages(stage_rows)
    stages = {**stages, "rows": normalized_stage_rows, "row_count": len(normalized_stage_rows)}
    await _audit_read(
        "get_session_data",
        data_types=["sleep_sessions", "sleep_stages"],
        returned_rows=len(session.get("rows", [])) + len(stages.get("rows", [])),
        result_summary={
            **(_bounded_result_summary("sleep_sessions", session) or {}),
            "stages": [
                {"stage": row.get("stage_type"), "start": row.get("start_time"), "end": row.get("end_time"), "seconds": row.get("duration_seconds")}
                for row in normalized_stage_rows[:40]
            ],
            "stage_totals": stage_totals,
        },
    )
    return {"session_key": key, "session": session, "stages": stages, "stage_totals": stage_totals}


def _health_summary_date(value: str | None) -> date:
    return _parse_date(value) or datetime.now(ZoneInfo(LOCAL_TIME_ZONE_LABEL)).date()


async def _sleep_health_summary(target: date) -> tuple[dict[str, object], int]:
    date_clause = _date_filter("end_time", target, target)
    sessions = await _post_query(
        "SELECT session_key, source, start_time, end_time, duration_minutes, efficiency_pct "
        f"FROM sleep_sessions WHERE {date_clause} "
        "ORDER BY duration_minutes DESC, end_time DESC LIMIT 20"
    )
    session_rows = [row for row in sessions.get("rows", []) if isinstance(row, dict)]
    if not session_rows:
        return {"session_count": 0, "main_sleep": None, "other_sessions": [], "stage_totals": [], "stage_intervals": []}, 0

    main = session_rows[0]
    stages = await _post_query(
        "SELECT stage_type, start_time, end_time, duration_seconds "
        f"FROM sleep_stages WHERE session_key = {_sql_quote(str(main.get('session_key') or ''))} "
        f"ORDER BY start_time ASC LIMIT {MAX_TOOL_ROWS}"
    )
    stage_rows = [row for row in stages.get("rows", []) if isinstance(row, dict)]
    normalized_stages, stage_totals = _summarize_sleep_stages(stage_rows)

    def session_view(row: dict[str, Any]) -> dict[str, object]:
        return {
            "start": row.get("start_time"),
            "end": row.get("end_time"),
            "minutes": row.get("duration_minutes"),
            "efficiency_percent": row.get("efficiency_pct"),
        }

    return {
        "session_count": len(session_rows),
        "main_sleep": session_view(main),
        "other_sessions": [session_view(row) for row in session_rows[1:]],
        "stage_totals": stage_totals,
        "stage_intervals": [
            {
                "stage": row.get("stage_type"),
                "start": row.get("start_time"),
                "end": row.get("end_time"),
                "seconds": row.get("duration_seconds"),
            }
            for row in normalized_stages
        ],
    }, len(session_rows) + len(normalized_stages)


async def _vitals_health_summary(target: date) -> tuple[dict[str, object], int]:
    date_clause = _date_filter("recorded_at", target, target)
    aggregates = await _post_query(
        "SELECT metric_type, unit, COUNT(*) AS record_count, "
        "MIN(numeric_value) AS minimum, MAX(numeric_value) AS maximum, "
        "AVG(numeric_value) AS average "
        f"FROM metric_points WHERE {date_clause} "
        "GROUP BY metric_type, unit ORDER BY metric_type ASC"
    )
    metrics: list[dict[str, object]] = []
    total_rows = 0
    for row in aggregates.get("rows", []):
        if not isinstance(row, dict) or not row.get("metric_type"):
            continue
        metric_type = str(row["metric_type"])
        latest = await _post_query(
            "SELECT recorded_at, numeric_value, text_value, unit FROM metric_points "
            f"WHERE {date_clause} AND metric_type = {_sql_quote(metric_type)} "
            "ORDER BY recorded_at DESC LIMIT 1"
        )
        latest_row = next((item for item in latest.get("rows", []) if isinstance(item, dict)), {})
        details = None
        if isinstance(latest_row.get("text_value"), str):
            try:
                decoded = json.loads(str(latest_row["text_value"]))
                details = decoded if isinstance(decoded, dict) else None
            except json.JSONDecodeError:
                details = None
        count = int(row.get("record_count") or 0)
        total_rows += count
        metrics.append({
            "metric": metric_type,
            "unit": row.get("unit") or latest_row.get("unit"),
            "count": count,
            "minimum": row.get("minimum"),
            "maximum": row.get("maximum"),
            "average": round(float(row["average"]), 1) if isinstance(row.get("average"), (int, float)) else None,
            "latest": latest_row.get("numeric_value"),
            "latest_at": latest_row.get("recorded_at"),
            "latest_details": details,
        })
    return {"metrics": metrics}, total_rows


async def _activity_health_summary(target: date) -> tuple[dict[str, object], int]:
    date_clause = _date_filter("start_time", target, target)
    aggregates = await _post_query(
        "SELECT metric_type, unit, COUNT(*) AS record_count, "
        "SUM(numeric_value) AS total, MIN(numeric_value) AS minimum, "
        "MAX(numeric_value) AS maximum, AVG(numeric_value) AS average "
        f"FROM metric_intervals WHERE {date_clause} "
        "AND metric_type NOT IN ('screen_time', 'menstruation_period') "
        "GROUP BY metric_type, unit ORDER BY metric_type ASC"
    )
    metrics: list[dict[str, object]] = []
    total_rows = 0
    for row in aggregates.get("rows", []):
        if not isinstance(row, dict):
            continue
        count = int(row.get("record_count") or 0)
        total_rows += count
        metrics.append({
            "metric": row.get("metric_type"),
            "unit": row.get("unit"),
            "count": count,
            "total": row.get("total"),
            "minimum": row.get("minimum"),
            "maximum": row.get("maximum"),
            "average": round(float(row["average"]), 1) if isinstance(row.get("average"), (int, float)) else None,
        })

    workouts_result = await _post_query(
        "SELECT activity_type, start_time, end_time, duration_minutes, calories, avg_hr "
        f"FROM workouts WHERE {date_clause} ORDER BY start_time ASC LIMIT {MAX_TOOL_ROWS}"
    )
    workouts = []
    for row in workouts_result.get("rows", []):
        if not isinstance(row, dict):
            continue
        workouts.append({
            "activity": _normalize_exercise_type(row.get("activity_type")),
            "activity_raw": row.get("activity_type"),
            "start": row.get("start_time"),
            "end": row.get("end_time"),
            "minutes": row.get("duration_minutes"),
            "calories": row.get("calories"),
            "average_heart_rate": row.get("avg_hr"),
        })
    total_rows += len(workouts)
    return {"metrics": metrics, "workouts": workouts}, total_rows


async def _cycle_health_summary(target: date) -> tuple[dict[str, object], int]:
    target_sql = _sql_quote(target.isoformat())
    result = await _post_query(
        "SELECT start_time, end_time, numeric_value AS duration_days, unit "
        "FROM metric_intervals WHERE metric_type = 'menstruation_period' "
        f"AND {_local_date_expression('start_time')} <= date({target_sql}) "
        f"AND {_local_date_expression('COALESCE(end_time, start_time)')} >= date({target_sql}) "
        f"ORDER BY start_time DESC LIMIT {MAX_TOOL_ROWS}"
    )
    periods = [
        {
            "start": row.get("start_time"),
            "end": row.get("end_time"),
            "duration_days": row.get("duration_days"),
        }
        for row in result.get("rows", [])
        if isinstance(row, dict)
    ]
    return {"active": bool(periods), "periods": periods}, len(periods)


async def _screen_time_health_summary(target: date) -> tuple[dict[str, object], int]:
    date_clause = _date_filter("start_time", target, target)
    result = await _post_query(
        "SELECT numeric_value, text_value, unit FROM metric_intervals "
        f"WHERE {date_clause} AND metric_type = 'screen_time' "
        f"ORDER BY numeric_value DESC LIMIT {MAX_TOOL_ROWS}"
    )
    by_app: dict[str, float] = {}
    row_count = 0
    for row in result.get("rows", []):
        if not isinstance(row, dict) or not isinstance(row.get("numeric_value"), (int, float)):
            continue
        row_count += 1
        app_name = "Unknown app"
        if isinstance(row.get("text_value"), str):
            try:
                decoded = json.loads(str(row["text_value"]))
                if isinstance(decoded, dict):
                    app_name = str(decoded.get("app") or decoded.get("package") or app_name)
            except json.JSONDecodeError:
                pass
        by_app[app_name] = by_app.get(app_name, 0.0) + float(row["numeric_value"])
    apps = [
        {"app": app, "minutes": round(minutes, 1)}
        for app, minutes in sorted(by_app.items(), key=lambda item: (-item[1], item[0]))
    ]
    return {"total_minutes": round(sum(by_app.values()), 1), "apps": apps}, row_count


@mcp.tool()
@_audit_read_failures(
    "get_health_summary",
    ["metric_points", "metric_intervals", "sleep_sessions", "sleep_stages", "workouts"],
)
async def get_health_summary(
    target_date: str | None = None,
    sections: list[str] | None = None,
) -> dict[str, object]:
    """Return a complete one-call daily summary from normalized Health Connect data.

    ``target_date`` is the Asia/Shanghai calendar date; sleep is assigned to
    the date on which the main session ended. ``sections`` can limit the call
    to sleep, vitals, activity, cycle, and/or screen_time. Omitting it returns all
    five sections. Sleep stage codes and exercise codes are translated to
    stable names, and stage durations and percentages are calculated here so
    callers never need to join low-level records themselves.
    """
    target = _health_summary_date(target_date)
    requested = list(HEALTH_SUMMARY_SECTIONS) if sections is None else [str(item).strip().lower() for item in sections]
    if not requested:
        raise ValueError("sections must contain at least one section")
    unknown = sorted(set(requested) - set(HEALTH_SUMMARY_SECTIONS))
    if unknown:
        raise ValueError(f"Unknown health summary sections: {', '.join(unknown)}")
    requested = list(dict.fromkeys(requested))

    response: dict[str, object] = {
        "date": target.isoformat(),
        "timezone": LOCAL_TIME_ZONE_LABEL,
        "sections": requested,
    }
    total_rows = 0
    data_types: list[str] = []
    if "sleep" in requested:
        response["sleep"], count = await _sleep_health_summary(target)
        total_rows += count
        data_types.extend(["sleep_sessions", "sleep_stages"])
    if "vitals" in requested:
        response["vitals"], count = await _vitals_health_summary(target)
        total_rows += count
        data_types.append("metric_points")
    if "activity" in requested:
        response["activity"], count = await _activity_health_summary(target)
        total_rows += count
        data_types.extend(["metric_intervals", "workouts"])
    if "cycle" in requested:
        response["cycle"], count = await _cycle_health_summary(target)
        total_rows += count
        data_types.append("metric_intervals")
    if "screen_time" in requested:
        response["screen_time"], count = await _screen_time_health_summary(target)
        total_rows += count
        data_types.append("metric_intervals")

    await _audit_read(
        "get_health_summary",
        data_types=data_types,
        start_date=target,
        end_date=target,
        returned_rows=total_rows,
        result_summary={"kind": "health_summary", **response},
    )
    return response


@mcp.tool()
@_audit_read_failures("aggregate")
async def aggregate(
    data_type: str,
    aggregation: str = "count",
    value_column: str | None = None,
    start_date: str | None = None,
    end_date: str | None = None,
    interval: str = "total",
) -> dict[str, object]:
    """Compute a bounded count/sum/average/min/max over a collection."""
    name, spec = _resolve_type(data_type)
    operation = aggregation.strip().lower()
    if operation not in {"count", "sum", "avg", "min", "max"}:
        raise ValueError("aggregation must be count, sum, avg, min, or max")
    column = value_column or ("numeric_value" if "numeric_value" in spec["columns"] else spec["time_column"])
    if column not in spec["columns"]:
        raise ValueError(f"Unknown value_column for {name}: {column}")
    interval_key = interval.strip().lower()
    if interval_key not in {"total", "day", "week", "month"}:
        raise ValueError("interval must be total, day, week, or month")
    value_expr = "COUNT(*)" if operation == "count" else f"{operation.upper()}({column})"
    date_clause = _date_filter(spec["time_column"], start_date, end_date)
    if interval_key == "total":
        sql = f"SELECT {value_expr} AS value FROM {spec['table']} WHERE {date_clause}"
    else:
        format_value = {"day": "%Y-%m-%d", "week": "%Y-%W", "month": "%Y-%m"}[interval_key]
        bucket_expr = _local_bucket_expression(spec["time_column"], format_value)
        sql = f"SELECT {bucket_expr} AS bucket, {value_expr} AS value FROM {spec['table']} WHERE {date_clause} GROUP BY bucket ORDER BY bucket ASC LIMIT {MAX_TOOL_ROWS}"
    result = await _post_query(sql)
    await _audit_read(
        "aggregate", data_types=[name], start_date=start_date, end_date=end_date,
        returned_rows=len(result.get("rows", [])),
        result_summary={
            "kind": name,
            "aggregation": operation,
            "value_column": column,
            "values": [
                {key: value for key, value in row.items() if key in {"bucket", "value"}}
                for row in result.get("rows", [])[:20]
                if isinstance(row, dict)
            ],
        },
    )
    return {"data_type": name, "aggregation": operation, "value_column": column, "interval": interval_key, **result}


@mcp.tool()
@_audit_read_failures("get_data_coverage")
async def get_data_coverage(
    data_type: str,
    start_date: str | None = None,
    end_date: str | None = None,
) -> dict[str, object]:
    """Return bounded coverage and missing-value facts for one collection.

    This is descriptive only: it reports how much data exists, its time span,
    source/device breadth, and null counts.  It does not infer health status.
    """
    name, spec = _resolve_type(data_type)
    coverage_time_expression = _raw_record_time_expression() if name == "health_connect_records" else spec["time_column"]
    date_clause = _date_filter(coverage_time_expression, start_date, end_date)
    columns = spec["columns"]
    source_expr = "COUNT(DISTINCT source)" if "source" in columns else "0"
    device_expr = "COUNT(DISTINCT device_id)" if "device_id" in columns else "0"
    null_exprs = ", ".join(
        f"COALESCE(SUM(CASE WHEN {column} IS NULL THEN 1 ELSE 0 END), 0) AS null_{column}"
        for column in columns
    )
    payload_expr = (
        "COALESCE(SUM(CASE WHEN payload_json IS NOT NULL THEN 1 ELSE 0 END), 0)"
        if "payload_json" in columns
        else "0"
    )
    deleted_expr = (
        "COALESCE(SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END), 0)"
        if "deleted_at" in columns
        else "0"
    )
    sql = (
        "SELECT COUNT(*) AS row_count, "
        f"COUNT(DISTINCT {_local_date_expression(coverage_time_expression)}) AS distinct_day_count, "
        f"MIN({coverage_time_expression}) AS first_record_at, "
        f"MAX({coverage_time_expression}) AS last_record_at, "
        f"{source_expr} AS distinct_source_count, "
        f"{device_expr} AS distinct_device_count, "
        f"{payload_expr} AS payload_present_count, "
        f"{deleted_expr} AS deleted_row_count, "
        f"{null_exprs} FROM {spec['table']} WHERE {date_clause}"
    )
    result = await _post_query(sql)
    row = result["rows"][0] if result["rows"] else {}
    null_counts = {column: row.get(f"null_{column}", 0) for column in columns}
    coverage = {
        "row_count": row.get("row_count", 0),
        "distinct_day_count": row.get("distinct_day_count", 0),
        "first_record_at": row.get("first_record_at"),
        "last_record_at": row.get("last_record_at"),
        "distinct_source_count": row.get("distinct_source_count", 0),
        "distinct_device_count": row.get("distinct_device_count", 0),
        "payload_present_count": row.get("payload_present_count", 0),
        "deleted_row_count": row.get("deleted_row_count", 0),
        "null_counts": null_counts,
    }
    response = {
        "data_type": name,
        "start_date": _parse_date(start_date).isoformat() if _parse_date(start_date) else None,
        "end_date": _parse_date(end_date).isoformat() if _parse_date(end_date) else None,
        "coverage": coverage,
    }
    await _audit_read(
        "get_data_coverage", data_types=[name], start_date=start_date, end_date=end_date, returned_rows=1
    )
    return response


@mcp.tool()
@_audit_read_failures("summarize_trends")
async def summarize_trends(
    data_type: str,
    value_column: str | None = None,
    start_date: str | None = None,
    end_date: str | None = None,
    interval: str = "day",
    limit: int = 100,
) -> dict[str, object]:
    """Summarize a numeric collection over total/day/week/month buckets.

    Results are deterministic descriptive aggregates (count, mean, min, max,
    and sum).  The tool deliberately does not label trends as good, bad, or
    diagnostic; callers can inspect the raw rows when context is needed.
    """
    name, spec = _resolve_type(data_type)
    bounded_limit = _limit(limit)
    column = value_column or (spec["value_columns"][0] if spec["value_columns"] else None)
    if not column:
        raise ValueError(f"value_column is required for {name}; no numeric value is defined for this collection")
    if column not in spec["value_columns"]:
        raise ValueError(f"Unknown numeric value_column for {name}: {column}")
    interval_key = interval.strip().lower()
    if interval_key not in {"total", "day", "week", "month"}:
        raise ValueError("interval must be total, day, week, or month")
    date_clause = _date_filter(spec["time_column"], start_date, end_date)
    value_expr = (
        f"COUNT(*) AS row_count, COUNT({column}) AS value_count, "
        f"AVG({column}) AS average, MIN({column}) AS minimum, "
        f"MAX({column}) AS maximum, SUM({column}) AS total"
    )
    if interval_key == "total":
        sql = f"SELECT {value_expr} FROM {spec['table']} WHERE {date_clause}"
    else:
        format_value = {"day": "%Y-%m-%d", "week": "%Y-%W", "month": "%Y-%m"}[interval_key]
        sql = (
            f"SELECT {_local_bucket_expression(spec['time_column'], format_value)} AS bucket, {value_expr} "
            f"FROM {spec['table']} WHERE {date_clause} GROUP BY bucket ORDER BY bucket ASC LIMIT {bounded_limit}"
        )
    result = await _post_query(sql)
    response = {
        "data_type": name,
        "value_column": column,
        "interval": interval_key,
        "start_date": _parse_date(start_date).isoformat() if _parse_date(start_date) else None,
        "end_date": _parse_date(end_date).isoformat() if _parse_date(end_date) else None,
        "limit": bounded_limit,
        **result,
    }
    await _audit_read(
        "summarize_trends", data_types=[name], start_date=start_date, end_date=end_date,
        returned_rows=len(result.get("rows", [])),
        result_summary={
            "kind": name,
            "value_column": column,
            "values": [
                {key: value for key, value in row.items() if key in {"bucket", "value_count", "average", "minimum", "maximum", "total"}}
                for row in result.get("rows", [])[:20]
                if isinstance(row, dict)
            ],
        },
    )
    return response


@mcp.tool()
@_audit_read_failures("list_health_connect_record_types", ["health_connect_records"])
async def list_health_connect_record_types(
    start_date: str | None = None,
    end_date: str | None = None,
    include_deleted: bool = False,
    limit: int = 100,
) -> dict[str, object]:
    """Catalog observed raw Android Health Connect record types.

    The catalog keeps platform type names visible while ``query_records`` on
    ``health_connect_records`` remains the lossless path for nested payload JSON.
    """
    bounded_limit = _limit(limit)
    date_clause = _date_filter(_raw_record_time_expression(), start_date, end_date)
    deleted_clause = "1 = 1" if include_deleted else "deleted_at IS NULL"
    sql = (
        "SELECT record_type, COUNT(*) AS row_count, COUNT(DISTINCT device_id) AS device_count, "
        "MIN(first_seen_at) AS first_seen_at, MAX(updated_at) AS last_updated_at, "
        "SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END) AS active_count, "
        "SUM(CASE WHEN payload_json IS NOT NULL THEN 1 ELSE 0 END) AS payload_present_count "
        f"FROM health_connect_records WHERE {date_clause} AND {deleted_clause} "
        f"GROUP BY record_type ORDER BY row_count DESC, record_type ASC LIMIT {bounded_limit}"
    )
    result = await _post_query(sql, timeout_seconds=MCP_CATALOG_TIMEOUT_SECONDS)
    response = {
        "start_date": _parse_date(start_date).isoformat() if _parse_date(start_date) else None,
        "end_date": _parse_date(end_date).isoformat() if _parse_date(end_date) else None,
        "include_deleted": include_deleted,
        "limit": bounded_limit,
        **result,
    }
    await _audit_read(
        "list_health_connect_record_types",
        data_types=["health_connect_records"],
        start_date=start_date,
        end_date=end_date,
        returned_rows=len(result.get("rows", [])),
    )
    return response


@mcp.custom_route("/oauth/consent", methods=["GET", "POST"], include_in_schema=False)
async def oauth_consent(request: Request) -> Response:
    """Owner-authenticated consent endpoint used by the SDK authorization route.

    A remote MCP client normally opens this URL in a fresh browser. Keep the
    existing signed browser-session boundary, offer the owner's password login
    inline, and redirect back to this request after issuing the session cookie.
    Canonical-origin browsers keep the normal two-step session flow. ChatGPT's
    opaque-origin embedded browser uses a one-time state-bound CSRF token and
    requires the owner password plus an explicit approve decision in one POST.
    """
    if request.method == "POST":
        await require_same_origin(request)
    form = await request.form() if request.method == "POST" else request.query_params
    state = str(form.get("state") or "")
    if not state:
        return Response("Missing OAuth state", status_code=400)

    if request.scope.get("healthquery.embedded_consent_validated") is True:
        password = str(form.get("password") or "")
        csrf_token = str(form.get("csrf_token") or "")
        if str(form.get("decision") or "") != "approve" or not await verify_admin_password(password):
            await audit_browser_event(request, "mcp_oauth_owner_login", False)
            return Response("Embedded OAuth consent was rejected", status_code=403)
        if not await oauth_provider.consume_consent_csrf(state, csrf_token):
            return Response("OAuth consent token is missing, expired, or already used", status_code=400)
        await audit_browser_event(request, "mcp_oauth_owner_login", True)
        try:
            redirect = await oauth_provider.approve(state, subject="browser")
        except ValueError as exc:
            return Response(str(exc), status_code=400)
        return RedirectResponse(redirect, status_code=302, headers={"cache-control": "no-store"})

    session_valid = await session_is_valid(request)
    force_inline_owner_auth = (
        request.method == "GET"
        and await oauth_provider.consent_requires_inline_owner_auth(state)
    )
    if not session_valid or force_inline_owner_auth:
        if request.method == "POST" and form.get("password"):
            password = str(form.get("password") or "")
            if await verify_admin_password(password):
                response = RedirectResponse(
                    f"{request.url.path}?{urlencode({'state': state})}",
                    status_code=303,
                    headers={"cache-control": "no-store"},
                )
                await issue_session_cookie(request, response)
                await audit_browser_event(request, "mcp_oauth_owner_login", True)
                return response
            await audit_browser_event(request, "mcp_oauth_owner_login", False)
            login_error = "Invalid owner credentials."
        else:
            login_error = ""
        csrf_token = await oauth_provider.issue_consent_csrf(state)
        if not csrf_token:
            return Response("OAuth request is missing or expired", status_code=400)
        safe_state = html.escape(state, quote=True)
        safe_csrf = html.escape(csrf_token, quote=True)
        safe_error = f"<p role=\"alert\">{html.escape(login_error)}</p>" if login_error else ""
        return HTMLResponse(
            "<!doctype html><html><body><h1>HealthQuery sign-in required</h1>"
            "<p>Sign in with the owner's password to continue this MCP authorization.</p>"
            f"{safe_error}<form method=\"post\">"
            f'<input type="hidden" name="state" value="{safe_state}">'
            f'<input type="hidden" name="csrf_token" value="{safe_csrf}">'
            '<label>Owner password <input name="password" type="password" autocomplete="current-password" maxlength="1024" required></label>'
            '<button type="submit" name="decision" value="approve">Sign in and allow read access</button>'
            '</form></body></html>',
            headers={"cache-control": "no-store"},
        )

    if request.method == "GET":
        safe_state = html.escape(state, quote=True)
        return HTMLResponse(
            "<!doctype html><html><body><h1>HealthQuery MCP access</h1>"
            "<p>Allow this MCP client to read HealthQuery data?</p>"
            f'<form method="post"><input type="hidden" name="state" value="{safe_state}">'
            '<button name="decision" value="deny">Deny</button>'
            '<button name="decision" value="approve">Allow read access</button></form>'
            "</body></html>",
            headers={"cache-control": "no-store"},
        )
    if str(form.get("decision") or "deny") != "approve":
        return Response("MCP authorization was denied", status_code=400)
    try:
        redirect = await oauth_provider.approve(state, subject="browser")
    except ValueError as exc:
        return Response(str(exc), status_code=400)
    return RedirectResponse(redirect, status_code=302, headers={"cache-control": "no-store"})


def main() -> None:
    # The official MCP SDK owns stdin/stdout and keeps logs on stderr; stdio tool calls
    # construct the legacy API client only when they execute.
    mcp.run()


if __name__ == "__main__":
    main()
