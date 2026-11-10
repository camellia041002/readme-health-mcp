package app.healthquery.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/** Small permission/availability facade used by the UI and bootstrap worker. */
class HealthConnectManager(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun isHealthConnectAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun getGrantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun hasPermissions(required: Set<String>): Boolean =
        getGrantedPermissions().containsAll(required)

    fun providerSupportedReadPermissions(): Set<String> =
        HealthRecordCatalog.providerSupportedReadPermissions(client)
}
