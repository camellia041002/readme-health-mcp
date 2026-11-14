from __future__ import annotations

import json
from datetime import datetime
from typing import Any


POINT_SPECS: dict[str, tuple[str, str, str]] = {
    "RestingHeartRateRecord": ("resting_heart_rate", "beatsPerMinute", "bpm"),
    "HeartRateVariabilityRmssdRecord": ("heart_rate_variability", "heartRateVariabilityMillis", "ms"),
    "OxygenSaturationRecord": ("oxygen_saturation", "percentage", "%"),
    "RespiratoryRateRecord": ("respiratory_rate", "rate", "breaths/min"),
    "WeightRecord": ("weight", "weight", "kg"),
    "HeightRecord": ("height", "height", "m"),
    "BodyFatRecord": ("body_fat", "percentage", "%"),
    "LeanBodyMassRecord": ("lean_body_mass", "mass", "kg"),
    "BoneMassRecord": ("bone_mass", "mass", "kg"),
    "BodyWaterMassRecord": ("body_water_mass", "mass", "kg"),
    "BodyTemperatureRecord": ("body_temperature", "temperature", "degC"),
}

INTERVAL_SPECS: dict[str, tuple[str, str, str]] = {
    "StepsRecord": ("steps", "count", "count"),
    "DistanceRecord": ("distance", "distance", "m"),
    "ActiveCaloriesBurnedRecord": ("active_calories", "energy", "kcal"),
    "TotalCaloriesBurnedRecord": ("total_calories", "energy", "kcal"),
    "HydrationRecord": ("hydration", "volume", "L"),
    "ReadMeScreenTimeRecord": ("screen_time", "durationMinutes", "min"),
    "MenstruationPeriodRecord": ("menstruation_period", "durationDays", "day"),
}


def short_record_type(record_type: str) -> str:
    return record_type.rsplit(".", 1)[-1]


def projection_prefix(device_id: str, record_type: str, record_id: str) -> str:
    return f"hc:{device_id}:{short_record_type(record_type)}:{record_id}"


def _projection_tables(record_type: str) -> tuple[tuple[str, str, str], ...]:
    """Return only the normalized tables that can contain this record's projection."""
    short_type = short_record_type(record_type)
    if short_type == "SleepSessionRecord":
        # Delete children first so this remains correct even if foreign-key
        # cascades are disabled by a caller's connection settings.
        return (
            ("sleep_stages", "stage_key", "start_time"),
            ("sleep_sessions", "session_key", "start_time"),
        )
    if short_type == "ExerciseSessionRecord":
        return (("workouts", "workout_key", "start_time"),)
    if short_type in {"HeartRateRecord", "BloodPressureRecord"} or short_type in POINT_SPECS:
        return (("metric_points", "record_key", "recorded_at"),)
    if short_type in INTERVAL_SPECS:
        return (("metric_intervals", "record_key", "start_time"),)
    # Unsupported Health Connect types are retained losslessly in
    # health_connect_records and have no normalized projection to scan.
    return ()


def _number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, dict):
        return _number(value.get("value"))
    return None


def _unit_number(value: Any, target: str) -> float | None:
    numeric = _number(value)
    if numeric is None or not isinstance(value, dict):
        return numeric
    unit_type = str(value.get("type") or "").upper()
    factors: dict[str, dict[str, float]] = {
        "m": {"METERS": 1, "KILOMETERS": 1_000, "MILES": 1_609.344, "FEET": 0.3048, "INCHES": 0.0254},
        "kg": {"KILOGRAMS": 1, "GRAMS": 0.001, "MILLIGRAMS": 0.000001, "POUNDS": 0.45359237, "OUNCES": 0.028349523125},
        "kcal": {"KILOCALORIES": 1, "CALORIES": 0.001, "JOULES": 0.000239005736, "KILOJOULES": 0.239005736},
        "L": {"LITERS": 1, "MILLILITERS": 0.001, "FLUID_OUNCES_US": 0.0295735295625},
        "%": {"PERCENT": 1, "PERCENTAGE": 1},
        "degC": {"CELSIUS": 1},
        "mmHg": {"MILLIMETERS_OF_MERCURY": 1},
    }
    return numeric * factors.get(target, {}).get(unit_type, 1)


