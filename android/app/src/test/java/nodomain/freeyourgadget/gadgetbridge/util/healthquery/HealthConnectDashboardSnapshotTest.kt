package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

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
}
