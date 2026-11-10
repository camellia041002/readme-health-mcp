package app.healthquery.sync

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class LifeDashboardApplication : Application() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)

        val engine = LosslessHealthSyncEngine(this)
        val migrated = runCatching { engine.ensureBootstrapPolicyCurrent() }.getOrDefault(false)
        if (migrated) {
            cancelSyncWork()
            if (engine.connected() != null) scheduleBootstrapSyncWork(replace = true)
        }

        // Periodic work is enabled only after the bounded bootstrap has committed its
        // final change token. Pairing enqueues the separate bootstrap job.
        scheduleHealthSyncWork()
    }

    fun scheduleHealthSyncWork() {
        if (!LosslessHealthSyncEngine(this).isBootstrapComplete()) {
            WorkManager.getInstance(this).cancelUniqueWork(HealthSyncWorker.WORK_NAME)
            WorkManager.getInstance(this).cancelUniqueWork(HealthSyncWorker.LEGACY_WORK_NAME)
            return
        }
        WorkManager.getInstance(this).cancelUniqueWork(HealthSyncWorker.LEGACY_WORK_NAME)
        val syncIntervalMinutes = preferencesManager.getHealthSyncIntervalMinutes()

        val syncWorkRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            repeatInterval = syncIntervalMinutes.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HealthSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWorkRequest
        )
    }

    /** Applies a client-owned cadence and starts a fresh periodic schedule from now. */
    fun updateHealthSyncFrequency(minutes: Int) {
        require(SyncFrequencies.optionFor(minutes) != null) { "Unsupported sync frequency: $minutes minutes" }
        preferencesManager.setHealthSyncIntervalMinutes(minutes)

        val manager = WorkManager.getInstance(this)
        manager.cancelUniqueWork(HealthSyncWorker.WORK_NAME)
        if (!LosslessHealthSyncEngine(this).isBootstrapComplete()) return

        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            repeatInterval = minutes.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        ).build()
        manager.enqueueUniquePeriodicWork(
            HealthSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }

    fun scheduleBootstrapSyncWork(replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BootstrapSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            BootstrapSyncWorker.WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedules the armed, non-destructive full re-sync after UI confirmation. */
    fun scheduleFullResyncWork(): Boolean {
        val engine = LosslessHealthSyncEngine(this)
        if (!engine.prepareFullResync()) return false
        val manager = WorkManager.getInstance(this)
        manager.cancelUniqueWork(HealthSyncWorker.WORK_NAME)
        manager.cancelUniqueWork(HealthSyncWorker.ONE_TIME_WORK_NAME)
        val request = OneTimeWorkRequestBuilder<BootstrapSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(
            BootstrapSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return true
    }

    fun scheduleIncrementalSyncWork() {
        if (!LosslessHealthSyncEngine(this).isBootstrapComplete()) {
            scheduleBootstrapSyncWork()
            return
        }
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(HealthSyncWorker.INPUT_RUN_KIND to SyncRunKind.MANUAL))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            HealthSyncWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelSyncWork() {
        val manager = WorkManager.getInstance(this)
        manager.cancelUniqueWork(BootstrapSyncWorker.WORK_NAME)
        manager.cancelUniqueWork(HealthSyncWorker.ONE_TIME_WORK_NAME)
        manager.cancelUniqueWork(HealthSyncWorker.WORK_NAME)
    }

}
