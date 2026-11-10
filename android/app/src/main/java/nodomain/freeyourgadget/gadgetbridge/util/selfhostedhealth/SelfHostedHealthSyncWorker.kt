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
package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

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
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextStore
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextSyncWorker
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryConnectionStore
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectHealthQuerySync
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryPayloadMapper
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransport
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransportResult
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads the samples Gadgetbridge already stores and posts them to the user's own health server.
 *
 * Replaces the Health Connect -> third party webhook chain for that one destination: same data, one
 * process, no Google component in the path. The Health Connect sync is untouched and stays useful
 * for anyone feeding other apps from it.
 */
class SelfHostedHealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = GBApplication.getPrefs()

        // A cycle deletion that never reached the server has no other way back: the local values
        // are already gone, so nothing but this periodic pass would ever retry it. Checked before
        // the enabled guard because the leftover copy outlives the sync being switched off.
        if (CycleContextStore.pendingClear()) {
            CycleContextSyncWorker.enqueue(applicationContext, clear = true)
        }

        if (!prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false)) {
            LOG.info("Self-hosted health sync is disabled, skipping")
            return Result.success()
        }

        val connection = HealthQueryConnectionStore(applicationContext).load()
        if (connection == null) {
            LOG.warn("HealthQuery sync is enabled but no encrypted connection is configured")
            storeStatus(applicationContext.getString(R.string.selfhosted_health_status_not_configured))
            return Result.success()
        }

        // ReadMe coexistence mode has no Bluetooth permission. Huawei Health / Health Sync write
        // to Health Connect; this process only reads the approved core types and uploads them.
        return when (val outcome = HealthConnectHealthQuerySync.run(applicationContext, connection)) {
            is HealthConnectHealthQuerySync.Outcome.Success -> {
                val timestamp = TIME_FORMAT.format(ZonedDateTime.now())
                storeStatus(applicationContext.getString(
                    R.string.selfhosted_health_status_success, timestamp, outcome.uploadedDays
                ))
                Result.success()
            }
            HealthConnectHealthQuerySync.Outcome.PermissionRequired -> {
                storeStatus(applicationContext.getString(R.string.health_connect_no_permissions))
                Result.success()
            }
            is HealthConnectHealthQuerySync.Outcome.Failure -> {
                val timestamp = TIME_FORMAT.format(ZonedDateTime.now())
                storeStatus(applicationContext.getString(
                    R.string.selfhosted_health_status_failed, timestamp, outcome.category
                ))
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }

        /* BLE-backed legacy path retained as source history, unreachable in coexistence builds.

        val requestedAddress = inputData.getString(INPUT_DEVICE_ADDRESS)
        val devices = selectedDevices(prefs, requestedAddress)
        if (devices.isEmpty()) {
            LOG.info("No devices selected for self-hosted health sync")
            storeStatus(applicationContext.getString(R.string.selfhosted_health_status_no_devices))
            return Result.success()
        }

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000L
        val uploader = HealthQueryTransport(applicationContext)
        // "Upload now" sets this; event-driven and periodic runs leave it false.
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        // One entry per posted day, written to the sync log in a single batch at the end.
        val logEntries = mutableListOf<SelfHostedHealthLogEntry>()

        var uploadedDays = 0
        var retryable = false
        var failure: String? = null

        for (device in devices) {
            val address = device.address
            val deviceName = device.aliasOrName
            val cursor = prefs.getLong(cursorKey(address), 0L)
            val sleepCursor = prefs.getLong(sleepCursorKey(address), 0L)
            val windowStart = windowStart(prefs, cursor, now, zone)

            // Reading and packaging share one guard: neither should be able to throw past doWork,
            // or WorkManager records a bare failure and this screen keeps showing the stale status.
            val samples: List<ActivitySample>
            val payload: SelfHostedHealthPayloadSet
            try {
                samples = readSamples(device, windowStart, now)
                val legacy = SelfHostedHealthPayload.build(samples, zone, sleepCursor, now)
                payload = SelfHostedWorkoutSync.attach(
                    legacy, { SelfHostedWorkoutReader.read(device, windowStart, now) }, zone, now,
                    prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_WORKOUTS_ENABLED, false)
                )
            } catch (e: Exception) {
                LOG.warn("Could not prepare HealthQuery payload ({})", e.javaClass.simpleName)
                failure = "prepare_error"
                retryable = true
                continue
            }

            LOG.info(
                "HealthQuery sync prepared {} sample(s) across {} day payload(s)",
                samples.size, payload.days.size
            )

            var allSucceeded = true
            for ((date, request) in HealthQueryPayloadMapper.buildDayRequests(payload.days, zone, connection.deviceId)) {
                val started = System.currentTimeMillis()
                val result = uploader.upload(connection, request)
                val succeeded = result is HealthQueryTransportResult.Success
                val category = (result as? HealthQueryTransportResult.Failure)?.category
                logEntries.add(SelfHostedHealthLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    deviceName = deviceName,
                    url = connection.serverUrl,
                    date = date,
                    manual = manual,
                    records = request.getJSONArray("records").length(),
                    httpCode = 0,
                    responseTimeMs = System.currentTimeMillis() - started,
                    success = succeeded,
                    message = category
                ))
                when (result) {
                    is HealthQueryTransportResult.Success -> {
                        uploadedDays++
                        // Persist progress after every accepted day. A retry re-covers 24 hours,
                        // so late-arriving data and the last accepted day remain idempotently safe.
                        prefs.preferences.edit()
                            .putLong(cursorKey(address), acceptedDayCursor(date, zone, now))
                            .apply()
                    }
                    is HealthQueryTransportResult.Failure -> {
                        LOG.warn("HealthQuery upload failed ({})", result.category)
                        failure = result.category
                        retryable = retryable || result.retryable
                        allSucceeded = false
                        break
                    }
                }
            }

            // Cursors move only on a clean run for this device. Steps and heart rate are safe to
            // re-send, and a sleep session that was not confirmed must stay eligible.
            if (allSucceeded) {
                val editor = GBApplication.getPrefs().preferences.edit()
                editor.putLong(cursorKey(address), now)
                if (payload.sleepUploadedThrough > sleepCursor) {
                    editor.putLong(sleepCursorKey(address), payload.sleepUploadedThrough)
                }
                editor.apply()
            }
        }

        SelfHostedHealthLog.append(applicationContext, logEntries)

        val timestamp = TIME_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), zone))
        if (failure == null) {
            storeStatus(
                applicationContext.getString(
                    R.string.selfhosted_health_status_success, timestamp, uploadedDays
                )
            )
            return Result.success()
        }

        storeStatus(
            applicationContext.getString(R.string.selfhosted_health_status_failed, timestamp, failure)
        )
        return if (retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        */
    }

    /**
     * Lower bound of the samples to read.
     *
     * Always a local midnight: the server keeps the larger step total per day, so a window that
     * starts mid-day would report a partial total for that day and, on a first run, leave it there.
     * The look-back re-covers recent days because a band delivers data late and out of order.
     */
    private fun windowStart(prefs: GBPrefs, cursor: Long, now: Long, zone: ZoneId): Long {
        val initial = prefs.getLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, 0L)
        val fromCursor = if (cursor > 0L) cursor - LOOK_BACK_SECONDS else initial
        val start = maxOf(fromCursor, initial, now - MAX_WINDOW_SECONDS)
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(start), zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toEpochSecond()
    }

    private fun readSamples(device: GBDevice, fromTs: Long, toTs: Long): List<ActivitySample> {
        return GBApplication.acquireDbReadOnly().use { db ->
            val provider = device.deviceCoordinator.getSampleProvider(device, db.daoSession)
                ?: return@use emptyList()
            @Suppress("UNCHECKED_CAST")
            provider.getAllActivitySamples(fromTs.toInt(), toTs.toInt()) as List<ActivitySample>
        }
    }

    private fun selectedDevices(prefs: GBPrefs, requestedAddress: String?): List<GBDevice> {
        val selected = prefs.getStringSet(GBPrefs.SELF_HOSTED_HEALTH_DEVICE_SELECTION, emptySet())
            .orEmpty()
            .map { it.uppercase(Locale.ROOT) }
            .toSet()
        if (selected.isEmpty()) {
            return emptyList()
        }
        return GBApplication.app().deviceManager.devices.filter { device ->
            val address = device.address?.uppercase(Locale.ROOT) ?: return@filter false
            address in selected &&
                (requestedAddress == null || address == requestedAddress.uppercase(Locale.ROOT))
        }
    }

    private fun storeStatus(status: String) {
        GBApplication.getPrefs().preferences.edit()
            .putString(GBPrefs.SELF_HOSTED_HEALTH_STATUS, status)
            .apply()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SelfHostedHealthSyncWorker::class.java)
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        const val INPUT_DEVICE_ADDRESS = "device_address"
        /** True when the run was started by the "Upload now" button, so the log can label it manual. */
        const val INPUT_MANUAL = "manual"
        const val WORK_TAG = "SelfHostedHealthSyncWorker"

        /** Unique name for the optional periodic upload, so scheduling it twice just updates it. */
        private const val PERIODIC_WORK_NAME = "SelfHostedHealthSyncWorker_Periodic"

        /**
         * Brings the periodic upload in line with the current settings, and is safe to call any
         * number of times: it cancels the schedule when the feature is off or the interval is 0, and
         * otherwise (re)installs one unique periodic work. Called on every settings change and once
         * when the service starts, so a schedule lost to a reinstall re-arms itself.
         *
         * [enabled] and [minutes] default to the stored values but can be passed in from a settings
         * listener, which fires before the new value is persisted.
         */
        @JvmStatic
        @JvmOverloads
        fun reschedulePeriodic(
            context: Context,
            enabled: Boolean = GBApplication.getPrefs().getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false),
            minutes: Int = intervalMinutes(GBApplication.getPrefs())
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled || minutes <= 0) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                LOG.info("Self-hosted health periodic upload cancelled (enabled={}, minutes={})", enabled, minutes)
                return
            }
            val request = PeriodicWorkRequest.Builder(
                SelfHostedHealthSyncWorker::class.java, minutes.toLong(), TimeUnit.MINUTES
            )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            // UPDATE keeps the running schedule when nothing changed and only reshuffles when the
            // interval actually moved, so reopening the screen does not restart the timer.
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            LOG.info("Self-hosted health periodic upload scheduled every {} minute(s)", minutes)
        }

        /** Stored cadence in minutes; 15 by default, so a missed on-event upload gets the earliest
         *  periodic retry Android WorkManager supports.
         *  0 means the user turned the periodic safety net off. */
        private fun intervalMinutes(prefs: GBPrefs): Int =
            prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_SYNC_INTERVAL, "15").orEmpty().toIntOrNull() ?: 0

        /** Re-cover this much before the cursor, so data the band delivers late still gets sent. */
        private const val LOOK_BACK_SECONDS = 24L * 60L * 60L

        /** Ceiling on a single run, so a stale cursor or a far-back start cannot read months at once. */
        private const val MAX_WINDOW_SECONDS = 30L * 24L * 60L * 60L

        private const val MAX_ATTEMPTS = 5

        @JvmStatic
        fun acceptedDayCursor(date: String, zone: ZoneId, now: Long): Long = minOf(
            LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toEpochSecond(), now
        )

        fun cursorKey(address: String): String = "selfhosted_health_cursor_" + address.uppercase(Locale.ROOT)

        fun sleepCursorKey(address: String): String = "selfhosted_health_sleep_cursor_" + address.uppercase(Locale.ROOT)
    }
}
