/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectClientProvider
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

data class HealthConnectDashboardSnapshot(
    val available: Boolean,
    val steps: Long,
    val latestHeartRate: Long,
    val latestHeartRateTime: String,
    val sleepMinutes: Long,
    val workoutCount: Int,
    val latestOxygen: Double,
    val latestOxygenTime: String,
    val latestPeriodStart: String,
    val stepsAvailable: Boolean = false,
    val sleepStartTime: String = "",
    val sleepEndTime: String = "",
    val latestHeartRateDateTime: String = "",
    val latestOxygenDateTime: String = "",
    val latestSleepDate: String = "",
    val latestStepsDateTime: String = ""
) {
    companion object {
        @Volatile private var cache: HealthConnectDashboardSnapshot? = null
        @JvmStatic fun cached(): HealthConnectDashboardSnapshot? = cache
        @JvmStatic
        fun load(context: Context): HealthConnectDashboardSnapshot = runBlocking {
            val client = HealthConnectClientProvider.healthConnectInit(context)
                ?: return@runBlocking unavailable()
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse { return@runBlocking unavailable() }
            if (!HealthConnectHealthQuerySync.hasUsablePermission(granted)) return@runBlocking unavailable()

            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val start = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val day = runCatching { HealthConnectReadPayload.build(client, start, now, zone, granted).lastOrNull() }
                .getOrNull()
            val body = day?.body ?: JSONObject().put("date", now.atZone(zone).toLocalDate().toString())
            // Steps are a true "today" value, but heart rate and sleep cards should
            // show the latest available measurement/session. An upload just after
            // midnight must not turn yesterday's freshly synced health data into
            // the misleading state "not synced".
            val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
            val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
            val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
            val latestHeartSample = if (heartPermission in granted) runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        HeartRateRecord::class,
                        TimeRangeFilter.between(now.minusSeconds(7L * 86400L), now),
                        pageSize = 1000
                    )
                ).records.flatMap { it.samples }.maxByOrNull { it.time }
            }.getOrNull() else null
            val heartTime = latestHeartSample?.time?.atZone(zone)?.toOffsetDateTime()?.toString().orEmpty()
            val displayTime = runCatching {
                OffsetDateTime.parse(heartTime).toLocalTime().withSecond(0).withNano(0).toString()
            }.getOrDefault("")
            val displayDateTime = runCatching {
                OffsetDateTime.parse(heartTime).atZoneSameInstant(zone).format(DATE_TIME_FORMAT)
            }.getOrDefault("")
            val latestSleep = if (sleepPermission in granted) runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(now.minusSeconds(7L * 86400L), now),
                        pageSize = 1000
                    )
                ).records.maxByOrNull { it.endTime }
            }.getOrNull() else null
            val sleepSeconds = latestSleep?.let { java.time.Duration.between(it.startTime, it.endTime).seconds } ?: 0L
            val sleepStart = latestSleep?.startTime?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty()
            val sleepEnd = latestSleep?.endTime?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty()
            val sleepDate = latestSleep?.endTime?.atZone(zone)?.format(DATE_FORMAT).orEmpty()
            val latestSteps = if (stepsPermission in granted) runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        StepsRecord::class,
                        TimeRangeFilter.between(start, now),
                        pageSize = 1000
                    )
                ).records.maxByOrNull { it.endTime }
            }.getOrNull() else null
            val oxygenPermission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            val periodPermission = HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
            val oxygen = if (oxygenPermission in granted) runCatching {
                client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(now.minusSeconds(30L * 86400L), now), pageSize = 1000))
                    .records.maxByOrNull { it.time }
            }.getOrNull() else null
            val period = if (periodPermission in granted) runCatching {
                client.readRecords(ReadRecordsRequest(MenstruationPeriodRecord::class, TimeRangeFilter.between(now.minusSeconds(180L * 86400L), now), pageSize = 1000))
                    .records.maxByOrNull { it.startTime }
            }.getOrNull() else null
            HealthConnectDashboardSnapshot(
                available = true,
                steps = body.optJSONObject("steps")?.optLong("total", 0L) ?: 0L,
                latestHeartRate = latestHeartSample?.beatsPerMinute ?: 0L,
                latestHeartRateTime = displayTime,
                sleepMinutes = sleepSeconds / 60L,
                workoutCount = body.optJSONArray("workouts")?.length() ?: 0,
                latestOxygen = oxygen?.percentage?.value ?: 0.0,
                latestOxygenTime = oxygen?.time?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty(),
                latestPeriodStart = period?.startTime?.atZone(zone)?.toLocalDate()?.format(java.time.format.DateTimeFormatter.ofPattern("M月d日")).orEmpty(),
                stepsAvailable = body.has("steps"),
                sleepStartTime = sleepStart,
                sleepEndTime = sleepEnd,
                latestHeartRateDateTime = displayDateTime,
                latestOxygenDateTime = oxygen?.time?.atZone(zone)?.format(DATE_TIME_FORMAT).orEmpty(),
                latestSleepDate = sleepDate,
                latestStepsDateTime = latestSteps?.endTime?.atZone(zone)?.format(DATE_TIME_FORMAT).orEmpty()
            ).also { cache = it }
        }

        private val DATE_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")
        private val DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("M月d日")

        private fun formatClock(value: String, zone: ZoneId): String = runCatching {
            OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalTime().withSecond(0).withNano(0).toString()
        }.getOrDefault("")

        private fun unavailable() = HealthConnectDashboardSnapshot(false, 0, 0, "", 0, 0, 0.0, "", "")
    }
}
