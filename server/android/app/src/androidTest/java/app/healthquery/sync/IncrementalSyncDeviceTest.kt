package app.healthquery.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device UAT for an already enrolled installation.
 *
 * This is skipped in ordinary CI because it intentionally exercises the real Health Connect
 * provider and configured server. Run with `-e healthqueryLiveSync true` on the explicit device.
 */
@RunWith(AndroidJUnit4::class)
class IncrementalSyncDeviceTest {
    @Test
    fun committedBootstrapContinuesWithIncrementalSync() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("healthqueryLiveSync") == "true")

        val engine = LosslessHealthSyncEngine(instrumentation.targetContext)
        assertTrue("device must have a completed bootstrap", engine.isBootstrapComplete())

        val startedAt = System.nanoTime()
        val summary = engine.syncIncremental()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertNotNull("enrolled device must return a sync summary", summary)
        assertEquals("changes", summary?.mode)
        assertTrue("incremental sync took ${elapsedMillis}ms", elapsedMillis < 30_000)
        assertTrue("incremental sync must not recreate bootstrap state", engine.isBootstrapComplete())
    }
}
