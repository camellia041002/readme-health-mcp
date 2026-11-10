package app.healthquery.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseUiThemeTest {
    @Test
    fun primaryUserSurfacesAreChineseAndDescribeThirtyDayImport() {
        val main = source("java/app/healthquery/sync/MainActivity.kt")
        val worker = source("java/app/healthquery/sync/BootstrapSyncWorker.kt")
        val strings = source("res/values/strings.xml")
        val frequencies = source("java/app/healthquery/sync/SyncFrequency.kt")

        listOf("Re-import all history", "Sync history", "Health sync", "Scan pairing QR").forEach {
            assertFalse("legacy English UI remains: $it", main.contains(it))
        }
        assertFalse(worker.contains("Importing Health Connect history"))
        assertFalse(frequencies.contains("Once a day"))
        assertTrue(main.contains("重新导入最近 30 天"))
        assertTrue(worker.contains("健康同步"))
        assertTrue(strings.contains(">健康同步<"))
    }

    @Test
    fun composeThemeUsesLauncherIconPalette() {
        val colors = source("java/app/healthquery/sync/ui/theme/Color.kt")
        assertTrue(colors.contains("0xFFF27B61"))
        assertTrue(colors.contains("0xFFFFF8EC"))
        assertTrue(colors.contains("0xFFD65D55"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relative")
    }
}
