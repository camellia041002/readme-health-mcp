package app.healthquery.sync

/** Distinguishes a real, permitted empty sync from a run that could not access Health Connect. */
object SyncSummaryPolicy {
    private val nonSuccessModes = setOf("no_access", "not_ready")

    fun isSuccessful(summary: SyncSummary): Boolean = summary.mode !in nonSuccessModes

    fun errorFor(summary: SyncSummary): String? =
        if (isSuccessful(summary)) null else "Health Connect permissions required"
}
