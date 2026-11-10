package app.healthquery.sync

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import java.util.Collections
import kotlin.reflect.KClass

/**
 * Minimal record surface for the private wearable bridge. Keeping this list in one place makes
 * permission requests and changes-token scopes identical. Add a new type only after the owner
 * explicitly opts in; sensitive categories must never appear merely because the SDK supports them.
 */
object HealthRecordCatalog {
    val recordTypes: List<KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class,
        DistanceRecord::class,
        ExerciseSessionRecord::class,
        HeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
    )

    val readPermissions: Set<String> = Collections.unmodifiableSet(
        (recordTypes.map(HealthPermission::getReadPermission) +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND).toSet()
    )

    /** Permissions the installed Health Connect provider can actually grant on this device. */
    fun providerSupportedReadPermissions(@Suppress("UNUSED_PARAMETER") client: HealthConnectClient): Set<String> {
        return readPermissionsExcluding(emptySet())
    }

    internal fun readPermissionsExcluding(@Suppress("UNUSED_PARAMETER") unavailableFeatures: Set<Int>): Set<String> {
        return (recordTypes.map(HealthPermission::getReadPermission) +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND).toSet()
    }
}
