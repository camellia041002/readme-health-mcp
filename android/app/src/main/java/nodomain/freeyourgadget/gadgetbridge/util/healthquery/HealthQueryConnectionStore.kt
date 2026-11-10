/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Android-Keystore-backed connection storage. Plaintext tokens never enter ordinary preferences. */
class HealthQueryConnectionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "healthquery_connection",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(connection: HealthQueryConnection) {
        require(HealthQueryPairingParser.isAllowedServerUrl(connection.serverUrl))
        require(connection.deviceToken.isNotBlank() && connection.deviceId.isNotBlank())
        check(preferences.edit()
            .putString(KEY_SERVER_URL, connection.serverUrl)
            .putString(KEY_DEVICE_TOKEN, connection.deviceToken)
            .putString(KEY_DEVICE_ID, connection.deviceId)
            .commit())
    }

    fun load(): HealthQueryConnection? {
        val url = preferences.getString(KEY_SERVER_URL, null) ?: return null
        val token = preferences.getString(KEY_DEVICE_TOKEN, null) ?: return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        return HealthQueryConnection(url, token, deviceId).takeIf {
            HealthQueryPairingParser.isAllowedServerUrl(url) && token.isNotBlank() && deviceId.isNotBlank()
        }
    }

    fun clear() = preferences.edit().clear().commit()

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
