from __future__ import annotations

import json
import math
from datetime import date, datetime, timedelta, timezone
from typing import Any

from db.database import fetch_all, fetch_one


# Values mirror ExerciseSessionRecord constants from AndroidX Health Connect 1.1.0
# (Apache-2.0). Keeping the mapping server-side prevents raw numeric SDK values from
# leaking into browser and MCP-facing presentation contracts.
EXERCISE_TYPES: dict[int, tuple[str, str]] = {
    0: ("other_workout", "Workout"),
    2: ("badminton", "Badminton"),
    4: ("baseball", "Baseball"),
    5: ("basketball", "Basketball"),
    8: ("biking", "Bike ride"),
    9: ("biking_stationary", "Indoor ride"),
    10: ("boot_camp", "Boot camp"),
    11: ("boxing", "Boxing"),
    13: ("calisthenics", "Calisthenics"),
    14: ("cricket", "Cricket"),
    16: ("dancing", "Dance"),
    25: ("elliptical", "Elliptical"),
    26: ("exercise_class", "Exercise class"),
    27: ("fencing", "Fencing"),
    28: ("football_american", "American football"),
    29: ("football_australian", "Australian football"),
    31: ("frisbee_disc", "Disc sport"),
    32: ("golf", "Golf"),
    33: ("guided_breathing", "Guided breathing"),
    34: ("gymnastics", "Gymnastics"),
    35: ("handball", "Handball"),
    36: ("high_intensity_interval_training", "HIIT"),
    37: ("hiking", "Hike"),
    38: ("ice_hockey", "Ice hockey"),
    39: ("ice_skating", "Ice skating"),
    44: ("martial_arts", "Martial arts"),
    46: ("paddling", "Paddling"),
    47: ("paragliding", "Paragliding"),
    48: ("pilates", "Pilates"),
    50: ("racquetball", "Racquetball"),
    51: ("rock_climbing", "Rock climbing"),
    52: ("roller_hockey", "Roller hockey"),
    53: ("rowing", "Rowing"),
    54: ("rowing_machine", "Indoor rowing"),
    55: ("rugby", "Rugby"),
    56: ("running", "Run"),
    57: ("running_treadmill", "Treadmill run"),
    58: ("sailing", "Sailing"),
    59: ("scuba_diving", "Scuba diving"),
    60: ("skating", "Skating"),
    61: ("skiing", "Skiing"),
    62: ("snowboarding", "Snowboarding"),
    63: ("snowshoeing", "Snowshoeing"),
    64: ("soccer", "Football"),
    65: ("softball", "Softball"),
    66: ("squash", "Squash"),
    68: ("stair_climbing", "Stair climbing"),
    69: ("stair_climbing_machine", "Stair machine"),
    70: ("strength_training", "Strength training"),
    71: ("stretching", "Stretching"),
    72: ("surfing", "Surfing"),
    73: ("swimming_open_water", "Open-water swim"),
    74: ("swimming_pool", "Pool swim"),
    75: ("table_tennis", "Table tennis"),
    76: ("tennis", "Tennis"),
    78: ("volleyball", "Volleyball"),
    79: ("walking", "Walk"),
    80: ("water_polo", "Water polo"),
    81: ("weightlifting", "Weightlifting"),
    82: ("wheelchair", "Wheelchair activity"),
    83: ("yoga", "Yoga"),
}

SERIES_RECORD_TYPES = {
    "HeartRateRecord",
    "SpeedRecord",
    "StepsCadenceRecord",
    "CyclingPedalingCadenceRecord",
    "PowerRecord",
}
TOTAL_RECORD_TYPES = {
    "DistanceRecord",
    "ActiveCaloriesBurnedRecord",
    "TotalCaloriesBurnedRecord",
    "ElevationGainedRecord",
}
ASSOCIATED_RECORD_TYPES = SERIES_RECORD_TYPES | TOTAL_RECORD_TYPES


