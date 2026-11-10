package app.healthquery.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-running, resumable 30-day import. The engine checkpoint is the source of truth. */
class BootstrapSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val engine = LosslessHealthSyncEngine(applicationContext)
            try {
                engine.ensureBootstrapPolicyCurrent()
            } catch (_: Exception) {
                return@withContext Result.retry()
            }
            val healthConnect = HealthConnectManager(applicationContext)
            if (!healthConnect.isHealthConnectAvailable()) {
                return@withContext Result.failure(workDataOf("reason" to "health_connect_unavailable"))
            }
            val permissionsGranted = try {
                healthConnect.hasPermissions(healthConnect.providerSupportedReadPermissions())
            } catch (_: Exception) {
                false
            }
            if (!permissionsGranted) {
                // Missing grants are an actionable user state, not a transient network/provider
                // failure. Returning RETRY here leaves the companion showing an endless import.
                return@withContext Result.failure(workDataOf("reason" to "health_connect_permissions_required"))
            }
            setForeground(foregroundInfo())
            val summary = engine.bootstrap { progress ->
                setForegroundAsync(foregroundInfo(progress))
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
            }
            if (summary == null) return@withContext Result.failure()
            if (summary.mode == "no_access") {
                return@withContext Result.failure(workDataOf("reason" to "health_connect_permissions_required"))
            }
            if (!engine.isBootstrapComplete()) return@withContext Result.retry()
            (applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()
            Result.success()
        } catch (e: PermanentSyncException) {
            // Keep the resumable checkpoint and pending body/cursor untouched so the user can
            // correct the server-side issue and retry from the same point.
            Result.failure(workDataOf(
                "reason" to e.reason,
                "status_code" to e.statusCode,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun foregroundInfo(progress: SyncProgress? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "健康数据导入", NotificationManager.IMPORTANCE_LOW))
        val percent = progress?.overallTotalRecords?.takeIf { it > 0 }?.let {
            ((progress.overallProcessedRecords * 100L) / it).coerceIn(0L, 100L).toInt()
        }
        val text = when {
            percent != null -> "最近 30 天数据已完成 $percent%"
            progress != null -> "正在处理健康数据"
            else -> "正在导入最近 30 天的健康数据"
        }
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("健康同步")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "health_bootstrap_work"
        private const val CHANNEL_ID = "health_bootstrap"
        private const val NOTIFICATION_ID = 7401
    }
}
