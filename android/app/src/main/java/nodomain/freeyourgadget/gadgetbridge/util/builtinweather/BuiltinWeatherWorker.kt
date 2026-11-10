package nodomain.freeyourgadget.gadgetbridge.util.builtinweather

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class BuiltinWeatherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = GBApplication.getPrefs()
        if (!prefs.getBoolean(GBPrefs.BUILTIN_WEATHER_ENABLED, false)) return Result.success()

        return when (val result = BuiltinWeatherFetcher.fetch(applicationContext)) {
            is BuiltinWeatherFetcher.FetchResult.Success -> {
                prefs.preferences.edit().putString(
                    GBPrefs.BUILTIN_WEATHER_STATUS,
                    applicationContext.getString(R.string.builtin_weather_status_updated, result.spec.location ?: "")
                ).apply()
                if (nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService.isRunning(applicationContext)) {
                    GBApplication.deviceService().onSendWeather()
                }
                Result.success()
            }
            BuiltinWeatherFetcher.FetchResult.NoLocation -> {
                prefs.preferences.edit().putString(
                    GBPrefs.BUILTIN_WEATHER_STATUS,
                    applicationContext.getString(R.string.builtin_weather_status_no_location)
                ).apply()
                Result.success()
            }
            is BuiltinWeatherFetcher.FetchResult.RetryableFailure -> {
                prefs.preferences.edit().putString(
                    GBPrefs.BUILTIN_WEATHER_STATUS,
                    applicationContext.getString(R.string.builtin_weather_status_failed, result.message)
                ).apply()
                Result.retry()
            }
            is BuiltinWeatherFetcher.FetchResult.Failure -> {
                prefs.preferences.edit().putString(
                    GBPrefs.BUILTIN_WEATHER_STATUS,
                    applicationContext.getString(R.string.builtin_weather_status_failed, result.message)
                ).apply()
                Result.failure()
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(BuiltinWeatherWorker::class.java)
        private const val WORK_NAME = "BuiltinWeatherWorker_Periodic"
        const val WORK_TAG = "BuiltinWeatherWorker"

        @JvmStatic
        @JvmOverloads
        fun reschedule(context: Context, enabled: Boolean = GBApplication.getPrefs().getBoolean(GBPrefs.BUILTIN_WEATHER_ENABLED, false)) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequest.Builder(BuiltinWeatherWorker::class.java, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK_TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            LOG.info("Built-in weather refresh scheduled hourly")
        }

        @JvmStatic
        fun executeNow(context: Context) {
            if (!GBApplication.getPrefs().getBoolean(GBPrefs.BUILTIN_WEATHER_ENABLED, false)) {
                return
            }
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequest.Builder(BuiltinWeatherWorker::class.java)
                    .addTag(WORK_TAG)
                    .build()
            )
        }
    }
}