def _safe_json(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value:
        return {}
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    if isinstance(value, dict):
        return _number(value.get("value"))
    return None


def _unit_value(value: Any, factors: dict[str, float]) -> float | None:
    number = _number(value)
    if number is None:
        return None
    if not isinstance(value, dict):
        return number
    unit = str(value.get("type") or "").upper()
    return number * factors.get(unit, 1.0)


def _meters(value: Any) -> float | None:
    return _unit_value(value, {
        "METERS": 1.0,
        "KILOMETERS": 1_000.0,
        "MILES": 1_609.344,
        "FEET": 0.3048,
        "INCHES": 0.0254,
    })


def _kilocalories(value: Any) -> float | None:
    return _unit_value(value, {
        "KILOCALORIES": 1.0,
        "CALORIES": 0.001,
        "JOULES": 0.000239005736,
        "KILOJOULES": 0.239005736,
    })


def _meters_per_second(value: Any) -> float | None:
    return _unit_value(value, {
        "METERS_PER_SECOND": 1.0,
        "KILOMETERS_PER_HOUR": 1 / 3.6,
        "MILES_PER_HOUR": 0.44704,
    })


def _watts(value: Any) -> float | None:
    return _unit_value(value, {"WATTS": 1.0, "KILOWATTS": 1_000.0})


def _parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _origin(payload: dict[str, Any]) -> str | None:
    metadata = payload.get("metadata")
    if not isinstance(metadata, dict):
        return None
    data_origin = metadata.get("dataOrigin")
    if not isinstance(data_origin, dict):
        return None
    package = data_origin.get("packageName")
    return str(package) if package else None


def _exercise_type(raw_type: Any) -> tuple[str, str, int | None]:
    try:
        code = int(raw_type)
    except (TypeError, ValueError):
        code = None
    if code is not None:
        key, label = EXERCISE_TYPES.get(code, ("other_workout", "Workout"))
        return key, label, code
    key = str(raw_type or "other_workout").strip().lower().replace("-", "_").replace(" ", "_")
    aliases = {"cycling": "biking", "bike": "biking", "walk": "walking", "run": "running"}
    key = aliases.get(key, key)
    label = key.replace("_", " ").title() if key else "Workout"
    return key or "other_workout", label, None


def workout_summary(row: dict[str, Any]) -> dict[str, Any]:
    raw = _safe_json(row.get("raw_json"))
    activity_type, activity_label, activity_code = _exercise_type(
        raw.get("exerciseType", row.get("activity_type"))
    )
    title = str(raw.get("title") or "").strip() or activity_label
    return {
        "workout_key": row.get("workout_key"),
        "activity_type": activity_type,
        "activity_label": activity_label,
        "activity_code": activity_code,
        "title": title,
        "source": row.get("source"),
        "source_app": _origin(raw),
        "start_time": row.get("start_time"),
        "end_time": row.get("end_time"),
        "duration_minutes": row.get("duration_minutes"),
        "calories": row.get("calories"),
        "avg_hr": row.get("avg_hr"),
    }


async def list_workout_summaries(
    *,
    limit: int = 500,
    start_date: str | None = None,
    end_date: str | None = None,
) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if start_date:
        clauses.append("substr(start_time, 1, 10) >= ?")
        params.append(start_date[:10])
    if end_date:
        clauses.append("substr(start_time, 1, 10) <= ?")
        params.append(end_date[:10])
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    bounded_limit = max(1, min(int(limit), 500))
    rows = await fetch_all(
        f"""
        SELECT workout_key, source, activity_type, start_time, end_time,
               duration_minutes, calories, avg_hr, raw_json
        FROM workouts
        {where}
        ORDER BY start_time DESC
        LIMIT ?
        """,
        tuple([*params, bounded_limit]),
    )
    return [workout_summary(row) for row in rows]


def _week_start(value: datetime) -> date:
    return value.date() - timedelta(days=value.weekday())


def _training_summaries(workouts: list[dict[str, Any]], weeks: int = 12) -> list[dict[str, Any]]:
    today_week = _week_start(datetime.now(timezone.utc))
    latest_time = next(
        (parsed for item in workouts if (parsed := _parse_time(item.get("start_time"))) is not None),
        None,
    )
    # Keep the training ribbon useful for historical imports. If there is no activity in the
    # current window, anchor it to the latest recorded workout and label the returned dates.
    earliest_current = today_week - timedelta(weeks=weeks - 1)
    anchor = today_week
    if latest_time and _week_start(latest_time) < earliest_current:
        anchor = _week_start(latest_time)
    buckets: dict[date, dict[str, Any]] = {}
    for offset in range(weeks - 1, -1, -1):
        week = anchor - timedelta(weeks=offset)
        buckets[week] = {
            "week_start": week.isoformat(),
            "duration_minutes": 0.0,
            "workouts": 0,
            "active_days": 0,
            "by_activity": {},
            "_days": set(),
        }
    for workout in workouts:
        started = _parse_time(workout.get("start_time"))
        if not started:
            continue
        bucket = buckets.get(_week_start(started))
        if not bucket:
            continue
        duration = float(workout.get("duration_minutes") or 0)
        activity = str(workout.get("activity_type") or "other_workout")
        bucket["duration_minutes"] += duration
        bucket["workouts"] += 1
        bucket["_days"].add(started.date().isoformat())
        bucket["by_activity"][activity] = bucket["by_activity"].get(activity, 0.0) + duration
    result: list[dict[str, Any]] = []
    for bucket in buckets.values():
        bucket["active_days"] = len(bucket.pop("_days"))
        bucket["duration_minutes"] = round(bucket["duration_minutes"], 1)
        bucket["by_activity"] = {key: round(value, 1) for key, value in bucket["by_activity"].items()}
        result.append(bucket)
    return result


def _sport_totals(workouts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    totals: dict[str, dict[str, Any]] = {}
    for workout in workouts:
        key = str(workout.get("activity_type") or "other_workout")
        total = totals.setdefault(key, {
            "activity_type": key,
            "activity_label": workout.get("activity_label") or "Workout",
            "workouts": 0,
            "duration_minutes": 0.0,
            "_days": set(),
        })
        total["workouts"] += 1
        total["duration_minutes"] += float(workout.get("duration_minutes") or 0)
        started = _parse_time(workout.get("start_time"))
        if started:
            total["_days"].add(started.date().isoformat())
    result = []
    for total in totals.values():
        total["active_days"] = len(total.pop("_days"))
        total["duration_minutes"] = round(total["duration_minutes"], 1)
        result.append(total)
    return sorted(result, key=lambda item: (-int(item["workouts"]), str(item["activity_label"])))


async def get_activity_training_view(*, workout_limit: int = 500) -> dict[str, Any]:
    workouts = await list_workout_summaries(limit=workout_limit)
    return {
        "daily_summaries": await fetch_all(
            """
            SELECT summary_date, steps, active_minutes, workouts
            FROM daily_summaries
            ORDER BY summary_date DESC
            LIMIT 30
            """
        ),
        "workouts": workouts,
        "weekly_training": _training_summaries(workouts),
        "sport_totals": _sport_totals(workouts),
        "history_total": int((await fetch_one("SELECT COUNT(*) AS count FROM workouts") or {}).get("count") or 0),
    }


def _downsample(points: list[dict[str, Any]], limit: int = 240) -> list[dict[str, Any]]:
    if len(points) <= limit:
        return points
    step = (len(points) - 1) / (limit - 1)
    indexes = sorted({round(index * step) for index in range(limit)})
    return [points[index] for index in indexes]


def _series_stats(points: list[dict[str, Any]]) -> tuple[float | None, float | None, float | None]:
    values = [float(point["value"]) for point in points if _number(point.get("value")) is not None]
    if not values:
        return None, None, None
    return sum(values) / len(values), min(values), max(values)


def _route_points(raw: dict[str, Any]) -> tuple[str, list[dict[str, Any]]]:
    result = raw.get("exerciseRouteResult")
    if not isinstance(result, dict):
        return "not_recorded", []
    route_container = result.get("exerciseRoute") if isinstance(result.get("exerciseRoute"), dict) else result
    route = route_container.get("route") if isinstance(route_container, dict) else None
    if not isinstance(route, list):
        # The current Android serializer preserves route Data, but the fieldless
        # ConsentRequired and NoData variants both arrive as {}.
        return "unavailable", []
    points: list[dict[str, Any]] = []
    for item in route:
        if not isinstance(item, dict):
            continue
        latitude = _number(item.get("latitude"))
        longitude = _number(item.get("longitude"))
        if latitude is None or longitude is None:
            continue
        point: dict[str, Any] = {"latitude": latitude, "longitude": longitude}
        altitude = _meters(item.get("altitude"))
        if altitude is not None:
            point["altitude_meters"] = altitude
        if item.get("time"):
            point["time"] = str(item["time"])
        points.append(point)
    return ("available" if points else "not_recorded"), _downsample(points, 800)


def _device_id_from_key(workout_key: str) -> str | None:
    parts = workout_key.split(":", 3)
    if len(parts) == 4 and parts[0] == "hc" and parts[2] == "ExerciseSessionRecord":
        return parts[1]
    return None


async def _associated_records(
    *,
    device_id: str,
    start_time: str,
    end_time: str,
) -> list[dict[str, Any]]:
    suffixes = sorted(ASSOCIATED_RECORD_TYPES)
    placeholders = ", ".join("?" for _ in suffixes)
    full_names = [f"androidx.health.connect.client.records.{suffix}" for suffix in suffixes]
    return await fetch_all(
        f"""
        SELECT record_type, payload_json
        FROM health_connect_records
        WHERE device_id = ?
          AND deleted_at IS NULL
          AND record_type IN ({placeholders})
          AND json_extract(payload_json, '$.startTime') < ?
          AND json_extract(payload_json, '$.endTime') > ?
        ORDER BY json_extract(payload_json, '$.startTime') ASC
        """,
        tuple([device_id, *full_names, end_time, start_time]),
    )


async def get_workout_detail(workout_key: str) -> dict[str, Any] | None:
    row = await fetch_one(
        """
        SELECT workout_key, source, activity_type, start_time, end_time,
               duration_minutes, calories, avg_hr, raw_json
        FROM workouts
        WHERE workout_key = ?
        LIMIT 1
        """,
        (workout_key,),
    )
    if not row:
        return None
    raw = _safe_json(row.get("raw_json"))
    summary = workout_summary(row)
    metadata = raw.get("metadata") if isinstance(raw.get("metadata"), dict) else {}
    device = metadata.get("device") if isinstance(metadata.get("device"), dict) else {}
    summary.update({
        "notes": str(raw.get("notes") or "").strip() or None,
        "recording_method": metadata.get("recordingMethod"),
        "device": {
            key: device.get(key)
            for key in ("manufacturer", "model", "type")
            if device.get(key) is not None
        },
    })

    route_status, route = _route_points(raw)
    series: dict[str, list[dict[str, Any]]] = {
        "heart_rate": [],
        "speed": [],
        "cadence": [],
        "power": [],
    }
    totals: dict[str, float | None] = {
        "distance_meters": None,
        "active_calories": _number(row.get("calories")),
        "total_calories": None,
        "elevation_meters": None,
        "avg_hr": _number(row.get("avg_hr")),
        "min_hr": None,
        "max_hr": None,
        "avg_speed_mps": None,
        "max_speed_mps": None,
        "avg_cadence": None,
        "max_cadence": None,
        "avg_power_watts": None,
        "max_power_watts": None,
    }
    device_id = _device_id_from_key(workout_key)
    associated = await _associated_records(
        device_id=device_id,
        start_time=str(row["start_time"]),
        end_time=str(row["end_time"]),
    ) if device_id else []
    source_app = _origin(raw)
    start = _parse_time(row.get("start_time"))
    end = _parse_time(row.get("end_time"))

    distance_values: list[float] = []
    active_calorie_values: list[float] = []
    total_calorie_values: list[float] = []
    elevation_values: list[float] = []
    for record in associated:
        payload = _safe_json(record.get("payload_json"))
        if source_app and _origin(payload) not in {None, source_app}:
            continue
        short_type = str(record.get("record_type") or "").rsplit(".", 1)[-1]
        if short_type in TOTAL_RECORD_TYPES:
            record_start = _parse_time(payload.get("startTime"))
            record_end = _parse_time(payload.get("endTime"))
            # Do not attribute a cumulative total that extends beyond the session.
            if not start or not end or not record_start or not record_end or record_start < start or record_end > end:
                continue
            if short_type == "DistanceRecord" and (value := _meters(payload.get("distance"))) is not None:
                distance_values.append(value)
            elif short_type == "ActiveCaloriesBurnedRecord" and (value := _kilocalories(payload.get("energy"))) is not None:
                active_calorie_values.append(value)
            elif short_type == "TotalCaloriesBurnedRecord" and (value := _kilocalories(payload.get("energy"))) is not None:
                total_calorie_values.append(value)
            elif short_type == "ElevationGainedRecord" and (value := _meters(payload.get("elevation"))) is not None:
                elevation_values.append(value)
            continue
        samples = payload.get("samples")
        if not isinstance(samples, list):
            continue
        for sample in samples:
            if not isinstance(sample, dict):
                continue
            sample_time = _parse_time(sample.get("time"))
            if not sample_time or (start and sample_time < start) or (end and sample_time > end):
                continue
            point: dict[str, Any] = {"time": str(sample.get("time"))}
            target: str | None = None
            value: float | None = None
            if short_type == "HeartRateRecord":
                target, value = "heart_rate", _number(sample.get("beatsPerMinute"))
            elif short_type == "SpeedRecord":
                target, value = "speed", _meters_per_second(sample.get("speed"))
            elif short_type == "StepsCadenceRecord":
                target, value = "cadence", _number(sample.get("rate"))
            elif short_type == "CyclingPedalingCadenceRecord":
                target, value = "cadence", _number(sample.get("revolutionsPerMinute"))
            elif short_type == "PowerRecord":
                target, value = "power", _watts(sample.get("power"))
            if target and value is not None:
                point["value"] = value
                series[target].append(point)

    if distance_values:
        totals["distance_meters"] = sum(distance_values)
    if active_calorie_values:
        totals["active_calories"] = sum(active_calorie_values)
    if total_calorie_values:
        totals["total_calories"] = sum(total_calorie_values)
    if elevation_values:
        totals["elevation_meters"] = sum(elevation_values)
    hr_avg, hr_min, hr_max = _series_stats(series["heart_rate"])
    speed_avg, _, speed_max = _series_stats(series["speed"])
    cadence_avg, _, cadence_max = _series_stats(series["cadence"])
    power_avg, _, power_max = _series_stats(series["power"])
    totals.update({
        "avg_hr": hr_avg if hr_avg is not None else totals["avg_hr"],
        "min_hr": hr_min,
        "max_hr": hr_max,
        "avg_speed_mps": speed_avg,
        "max_speed_mps": speed_max,
        "avg_cadence": cadence_avg,
        "max_cadence": cadence_max,
        "avg_power_watts": power_avg,
        "max_power_watts": power_max,
    })
    for key, points in series.items():
        series[key] = _downsample(sorted(points, key=lambda point: str(point.get("time") or "")))

    return {
        "session": summary,
        "notes": summary.get("notes"),
        "metadata": {
            "recording_method": summary.get("recording_method"),
            "device": summary.get("device"),
        },
        "totals": {key: round(value, 2) if value is not None else None for key, value in totals.items()},
        "laps": raw.get("laps") if isinstance(raw.get("laps"), list) else [],
        "segments": raw.get("segments") if isinstance(raw.get("segments"), list) else [],
        "route": {"status": route_status, "points": route},
        "series": series,
        "provenance": {
            "association": "same device, source app, and session time window" if device_id else "session summary only",
            "source_app": source_app,
            "route_note": "Health Connect did not supply a route or requires one-time foreground consent" if route_status == "unavailable" else None,
        },
    }


__all__ = [
    "EXERCISE_TYPES",
    "get_activity_training_view",
    "get_workout_detail",
    "list_workout_summaries",
    "workout_summary",
]
