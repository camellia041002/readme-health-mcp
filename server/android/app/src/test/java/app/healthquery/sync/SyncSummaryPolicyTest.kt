package app.healthquery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncSummaryPolicyTest {
    private val completedAt = Instant.parse("2026-08-23T00:00:00Z")

    @Test
    fun accessFailuresAreNotRecordedAsSuccessfulRuns() {
        val noAccess = SyncSummary("no_access", 0, completedAt)
        val notReady = SyncSummary("not_ready", 0, completedAt)
        val emptyPermittedSnapshot = SyncSummary("snapshot", 0, completedAt)

        assertFalse(SyncSummaryPolicy.isSuccessful(noAccess))
        assertFalse(SyncSummaryPolicy.isSuccessful(notReady))
        assertTrue(SyncSummaryPolicy.isSuccessful(emptyPermittedSnapshot))
        assertEquals("Health Connect permissions required", SyncSummaryPolicy.errorFor(noAccess))
        assertNull(SyncSummaryPolicy.errorFor(emptyPermittedSnapshot))
    }
}
