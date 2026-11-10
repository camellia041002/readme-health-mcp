/* Copyright (C) 2026 Gadgetbridge contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class HealthQueryImportPreview(
    val days: List<SelfHostedHealthDay>,
    val firstDate: String,
    val lastDate: String,
    val categories: Set<String>,
    val recordCount: Int
)

/** Parses the versioned, bounded interchange format without retaining the source document. */
object HealthQueryHistoryImport {
    private const val MAX_DAYS = 30
    private const val MAX_ROWS = 20_000

    fun parse(name: String, text: String): HealthQueryImportPreview =
        if (name.lowercase().endsWith(".csv")) parseCsv(text) else parseJson(text)

    fun parseJson(text: String): HealthQueryImportPreview {
        val trimmed = text.trim()
        require(trimmed.length <= 8_000_000) { "file_too_large" }
        val days = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            require(root.optInt("version", 1) == 1) { "unsupported_version" }
            root.getJSONArray("days")
        }
        require(days.length() in 1..MAX_ROWS) { "invalid_row_count" }
        return preview((0 until days.length()).map { index ->
            val body = days.getJSONObject(index)
            val date = LocalDate.parse(body.getString("date")).toString()
            SelfHostedHealthDay(date, sanitizeDay(date, body))
        })
    }

    fun parseCsv(text: String): HealthQueryImportPreview {
        require(text.length <= 8_000_000) { "file_too_large" }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size in 2..(MAX_ROWS + 1)) { "invalid_row_count" }
        val header = csvLine(lines.first()).map { it.trim() }
        require("type" in header && "date" in header) { "missing_header" }
        val byDate = linkedMapOf<String, JSONObject>()
        for (line in lines.drop(1)) {
            val values = csvLine(line)
            val row = header.mapIndexed { index, key -> key to values.getOrElse(index) { "" }.trim() }.toMap()
            val date = LocalDate.parse(row.getValue("date")).toString()
            val day = byDate.getOrPut(date) { JSONObject().put("date", date) }
            when (row.getValue("type").lowercase()) {
                "steps" -> day.put("steps", JSONObject().put("total", row["value"]?.toLongOrNull()
                    ?: error("invalid_steps")))
                "heart_rate" -> day.optArray("heart_rate").put(JSONObject()
                    .put("timestamp", row.required("timestamp"))
                    .put("value", row["value"]?.toIntOrNull() ?: error("invalid_heart_rate")))
                "sleep" -> day.optArray("sleep").put(JSONObject()
                    .put("session_start_time", row.required("start_time"))
                    .put("session_end_time", row.required("end_time"))
                    .put("stages", JSONArray().also { stages ->
                        if (!row["stage"].isNullOrBlank()) stages.put(JSONObject()
                            .put("stage", row["stage"])
                            .put("start_time", row.required("start_time"))
                            .put("end_time", row.required("end_time")))
                    }))
                "workout" -> day.optArray("workouts").put(JSONObject()
                    .put("id", row["id"].orEmpty())
                    .put("raw_type", row["raw_type"]?.toIntOrNull() ?: 0)
                    .put("start_time", row.required("start_time"))
                    .put("end_time", row.required("end_time")))
                else -> error("unsupported_type")
            }
        }
        return preview(byDate.map { (date, body) -> SelfHostedHealthDay(date, body) })
    }

    private fun sanitizeDay(date: String, source: JSONObject): JSONObject {
        val out = JSONObject().put("date", date)
        source.optJSONObject("steps")?.let { out.put("steps", it) }
        source.optJSONArray("heart_rate")?.let { out.put("heart_rate", it) }
        source.optJSONArray("sleep")?.let { out.put("sleep", it) }
        source.optJSONArray("workouts")?.let { out.put("workouts", it) }
        require(out.length() > 1) { "empty_day" }
        return out
    }

    private fun preview(input: List<SelfHostedHealthDay>): HealthQueryImportPreview {
        val days = input.sortedBy { it.date }
        require(days.isNotEmpty()) { "empty_import" }
        val first = LocalDate.parse(days.first().date)
        val last = LocalDate.parse(days.last().date)
        require(!last.isAfter(LocalDate.now())) { "future_data" }
        require(last.toEpochDay() - first.toEpochDay() < MAX_DAYS) { "range_too_wide" }
        val categories = linkedSetOf<String>()
        var count = 0
        for (day in days) {
            day.body.optJSONObject("steps")?.let { categories += "steps"; count++ }
            day.body.optJSONArray("heart_rate")?.let { categories += "heart_rate"; count += it.length() }
            day.body.optJSONArray("sleep")?.let { categories += "sleep"; count += it.length() }
            day.body.optJSONArray("workouts")?.let { categories += "workout"; count += it.length() }
        }
        require(count in 1..MAX_ROWS) { "invalid_record_count" }
        return HealthQueryImportPreview(days, days.first().date, days.last().date, categories, count)
    }

    private fun JSONObject.optArray(key: String): JSONArray = optJSONArray(key) ?: JSONArray().also { put(key, it) }

    private fun Map<String, String>.required(key: String): String = get(key)?.takeIf { it.isNotBlank() }
        ?: error("missing_$key")

    private fun csvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { values += current.toString(); current.clear() }
                else -> current.append(char)
            }
            index++
        }
        require(!quoted) { "invalid_csv_quote" }
        values += current.toString()
        return values
    }
}
