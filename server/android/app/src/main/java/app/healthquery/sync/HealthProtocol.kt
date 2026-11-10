package app.healthquery.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val HEALTH_SYNC_PROTOCOL_VERSION = 1

@Serializable
data class HealthRecordEnvelope(
    val operation: String,
    val record_type: String,
    val record_id: String,
    val payload: JsonElement? = null,
)

@Serializable
data class HealthSyncRequest(
    val protocol_version: Int = HEALTH_SYNC_PROTOCOL_VERSION,
    val mode: String,
    val batch_id: String,
    val records: List<HealthRecordEnvelope>,
    val next_change_token: String? = null,
    val run_id: String? = null,
    val run_kind: String? = null,
    val run_started_at: String? = null,
    val run_total_records: Int? = null,
    val run_processed_records: Int? = null,
    val run_completed: Boolean? = null,
    val run_duration_ms: Long? = null,
    val run_current_record_type: String? = null,
)

@Serializable
data class HealthSyncResponse(
    val status: String? = null,
    val batch_id: String? = null,
    val accepted: Int? = null,
    val next_change_token: String? = null,
)

data class ServerPairing(val serverUrl: String, val token: String)

/** Parses the t3code QR contract: https://server.example/pair/android#token=SHORT_LIVED. */
object ServerQrParser {
    fun parse(raw: String): Result<ServerPairing> = runCatching {
        val uri = java.net.URI(raw.trim())
        require(uri.scheme == "https" || uri.scheme == "http") { "QR link must use http(s)" }
        val host = uri.host ?: error("QR link has no server host")
        require(uri.path == "/pair/android" || uri.path == "/pair/android/") { "Not an Android pairing link" }
        require(uri.userInfo == null && uri.rawQuery == null) { "Pairing link must not contain credentials or query parameters" }
        require(uri.scheme == "https" || isLocalDevelopmentHost(host)) { "HTTPS is required for non-local servers" }
        val token = uri.rawFragment?.split('&')?.firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "token") java.net.URLDecoder.decode(pair[1], "UTF-8") else null
        }?.takeIf { it.isNotBlank() } ?: error("Pairing link has no token fragment")
        ServerPairing("${uri.scheme}://${uri.rawAuthority}".trimEnd('/'), token)
    }

    /** Guards persisted connection origins as well as freshly scanned QR links. */
    fun isAllowedServerUrl(raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw.trim())
        require(uri.path.isEmpty() || uri.path == "/")
        require(uri.query == null && uri.fragment == null && uri.userInfo == null)
        isAllowedCleartextUrl(uri)
        true
    }.getOrDefault(false)

    /** Allows HTTPS anywhere, but permits HTTP only to loopback/LAN/Tailscale hosts. */
    fun isAllowedCleartextUrl(raw: String): Boolean = runCatching {
        isAllowedCleartextUrl(java.net.URI(raw.trim()))
    }.getOrDefault(false)

    private fun isAllowedCleartextUrl(uri: java.net.URI): Boolean {
        val host = uri.host ?: error("URL has no host")
        require(uri.scheme == "https" || (uri.scheme == "http" && isLocalDevelopmentHost(host)))
        return true
    }

    private fun isLocalDevelopmentHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1") return true
        if (normalized.contains(':')) {
            val bytes = runCatching { java.net.InetAddress.getByName(normalized).address }.getOrNull()
            if (bytes != null && bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return true // IPv6 ULA
        }
        val octets = normalized.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }
}
