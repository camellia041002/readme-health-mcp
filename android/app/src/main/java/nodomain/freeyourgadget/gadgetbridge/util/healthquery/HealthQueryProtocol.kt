/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HealthQueryPairing(val serverUrl: String, val enrollmentToken: String)
data class HealthQueryConnection(val serverUrl: String, val deviceToken: String, val deviceId: String)

object HealthQueryPairingParser {
    @JvmStatic
    fun parse(raw: String): HealthQueryPairing {
        val uri = URI(raw.trim())
        require(uri.scheme == "https" || uri.scheme == "http")
        val host = uri.host ?: error("missing_host")
        require(uri.path == "/pair/android" || uri.path == "/pair/android/")
        require(uri.userInfo == null && uri.rawQuery == null)
        require(uri.scheme == "https" || isPrivateHost(host))
        val token = uri.rawFragment?.split('&')?.firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "token") URLDecoder.decode(pair[1], "UTF-8") else null
        }?.takeIf { it.isNotBlank() } ?: error("missing_enrollment_token")
        return HealthQueryPairing("${uri.scheme}://${uri.rawAuthority}".trimEnd('/'), token)
    }

    @JvmStatic
    fun isAllowedServerUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        require(uri.path.isEmpty() || uri.path == "/")
        require(uri.query == null && uri.fragment == null && uri.userInfo == null)
        require(uri.scheme == "https" || (uri.scheme == "http" && isPrivateHost(uri.host ?: "")))
        true
    }.getOrDefault(false)

    private fun isPrivateHost(host: String): Boolean {
        val value = host.lowercase().removePrefix("[").removeSuffix("]")
        if (value == "localhost" || value == "::1") return true
        if (value.contains(':')) return value.startsWith("fc") || value.startsWith("fd")
        val octets = value.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }
}

/** Converts the already-bounded Gadgetbridge projection into HealthQuery's lossless device format. */
object HealthQueryPayloadMapper {
    private const val PROTOCOL_VERSION = 1

    @JvmStatic
    fun buildRequest(
        days: List<SelfHostedHealthDay>,
        zone: ZoneId,
        deviceId: String,
        mode: String = "changes"
    ): JSONObject {
        require(deviceId.isNotBlank())
        val records = JSONArray()
        for (day in days.sortedBy { it.date }) appendDay(records, day, zone)
        val canonical = JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("mode", mode)
            .put("records", records)
        val batchId = digest("$deviceId\n${canonical}").take(32)
        return JSONObject(canonical.toString()).put("batch_id", batchId)
    }

    /** One bounded request per local day keeps bootstrap bodies small and independently replayable. */
    @JvmStatic
    fun buildDayRequests(
        days: List<SelfHostedHealthDay>, zone: ZoneId, deviceId: String, mode: String = "changes"
    ): List<Pair<String, JSONObject>> = days.sortedBy { it.date }.map { day ->
        day.date to buildRequest(listOf(day), zone, deviceId, mode)
    }

    private fun appendDay(out: JSONArray, day: SelfHostedHealthDay, zone: ZoneId) {
        val date = LocalDate.parse(day.date)
        val start = date.atStartOfDay(zone)
        val end = date.plusDays(1).atStartOfDay(zone)
        day.body.optJSONObject("steps")?.optLong("total", -1)?.takeIf { it >= 0 }?.let { count ->
            out.put(envelope("StepsRecord", "gb-steps-${day.date}", JSONObject()
                .put("startTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start))
                .put("endTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(end))
                .put("count", count)))
        }
        day.body.optJSONArray("heart_rate")?.takeIf { it.length() > 0 }?.let { source ->
            val samples = JSONArray()
            for (i in 0 until source.length()) {
                val point = source.optJSONObject(i) ?: continue
                val time = point.optString("timestamp")
                val bpm = point.optInt("value", -1)
                if (time.isNotBlank() && bpm in 1..300) {
                    samples.put(JSONObject().put("time", time).put("beatsPerMinute", bpm))
                }
            }
            if (samples.length() > 0) out.put(envelope("HeartRateRecord", "gb-heart-${day.date}", JSONObject()
                .put("startTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start))
                .put("endTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(end))
                .put("samples", samples)))
        }
        day.body.optJSONArray("sleep")?.let { sessions ->
            for (i in 0 until sessions.length()) {
                val session = sessions.optJSONObject(i) ?: continue
                val from = session.optString("session_start_time")
                val to = session.optString("session_end_time")
                if (from.isBlank() || to.isBlank()) continue
                val stages = JSONArray()
                val sourceStages = session.optJSONArray("stages") ?: JSONArray()
                for (j in 0 until sourceStages.length()) {
                    val stage = sourceStages.optJSONObject(j) ?: continue
                    val stageStart = stage.optString("start_time")
                    val stageEnd = stage.optString("end_time")
                    if (stageStart.isBlank() || stageEnd.isBlank()) continue
                    stages.put(JSONObject()
                        .put("stage", stage.optString("stage", "unknown"))
                        .put("startTime", stageStart)
                        .put("endTime", stageEnd))
                }
                out.put(envelope("SleepSessionRecord", "gb-sleep-${digest(from).take(24)}", JSONObject()
                    .put("startTime", from).put("endTime", to).put("stages", stages)))
            }
        }
        day.body.optJSONArray("workouts")?.let { workouts ->
            for (i in 0 until workouts.length()) {
                val workout = workouts.optJSONObject(i) ?: continue
                val from = workout.optString("start_time")
                val to = workout.optString("end_time")
                if (from.isBlank() || to.isBlank()) continue
                val sourceId = workout.optString("id").ifBlank { "$from:$to" }
                out.put(envelope("ExerciseSessionRecord", "gb-workout-${digest(sourceId).take(24)}", JSONObject()
                    .put("startTime", from)
                    .put("endTime", to)
                    .put("exerciseType", workout.optInt("raw_type", 0).toString())))
            }
        }
        day.body.optJSONArray("screen_time")?.let { apps ->
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                val packageName = app.optString("package")
                val startTime = app.optString("start_time")
                val endTime = app.optString("end_time")
                val minutes = app.optDouble("duration_minutes", -1.0)
                if (packageName.isBlank() || startTime.isBlank() || endTime.isBlank() || minutes < 0) continue
                out.put(envelope("ReadMeScreenTimeRecord", "readme-screen-${day.date}-${digest(packageName).take(16)}", JSONObject()
                    .put("startTime", startTime)
                    .put("endTime", endTime)
                    .put("durationMinutes", minutes)
                    .put("appPackage", packageName)
                    .put("appLabel", app.optString("label", packageName))))
            }
        }
    }

    private fun envelope(type: String, id: String, payload: JSONObject) = JSONObject()
        .put("operation", "upsert").put("record_type", type).put("record_id", id).put("payload", payload)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