def _duration_minutes(start: str, end: str) -> float | None:
    try:
        start_dt = datetime.fromisoformat(start.replace("Z", "+00:00"))
        end_dt = datetime.fromisoformat(end.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    return max(0.0, (end_dt - start_dt).total_seconds() / 60.0)


async def delete_projection(conn, *, device_id: str, record_type: str, record_id: str) -> set[str]:
    prefix = projection_prefix(device_id, record_type, record_id)
    touched_dates: set[str] = set()
    for table, key_column, time_column in _projection_tables(record_type):
        # Projection keys are either the exact prefix or use a ':' suffix;
        # the adjacent ';' upper bound keeps this prefix lookup indexable.
        lower_prefix = f"{prefix}:"
        upper_prefix = f"{prefix};"
        async with conn.execute(
            f"SELECT DISTINCT substr({time_column}, 1, 10) AS day FROM {table} "
            f"WHERE {key_column} = ? OR ({key_column} >= ? AND {key_column} < ?)",
            (prefix, lower_prefix, upper_prefix),
        ) as cursor:
            touched_dates.update(str(row["day"]) for row in await cursor.fetchall() if row["day"])
        await conn.execute(
            f"DELETE FROM {table} WHERE {key_column} = ? OR ({key_column} >= ? AND {key_column} < ?)",
            (prefix, lower_prefix, upper_prefix),
        )
    return touched_dates


async def _insert_point(
    conn,
    *,
    key: str,
    batch_id: str,
    source: str,
    metric_type: str,
    recorded_at: str,
    numeric_value: float | None,
    text_value: str | None,
    unit: str | None,
    payload: dict[str, Any],
) -> None:
    await conn.execute(
        """
        INSERT INTO metric_points (
          record_key, batch_id, source, metric_type, recorded_at,
          numeric_value, text_value, unit, raw_json, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT(record_key) DO UPDATE SET
          batch_id = excluded.batch_id, source = excluded.source,
          metric_type = excluded.metric_type, recorded_at = excluded.recorded_at,
          numeric_value = excluded.numeric_value, text_value = excluded.text_value,
          unit = excluded.unit, raw_json = excluded.raw_json, updated_at = CURRENT_TIMESTAMP
        """,
        (key, batch_id, source, metric_type, recorded_at, numeric_value, text_value, unit, json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)),
    )


