package app.healthquery.sync

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Fixed, privacy-first bootstrap policy for recent health context. */
object BootstrapWindowPolicy {
    const val VERSION = 2
    const val DAYS = 30L

    fun snapshotStart(snapshotEnd: Instant): Instant = snapshotEnd.minus(DAYS, ChronoUnit.DAYS)

    fun requiresMigration(storedVersion: Int): Boolean = storedVersion < VERSION
}
