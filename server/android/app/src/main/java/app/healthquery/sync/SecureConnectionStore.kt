package app.healthquery.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServerConnection(
    val serverUrl: String,
    val bearerToken: String,
    /** Server-issued stable scope for globally unique idempotency batch IDs. */
    val deviceId: String? = null,
)

/** Keystore-backed storage; plaintext credentials never enter SharedPreferences or logs. */
class SecureConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("android_server_connection", Context.MODE_PRIVATE)
    private val keyAlias = "android-health-device-credential"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    fun save(connection: ServerConnection) {
        val plain = "${connection.serverUrl}\n${connection.bearerToken}\n${connection.deviceId.orEmpty()}"
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(plain)
        check(prefs.edit().putString(KEY_CONNECTION, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Unable to persist server connection"
        }
    }

    fun load(): ServerConnection? = runCatching {
        val packed = Base64.decode(prefs.getString(KEY_CONNECTION, null) ?: return null, Base64.NO_WRAP)
        require(packed.size > GCM_IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, GCM_IV_LENGTH)))
        }
        val text = String(cipher.doFinal(packed.copyOfRange(GCM_IV_LENGTH, packed.size)), StandardCharsets.UTF_8)
        val lines = text.split('\n', limit = 3)
        require(lines.size >= 2)
        ServerConnection(lines[0], lines[1], lines.getOrNull(2)?.takeIf { it.isNotBlank() })
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY_CONNECTION).apply()
    }

    companion object {
        private const val KEY_CONNECTION = "encrypted_connection"
        private const val GCM_IV_LENGTH = 12
    }
}
