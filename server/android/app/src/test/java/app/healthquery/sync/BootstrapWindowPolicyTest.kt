package app.healthquery.sync

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapWindowPolicyTest {
    @Test
    fun snapshotAlwaysStartsExactlyThirtyDaysBeforeFixedEnd() {
        val end = Instant.parse("2026-11-03T06:00:00Z")
        assertEquals(end.minus(30, ChronoUnit.DAYS), BootstrapWindowPolicy.snapshotStart(end))
    }

    @Test
    fun migrationRunsOnceForLegacyOrMissingPolicy() {
        assertTrue(BootstrapWindowPolicy.requiresMigration(0))
        assertTrue(BootstrapWindowPolicy.requiresMigration(1))
        assertFalse(BootstrapWindowPolicy.requiresMigration(BootstrapWindowPolicy.VERSION))
    }
}
