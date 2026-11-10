package app.healthquery.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val engine = LosslessHealthSyncEngine(applicationContext)
            try {
                engine.ensureBootstrapPolicyCurrent()
            } catch (_: Exception) {
                return@withContext Result.retry()
            }
            val runKind = inputData.getString(INPUT_RUN_KIND)
                ?.takeIf { it in SyncRunKind.values }
                ?: SyncRunKind.AUTOMATIC
            val summary = engine.syncIncremental(onProgress = { progress ->
                setProgressAsync(workDataOf(
                    "phase" to progress.phase,
                    "record_type" to progress.recordType,
                    "processed_records" to progress.processedRecords,
                    "sent_records" to progress.sentRecords,
                    "completed_pages" to progress.completedPages,
                    "completed_batches" to progress.completedBatches,
                    "elapsed_millis" to progress.elapsedMillis,
                    "overall_total_records" to progress.overallTotalRecords,
                    "overall_processed_records" to progress.overallProcessedRecords,
                    "current_type_total" to progress.currentTypeTotal,
                    "current_type_progress" to progress.currentTypeProgress,
                    "type_index" to progress.typeIndex,
                    "type_count" to progress.typeCount,
                    "eta_millis" to progress.etaMillis,
                ))
            }, runKind = runKind)
            if (summary?.mode == "not_ready") {
                (applicationContext as? LifeDashboardApplication)?.scheduleBootstrapSyncWork()
            }
            Result.success()
        } catch (e: BootstrapRequiredException) {
            (applicationContext as? LifeDashboardApplication)?.scheduleBootstrapSyncWork()
            Result.success()
        } catch (e: PermanentSyncException) {
            // The pending body/cursor remains intact for user recovery. A permanent server
            // response must not be converted into an endless WorkManager retry loop.
            Result.failure(workDataOf(
                "reason" to e.reason,
                "status_code" to e.statusCode,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A network outage, a temporarily unavailable Health Connect provider, or a 5xx
            // must leave the pending body/cursor intact and be retried by WorkManager.
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "health_sync_periodic_work"
        const val ONE_TIME_WORK_NAME = "health_sync_incremental_work"
        const val LEGACY_WORK_NAME = "health_sync_work"
        const val INPUT_RUN_KIND = "run_kind"
    }
}
