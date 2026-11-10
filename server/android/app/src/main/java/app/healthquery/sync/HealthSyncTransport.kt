package app.healthquery.sync

import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HealthSyncTransport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun exchange(pairing: ServerPairing, deviceName: String, appVersion: String): ServerConnection =
        withContext(Dispatchers.IO) {
            check(ServerQrParser.isAllowedServerUrl(pairing.serverUrl)) { "Pairing server URL is not allowed" }
            val body = json.encodeToString(mapOf("token" to pairing.token, "device_name" to deviceName, "app_version" to appVersion))
                .toRequestBody(mediaType)
            val request = Request.Builder().url(pairing.serverUrl.trimEnd('/') + "/api/device-enrollment/exchange")
                .post(body).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (!RetryPolicy.isRetryableHttp(response.code)) {
                        throw PermanentSyncException(response.code, RetryPolicy.permanentReason(response.code))
                    }
                    throw IOException("Enrollment temporarily unavailable (${response.code})")
                }
                val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val token = obj["device_token"]?.toString()?.trim('"')
                    ?: obj["token"]?.toString()?.trim('"')
                    ?: error("Enrollment response did not include device_token")
                val deviceId = obj["device_id"]?.toString()?.trim('"')
                ServerConnection(pairing.serverUrl, token, deviceId)
            }
        }

    suspend fun send(connection: ServerConnection, requestBody: String): HealthSyncResponse =
        withContext(Dispatchers.IO) {
            check(ServerQrParser.isAllowedServerUrl(connection.serverUrl)) { "Stored server URL is not allowed" }
            val request = Request.Builder().url(connection.serverUrl.trimEnd('/') + "/api/device-sync/v1/changes")
                .post(requestBody.toRequestBody(mediaType))
                .header("Authorization", "Bearer ${connection.bearerToken}")
                .header("Accept", "application/json").build()
            var attempt = 0
            while (true) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val parsed = json.decodeFromString<HealthSyncResponse>(response.body?.string().orEmpty())
                            check(parsed.status == null || parsed.status == "success") { "Sync rejected: ${parsed.status}" }
                            return@withContext parsed
                        }
                        if (!RetryPolicy.isRetryableHttp(response.code)) {
                            // Do not include the response body: it may contain health data or
                            // server-side credentials. Workers surface only this bounded reason.
                            throw PermanentSyncException(response.code, RetryPolicy.permanentReason(response.code))
                        }
                        if (attempt >= MAX_ATTEMPTS - 1) {
                            error("Sync temporarily unavailable (${response.code})")
                        }
                    }
                } catch (e: IOException) {
                    if (attempt >= MAX_ATTEMPTS - 1) throw e
                }
                delay(RetryPolicy.backoffMillis(attempt))
                attempt++
            }
            error("Sync retry loop exited unexpectedly")
        }

    internal object RetryPolicy {
        fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

        /** Stable, privacy-safe reason suitable for WorkManager output and UI diagnostics. */
        fun permanentReason(code: Int): String = when (code) {
            400 -> "bad_request"
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            409 -> "conflict"
            413 -> "payload_too_large"
            415 -> "unsupported_media_type"
            422 -> "unprocessable_entity"
            else -> "http_$code"
        }

        fun backoffMillis(attempt: Int): Long = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            else -> 4_000L
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** A server response that cannot succeed by retrying the same pending request. */
internal class PermanentSyncException(
    val statusCode: Int,
    val reason: String,
) : Exception("Sync request rejected ($reason)")
