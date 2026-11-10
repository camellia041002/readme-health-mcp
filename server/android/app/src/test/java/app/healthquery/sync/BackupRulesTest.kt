package app.healthquery.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesTest {
    @Test
    fun legacyBackupRulesExcludeSensitiveStateAndWorkManagerDatabase() {
        val xml = readResource("backup_rules.xml")
        assertFalse(xml.contains("<include"))
        assertExcludesSensitiveState(xml)
    }

    @Test
    fun cloudAndDeviceTransferRulesExcludeSensitiveStateAndWorkManagerDatabase() {
        val xml = readResource("data_extraction_rules.xml")
        assertFalse(xml.contains("<include"))
        assertExcludesSensitiveState(xml)
        assertTrue(xml.contains("<cloud-backup>"))
        assertTrue(xml.contains("<device-transfer>"))
    }

    private fun assertExcludesSensitiveState(xml: String) {
        assertTrue(xml.contains("<exclude domain=\"sharedpref\" path=\".\"/>"))
        listOf(
            "androidx.work.workdb",
            "androidx.work.workdb-journal",
            "androidx.work.workdb-wal",
        ).forEach { database ->
            assertTrue(xml.contains("<exclude domain=\"database\" path=\"$database\"/>"))
        }
    }

    private fun readResource(name: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: run {
            val cwd = File(".").absolutePath
            error("Could not locate Android resource $name from $cwd")
        }
    }
}
