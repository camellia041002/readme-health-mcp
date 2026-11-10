package app.healthquery.sync

import kotlinx.serialization.Serializable

/**
 * Privacy-safe bootstrap state.  This is deliberately made up of cursors, counts, and run
 * metadata only; Health Connect values and credentials must never be written here.
 *
 * [providerPageToken] is the cursor for the current phase/type.  A page is replay-safe: the
 * cursor is advanced only after all batches from that page have been acknowledged by the server.
 */
@Serializable
data class BootstrapCheckpoint(
    val snapshotStartMillis: Long,
    val snapshotEndMillis: Long,
    val scopeKey: String,
    val baseChangeToken: String,
    val recordTypeIndex: Int,
    val providerPageToken: String? = null,
    val phase: String = BootstrapPhase.INVENTORY,
    val inventoryCounts: Map<String, Int> = emptyMap(),
    val totalRecords: Int = 0,
    val uploadedRecords: Int = 0,
    val runId: String = "",
    val runKind: String = SyncRunKind.INITIAL,
    val runStartedAt: String = "",
)

object BootstrapPhase {
    const val INVENTORY = "inventory"
    const val UPLOAD = "upload"
    const val CHANGES = "changes"
}

object SyncRunKind {
    const val INITIAL = "initial"
    const val INCREMENTAL = "incremental"
    const val AUTOMATIC = "automatic"
    const val MANUAL = "manual"
    const val FULL = "full"

    val values: Set<String> = setOf(INITIAL, INCREMENTAL, AUTOMATIC, MANUAL, FULL)
}

/** Stable context for one sync transaction, persisted until its final ACK. */
@Serializable
data class SyncRunContext(
    val runId: String,
    val runKind: String,
    val runStartedAt: String,
    val totalRecords: Int? = null,
    val processedRecords: Int = 0,
    val completed: Boolean = false,
    val durationMillis: Long? = null,
    val currentRecordType: String? = null,
) {
    init {
        require(runId.isNotBlank())
        require(runKind in SyncRunKind.values)
        require(runStartedAt.isNotBlank())
        require(processedRecords >= 0)
        require(totalRecords == null || totalRecords >= 0)
    }
}

object BootstrapCheckpointPolicy {
    fun canResume(checkpoint: BootstrapCheckpoint, scopeKey: String): Boolean =
        checkpoint.scopeKey == scopeKey && checkpoint.snapshotStartMillis < checkpoint.snapshotEndMillis &&
            checkpoint.baseChangeToken.isNotBlank() && checkpoint.recordTypeIndex >= 0 &&
            checkpoint.phase in setOf(BootstrapPhase.INVENTORY, BootstrapPhase.UPLOAD, BootstrapPhase.CHANGES) &&
            checkpoint.inventoryCounts.values.all { it >= 0 } && checkpoint.totalRecords >= 0 &&
            checkpoint.uploadedRecords >= 0 &&
            (checkpoint.runId.isBlank() || SyncRunKind.values.contains(checkpoint.runKind))

    fun nextPage(checkpoint: BootstrapCheckpoint, providerPageToken: String?): BootstrapCheckpoint =
        checkpoint.copy(providerPageToken = providerPageToken)

    fun nextType(checkpoint: BootstrapCheckpoint): BootstrapCheckpoint =
        checkpoint.copy(recordTypeIndex = checkpoint.recordTypeIndex + 1, providerPageToken = null)

    fun inventoryPage(
        checkpoint: BootstrapCheckpoint,
        recordType: String,
        pageCount: Int,
        nextPageToken: String?,
    ): BootstrapCheckpoint {
        require(pageCount >= 0)
        val counts = checkpoint.inventoryCounts.toMutableMap()
        counts[recordType] = (counts[recordType] ?: 0) + pageCount
        return checkpoint.copy(
            phase = BootstrapPhase.INVENTORY,
            inventoryCounts = counts,
            totalRecords = checkpoint.totalRecords + pageCount,
            providerPageToken = nextPageToken,
        )
    }

    fun beginUpload(checkpoint: BootstrapCheckpoint): BootstrapCheckpoint = checkpoint.copy(
        phase = BootstrapPhase.UPLOAD,
        recordTypeIndex = 0,
        providerPageToken = null,
        uploadedRecords = 0,
    )

    fun uploadPage(
        checkpoint: BootstrapCheckpoint,
        pageCount: Int,
        nextPageToken: String?,
    ): BootstrapCheckpoint {
        require(pageCount >= 0)
        val uploadedRecords = checkpoint.uploadedRecords + pageCount
        return checkpoint.copy(
            phase = BootstrapPhase.UPLOAD,
            uploadedRecords = uploadedRecords,
            // Health Connect is live rather than a database snapshot. A record can be inserted
            // inside the fixed time range between inventory and upload; keep progress resumable
            // and expand the denominator until the final uploaded count is known.
            totalRecords = maxOf(checkpoint.totalRecords, uploadedRecords),
            providerPageToken = nextPageToken,
        )
    }
}

object BootstrapWorkPolicy {
    fun periodicAllowed(changeToken: String?, checkpoint: BootstrapCheckpoint?): Boolean =
        !changeToken.isNullOrBlank() && checkpoint == null

    fun manualLane(changeToken: String?, checkpoint: BootstrapCheckpoint?): String =
        if (periodicAllowed(changeToken, checkpoint)) "incremental" else "bootstrap"
}
