/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.QuicOptions
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

sealed class HealthQueryTransportResult {
    data class Success(val accepted: Int) : HealthQueryTransportResult()
    data class Failure(val category: String, val retryable: Boolean) : HealthQueryTransportResult()
}

/** HTTPS transport that exposes only bounded status categories and never logs response bodies. */
class HealthQueryTransport(context: Context) {
    private val appContext = context.applicationContext
    fun exchange(pairing: HealthQueryPairing, deviceName: String, appVersion: String): HealthQueryConnection {
        require(HealthQueryPairingParser.isAllowedServerUrl(pairing.serverUrl))
        val body = JSONObject().put("token", pairing.enrollmentToken)
            .put("device_name", deviceName).put("app_version", appVersion).toString()
        val response = postJsonWithRetry(
            pairing.serverUrl + "/api/device-enrollment/exchange",
            body,
            emptyMap()
        )
        if (response.code !in 200..299) throw IOException("enrollment_${category(response.code)}")
        val responseJson = JSONObject(response.body)
        val token = responseJson.optString("device_token").ifBlank { responseJson.optString("token") }
        val deviceId = responseJson.optString("device_id")
        require(token.isNotBlank() && deviceId.isNotBlank()) { "invalid_enrollment_response" }
        return HealthQueryConnection(pairing.serverUrl, token, deviceId)
    }

    fun upload(connection: HealthQueryConnection, requestBody: JSONObject): HealthQueryTransportResult {
        require(HealthQueryPairingParser.isAllowedServerUrl(connection.serverUrl))
        return try {
            val response = postJsonWithRetry(
                connection.serverUrl + "/api/device-sync/v1/changes",
                requestBody.toString(),
                mapOf("Authorization" to "Bearer ${connection.deviceToken}")
            )
            if (response.code !in 200..299) {
                HealthQueryTransportResult.Failure(category(response.code), retryable(response.code))
            } else {
                val accepted = runCatching { JSONObject(response.body).optInt("accepted", 0) }.getOrDefault(0)
                HealthQueryTransportResult.Success(accepted)
            }
        } catch (exception: IOException) {
            Log.w(TAG, "HealthQuery upload failed: ${diagnostic(exception)}")
            HealthQueryTransportResult.Failure("network_error", true)
        }
    }

    fun readerAccess(connection: HealthQueryConnection): JSONArray {
        require(HealthQueryPairingParser.isAllowedServerUrl(connection.serverUrl))
        val response = getJsonWithRetry(
            connection.serverUrl + "/api/device-sync/v1/reader-access?limit=50",
            mapOf("Authorization" to "Bearer ${connection.deviceToken}")
        )
        if (response.code !in 200..299) throw IOException("reader_access_${category(response.code)}")
        return JSONObject(response.body).optJSONArray("events") ?: JSONArray()
    }

    private fun postJsonWithRetry(url: String, body: String, headers: Map<String, String>): HttpResponse {
        var lastFailure: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return postJson(url, body, headers)
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt + 1 < CONNECT_ATTEMPTS) Thread.sleep((attempt + 1) * RETRY_DELAY_MILLIS)
            }
        }
        throw lastFailure ?: IOException("connection_failed")
    }

    private fun getJsonWithRetry(url: String, headers: Map<String, String>): HttpResponse {
        var lastFailure: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return getJson(url, headers)
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt + 1 < CONNECT_ATTEMPTS) Thread.sleep((attempt + 1) * RETRY_DELAY_MILLIS)
            }
        }
        throw lastFailure ?: IOException("connection_failed")
    }

    private fun getJson(url: String, headers: Map<String, String>): HttpResponse {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https" || parsedUrl.protocol == "http")
        val connection = if (Build.VERSION.SDK_INT >= 34) PlatformHttpEngine.open(appContext, parsedUrl)
        else parsedUrl.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use(::readBounded) ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https" || parsedUrl.protocol == "http")
        val connection = if (Build.VERSION.SDK_INT >= 34) {
            PlatformHttpEngine.open(appContext, parsedUrl)
        } else {
            parsedUrl.openConnection() as HttpURLConnection
        }
        try {
            require(connection is HttpsURLConnection || parsedUrl.protocol == "http" || Build.VERSION.SDK_INT >= 34)
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use(::readBounded) ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() < MAX_RESPONSE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class HttpResponse(val code: Int, val body: String)

    @RequiresApi(34)
    private object PlatformHttpEngine {
        private val engines = ConcurrentHashMap<String, HttpEngine>()

        fun open(context: Context, url: URL): HttpURLConnection {
            val current = engines.computeIfAbsent(url.host) { host ->
                HttpEngine.Builder(context)
                    .setEnableHttp2(true)
                    .setEnableQuic(true)
                    .setQuicOptions(QuicOptions.Builder().addAllowedQuicHost(host).build())
                    .addQuicHint(host, url.port.takeIf { it > 0 } ?: url.defaultPort, url.port.takeIf { it > 0 } ?: url.defaultPort)
                    .build()
            }
            return current.openConnection(url) as HttpURLConnection
        }
    }

    companion object {
        private const val TAG = "HealthQueryTransport"
        private const val TIMEOUT_MILLIS = 90_000
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val CONNECT_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 500L
        private fun diagnostic(exception: Throwable): String = generateSequence(exception) { it.cause }
            .take(4)
            .joinToString(" <- ") { cause ->
                val safeMessage = cause.message.orEmpty()
                    .replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer [redacted]")
                    .take(240)
                "${cause.javaClass.simpleName}: $safeMessage"
            }
        @JvmStatic fun retryable(code: Int) = code == 408 || code == 429 || code in 500..599
        @JvmStatic fun category(code: Int) = when (code) {
            400 -> "bad_request"; 401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not_found"
            409 -> "conflict"; 413 -> "payload_too_large"; 422 -> "unprocessable_entity"
            else -> if (retryable(code)) "temporary_server_error" else "http_error"
        }
    }
}
