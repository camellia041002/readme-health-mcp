package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryPairingParser
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectHealthQuerySync
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryPayloadMapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthQueryProtocolTest {
    @Test
    fun pairingTokenRemainsInFragmentAndCleartextIsPrivateOnly() {
        val parsed = HealthQueryPairingParser.parse("https://health.example/pair/android#token=one%20time")
        assertEquals("https://health.example", parsed.serverUrl)
        assertEquals("one time", parsed.enrollmentToken)
        assertTrue(HealthQueryPairingParser.isAllowedServerUrl("http://192.168.1.2"))
        assertFalse(HealthQueryPairingParser.isAllowedServerUrl("http://health.example"))
        assertFalse(HealthQueryPairingParser.isAllowedServerUrl("http://router.lan"))
    }

    @Test
    fun mapsCoreTypesWithStableIdsAndBatch() {
        val body = JSONObject()
            .put("date", "2026-11-05")
            .put("steps", JSONObject().put("total", 4321))
            .put("heart_rate", JSONArray().put(JSONObject()
                .put("timestamp", "2026-11-05T08:00:00+08:00").put("value", 72)))
            .put("oxygen_saturation", JSONArray().put(JSONObject()
                .put("time", "2026-11-05T08:05:00+08:00").put("percentage", 98.0)))
            .put("sleep", JSONArray().put(JSONObject()
                .put("session_start_time", "2026-11-04T23:00:00+08:00")
                .put("session_end_time", "2026-11-05T07:00:00+08:00")
                .put("stages", JSONArray().put(JSONObject().put("stage", "deep")
                    .put("start_time", "2026-11-04T23:00:00+08:00")
                    .put("end_time", "2026-11-05T00:00:00+08:00")))))
            .put("workouts", JSONArray().put(JSONObject().put("id", "gbw1:1")
                .put("raw_type", 7).put("start_time", "2026-11-05T18:00:00+08:00")
                .put("end_time", "2026-11-05T18:30:00+08:00")))
            .put("active_calories", JSONArray().put(JSONObject()
                .put("start_time", "2026-11-05T18:00:00+08:00")
                .put("end_time", "2026-11-05T18:30:00+08:00").put("calories", 120.5)))
            .put("menstruation_periods", JSONArray().put(JSONObject()
                .put("start_time", "2026-11-05T00:00:00+08:00")
                .put("end_time", "2026-11-08T00:00:00+08:00").put("duration_days", 3)))
            .put("screen_time", JSONArray().put(JSONObject()
                .put("package", "com.example.reader").put("label", "示例")
                .put("duration_minutes", 42.5)
                .put("start_time", "2026-11-05T00:00:00+08:00")
                .put("end_time", "2026-11-05T20:00:00+08:00")))
        val day = SelfHostedHealthDay("2026-11-05", body)
        val first = HealthQueryPayloadMapper.buildRequest(listOf(day), ZoneId.of("Asia/Shanghai"), "device-1")
        val second = HealthQueryPayloadMapper.buildRequest(listOf(day), ZoneId.of("Asia/Shanghai"), "device-1")

        assertEquals(first.toString(), second.toString())
        assertEquals(1, first.getInt("protocol_version"))
        assertEquals(8, first.getJSONArray("records").length())
        assertEquals(
            setOf(
                "StepsRecord", "HeartRateRecord", "OxygenSaturationRecord", "SleepSessionRecord",
                "ExerciseSessionRecord", "ActiveCaloriesBurnedRecord", "MenstruationPeriodRecord",
                "ReadMeScreenTimeRecord"
            ),
            (0 until first.getJSONArray("records").length()).map {
                first.getJSONArray("records").getJSONObject(it).getString("record_type")
            }.toSet()
        )
        assertEquals(32, first.getString("batch_id").length)
        val screen = (0 until first.getJSONArray("records").length())
            .map { first.getJSONArray("records").getJSONObject(it) }
            .first { it.getString("record_type") == "ReadMeScreenTimeRecord" }
        assertEquals(42.5, screen.getJSONObject("payload").getDouble("durationMinutes"), 0.01)
        assertEquals("示例", screen.getJSONObject("payload").getString("appLabel"))
    }

    @Test
    fun anySupportedHealthPermissionCanRunIndependently() {
        assertFalse(HealthConnectHealthQuerySync.hasUsablePermission(emptySet()))
        assertFalse(HealthConnectHealthQuerySync.hasUsablePermission(setOf("unrelated.permission")))
        assertTrue(HealthConnectHealthQuerySync.hasUsablePermission(setOf(
            HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        )))
    }

    @Test
    fun bootstrapIsChunkedByDayAndCursorAdvancesPerAcceptedChunk() {
        val zone = ZoneId.of("Asia/Shanghai")
        val days = (1..30).map { day ->
            val date = "2026-10-${day.toString().padStart(2, '0')}"
            SelfHostedHealthDay(date, JSONObject().put("date", date)
                .put("steps", JSONObject().put("total", day)))
        }
        val chunks = HealthQueryPayloadMapper.buildDayRequests(days, zone, "device-1", "snapshot")
        assertEquals(30, chunks.size)
        assertTrue(chunks.all { it.second.getJSONArray("records").length() == 1 })
        assertTrue(chunks.map { it.second.getString("batch_id") }.toSet().size == 30)

        val now = ZonedDateTime.parse("2026-11-05T12:00:00+08:00").toEpochSecond()
        assertEquals(
            ZonedDateTime.parse("2026-10-16T00:00:00+08:00").toEpochSecond(),
            SelfHostedHealthSyncWorker.acceptedDayCursor("2026-10-15", zone, now)
        )
        assertEquals(now, SelfHostedHealthSyncWorker.acceptedDayCursor("2026-11-05", zone, now))
    }
}
