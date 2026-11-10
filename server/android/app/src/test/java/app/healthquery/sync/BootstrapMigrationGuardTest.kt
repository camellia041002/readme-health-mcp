package app.healthquery.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapMigrationGuardTest {
    @Test
    fun workersGateOldStateBeforeAnySyncCall() {
        val bootstrap = source("BootstrapSyncWorker.kt")
        val incremental = source("HealthSyncWorker.kt")
        assertTrue(bootstrap.indexOf("ensureBootstrapPolicyCurrent()") in 0 until bootstrap.indexOf("engine.bootstrap"))
        assertTrue(incremental.indexOf("ensureBootstrapPolicyCurrent()") in 0 until incremental.indexOf("engine.syncIncremental"))
    }

    @Test
    fun migrationUsesVersionedStateAndNeverClearsEncryptedConnection() {
        val engine = source("LosslessHealthSyncEngine.kt")
        val migration = engine.substring(
            engine.indexOf("fun ensureBootstrapPolicyCurrent"),
            engine.indexOf("suspend fun pair"),
        )
        assertTrue(engine.contains("bootstrap_v2_checkpoint"))
        assertTrue(migration.contains("LEGACY_SYNC_STATE_KEYS + CURRENT_SYNC_STATE_KEYS"))
        assertTrue(migration.contains("putInt(KEY_BOOTSTRAP_POLICY_VERSION"))
        assertFalse(migration.contains("credentialStore.clear"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/app/healthquery/sync/$name"),
            File("app/src/main/java/app/healthquery/sync/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $name")
    }
}
