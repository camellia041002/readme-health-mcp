/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectClientProvider
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

data class DashboardSleepSession(
    val startTime: String,
    val endTime: String,
    val asleepMinutes: Long,
    val deepMinutes: Long,
    val lightMinutes: Long,
    val remMinutes: Long,
    val awakeMinutes: Long,
    val nap: Boolean,
    val hasStageDetails: Boolean
)

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
    val latestStepsDateTime: String = "",
    val sleepSessionCount: Int = 0,
    val sleepRanges: List<String> = emptyList(),
    val sleepSessions: List<DashboardSleepSession> = emptyList()
) {
    companion object {
        @Volatile private var cache: HealthConnectDashboardSnapshot? = null
        @JvmStatic fun cached(): HealthConnectDashboardSnapshot? = cache
        @JvmStatic
        fun load(context: Context): HealthConnectDashboardSnapshot = runBlocking {
            val previous = cache
            val client = HealthConnectClientProvider.healthConnectInit(context)
                ?: return@runBlocking previous ?: unavailable()
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse { return@runBlocking previous ?: unavailable() }
            if (!HealthConnectHealthQuerySync.hasUsablePermission(granted)) return@runBlocking unavailable()

            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val today = now.atZone(zone).toLocalDate()
            val start = today.atStartOfDay(zone).toInstant()
            val day = runCatching {
                selectDay(HealthConnectReadPayload.build(client, start, now, zone, granted), today)
            }
                .getOrNull()
            val body = day?.body ?: JSONObject().put("date", today.toString())
            // Dashboard cards show the latest available real record. In particular,
            // midnight or a delayed Health Sync run must not erase the last valid
            // daily step total just because today's first step record is not present yet.
            val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
            val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
            val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
            val heartResult = if (heartPermission in granted) runCatching {
                readLatest(client, HeartRateRecord::class, now.minusSeconds(7L * 86400L), now)
                    ?.samples
                    ?.maxByOrNull { it.time }
            } else Result.success(null)
            val latestHeartSample = heartResult.getOrNull()
            val heartTime = latestHeartSample?.time?.atZone(zone)?.toOffsetDateTime()?.toString().orEmpty()
            val displayTime = runCatching {
                OffsetDateTime.parse(heartTime).toLocalTime().withSecond(0).withNano(0).toString()
            }.getOrDefault("")
            val displayDateTime = runCatching {
                OffsetDateTime.parse(heartTime).atZoneSameInstant(zone).format(DATE_TIME_FORMAT)
            }.getOrDefault("")
            val sleepResult = if (sleepPermission in granted) runCatching {
                val records = readAllSleep(client, now.minusSeconds(7L * 86400L), now)
                summarizeLatestSleepDay(records.map(::toSleepSpan), zone)
            } else Result.success(null)
            val latestSleep = sleepResult.getOrNull()
            val sleepSeconds = latestSleep?.totalSeconds ?: 0L
            val sleepStart = latestSleep?.sessions?.minByOrNull { it.startTime }
                ?.startTime?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty()
            val sleepEnd = latestSleep?.sessions?.maxByOrNull { it.endTime }
                ?.endTime?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty()
            val sleepDate = latestSleep?.date?.format(DATE_FORMAT).orEmpty()
            val sleepRanges = latestSleep?.sessions.orEmpty().sortedBy { it.startTime }.map {
                "${formatClock(it.startTime, zone)}–${formatClock(it.endTime, zone)}"
            }
            val sleepSessions = latestSleep?.sessions.orEmpty().sortedBy { it.startTime }.map {
                DashboardSleepSession(
                    startTime = formatClock(it.startTime, zone),
                    endTime = formatClock(it.endTime, zone),
                    asleepMinutes = it.asleepSeconds / 60L,
                    deepMinutes = it.deepSeconds / 60L,
                    lightMinutes = it.lightSeconds / 60L,
                    remMinutes = it.remSeconds / 60L,
                    awakeMinutes = it.awakeSeconds / 60L,
                    nap = isNap(it, zone),
                    hasStageDetails = it.hasStageDetails
                )
            }
            val stepsResult = if (stepsPermission in granted) runCatching {
                readLatestStepTotal(client, now, zone)
            } else Result.success(null)
            val latestSteps = stepsResult.getOrNull()
            val oxygenPermission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            val periodPermission = HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
            val oxygenResult = if (oxygenPermission in granted) runCatching {
                readLatest(client, OxygenSaturationRecord::class, now.minusSeconds(30L * 86400L), now)
            } else Result.success(null)
            val oxygen = oxygenResult.getOrNull()
            val periodResult = if (periodPermission in granted) runCatching {
                readLatest(client, MenstruationPeriodRecord::class, now.minusSeconds(180L * 86400L), now)
            } else Result.success(null)
            val period = periodResult.getOrNull()
            HealthConnectDashboardSnapshot(
                available = true,
                steps = when {
                    stepsResult.isFailure -> previous?.steps ?: 0L
                    else -> latestSteps?.total ?: 0L
                },
                latestHeartRate = when {
                    heartPermission !in granted -> 0L
                    heartResult.isFailure -> previous?.latestHeartRate ?: 0L
                    else -> latestHeartSample?.beatsPerMinute ?: 0L
                },
                latestHeartRateTime = when {
                    heartPermission !in granted -> ""
                    heartResult.isFailure -> previous?.latestHeartRateTime.orEmpty()
                    else -> displayTime
                },
                sleepMinutes = when {
                    sleepPermission !in granted -> 0L
                    sleepResult.isFailure -> previous?.sleepMinutes ?: 0L
                    else -> sleepSeconds / 60L
                },
                workoutCount = body.optJSONArray("workouts")?.length() ?: 0,
                latestOxygen = when {
                    oxygenPermission !in granted -> 0.0
                    oxygenResult.isFailure -> previous?.latestOxygen ?: 0.0
                    else -> oxygen?.percentage?.value ?: 0.0
                },
                latestOxygenTime = when {
                    oxygenPermission !in granted -> ""
                    oxygenResult.isFailure -> previous?.latestOxygenTime.orEmpty()
                    else -> oxygen?.time?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString().orEmpty()
                },
                latestPeriodStart = when {
                    periodPermission !in granted -> ""
                    periodResult.isFailure -> previous?.latestPeriodStart.orEmpty()
                    else -> period?.startTime?.atZone(zone)?.toLocalDate()?.format(java.time.format.DateTimeFormatter.ofPattern("M月d日")).orEmpty()
                },
                stepsAvailable = when {
                    stepsPermission !in granted -> false
                    stepsResult.isFailure -> previous?.stepsAvailable ?: false
                    else -> latestSteps != null
                },
                sleepStartTime = when {
                    sleepPermission !in granted -> ""
                    sleepResult.isFailure -> previous?.sleepStartTime.orEmpty()
                    else -> sleepStart
                },
                sleepEndTime = when {
                    sleepPermission !in granted -> ""
                    sleepResult.isFailure -> previous?.sleepEndTime.orEmpty()
                    else -> sleepEnd
                },
                latestHeartRateDateTime = when {
                    heartPermission !in granted -> ""
                    heartResult.isFailure -> previous?.latestHeartRateDateTime.orEmpty()
                    else -> displayDateTime
                },
                latestOxygenDateTime = when {
                    oxygenPermission !in granted -> ""
                    oxygenResult.isFailure -> previous?.latestOxygenDateTime.orEmpty()
                    else -> oxygen?.time?.atZone(zone)?.format(DATE_TIME_FORMAT).orEmpty()
                },
                latestSleepDate = when {
                    sleepPermission !in granted -> ""
                    sleepResult.isFailure -> previous?.latestSleepDate.orEmpty()
                    else -> sleepDate
                },
                latestStepsDateTime = when {
                    stepsPermission !in granted -> ""
                    stepsResult.isFailure -> previous?.latestStepsDateTime.orEmpty()
                    else -> latestSteps?.latestRecordTime?.atZone(zone)?.format(DATE_TIME_FORMAT).orEmpty()
                },
                sleepSessionCount = when {
                    sleepPermission !in granted -> 0
                    sleepResult.isFailure -> previous?.sleepSessionCount ?: 0
                    else -> latestSleep?.sessions?.size ?: 0
                },
                sleepRanges = when {
                    sleepPermission !in granted -> emptyList()
                    sleepResult.isFailure -> previous?.sleepRanges.orEmpty()
                    else -> sleepRanges
                },
                sleepSessions = when {
                    sleepPermission !in granted -> emptyList()
                    sleepResult.isFailure -> previous?.sleepSessions.orEmpty()
                    else -> sleepSessions
                }
            ).also { cache = it }
        }

        private val DATE_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")
        private val DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("M月d日")

        internal fun selectDay(days: List<SelfHostedHealthDay>, date: java.time.LocalDate): SelfHostedHealthDay? =
            days.firstOrNull { it.date == date.toString() }

        internal fun <T : Record> latestRequest(
            type: kotlin.reflect.KClass<T>,
            from: Instant,
            to: Instant
        ): ReadRecordsRequest<T> = ReadRecordsRequest(
            recordType = type,
            timeRangeFilter = TimeRangeFilter.between(from, to),
            ascendingOrder = false,
            pageSize = 1
        )

        private suspend fun <T : Record> readLatest(
            client: HealthConnectClient,
            type: kotlin.reflect.KClass<T>,
            from: Instant,
            to: Instant
        ): T? = client.readRecords(latestRequest(type, from, to)).records.firstOrNull()

        private suspend fun readAllSleep(
            client: HealthConnectClient,
            from: Instant,
            to: Instant
        ): List<SleepSessionRecord> {
            val records = mutableListOf<SleepSessionRecord>()
            var pageToken: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageSize = 100,
                        pageToken = pageToken
                    )
                )
                records.addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
            return HealthConnectReadPayload.dedupeSleep(records)
        }

        internal data class SleepSpan(
            val startTime: Instant,
            val endTime: Instant,
            val asleepSeconds: Long,
            val deepSeconds: Long = 0L,
            val lightSeconds: Long = 0L,
            val remSeconds: Long = 0L,
            val awakeSeconds: Long = 0L,
            val hasStageDetails: Boolean = false
        )

        internal data class SleepDaySummary(
            val date: LocalDate,
            val totalSeconds: Long,
            val sessions: List<SleepSpan>
        )

        internal fun summarizeLatestSleepDay(
            sessions: List<SleepSpan>,
            zone: ZoneId
        ): SleepDaySummary? {
            val latestDay = sessions.maxOfOrNull { it.endTime.atZone(zone).toLocalDate() } ?: return null
            val daySessions = sessions
                .filter { it.endTime.atZone(zone).toLocalDate() == latestDay }
                .sortedBy { it.startTime }
            return SleepDaySummary(
                date = latestDay,
                totalSeconds = daySessions.sumOf { it.asleepSeconds.coerceAtLeast(0L) },
                sessions = daySessions
            )
        }

        private fun toSleepSpan(record: SleepSessionRecord): SleepSpan {
            var deep = 0L
            var light = 0L
            var rem = 0L
            var awake = 0L
            record.stages.forEach { stage ->
                val seconds = java.time.Duration.between(stage.startTime, stage.endTime)
                    .seconds.coerceAtLeast(0L)
                when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> deep += seconds
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> light += seconds
                    SleepSessionRecord.STAGE_TYPE_REM -> rem += seconds
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += seconds
                }
            }
            val stagedSleep = deep + light + rem
            val asleep = if (stagedSleep > 0L) stagedSleep else
                java.time.Duration.between(record.startTime, record.endTime).seconds.coerceAtLeast(0L)
            return SleepSpan(
                startTime = record.startTime,
                endTime = record.endTime,
                asleepSeconds = asleep,
                deepSeconds = deep,
                lightSeconds = light,
                remSeconds = rem,
                awakeSeconds = awake,
                hasStageDetails = record.stages.isNotEmpty()
            )
        }

        private fun isNap(session: SleepSpan, zone: ZoneId): Boolean {
            val localStart = session.startTime.atZone(zone).toLocalTime()
            val sessionSeconds = java.time.Duration.between(session.startTime, session.endTime).seconds
            return !localStart.isBefore(java.time.LocalTime.of(6, 0)) &&
                localStart.isBefore(java.time.LocalTime.of(18, 0)) &&
                sessionSeconds <= 4L * 3600L
        }

        internal data class LatestStepTotal(val total: Long, val latestRecordTime: Instant)

        private suspend fun readLatestStepTotal(
            client: HealthConnectClient,
            now: Instant,
            zone: ZoneId
        ): LatestStepTotal? {
            val lookupRange = stepLookupRange(now)
            val latest = readLatest(client, StepsRecord::class, lookupRange.first, lookupRange.second)
                ?: return null
            val dayRange = stepDayRange(latest.endTime, now, zone)
            val total = client.aggregate(
                AggregateRequest(
                    setOf(StepsRecord.COUNT_TOTAL),
                    TimeRangeFilter.between(dayRange.first, dayRange.second)
                )
            )[StepsRecord.COUNT_TOTAL] ?: return null
            return LatestStepTotal(total, latest.endTime)
        }

        internal const val STEP_LOOKBACK_SECONDS = 30L * 86400L

        internal fun stepLookupRange(now: Instant): Pair<Instant, Instant> =
            now.minusSeconds(STEP_LOOKBACK_SECONDS) to now

        internal fun stepDayRange(latestRecordTime: Instant, now: Instant, zone: ZoneId): Pair<Instant, Instant> {
            val date = latestRecordTime.atZone(zone).toLocalDate()
            return date.atStartOfDay(zone).toInstant() to
                minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), now)
        }

        private fun formatClock(value: String, zone: ZoneId): String = runCatching {
            OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalTime().withSecond(0).withNano(0).toString()
        }.getOrDefault("")

        private fun formatClock(value: Instant, zone: ZoneId): String =
            value.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()

        private fun unavailable() = HealthConnectDashboardSnapshot(false, 0, 0, "", 0, 0, 0.0, "", "")
    }
}