async def project_upsert(
    conn,
    *,
    device_id: str,
    record_type: str,
    record_id: str,
    payload: dict[str, Any],
    batch_id: str,
) -> set[str]:
    short_type = short_record_type(record_type)
    prefix = projection_prefix(device_id, record_type, record_id)
    source = f"device:{device_id}"
    touched = await delete_projection(
        conn,
        device_id=device_id,
        record_type=record_type,
        record_id=record_id,
    )

    if short_type == "HeartRateRecord":
        for index, sample in enumerate(payload.get("samples") or []):
            if not isinstance(sample, dict) or not sample.get("time"):
                continue
            await _insert_point(
                conn,
                key=f"{prefix}:{index}",
                batch_id=batch_id,
                source=source,
                metric_type="heart_rate",
                recorded_at=str(sample["time"]),
                numeric_value=_number(sample.get("beatsPerMinute")),
                text_value=None,
                unit="bpm",
                # The lossless parent record is stored in
                # health_connect_records.  Keeping only the sample here
                # prevents serializing and writing the full samples array
                # once per point (which is quadratic for large records).
                payload=sample,
            )
            touched.add(str(sample["time"])[:10])
        return touched

    if short_type == "BloodPressureRecord":
        recorded_at = str(payload.get("time") or "")
        systolic = _unit_number(payload.get("systolic"), "mmHg")
        diastolic = _unit_number(payload.get("diastolic"), "mmHg")
        if recorded_at:
            await _insert_point(
                conn,
                key=prefix,
                batch_id=batch_id,
                source=source,
                metric_type="blood_pressure",
                recorded_at=recorded_at,
                numeric_value=systolic,
                text_value=json.dumps({"systolic": systolic, "diastolic": diastolic}, separators=(",", ":")),
                unit="mmHg",
                payload=payload,
            )
            touched.add(recorded_at[:10])
        return touched

    if short_type in POINT_SPECS:
        metric_type, field, unit = POINT_SPECS[short_type]
        recorded_at = str(payload.get("time") or "")
        if recorded_at:
            value = _unit_number(payload.get(field), unit)
            await _insert_point(
                conn,
                key=prefix,
                batch_id=batch_id,
                source=source,
                metric_type=metric_type,
                recorded_at=recorded_at,
                numeric_value=value,
                text_value=None,
                unit=unit,
                payload=payload,
            )
            touched.add(recorded_at[:10])
        return touched

    if short_type in INTERVAL_SPECS:
        metric_type, field, unit = INTERVAL_SPECS[short_type]
        start = str(payload.get("startTime") or "")
        end = str(payload.get("endTime") or "") or None
        if start:
            value = _unit_number(payload.get(field), unit)
            text_value = None
            if short_type == "ReadMeScreenTimeRecord":
                text_value = json.dumps(
                    {"app": payload.get("appLabel"), "package": payload.get("appPackage")},
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
            await conn.execute(
                """
                INSERT INTO metric_intervals (
                  record_key, batch_id, source, metric_type, start_time, end_time,
                  numeric_value, text_value, unit, raw_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(record_key) DO UPDATE SET
                  batch_id = excluded.batch_id, source = excluded.source,
                  metric_type = excluded.metric_type, start_time = excluded.start_time,
                  end_time = excluded.end_time, numeric_value = excluded.numeric_value,
                  text_value = excluded.text_value,
                  unit = excluded.unit, raw_json = excluded.raw_json, updated_at = CURRENT_TIMESTAMP
                """,
                (prefix, batch_id, source, metric_type, start, end, value, text_value, unit, json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)),
            )
            touched.add(start[:10])
        return touched

    if short_type == "SleepSessionRecord":
        start = str(payload.get("startTime") or "")
        end = str(payload.get("endTime") or "")
        if not start or not end:
            return touched
        await conn.execute(
            """
            INSERT INTO sleep_sessions (
              session_key, batch_id, source, start_time, end_time,
              duration_minutes, efficiency_pct, raw_json, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(session_key) DO UPDATE SET
              batch_id = excluded.batch_id, source = excluded.source,
              start_time = excluded.start_time, end_time = excluded.end_time,
              duration_minutes = excluded.duration_minutes,
              raw_json = excluded.raw_json, updated_at = CURRENT_TIMESTAMP
            """,
            (prefix, batch_id, source, start, end, _duration_minutes(start, end), json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)),
        )
        for index, stage in enumerate(payload.get("stages") or []):
            if not isinstance(stage, dict) or not stage.get("startTime") or not stage.get("endTime"):
                continue
            stage_start = str(stage["startTime"])
            stage_end = str(stage["endTime"])
            await conn.execute(
                """
                INSERT INTO sleep_stages (
                  stage_key, batch_id, session_key, source, stage_type,
                  start_time, end_time, duration_seconds, raw_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                (
                    f"{prefix}:stage:{index}", batch_id, prefix, source,
                    str(stage.get("stage") or "unknown"), stage_start, stage_end,
                    (_duration_minutes(stage_start, stage_end) or 0) * 60,
                    json.dumps(stage, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
                ),
            )
        touched.update({start[:10], end[:10]})
        return touched

    if short_type == "ExerciseSessionRecord":
        start = str(payload.get("startTime") or "")
        end = str(payload.get("endTime") or "")
        if not start or not end:
            return touched
        await conn.execute(
            """
            INSERT INTO workouts (
              workout_key, batch_id, source, activity_type, start_time, end_time,
              duration_minutes, calories, avg_hr, raw_json, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(workout_key) DO UPDATE SET
              batch_id = excluded.batch_id, source = excluded.source,
              activity_type = excluded.activity_type, start_time = excluded.start_time,
              end_time = excluded.end_time, duration_minutes = excluded.duration_minutes,
              raw_json = excluded.raw_json, updated_at = CURRENT_TIMESTAMP
            """,
            (prefix, batch_id, source, str(payload.get("exerciseType") or "exercise"), start, end, _duration_minutes(start, end), json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)),
        )
        touched.update({start[:10], end[:10]})
    return touched


async def recompute_daily_summaries(conn, dates: set[str]) -> None:
    for day in sorted(value for value in dates if value):
        async with conn.execute(
            "SELECT COALESCE(SUM(numeric_value), 0) AS value FROM metric_intervals WHERE metric_type = 'steps' AND substr(start_time, 1, 10) = ?",
            (day,),
        ) as cursor:
            steps = (await cursor.fetchone())["value"]
        async with conn.execute(
            "SELECT COALESCE(SUM(duration_minutes), 0) AS value FROM sleep_sessions WHERE substr(start_time, 1, 10) = ?",
            (day,),
        ) as cursor:
            sleep = (await cursor.fetchone())["value"]
        async with conn.execute(
            "SELECT COALESCE(SUM(duration_minutes), 0) AS minutes, COUNT(*) AS count FROM workouts WHERE substr(start_time, 1, 10) = ?",
            (day,),
        ) as cursor:
            workouts = await cursor.fetchone()
        await conn.execute(
            """
            INSERT INTO daily_summaries (
              summary_date, steps, active_minutes, sleep_minutes, workouts, updated_at
            ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(summary_date) DO UPDATE SET
              steps = excluded.steps, active_minutes = excluded.active_minutes,
              sleep_minutes = excluded.sleep_minutes, workouts = excluded.workouts,
              updated_at = CURRENT_TIMESTAMP
            """,
            (day, round(steps or 0), round(workouts["minutes"] or 0), round(sleep or 0), workouts["count"]),
        )
