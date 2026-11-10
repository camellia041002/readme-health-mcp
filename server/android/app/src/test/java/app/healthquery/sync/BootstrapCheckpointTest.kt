package app.healthquery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCheckpointTest {
    private val initial = BootstrapCheckpoint(
        snapshotStartMillis = 1_000,
        snapshotEndMillis = 2_000,
        scopeKey = "steps,heart-rate",
        baseChangeToken = "base-token",
        recordTypeIndex = 0,
    )

    @Test
    fun acknowledgedPagePersistsProviderCursorAndTypeCompletion() {
        val page = BootstrapCheckpointPolicy.nextPage(initial, "provider-page-2")
        assertEquals("provider-page-2", page.providerPageToken)
        assertEquals(0, page.recordTypeIndex)
        val next = BootstrapCheckpointPolicy.nextType(page)
        assertEquals(1, next.recordTypeIndex)
        assertEquals(null, next.providerPageToken)
        assertEquals(initial.baseChangeToken, next.baseChangeToken)
    }

    @Test
    fun restartResumesOnlyMatchingFixedScope() {
        assertTrue(BootstrapCheckpointPolicy.canResume(initial, "steps,heart-rate"))
        assertFalse(BootstrapCheckpointPolicy.canResume(initial, "steps"))
        assertFalse(BootstrapCheckpointPolicy.canResume(initial.copy(snapshotEndMillis = 1_000), "steps,heart-rate"))
    }

    @Test
    fun periodicAndManualLanesWaitForFinalCursorCommit() {
        assertFalse(BootstrapWorkPolicy.periodicAllowed(null, null))
        assertFalse(BootstrapWorkPolicy.periodicAllowed(null, initial))
        assertFalse(BootstrapWorkPolicy.periodicAllowed("token", initial))
        assertTrue(BootstrapWorkPolicy.periodicAllowed("token", null))
        assertEquals("bootstrap", BootstrapWorkPolicy.manualLane(null, null))
        assertEquals("bootstrap", BootstrapWorkPolicy.manualLane("token", initial))
        assertEquals("incremental", BootstrapWorkPolicy.manualLane("token", null))
    }

    @Test
    fun expiredIncrementalCursorEscalatesToBootstrapWorker() {
        val error = BootstrapRequiredException()
        assertEquals("Health Connect bootstrap is required", error.message)
    }

    @Test
    fun inventoryAndUploadCursorsKeepPrivacySafeCountsOnly() {
        val inventoried = BootstrapCheckpointPolicy.inventoryPage(initial, "StepsRecord", 100, "page-2")
        assertEquals(BootstrapPhase.INVENTORY, inventoried.phase)
        assertEquals(100, inventoried.inventoryCounts["StepsRecord"])
        assertEquals(100, inventoried.totalRecords)
        val upload = BootstrapCheckpointPolicy.beginUpload(inventoried)
        assertEquals(BootstrapPhase.UPLOAD, upload.phase)
        assertEquals(0, upload.uploadedRecords)
        val acknowledged = BootstrapCheckpointPolicy.uploadPage(upload, 100, null)
        assertEquals(100, acknowledged.uploadedRecords)
        assertEquals(null, acknowledged.providerPageToken)
    }

    @Test
    fun liveProviderGrowthExpandsUploadDenominatorWithoutInvalidatingResume() {
        val inventoried = BootstrapCheckpointPolicy.inventoryPage(initial, "StepsRecord", 100, null)
        val upload = BootstrapCheckpointPolicy.beginUpload(inventoried)
        val grown = BootstrapCheckpointPolicy.uploadPage(upload, 125, "next-page")

        assertEquals(125, grown.uploadedRecords)
        assertEquals(125, grown.totalRecords)
        assertTrue(BootstrapCheckpointPolicy.canResume(grown, initial.scopeKey))
    }
}
