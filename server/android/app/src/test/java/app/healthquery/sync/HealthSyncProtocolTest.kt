package app.healthquery.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation

class HealthSyncProtocolTest {
    private class RichRecord(
        val enabled: Boolean,
        val values: IntArray,
        val attributes: Map<String, Any?>,
        override val metadata: Metadata = Metadata.manualEntry(),
    ) : Record

    @Test
    fun qrParserReadsTokenFromFragmentOnly() {
        val pairing = ServerQrParser.parse("https://server.example/pair/android#token=short-lived").getOrThrow()
        assertEquals("https://server.example", pairing.serverUrl)
        assertEquals("short-lived", pairing.token)
        assertTrue(ServerQrParser.parse("https://server.example/pair/android?token=wrong").isFailure)
        assertTrue(ServerQrParser.parse("http://192.168.1.20/pair/android#token=dev").isSuccess)
        assertTrue(ServerQrParser.parse("http://server.example/pair/android#token=insecure").isFailure)
        assertTrue(ServerQrParser.isAllowedServerUrl("http://192.168.1.20"))
        assertTrue(ServerQrParser.isAllowedServerUrl("http://100.64.0.8"))
        assertTrue(!ServerQrParser.isAllowedServerUrl("http://server.example"))
        assertTrue(!ServerQrParser.isAllowedServerUrl("https://server.example/path"))
        assertTrue(ServerQrParser.isAllowedCleartextUrl("http://192.168.1.20/hooks"))
        assertTrue(!ServerQrParser.isAllowedCleartextUrl("http://server.example/hooks"))
    }

