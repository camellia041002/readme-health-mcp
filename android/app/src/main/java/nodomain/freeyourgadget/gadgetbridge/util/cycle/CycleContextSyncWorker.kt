/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util.cycle

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthEndpoint
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthUploadResult
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthUploader
import org.json.JSONObject

/** Uploads only the small cycle configuration, never any Huawei protocol data. */
class CycleContextSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val prefs = GBApplication.getPrefs()
        val clearing = inputData.getBoolean(INPUT_CLEAR, false)

        val url = SelfHostedHealthEndpoint.cycle(prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_URL, null))
        val token = SelfHostedHealthUploader.sanitizeToken(
            prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_TOKEN, null)
        )
        if (url == null || token.isEmpty()) {
            // Without a server address nothing was ever uploaded, so a pending clear has nothing
            // left to chase and would otherwise warn about a copy that does not exist. Resolving
            // it here trades a rare miss (a server configured, then its address cleared, then the
            // feature switched off) for not crying wolf in the common case.
            if (clearing) CycleContextStore.resolvePendingClear()
            return Result.failure()
        }

        val payload = if (clearing) {
            JSONObject().put("enabled", false)
        } else {
            val config = CycleContextStore.config() ?: return Result.success()
            JSONObject()
                .put("enabled", true)
                .put("last_start", config.lastStart.toString())
                .put("cycle_length_days", config.cycleLengthDays)
                .put("cycle_period_days", config.periodDays)
                .apply {
                    config.lastConfirmed?.let { put("last_confirmed", it.toString()) }
                }
        }

        return when (val result = SelfHostedHealthUploader().upload(url, token, payload.toString())) {
            is SelfHostedHealthUploadResult.Success -> {
                // Any accepted upload settles a pending clear: the server stores this config as a
                // whole file, so a fresh config overwrites the copy we were trying to delete just
                // as surely as the deletion itself would have.
                CycleContextStore.resolvePendingClear()
                Result.success()
            }
            is SelfHostedHealthUploadResult.Failure -> {
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "CycleContextSyncWorker"
        private const val INPUT_CLEAR = "clear"
        private const val MAX_ATTEMPTS = 5

        @JvmStatic
        @JvmOverloads
        fun enqueue(context: Context, clear: Boolean = false) {
            val request = OneTimeWorkRequest.Builder(CycleContextSyncWorker::class.java)
                .setInputData(Data.Builder().putBoolean(INPUT_CLEAR, clear).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
