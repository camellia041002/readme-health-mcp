from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator

from auth import require_read_auth
from services.health_views import (
    get_batches_view,
    get_activity_view,
    get_body_view,
    get_overview_view,
    get_sleep_view,
    get_sync_status,
    get_timeline_view,
    get_vitals_view,
)
from services.operational_settings import OperationalSettings, load_operational_settings, save_operational_settings
from services.safe_sql import SafeSqlTimeoutError, execute_safe_select
from services.sql_guard import SqlGuardError
from services.workout_views import get_workout_detail

router = APIRouter(prefix="/api/health", tags=["health"], dependencies=[Depends(require_read_auth)])

MAX_QUERY_ROWS = 1000
MAX_QUERY_BYTES = 1_000_000


class SqlQueryRequest(BaseModel):
    sql: str = Field(..., min_length=1)


@router.get("/overview", operation_id="get_health_overview")
async def health_overview() -> dict[str, Any]:
    return await get_overview_view()


@router.get("/activity", operation_id="get_health_activity")
async def health_activity(workout_limit: int = 500) -> dict[str, Any]:
    if not 1 <= workout_limit <= 500:
        raise HTTPException(status_code=422, detail="workout_limit must be between 1 and 500")
    return await get_activity_view(workout_limit=workout_limit)


@router.get("/workouts/{workout_key:path}", operation_id="get_health_workout")
async def health_workout(workout_key: str) -> dict[str, Any]:
    detail = await get_workout_detail(workout_key.strip())
    if detail is None:
        raise HTTPException(status_code=404, detail="Workout not found")
    return detail


@router.get("/sleep", operation_id="get_health_sleep")
async def health_sleep() -> dict[str, Any]:
    return await get_sleep_view()


@router.get("/vitals", operation_id="get_health_vitals")
async def health_vitals() -> dict[str, Any]:
    return await get_vitals_view()


@router.get("/body", operation_id="get_health_body")
async def health_body() -> dict[str, Any]:
    return await get_body_view()


@router.get("/timeline", operation_id="get_health_timeline")
async def health_timeline(days: int = 14) -> dict[str, Any]:
    return await get_timeline_view(days=days)


@router.get("/summary", operation_id="get_health_summary")
async def health_summary() -> dict[str, Any]:
    return await get_overview_view()


@router.get("/status", operation_id="get_health_status")
async def health_status() -> dict[str, Any]:
    return await get_sync_status()


@router.get("/batches", operation_id="get_health_batches")
async def health_batches(limit: int = 10) -> dict[str, Any]:
    return await get_batches_view(limit=limit)


def _config_response(operational: OperationalSettings) -> dict[str, Any]:
    return {
        "stale_sync_threshold_minutes": operational.stale_sync_threshold_minutes,
        "timeline_window_days": operational.timeline_window_days,
    }


@router.get("/config", operation_id="get_health_config")
async def health_config() -> dict[str, Any]:
    return _config_response(await load_operational_settings())


class ConfigUpdateRequest(BaseModel):
    stale_sync_threshold_minutes: int | None = None
    timeline_window_days: int | None = None

    @field_validator("stale_sync_threshold_minutes", "timeline_window_days", mode="before")
    @classmethod
    def positive_int(cls, value: Any) -> Any:
        if value is not None and int(value) < 1:
            raise ValueError("must be a positive integer")
        return value


@router.put("/config", operation_id="put_health_config")
async def update_health_config(body: ConfigUpdateRequest) -> dict[str, Any]:
    current = await load_operational_settings()
    updated = OperationalSettings(
        stale_sync_threshold_minutes=body.stale_sync_threshold_minutes if body.stale_sync_threshold_minutes is not None else current.stale_sync_threshold_minutes,
        timeline_window_days=body.timeline_window_days if body.timeline_window_days is not None else current.timeline_window_days,
    )
    await save_operational_settings(updated)
    return _config_response(updated)


@router.post("/query", operation_id="post_health_query")
async def health_query(body: SqlQueryRequest) -> dict[str, Any]:
    try:
        result = await execute_safe_select(
            body.sql,
            max_rows=MAX_QUERY_ROWS,
            max_bytes=MAX_QUERY_BYTES,
        )
    except SqlGuardError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except SafeSqlTimeoutError as exc:
        raise HTTPException(status_code=408, detail=str(exc)) from exc
    return result.as_dict()
