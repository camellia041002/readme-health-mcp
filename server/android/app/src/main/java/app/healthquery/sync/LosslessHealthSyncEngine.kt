package app.healthquery.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

data class SyncSummary(
    val mode: String,
    val records: Int,
    val completedAt: Instant,
    val historyLimited: Boolean = false,
    val skippedTypes: List<String> = emptyList(),
)

/**
 * Snapshot and incremental Health Connect synchronization. The pending body is written before
 * transport and the cursor is written only after the server acknowledges that exact batch.
 */
class LosslessHealthSyncEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentialStore = SecureConnectionStore(context)
    private val transport = HealthSyncTransport()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun connected(): ServerConnection? = credentialStore.load()

    fun lastSuccessfulSyncMillis(): Long? = SyncHistoryPolicy.lastSuccessfulTimestamp(syncHistory())

    /** True only after an acknowledged batch has committed the final change token. */
    fun isBootstrapComplete(): Boolean = BootstrapWorkPolicy.periodicAllowed(
        prefs.getString(KEY_CHANGE_TOKEN, null), loadBootstrapCheckpoint()
    )

    fun bootstrapCheckpoint(): BootstrapCheckpoint? = loadBootstrapCheckpoint()

    /**
     * Atomically abandons every pre-v2 cursor/checkpoint while preserving the encrypted server
     * connection, installation identity, and completed diagnostic history. A missing marker is
     * deliberately treated as legacy so an interrupted migration is safe to repeat.
     */
    fun ensureBootstrapPolicyCurrent(): Boolean {
        val storedVersion = prefs.getInt(KEY_BOOTSTRAP_POLICY_VERSION, 0)
        if (!BootstrapWindowPolicy.requiresMigration(storedVersion)) return false
        val edit = prefs.edit()
        (LEGACY_SYNC_STATE_KEYS + CURRENT_SYNC_STATE_KEYS).forEach(edit::remove)
        check(edit.putInt(KEY_BOOTSTRAP_POLICY_VERSION, BootstrapWindowPolicy.VERSION).commit()) {
            "Unable to migrate Health Connect bootstrap policy"
        }
        return true
    }

    /**
     * Enrollment performs a blocking OkHttp exchange. Keep the public API suspend so callers
     * cannot accidentally run it on the Compose/Main dispatcher (which causes
     * NetworkOnMainThreadException on Android).
     */
    suspend fun pair(rawQr: String): ServerConnection = withContext(Dispatchers.IO) {
        val pairing = ServerQrParser.parse(rawQr).getOrThrow()
        val connection = transport.exchange(pairing, deviceName(), appVersion())
        credentialStore.save(connection)
        clearCursor(clearScope = true)
        connection
    }

    fun disconnect() {
        credentialStore.clear()
        clearCursor(clearScope = true)
    }

    suspend fun sync(onProgress: ((SyncProgress) -> Unit)? = null): SyncSummary? =
        runSync(true, onProgress, SyncRunKind.MANUAL)

    suspend fun bootstrap(onProgress: ((SyncProgress) -> Unit)? = null): SyncSummary? =
        runSync(true, onProgress, requestedBootstrapKind())

    suspend fun syncIncremental(
        onProgress: ((SyncProgress) -> Unit)? = null,
        runKind: String = SyncRunKind.AUTOMATIC,
    ): SyncSummary? = runSync(false, onProgress, runKind)

    /**
     * Arms a destructive-free full re-sync.  This only clears local cursor state and asks the
     * next bootstrap worker to re-read/upsert accessible history; server records are never
     * deleted.  The caller is responsible for confirmation in the UI before invoking this API.
     */
    fun prepareFullResync(): Boolean {
        if (credentialStore.load() == null) return false
        // Completed-run history remains visible while the replacement import runs.
        clearCursor(clearPending = false, clearScope = true)
        check(prefs.edit().putString(KEY_REQUESTED_RUN_KIND, SyncRunKind.FULL).commit()) {
            "Unable to persist full re-sync request"
        }
        return true
    }

    fun hasPreparedFullResync(): Boolean = prefs.getString(KEY_REQUESTED_RUN_KIND, null) == SyncRunKind.FULL

    private suspend fun runSync(
        allowBootstrap: Boolean,
        onProgress: ((SyncProgress) -> Unit)?,
        requestedKind: String,
    ): SyncSummary? = ProcessSyncGate.run {
        val progress = SyncProgressTracker(onProgress)
        progress.emit("starting", force = true)
        try {
            val summary = syncInternal(
                retryExpiredToken = true,
                progress = progress,
                allowBootstrap = allowBootstrap,
                requestedKind = requestedKind,
            )
            if (summary != null) {
                withContext(Dispatchers.IO) {
                    val run = loadRunContext()
                    val completedAt = System.currentTimeMillis()
                    val accessReady = SyncSummaryPolicy.isSuccessful(summary)
                    recordHistory(
                        SyncHistoryEntry(
                            timestampMillis = completedAt,
                            success = accessReady,
                            mode = summary.mode,
                            records = summary.records,
                            error = SyncSummaryPolicy.errorFor(summary),
                            runKind = run?.runKind ?: requestedKind,
                            durationMillis = run?.let { (completedAt - parseRunStartMillis(it)).coerceAtLeast(0L) },
                            runId = run?.runId,
                        )
                    )
                    run?.let { saveRunContext(it.copy(completed = true, durationMillis = (completedAt - parseRunStartMillis(it)).coerceAtLeast(0L))) }
                    clearRunContext()
                }
            }
            progress.emit(if (summary != null && !SyncSummaryPolicy.isSuccessful(summary)) "failed" else "complete", force = true)
            summary
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) {
                val completedAt = System.currentTimeMillis()
                val run = loadRunContext()
                recordHistory(
                    SyncHistoryEntry(
                        timestampMillis = completedAt,
                        success = false,
                        mode = run?.runKind,
                        records = run?.processedRecords,
                        error = SyncHistoryPolicy.safeError(e),
                        runKind = run?.runKind ?: requestedKind,
                        durationMillis = run?.let { (completedAt - parseRunStartMillis(it)).coerceAtLeast(0L) },
                        runId = run?.runId,
                    )
                )
            }
            progress.emit("failed", force = true)
            throw e
        }
    }

    fun syncHistory(): List<SyncHistoryEntry> = runCatching {
        json.decodeFromString<List<SyncHistoryEntry>>(prefs.getString(KEY_SYNC_HISTORY, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun recordHistory(entry: SyncHistoryEntry) {
        val next = SyncHistoryPolicy.append(syncHistory(), entry)
        // History is diagnostic UI state; a rare preferences write failure must not turn an
        // already-acknowledged health sync into a retry (or recursively create another history
        // entry while reporting that failure).
        prefs.edit().putString(KEY_SYNC_HISTORY, json.encodeToString(next)).commit()
    }

    private suspend fun syncInternal(
        retryExpiredToken: Boolean,
        progress: SyncProgressTracker,
        allowBootstrap: Boolean,
        requestedKind: String,
    ): SyncSummary? {
        val connection = credentialStore.load() ?: return null
        progress.emit("preparing")
        sendPending(connection)

        // Health Connect tokens are scoped to the requested record types. Asking for a denied or
        // feature-gated type makes the whole token/read fail, so derive the scope from the actual
        // grants and provider feature availability on every run.
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        val readableTypes = readableRecordTypes(grantedPermissions)
        if (readableTypes.isEmpty()) {
            return SyncSummary("no_access", 0, Instant.now())
        }
        val scopeKey = readableTypes.joinToString(",") { it.qualifiedName ?: it.java.name }
        val previousScope = prefs.getString(KEY_RECORD_SCOPE, null)
        val existingToken = prefs.getString(KEY_CHANGE_TOKEN, null)
        var checkpoint = loadBootstrapCheckpoint()
        if (checkpoint != null && !BootstrapCheckpointPolicy.canResume(checkpoint!!, scopeKey)) {
            clearBootstrapCheckpoint()
            checkpoint = null
        }
        // A pre-hardening installation has a token but no scope marker. Rebuild once so that
        // the token is never reused with a narrower/different permission set.
        val scopeChanged = existingToken != null && (previousScope == null || previousScope != scopeKey)
        if (scopeChanged) {
            // A token cannot be widened after permissions are granted. Keep any pending body,
            // then start a new snapshot for the new scope once it has been acknowledged.
            clearCursor(clearPending = false, clearScope = false)
            clearBootstrapCheckpoint()
            checkpoint = null
        }

        val storedToken = if (scopeChanged) null else existingToken
        if (!allowBootstrap && storedToken == null) {
            return SyncSummary("not_ready", 0, Instant.now())
        }
        val snapshotNeeded = allowBootstrap && (checkpoint != null || storedToken == null)
        if (snapshotNeeded && checkpoint == null) {
            // Capture the token and immutable bounds before backfill. The checkpoint is written
            // before the first provider read, so process death cannot restart at type zero.
            val snapshotEnd = Instant.now()
            val snapshotStart = BootstrapWindowPolicy.snapshotStart(snapshotEnd)
            checkpoint = BootstrapCheckpoint(
                snapshotStartMillis = snapshotStart.toEpochMilli(),
                snapshotEndMillis = snapshotEnd.toEpochMilli(),
                scopeKey = scopeKey,
                baseChangeToken = client.getChangesToken(ChangesTokenRequest(readableTypes.toSet(), emptySet())),
                recordTypeIndex = 0,
                phase = BootstrapPhase.INVENTORY,
                runId = UUID.randomUUID().toString(),
                runKind = requestedKind.takeIf { it in SyncRunKind.values } ?: SyncRunKind.INITIAL,
                runStartedAt = Instant.now().toString(),
            )
            saveBootstrapCheckpoint(checkpoint!!)
        }

        var run = ensureRunContext(
            checkpoint = checkpoint,
            requestedKind = requestedKind,
            totalRecords = checkpoint?.totalRecords,
        )
        checkpoint?.let {
            val persistedProcessed = if (it.phase == BootstrapPhase.INVENTORY) {
                it.inventoryCounts.values.sum()
            } else it.uploadedRecords
            progress.restore(persistedProcessed, it.uploadedRecords)
        }
        progress.configure(run?.totalRecords, run = run)
        val baseToken = checkpoint?.baseChangeToken ?: storedToken
            ?: error("Missing Health Connect change token")

        var total = checkpoint?.totalRecords ?: 0
        val skippedTypes = mutableListOf<String>()
        val historyLimited = false
        if (snapshotNeeded) {
            // Phase one: inventory every page with the bounded provider page size.  Values are
            // intentionally discarded; only per-type counts/cursors are persisted.
            val snapshotEnd = Instant.ofEpochMilli(checkpoint!!.snapshotEndMillis)
            val snapshotStart = Instant.ofEpochMilli(checkpoint!!.snapshotStartMillis)
            var typeIndex = checkpoint!!.recordTypeIndex
            if (checkpoint!!.phase == BootstrapPhase.INVENTORY) {
                while (typeIndex < readableTypes.size) {
                    val type = readableTypes[typeIndex]
                    val shortType = type.qualifiedName?.substringAfterLast('.') ?: type.java.simpleName
                    var pageToken: String? = if (typeIndex == checkpoint!!.recordTypeIndex) checkpoint!!.providerPageToken else null
                    try {
                        do {
                            progress.currentType(null, typeIndex, readableTypes.size, shortType)
                            progress.emit(BootstrapPhase.INVENTORY, shortType)
                            val response = readPage(type, snapshotStart, snapshotEnd, pageToken)
                            val pageCount = response.records.size
                            progress.recordProcessed(pageCount)
                            val nextPageToken = response.pageToken
                            if (nextPageToken != null && nextPageToken == pageToken) {
                                error("Health Connect returned a repeating page token for ${type.qualifiedName}")
                            }
                            checkpoint = BootstrapCheckpointPolicy.inventoryPage(checkpoint!!, shortType, pageCount, nextPageToken)
                            checkpoint = if (nextPageToken == null) BootstrapCheckpointPolicy.nextType(checkpoint!!) else checkpoint
                            saveBootstrapCheckpoint(checkpoint!!)
                            typeIndex = checkpoint!!.recordTypeIndex
                            pageToken = nextPageToken
                            progress.pageCompleted(BootstrapPhase.INVENTORY, shortType)
                        } while (pageToken != null)
                    } catch (e: SecurityException) {
                        skippedTypes += type.qualifiedName ?: type.java.name
                        checkpoint = BootstrapCheckpointPolicy.nextType(checkpoint!!)
                        saveBootstrapCheckpoint(checkpoint!!)
                        typeIndex = checkpoint!!.recordTypeIndex
                    } catch (e: IllegalArgumentException) {
                        skippedTypes += type.qualifiedName ?: type.java.name
                        checkpoint = BootstrapCheckpointPolicy.nextType(checkpoint!!)
                        saveBootstrapCheckpoint(checkpoint!!)
                        typeIndex = checkpoint!!.recordTypeIndex
                    }
                }
                checkpoint = BootstrapCheckpointPolicy.beginUpload(checkpoint!!)
                saveBootstrapCheckpoint(checkpoint!!)
                run = updateRunContext(run.copy(totalRecords = checkpoint!!.totalRecords), checkpoint!!.uploadedRecords, null)
                progress.resetProcessed(checkpoint!!.uploadedRecords)
                progress.configure(checkpoint!!.totalRecords, run = run)
            }

            // Phase two: re-read the same immutable range and upload against the known inventory
            // total. A page cursor moves only after every idempotent batch from that page ACKs.
            typeIndex = checkpoint!!.recordTypeIndex
            while (checkpoint!!.phase == BootstrapPhase.UPLOAD && typeIndex < readableTypes.size) {
                val type = readableTypes[typeIndex]
                val shortType = type.qualifiedName?.substringAfterLast('.') ?: type.java.simpleName
                var pageToken: String? = if (typeIndex == checkpoint!!.recordTypeIndex) checkpoint!!.providerPageToken else null
                val typeTotal = checkpoint!!.inventoryCounts[shortType] ?: 0
                try {
                    do {
                        progress.currentType(typeTotal, typeIndex, readableTypes.size, shortType)
                        progress.emit(BootstrapPhase.UPLOAD, shortType)
                        val response = readPage(type, snapshotStart, snapshotEnd, pageToken)
                        val pageCount = response.records.size
                        val page = response.records.asSequence().map(::encodedEnvelope)
                        progress.recordProcessed(pageCount)
                        sendChunks(connection, "snapshot", page, commitToken = null, progress = progress, recordType = shortType, run = run)
                        val nextPageToken = response.pageToken
                        if (nextPageToken != null && nextPageToken == pageToken) {
                            error("Health Connect returned a repeating page token for ${type.qualifiedName}")
                        }
                        checkpoint = BootstrapCheckpointPolicy.uploadPage(checkpoint!!, pageCount, nextPageToken)
                        checkpoint = if (nextPageToken == null) BootstrapCheckpointPolicy.nextType(checkpoint!!) else checkpoint
                        saveBootstrapCheckpoint(checkpoint!!)
                        run = updateRunContext(run, checkpoint!!.uploadedRecords, shortType)
                        progress.pageCompleted(BootstrapPhase.UPLOAD, shortType)
                        typeIndex = checkpoint!!.recordTypeIndex
                        pageToken = nextPageToken
                    } while (pageToken != null)
                } catch (e: SecurityException) {
                    skippedTypes += type.qualifiedName ?: type.java.name
                    checkpoint = BootstrapCheckpointPolicy.nextType(checkpoint!!)
                    saveBootstrapCheckpoint(checkpoint!!)
                    typeIndex = checkpoint!!.recordTypeIndex
                } catch (e: IllegalArgumentException) {
                    skippedTypes += type.qualifiedName ?: type.java.name
                    checkpoint = BootstrapCheckpointPolicy.nextType(checkpoint!!)
                    saveBootstrapCheckpoint(checkpoint!!)
                    typeIndex = checkpoint!!.recordTypeIndex
                }
            }
        }

        if (snapshotNeeded && checkpoint?.phase != BootstrapPhase.CHANGES) {
            checkpoint = checkpoint?.copy(
                phase = BootstrapPhase.CHANGES,
                // The provider is live. If records changed inside the fixed time range between
                // inventory and upload, the uploaded count is the honest final denominator.
                totalRecords = checkpoint?.uploadedRecords ?: 0,
            )
            checkpoint?.let(::saveBootstrapCheckpoint)
            checkpoint?.let {
                run = updateRunContext(run.copy(totalRecords = it.totalRecords), it.uploadedRecords, null)
            }
        }
        progress.resetProcessed(progress.sentRecords)
        progress.configure(if (snapshotNeeded) checkpoint?.totalRecords else null, run = run)
        var token = baseToken
        var more: Boolean
        do {
            progress.emit(BootstrapPhase.CHANGES, "changes")
            val changes = client.getChanges(token)
            if (changes.changesTokenExpired) {
                clearCursor(clearPending = false, clearScope = false)
                clearBootstrapCheckpoint()
                if (!allowBootstrap) throw BootstrapRequiredException()
                if (retryExpiredToken) return syncInternal(
                    retryExpiredToken = false,
                    progress = progress,
                    allowBootstrap = true,
                    requestedKind = requestedKind,
                )
                error("Health Connect changes token expired during a fresh snapshot")
            }
            val envelopes = changes.changes.asSequence().mapNotNull { change ->
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record
                        total++
                        progress.recordProcessed()
                        encodedEnvelope(record)
                    }
                    is DeletionChange -> {
                        val id = change.recordId
                        total++
                        progress.recordProcessed()
                        // Health Connect's deletion change intentionally contains only the UID;
                        // the server resolves "unknown" against its durable raw-record index.
                        EncodedEnvelope("delete", "unknown", id, null)
                    }
                    else -> null
                }
            }
            val changeCount = changes.changes.size
            val requestRun = run.copy(
                processedRecords = (run.processedRecords + changeCount).coerceAtLeast(run.processedRecords),
                currentRecordType = "changes",
                completed = !changes.hasMore,
                durationMillis = if (!changes.hasMore) {
                    (System.currentTimeMillis() - parseRunStartMillis(run)).coerceAtLeast(0L)
                } else null,
            )
            sendChunks(
                connection,
                "changes",
                envelopes,
                commitToken = if (!changes.hasMore) changes.nextChangesToken else null,
                progress = progress,
                recordType = "changes",
                run = requestRun,
            )
            progress.pageCompleted("changes", "changes")
            run = updateRunContext(requestRun, progress.sentRecords, "changes")
            token = changes.nextChangesToken
            more = changes.hasMore
        } while (more)
        // The do/while always sends one final request, including for an empty changes page, so
        // its cursor and run completion are acknowledged exactly once.
        // Only publish the scope after the final token-bearing batch was acknowledged. A crash
        // before this point intentionally causes a replay under the old scope rather than a
        // cursor advance with no durable scope record.
        check(prefs.edit().putString(KEY_RECORD_SCOPE, scopeKey).commit()) { "Unable to persist Health Connect scope" }
        if (snapshotNeeded) clearBootstrapCheckpoint()
        return SyncSummary(if (snapshotNeeded) "snapshot" else "changes", total, Instant.now(), historyLimited, skippedTypes)
    }

    private suspend fun <T : Record> readPage(
        type: KClass<T>,
        snapshotStart: Instant,
        snapshotEnd: Instant,
        pageToken: String?,
    ): ReadRecordsResponse<T> = client.readRecords(
        ReadRecordsRequest(
            recordType = type,
            timeRangeFilter = TimeRangeFilter.between(snapshotStart, snapshotEnd),
            ascendingOrder = true,
            pageToken = pageToken,
            pageSize = READ_PAGE_SIZE,
            // The public JVM constant is 0; the Kotlin companion is internal in connect-client
            // 1.1.0, so keep the API value explicit here.
            deduplicateStrategy = 0,
        )
    )

    private fun readableRecordTypes(grantedPermissions: Set<String>): List<kotlin.reflect.KClass<out Record>> {
        return HealthRecordCatalog.recordTypes.filter { type ->
            val permissionGranted = HealthPermission.getReadPermission(type) in grantedPermissions
            val requiredFeature = when (type) {
                androidx.health.connect.client.records.PlannedExerciseSessionRecord::class ->
                    HealthConnectFeatures.FEATURE_PLANNED_EXERCISE
                androidx.health.connect.client.records.SkinTemperatureRecord::class ->
                    HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE
                androidx.health.connect.client.records.MindfulnessSessionRecord::class ->
                    HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
                else -> null
            }
            val featureAvailable = requiredFeature == null || runCatching {
                client.features.getFeatureStatus(requiredFeature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            }.getOrDefault(true)
            permissionGranted && featureAvailable
        }
    }

    private suspend fun sendChunks(
        connection: ServerConnection,
        mode: String,
        records: Sequence<EncodedEnvelope>,
        commitToken: String?,
        progress: SyncProgressTracker,
        recordType: String?,
        run: SyncRunContext,
    ) {
        val iterator = records.iterator()
        if (!iterator.hasNext()) {
            val body = stableRequest(mode, emptyList(), commitToken, run)
            persistPending(body, commitToken)
            sendPending(connection)
            progress.batchCompleted(0, "sending", recordType)
            return
        }
        val batch = ArrayList<EncodedEnvelope>(MAX_RECORDS_PER_BATCH)
        var pending: EncodedEnvelope? = iterator.next()
        while (pending != null) {
            batch.clear()
            val current = pending ?: break
            batch += current
            pending = null
            while (batch.size < MAX_RECORDS_PER_BATCH && iterator.hasNext()) {
                val candidate = iterator.next()
                batch += candidate
                val candidateBody = stableRequest(mode, batch, null, run)
                if (candidateBody.toByteArray(Charsets.UTF_8).size > TARGET_BODY_BYTES) {
                    batch.removeAt(batch.lastIndex)
                    pending = candidate
                    break
                }
            }
            val hasMore = pending != null || iterator.hasNext()
            val requestRun = if (hasMore && run.completed) {
                // A final provider page may still be split by the byte/record cap. Only the last
                // HTTP batch is allowed to make the cloud run appear complete.
                run.copy(completed = false, durationMillis = null)
            } else run
            val body = stableRequest(mode, batch, if (!hasMore) commitToken else null, requestRun)
            require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "A Health Connect record exceeds the 8 MiB batch limit" }
            persistPending(body, if (!hasMore) commitToken else null)
            sendPending(connection)
            progress.batchCompleted(batch.size, "sending", recordType)
        }
    }

    private suspend fun sendPending(connection: ServerConnection) {
        val body = prefs.getString(KEY_PENDING_BODY, null) ?: return
        val commitToken = prefs.getString(KEY_PENDING_TOKEN, null)
        val response = transport.send(connection, body)
        val expectedBatch = Json.parseToJsonElement(body).jsonObject["batch_id"]?.toString()?.trim('"')
        check(response.batch_id == null || response.batch_id == expectedBatch) { "Server acknowledged a different batch" }
        if (commitToken != null && response.next_change_token != null) {
            check(response.next_change_token == commitToken) { "Server acknowledged a different change token" }
        }
        val acknowledgedToken = if (commitToken != null) response.next_change_token ?: commitToken else null
        val edit = prefs.edit().remove(KEY_PENDING_BODY).remove(KEY_PENDING_TOKEN)
        if (acknowledgedToken != null) edit.putString(KEY_CHANGE_TOKEN, acknowledgedToken)
        // A cursor is a commit point. apply() may leave it only in the process memory cache when
        // Android kills the worker, so use a synchronous disk commit for this tiny critical state.
        check(edit.commit()) { "Unable to persist acknowledged Health Connect cursor" }
    }

    private fun stableRequest(mode: String, records: List<EncodedEnvelope>, token: String?, run: SyncRunContext): String {
        val canonical = encodeRequest(mode, "", records, token, run)
        val digest = BatchIdPolicy.digest(batchScopeId(), canonical)
        return encodeRequest(mode, "android-$digest", records, token, run)
    }

    private fun encodeRequest(
        mode: String,
        batchId: String,
        records: List<EncodedEnvelope>,
        token: String?,
        run: SyncRunContext? = null,
    ): String {
        val out = StringBuilder()
        out.append('{')
            .append("\"protocol_version\":").append(HEALTH_SYNC_PROTOCOL_VERSION)
            .append(",\"mode\":").append(quote(mode))
            .append(",\"batch_id\":").append(quote(batchId))
            .append(",\"records\":[")
        records.forEachIndexed { index, record ->
            if (index > 0) out.append(',')
            out.append('{').append("\"operation\":").append(quote(record.operation))
                .append(",\"record_type\":").append(quote(record.recordType))
                .append(",\"record_id\":").append(quote(record.recordId))
                .append(",\"payload\":").append(record.payload ?: "null").append('}')
        }
        out.append(']')
        out.append(",\"next_change_token\":").append(token?.let(::quote) ?: "null")
        run?.let {
            out.append(",\"run_id\":").append(quote(it.runId))
                .append(",\"run_kind\":").append(quote(it.runKind))
                .append(",\"run_started_at\":").append(quote(it.runStartedAt))
                .append(",\"run_total_records\":").append(it.totalRecords?.toString() ?: "null")
                .append(",\"run_processed_records\":").append(it.processedRecords)
                .append(",\"run_completed\":").append(it.completed)
                .append(",\"run_duration_ms\":").append(it.durationMillis?.toString() ?: "null")
                .append(",\"run_current_record_type\":").append(it.currentRecordType?.let(::quote) ?: "null")
        }
        out.append('}')
        return out.toString()
    }

    private fun quote(value: String): String = kotlinx.serialization.json.JsonPrimitive(value).toString()

    private fun encodedEnvelope(record: Record): EncodedEnvelope {
        val payload = StringBuilder()
        HealthRecordSerializer.writePayload(record, payload)
        return EncodedEnvelope(
            operation = "upsert",
            recordType = record::class.qualifiedName ?: record::class.java.name,
            recordId = record.metadata.id,
            payload = payload.toString(),
        )
    }

    private fun installationId(): String {
        prefs.getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString(KEY_INSTALLATION_ID, generated).commit()) {
            "Unable to persist Android installation identifier"
        }
        return generated
    }

    private fun batchScopeId(): String = credentialStore.load()?.deviceId?.takeIf { it.isNotBlank() }
        ?: installationId()

    private fun persistPending(body: String, token: String?) {
        val edit = prefs.edit().putString(KEY_PENDING_BODY, body).remove(KEY_PENDING_TOKEN)
        if (token != null) edit.putString(KEY_PENDING_TOKEN, token)
        check(edit.commit()) { "Unable to persist pending Health Connect batch" }
    }

    private fun loadBootstrapCheckpoint(): BootstrapCheckpoint? = runCatching {
        prefs.getString(KEY_BOOTSTRAP_CHECKPOINT, null)?.let { json.decodeFromString<BootstrapCheckpoint>(it) }
    }.getOrNull()

    private fun saveBootstrapCheckpoint(checkpoint: BootstrapCheckpoint) {
        check(prefs.edit().putString(KEY_BOOTSTRAP_CHECKPOINT, json.encodeToString(checkpoint)).commit()) {
            "Unable to persist Health Connect bootstrap checkpoint"
        }
    }

    private fun clearBootstrapCheckpoint() {
        check(prefs.edit().remove(KEY_BOOTSTRAP_CHECKPOINT).commit()) {
            "Unable to clear Health Connect bootstrap checkpoint"
        }
    }

    private fun requestedBootstrapKind(): String = prefs.getString(KEY_REQUESTED_RUN_KIND, null)
        ?.takeIf { it in SyncRunKind.values }
        ?: SyncRunKind.INITIAL

    private fun ensureRunContext(
        checkpoint: BootstrapCheckpoint?,
        requestedKind: String,
        totalRecords: Int?,
    ): SyncRunContext {
        checkpoint?.let {
            val context = SyncRunContext(
                runId = it.runId.ifBlank { UUID.randomUUID().toString() },
                runKind = it.runKind.takeIf { kind -> kind in SyncRunKind.values }
                    ?: requestedKind.takeIf { kind -> kind in SyncRunKind.values }
                    ?: SyncRunKind.INITIAL,
                runStartedAt = it.runStartedAt.ifBlank { Instant.now().toString() },
                totalRecords = totalRecords,
                processedRecords = it.uploadedRecords,
                currentRecordType = null,
            )
            saveRunContext(context)
            return context
        }
        loadRunContext()?.takeIf { !it.completed }?.let { return it }
        val context = SyncRunContext(
            runId = UUID.randomUUID().toString(),
            runKind = requestedKind.takeIf { it in SyncRunKind.values } ?: SyncRunKind.INCREMENTAL,
            runStartedAt = Instant.now().toString(),
            totalRecords = totalRecords,
        )
        saveRunContext(context)
        return context
    }

    private fun updateRunContext(context: SyncRunContext, processed: Int, recordType: String?): SyncRunContext {
        val next = context.copy(
            totalRecords = context.totalRecords,
            processedRecords = processed.coerceAtLeast(context.processedRecords),
            currentRecordType = recordType,
        )
        saveRunContext(next)
        return next
    }

    private fun parseRunStartMillis(context: SyncRunContext): Long = runCatching {
        Instant.parse(context.runStartedAt).toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }

    private fun loadRunContext(): SyncRunContext? = runCatching {
        prefs.getString(KEY_RUN_CONTEXT, null)?.let { json.decodeFromString<SyncRunContext>(it) }
    }.getOrNull()

    private fun saveRunContext(context: SyncRunContext) {
        check(prefs.edit().putString(KEY_RUN_CONTEXT, json.encodeToString(context)).commit()) {
            "Unable to persist sync run context"
        }
    }

    private fun clearRunContext() {
        check(prefs.edit().remove(KEY_RUN_CONTEXT).remove(KEY_REQUESTED_RUN_KIND).commit()) {
            "Unable to clear sync run context"
        }
    }

    private fun clearCursor(
        clearPending: Boolean = true,
        clearScope: Boolean = false,
    ) {
        val edit = prefs.edit().remove(KEY_CHANGE_TOKEN)
        if (clearPending) edit.remove(KEY_PENDING_BODY).remove(KEY_PENDING_TOKEN)
        if (clearScope) {
            edit.remove(KEY_RECORD_SCOPE).remove(KEY_BOOTSTRAP_CHECKPOINT)
                .remove(KEY_RUN_CONTEXT).remove(KEY_REQUESTED_RUN_KIND)
        }
        check(edit.commit()) { "Unable to clear Health Connect sync cursor" }
    }

    private fun deviceName(): String = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    companion object {
        private const val PREFS = "lossless_health_sync"
        private const val KEY_BOOTSTRAP_POLICY_VERSION = "bootstrap_policy_version"
        private const val KEY_CHANGE_TOKEN = "bootstrap_v2_change_token"
        private const val KEY_PENDING_BODY = "bootstrap_v2_pending_body"
        private const val KEY_PENDING_TOKEN = "bootstrap_v2_pending_token"
        private const val KEY_RECORD_SCOPE = "bootstrap_v2_record_scope"
        private const val KEY_BOOTSTRAP_CHECKPOINT = "bootstrap_v2_checkpoint"
        private const val KEY_SYNC_HISTORY = "sync_history"
        private const val KEY_RUN_CONTEXT = "bootstrap_v2_run_context"
        private const val KEY_REQUESTED_RUN_KIND = "bootstrap_v2_requested_run_kind"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private val CURRENT_SYNC_STATE_KEYS = listOf(
            KEY_CHANGE_TOKEN, KEY_PENDING_BODY, KEY_PENDING_TOKEN, KEY_RECORD_SCOPE,
            KEY_BOOTSTRAP_CHECKPOINT, KEY_RUN_CONTEXT, KEY_REQUESTED_RUN_KIND,
        )
        private val LEGACY_SYNC_STATE_KEYS = listOf(
            "change_token", "pending_body", "pending_token", "record_scope",
            "bootstrap_checkpoint", "sync_run_context", "requested_run_kind",
        )
        private const val MAX_RECORDS_PER_BATCH = 5_000
        // Stay well below the server's hard 8 MiB request ceiling. Large near-limit payloads
        // can take longer than a mobile HTTP call timeout to project; smaller batches make
        // retries and progress updates substantially more reliable.
        private const val TARGET_BODY_BYTES = 1 * 1024 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val READ_PAGE_SIZE = 100
    }

    private data class EncodedEnvelope(
        val operation: String,
        val recordType: String,
        val recordId: String,
        val payload: String?,
    )

}
