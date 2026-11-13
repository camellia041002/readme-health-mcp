---
name: healthquery-readonly
description: Query the connected HealthQuery MCP server safely. Discover data types and schemas first, use read-only tools only, report coverage and uncertainty, and never diagnose.
---

# HealthQuery read-only workflow

Use this skill whenever a user asks about data in the connected HealthQuery
server. The server is a personal health-data source, not a clinician.

## Safety contract

- Use only the MCP tools exposed by HealthQuery. Never call an ingest, device,
  credential, or other mutation endpoint.
- Use only the typed, bounded read tools. The hardened deployment does not
  expose a free-form SQL tool.
- Never request, reveal, log, or echo OAuth tokens, bearer credentials, QR
  enrollment values, or raw authorization headers.
- Do not diagnose, prescribe, assess emergencies, or imply that a trend proves a
  medical condition. If the user asks for medical advice, explain the limit and
  suggest a qualified clinician or appropriate urgent-care service.

## Query sequence

1. Establish the requested date range, timezone, person/device scope, and unit
   assumptions. Ask one concise clarification when a missing choice could alter
   the result.
2. For an ordinary daily question, call `get_health_summary` once with the
   requested local date and only the needed sections (`sleep`, `vitals`,
   `activity`, and/or `screen_time`). Do not split a sleep summary into a
   session lookup plus a second stage lookup.
3. Use `list_data_types` and `describe_data_type` only when the user asks about
   an uncommon field, raw record availability, or schema/coverage details.
4. For drill-down, use the narrowest low-level tool (`query_records`,
   `list_workouts`, `get_workout`, `get_session_data`, `aggregate`, or
   `summarize_trends`). Use
   `list_health_connect_record_types` when the question concerns raw Android
   record kinds. If the typed surface cannot express a request, report the
   limitation instead of attempting a database query.
5. Call `get_data_coverage` for the relevant collection when completeness,
   missing days, source/device breadth, or tombstones matter. Also check result
   limits, timestamps, and duplicate-looking records before summarizing.
6. Report observations with the exact time range and units. Label calculations
   and inferences separately, and say when the data is absent, partial, stale, or
   too sparse to support a conclusion.

## Response shape

Keep answers concise and auditable:

- **Observed:** values or records returned, with units and timestamps.
- **Coverage:** queried range, data types, source/device scope, and gaps or
  limits.
- **Interpretation:** only non-diagnostic descriptive patterns; identify any
  assumptions or derived calculations.
- **Next read-only check:** one optional narrower query if it would reduce
  uncertainty.

Starter prompt: “Inspect the HealthQuery schema, then summarize the requested
range using read-only tools. State coverage and uncertainty, and do not diagnose.”
