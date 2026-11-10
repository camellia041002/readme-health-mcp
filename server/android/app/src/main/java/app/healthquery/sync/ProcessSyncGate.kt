package app.healthquery.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide single-flight gate for the Health Connect -> server transaction.
 *
 * WorkManager and the foreground screen can each construct an engine instance, so an engine
 * instance lock is insufficient. Mutex.withLock is cancellable while queued and while executing.
 */
object ProcessSyncGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
