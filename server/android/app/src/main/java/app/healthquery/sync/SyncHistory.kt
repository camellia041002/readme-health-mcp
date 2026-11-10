package app.healthquery.sync

import kotlinx.serialization.Serializable

/** A small, secret-free status item kept locally for the connected companion screen. */
@Serializable
data class SyncHistoryEntry(
    val timestampMillis: Long,
    val success: Boolean,
    val mode: String? = null,
    val records: Int? = null,
    val error: String? = null,
    val runKind: String? = null,
    val durationMillis: Long? = null,
    val runId: String? = null,
)

object SyncHistoryPolicy {
    const val MAX_ENTRIES = 30

    fun append(current: List<SyncHistoryEntry>, entry: SyncHistoryEntry): List<SyncHistoryEntry> =
        (listOf(entry) + current).take(MAX_ENTRIES)

    /**
     * The connected-screen timestamp and history must describe the same completed run.
     * Acknowledged intermediate batches are durable progress, but are not a successful sync.
     */
    fun lastSuccessfulTimestamp(entries: List<SyncHistoryEntry>): Long? =
        entries.firstOrNull { it.success }?.timestampMillis

    /** Keep status useful without allowing exception text to become an accidental secret sink. */
    fun safeError(error: Throwable): String {
        val kind = error::class.simpleName ?: "Error"
        val detail = error.message.orEmpty()
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer <redacted>")
            .replace(Regex("(?i)(token|secret|password)=?[^\\s,;]+"), "$1=<redacted>")
            .trim()
        return "$kind${if (detail.isEmpty()) "" else ": $detail"}".take(240)
    }
}
