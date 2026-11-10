package app.healthquery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncFrequencyTest {
    @Test
    fun offersDailyAndWeeklySchedules() {
        assertNotNull(SyncFrequencies.optionFor(24 * 60))
        assertNotNull(SyncFrequencies.optionFor(7 * 24 * 60))
    }

    @Test
    fun defaultsToDailyAndDoesNotOfferHourlySync() {
        assertEquals(24 * 60, SyncFrequencies.default.minutes)
        assertEquals("每天一次", SyncFrequencies.labelFor(60))
    }

    @Test
    fun rejectsLegacyOrCorruptValuesToSafeDefault() {
        assertEquals(24 * 60, SyncFrequencies.normalizedMinutes(0))
        assertEquals(24 * 60, SyncFrequencies.normalizedMinutes(15))
    }
}
