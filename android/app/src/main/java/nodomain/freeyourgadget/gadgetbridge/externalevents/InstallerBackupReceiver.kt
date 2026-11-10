package nodomain.freeyourgadget.gadgetbridge.externalevents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import nodomain.freeyourgadget.gadgetbridge.util.backup.InstallerBackupWorker
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * ADB-only bridge for creating and cleaning up the installer's temporary ZIP backup.
 *
 * The manifest requires android.permission.DUMP on callers. Normal third-party applications do not
 * hold that privileged permission, while adb shell does.
 */
class InstallerBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (!InstallerBackupWorker.isValidRequestId(requestId)) {
            LOG.warn("Ignoring installer backup request with invalid request id")
            return
        }
        val validRequestId = requestId!!

        when (intent.action) {
            ACTION_CREATE -> {
                if (!InstallerBackupWorker.prepareRequest(context, validRequestId)) {
                    LOG.error("Unable to prepare installer backup request {}", validRequestId)
                    return
                }

                val request = OneTimeWorkRequest.Builder(InstallerBackupWorker::class.java)
                    .setInputData(
                        Data.Builder()
                            .putString(InstallerBackupWorker.INPUT_REQUEST_ID, validRequestId)
                            .build()
                    )
                    .addTag(InstallerBackupWorker.WORK_TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    InstallerBackupWorker.uniqueWorkName(validRequestId),
                    ExistingWorkPolicy.KEEP,
                    request
                )
                LOG.info("Queued installer backup request {}", validRequestId)
            }

            ACTION_CLEANUP -> {
                if (InstallerBackupWorker.cleanupRequest(context, validRequestId)) {
                    LOG.info("Cleaned installer backup request {}", validRequestId)
                } else {
                    LOG.warn("Installer backup cleanup was incomplete for {}", validRequestId)
                }
            }

            else -> LOG.warn("Ignoring unknown installer backup action {}", intent.action)
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(InstallerBackupReceiver::class.java)

        const val ACTION_CREATE =
            "nodomain.freeyourgadget.gadgetbridge.toge.command.CREATE_INSTALLER_BACKUP"
        const val ACTION_CLEANUP =
            "nodomain.freeyourgadget.gadgetbridge.toge.command.CLEANUP_INSTALLER_BACKUP"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
