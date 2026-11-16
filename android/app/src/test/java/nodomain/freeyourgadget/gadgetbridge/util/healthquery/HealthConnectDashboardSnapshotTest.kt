package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import androidx.health.connect.client.records.OxygenSaturationRecord
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectDashboardSnapshotTest {
    @Test
    fun `selects requested dashboard date instead of last inserted historical date`() {
        val today = SelfHostedHealthDay(
            "2026-11-14",
            JSONObject().put("date", "2026-11-14").put("steps", JSONObject().put("total", 1505))
        )
        val historicalCycleDay = SelfHostedHealthDay(
            "2026-09-09",
            JSONObject().put("date", "2026-09-09").put("menstruation_periods", org.json.JSONArray())
        )

        val selected = HealthConnectDashboardSnapshot.selectDay(
            listOf(today, historicalCycleDay),
            LocalDate.parse("2026-11-14")
        )

        assertEquals("2026-11-14", selected?.date)
        assertEquals(1505L, selected?.body?.getJSONObject("steps")?.getLong("total"))
    }

    @Test
    fun `latest card request is newest first and independent of record count`() {
        val request = HealthConnectDashboardSnapshot.latestRequest(
            OxygenSaturationRecord::class,
            Instant.parse("2026-10-16T00:00:00Z"),
            Instant.parse("2026-11-15T12:00:00Z")
        )

        assertFalse(request.ascendingOrder)
        assertEquals(1, request.pageSize)
    }

    @Test
    fun `step fallback searches history and aggregates the latest record day`() {
        val now = Instant.parse("2026-11-16T00:40:00Z")
        val latestRecord = Instant.parse("2026-11-15T15:33:00Z")
        val zone = ZoneId.of("Asia/Shanghai")

        val lookup = HealthConnectDashboardSnapshot.stepLookupRange(now)
        val day = HealthConnectDashboardSnapshot.stepDayRange(latestRecord, now, zone)

        assertEquals(Instant.parse("2026-10-17T00:40:00Z"), lookup.first)
        assertEquals(now, lookup.second)
        assertEquals(Instant.parse("2026-11-14T16:00:00Z"), day.first)
        assertEquals(Instant.parse("2026-11-15T16:00:00Z"), day.second)
    }
}
