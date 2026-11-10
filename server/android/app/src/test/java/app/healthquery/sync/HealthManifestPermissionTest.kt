package app.healthquery.sync

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.StepsRecord
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthManifestPermissionTest {
    @Test
    fun manifestDeclaresEveryPermissionRequestedByTheLosslessCatalog() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate the Android manifest")

        HealthRecordCatalog.readPermissions.forEach { permission ->
            assertTrue("Manifest does not declare $permission", manifest.contains("android:name=\"$permission\""))
        }
    }

    @Test
    fun featureGatedPermissionsDoNotBlockProvidersThatCannotGrantThem() {
        val permissions = HealthRecordCatalog.readPermissionsExcluding(
            setOf(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
        )

        assertTrue(HealthPermission.getReadPermission(StepsRecord::class) in permissions)
        assertTrue(HealthPermission.getReadPermission(MindfulnessSessionRecord::class) !in permissions)
        assertFalse(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in permissions)
        assertTrue(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in permissions)
    }

    @Test
    fun historyPermissionIsNeitherRequestedNorDeclared() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate the Android manifest")

        assertFalse(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in HealthRecordCatalog.readPermissions)
        assertFalse(manifest.contains("READ_HEALTH_DATA_HISTORY"))
    }
}
