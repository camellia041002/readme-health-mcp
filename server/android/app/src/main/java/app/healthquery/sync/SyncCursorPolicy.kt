package app.healthquery.sync

/** Small pure state machine documenting the commit boundary used by the WorkManager lane. */
data class PendingSyncBatch(val body: String, val nextChangeToken: String)

object SyncCursorPolicy {
    fun prepare(body: String, nextChangeToken: String): PendingSyncBatch =
        PendingSyncBatch(body, nextChangeToken)

    /** A failed transport returns the same pending batch; callers must not advance the cursor. */
    fun afterFailure(pending: PendingSyncBatch): PendingSyncBatch = pending

    fun afterAck(pending: PendingSyncBatch, acknowledgedToken: String?): String =
        acknowledgedToken ?: pending.nextChangeToken
}