    @Test
    fun serializerPreservesPointAndSeriesFieldsAndMetadata() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val steps = StepsRecord(start, ZoneOffset.UTC, start.plusSeconds(60), ZoneOffset.UTC, 12, Metadata.manualEntry("client-1", 3))
        val point = HealthRecordSerializer.payload(steps)
        assertEquals(12, point["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("client-1", point["metadata"]!!.jsonObject["clientRecordId"]!!.jsonPrimitive.content)
        val heartRate = HeartRateRecord(
            start, ZoneOffset.UTC, start.plusSeconds(5), ZoneOffset.UTC,
            listOf(HeartRateRecord.Sample(start.plusSeconds(1), 72)), Metadata.manualEntry()
        )
        assertEquals(72, HealthRecordSerializer.payload(heartRate)["samples"]!!.jsonArray[0].jsonObject["beatsPerMinute"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun serializerPreservesOriginDeviceAndRecordVersion() {
        val device = Device(Device.TYPE_WATCH, "Acme", "Tracker")
        val metadata = Metadata.autoRecorded(device, "client-record-7", 9)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val payload = HealthRecordSerializer.payload(
            StepsRecord(start, ZoneOffset.UTC, start.plusSeconds(60), ZoneOffset.UTC, 4, metadata)
        )
        val encodedMetadata = payload["metadata"]!!.jsonObject
        assertEquals("client-record-7", encodedMetadata["clientRecordId"]!!.jsonPrimitive.content)
        assertEquals("9", encodedMetadata["clientRecordVersion"]!!.jsonPrimitive.content)
        assertEquals("Acme", encodedMetadata["device"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
        assertEquals("Tracker", encodedMetadata["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(encodedMetadata["dataOrigin"]!!.jsonObject.containsKey("packageName"))
    }

    @Test
    fun catalogContainsOnlyApprovedRecordTypesAndRequiredReadPermissions() {
        assertEquals(10, HealthRecordCatalog.recordTypes.size)
        assertEquals(10, HealthRecordCatalog.recordTypes.toSet().size)
        assertFalse(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in HealthRecordCatalog.readPermissions)
        assertTrue(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in HealthRecordCatalog.readPermissions)
    }

    @Test
    fun deletionEnvelopeHasStableIdAndNullPayload() {
        val envelope = HealthRecordEnvelope("delete", "androidx.health.connect.client.records.StepsRecord", "record-7", null)
        assertEquals("delete", envelope.operation)
        assertEquals("record-7", envelope.record_id)
        assertEquals(null, envelope.payload)
    }

    @Test
    fun serializerKeepsBooleansAndPrimitiveArraysTyped() {
        val payload = HealthRecordSerializer.payload(RichRecord(true, intArrayOf(1, 2), mapOf("ok" to false)))
        assertTrue(!payload["enabled"]!!.jsonPrimitive.isString)
        assertEquals("2", payload["values"]!!.jsonArray[1].jsonPrimitive.content)
        assertTrue(!payload["attributes"]!!.jsonObject["ok"]!!.jsonPrimitive.isString)
    }

    @Test
    fun streamingSerializerMatchesLosslessPayloadTree() {
        val record = HeartRateRecord(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC,
            Instant.parse("2026-01-01T00:01:00Z"), ZoneOffset.UTC,
            listOf(HeartRateRecord.Sample(Instant.parse("2026-01-01T00:00:30Z"), 72)),
            Metadata.manualEntry("stream-check")
        )
        val streamed = StringBuilder().also { HealthRecordSerializer.writePayload(record, it) }.toString()
        assertEquals(HealthRecordSerializer.payload(record).toString(), streamed)

        val rich = RichRecord(true, intArrayOf(1, 2), mapOf("ok" to false))
        val richStream = StringBuilder().also { HealthRecordSerializer.writePayload(rich, it) }.toString()
        assertEquals(HealthRecordSerializer.payload(rich).toString(), richStream)
    }

    @Test
    fun failedBatchKeepsCursorAndAckCommitsIt() {
        val pending = SyncCursorPolicy.prepare("body", "token-2")
        assertEquals(pending, SyncCursorPolicy.afterFailure(pending))
        assertEquals("token-2", SyncCursorPolicy.afterAck(pending, null))
        assertEquals("token-3", SyncCursorPolicy.afterAck(pending, "token-3"))
    }

    @Test
    fun transportRetriesOnlyTransientFailuresWithBoundedBackoff() {
        assertTrue(HealthSyncTransport.RetryPolicy.isRetryableHttp(408))
        assertTrue(HealthSyncTransport.RetryPolicy.isRetryableHttp(429))
        assertTrue(HealthSyncTransport.RetryPolicy.isRetryableHttp(503))
        assertTrue(!HealthSyncTransport.RetryPolicy.isRetryableHttp(400))
        assertTrue(!HealthSyncTransport.RetryPolicy.isRetryableHttp(401))
        assertEquals(1_000L, HealthSyncTransport.RetryPolicy.backoffMillis(0))
        assertEquals(2_000L, HealthSyncTransport.RetryPolicy.backoffMillis(1))
    }

    @Test
    fun permanentHttpFailuresHaveTypedPrivacySafeReasons() {
        assertEquals("bad_request", HealthSyncTransport.RetryPolicy.permanentReason(400))
        assertEquals("unauthorized", HealthSyncTransport.RetryPolicy.permanentReason(401))
        assertEquals("forbidden", HealthSyncTransport.RetryPolicy.permanentReason(403))
        assertEquals("not_found", HealthSyncTransport.RetryPolicy.permanentReason(404))
        assertEquals("conflict", HealthSyncTransport.RetryPolicy.permanentReason(409))
        assertEquals("payload_too_large", HealthSyncTransport.RetryPolicy.permanentReason(413))
        assertEquals("http_451", HealthSyncTransport.RetryPolicy.permanentReason(451))
        assertTrue(!HealthSyncTransport.RetryPolicy.isRetryableHttp(400))
        val error = PermanentSyncException(401, HealthSyncTransport.RetryPolicy.permanentReason(401))
        assertEquals(401, error.statusCode)
        assertEquals("unauthorized", error.reason)
        assertTrue(!error.message.orEmpty().contains("response body"))
    }

    @Test
    fun pairingApiIsSuspendSoEnrollmentCannotRunOnMain() {
        val method = LosslessHealthSyncEngine::class.java.declaredMethods.single { it.name == "pair" }
        assertEquals(Continuation::class.java, method.parameterTypes.last())
    }

    @Test
    fun syncTransportApiIsSuspendForOffMainNetworkExecution() {
        val method = HealthSyncTransport::class.java.declaredMethods.single { it.name == "send" }
        assertEquals(Continuation::class.java, method.parameterTypes.last())
    }

    @Test
    fun syncHistoryKeepsAtLeastTenAndRedactsCredentialText() {
        val entries = (0 until 37).map {
            SyncHistoryEntry(it.toLong(), true, mode = "changes", records = it)
        }
        val result = entries.fold(emptyList<SyncHistoryEntry>()) { current, entry ->
            SyncHistoryPolicy.append(current, entry)
        }
        assertEquals(SyncHistoryPolicy.MAX_ENTRIES, result.size)
        assertEquals(36L, result.first().timestampMillis)
        assertTrue(SyncHistoryPolicy.safeError(IllegalStateException("Bearer abc token=secret"))
            .contains("<redacted>"))
    }

    @Test
    fun lastSuccessfulSyncComesFromTheMatchingCompletedHistoryEntry() {
        val entries = listOf(
            SyncHistoryEntry(300L, false, mode = "initial", records = 2_855),
            SyncHistoryEntry(200L, true, mode = "changes", records = 245),
            SyncHistoryEntry(100L, true, mode = "snapshot", records = 18_413),
        )

        assertEquals(200L, SyncHistoryPolicy.lastSuccessfulTimestamp(entries))
        assertEquals(null, SyncHistoryPolicy.lastSuccessfulTimestamp(entries.filterNot { it.success }))
    }

    @Test
    fun batchIdsAreStablePerInstallButDoNotCollideAcrossInstalls() {
        val canonical = "{\"mode\":\"changes\",\"records\":[]}"
        val first = BatchIdPolicy.digest("install-a", canonical)
        assertEquals(first, BatchIdPolicy.digest("install-a", canonical))
        assertTrue(first != BatchIdPolicy.digest("install-b", canonical))
    }

    @Test
    fun processSyncGateSerializesConcurrentEngineTransactions() = runBlocking {
        var active = 0
        var maximum = 0
        coroutineScope {
            val first = async {
                ProcessSyncGate.run {
                    active++
                    maximum = maxOf(maximum, active)
                    delay(25)
                    active--
                }
            }
            val second = async {
                ProcessSyncGate.run {
                    active++
                    maximum = maxOf(maximum, active)
                    delay(25)
                    active--
                }
            }
            first.await()
            second.await()
        }
        assertEquals(1, maximum)
        assertEquals(0, active)
    }

    @Test
    fun progressPolicyBoundsUiEmissionUnlessForced() {
        assertTrue(SyncProgressPolicy.shouldEmit(1_000L, 0L))
        assertTrue(!SyncProgressPolicy.shouldEmit(1_100L, 1_000L))
        assertTrue(SyncProgressPolicy.shouldEmit(1_100L, 1_000L, force = true))
        assertEquals(1_000L, SyncProgressPolicy.etaMillis(20, 10, 1_000L))
        assertEquals(1_000L, SyncProgressPolicy.etaMillis(200, 150, 1_000L, baselineProcessed = 100))
        assertEquals(null, SyncProgressPolicy.etaMillis(null, 10, 1_000L))
    }
}
