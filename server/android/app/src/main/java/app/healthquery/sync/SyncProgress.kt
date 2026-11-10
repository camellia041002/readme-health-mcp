package app.healthquery.sync

import java.time.Instant

data class SyncProgress(
    val phase: String,
    val recordType: String? = null,
    val processedRecords: Int = 0,
    val sentRecords: Int = 0,
    val completedPages: Int = 0,
    val completedBatches: Int = 0,
    val elapsedMillis: Long = 0L,
    val overallTotalRecords: Int? = null,
    val overallProcessedRecords: Int = processedRecords,
    val currentTypeTotal: Int? = null,
    val currentTypeProgress: Int = 0,
    val typeIndex: Int? = null,
    val typeCount: Int? = null,
    val etaMillis: Long? = null,
    val runId: String? = null,
    val runKind: String? = null,
    val runStartedAt: String? = null,
)

object SyncProgressPolicy {
    const val MIN_EMIT_INTERVAL_MILLIS = 250L

    fun shouldEmit(nowMillis: Long, lastEmitMillis: Long, force: Boolean = false): Boolean =
        force || lastEmitMillis < 0L || nowMillis - lastEmitMillis >= MIN_EMIT_INTERVAL_MILLIS

    /** Returns null until a meaningful rate/total exists, avoiding noisy or infinite ETAs. */
    fun etaMillis(total: Int?, processed: Int, elapsedMillis: Long, baselineProcessed: Int = 0): Long? {
        val measuredRecords = processed - baselineProcessed
        if (total == null || total <= 0 || measuredRecords <= 0 || elapsedMillis <= 0 || processed >= total) return null
        return ((total - processed).toDouble() * elapsedMillis / measuredRecords.toDouble()).toLong()
            .coerceAtLeast(0L)
    }
}

internal class SyncProgressTracker(private val callback: ((SyncProgress) -> Unit)?) {
    private val startedAtMillis = System.currentTimeMillis()
    private var rateStartedAtMillis = startedAtMillis
    private var rateBaselineProcessed = 0
    private var lastEmitMillis = -1L
    var processedRecords: Int = 0
        private set
    var sentRecords: Int = 0
        private set
    var completedPages: Int = 0
        private set
    var completedBatches: Int = 0
        private set

    private var phase: String = "starting"
    private var recordType: String? = null
    private var overallTotalRecords: Int? = null
    private var currentTypeTotal: Int? = null
    private var currentTypeProgress: Int = 0
    private var typeIndex: Int? = null
    private var typeCount: Int? = null
    private var runId: String? = null
    private var runKind: String? = null
    private var runStartedAt: String? = null

    fun emit(phase: String, recordType: String? = null, force: Boolean = false) {
        this.phase = phase
        if (recordType != null) this.recordType = recordType
        val now = System.currentTimeMillis()
        if (callback == null || !SyncProgressPolicy.shouldEmit(now, lastEmitMillis, force)) return
        lastEmitMillis = now
        val elapsed = runStartedAt?.let { value ->
            runCatching { now - Instant.parse(value).toEpochMilli() }.getOrNull()
        }?.coerceAtLeast(0L) ?: (now - startedAtMillis).coerceAtLeast(0L)
        val rateElapsed = (now - rateStartedAtMillis).coerceAtLeast(0L)
        val eta = SyncProgressPolicy.etaMillis(
            overallTotalRecords,
            processedRecords,
            rateElapsed,
            rateBaselineProcessed,
        )
        callback.invoke(
            SyncProgress(
                phase = this.phase,
                recordType = this.recordType,
                processedRecords = processedRecords,
                sentRecords = sentRecords,
                completedPages = completedPages,
                completedBatches = completedBatches,
                elapsedMillis = elapsed,
                overallTotalRecords = overallTotalRecords,
                overallProcessedRecords = processedRecords,
                currentTypeTotal = currentTypeTotal,
                currentTypeProgress = currentTypeProgress,
                typeIndex = typeIndex,
                typeCount = typeCount,
                etaMillis = eta,
                runId = runId,
                runKind = runKind,
                runStartedAt = runStartedAt,
            )
        )
    }

    fun configure(
        totalRecords: Int?,
        currentTypeTotal: Int? = null,
        typeIndex: Int? = null,
        typeCount: Int? = null,
        run: SyncRunContext? = null,
    ) {
        overallTotalRecords = totalRecords
        this.currentTypeTotal = currentTypeTotal
        this.typeIndex = typeIndex
        this.typeCount = typeCount
        run?.let {
            runId = it.runId
            runKind = it.runKind
            runStartedAt = it.runStartedAt
        }
    }

    fun restore(processed: Int, sent: Int = 0) {
        processedRecords = processed.coerceAtLeast(0)
        sentRecords = sent.coerceAtLeast(0)
        rateBaselineProcessed = processedRecords
        rateStartedAtMillis = System.currentTimeMillis()
    }

    fun resetProcessed(sent: Int = sentRecords) {
        processedRecords = 0
        sentRecords = sent.coerceAtLeast(0)
        currentTypeProgress = 0
        rateBaselineProcessed = 0
        rateStartedAtMillis = System.currentTimeMillis()
    }

    fun currentType(total: Int?, index: Int?, count: Int?, type: String?) {
        currentTypeTotal = total
        currentTypeProgress = 0
        typeIndex = index
        typeCount = count
        if (type != null) recordType = type
    }

    fun recordProcessed(count: Int = 1) {
        require(count >= 0)
        processedRecords += count
        currentTypeProgress += count
    }

    fun pageCompleted(phase: String, recordType: String?) {
        completedPages++
        emit(phase, recordType)
    }

    fun batchCompleted(count: Int, phase: String, recordType: String?) {
        require(count >= 0)
        sentRecords += count
        completedBatches++
        emit(phase, recordType)
    }
}
