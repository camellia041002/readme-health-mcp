/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bounded, read-only Health Connect projection used by ReadMe coexistence mode.
 *
 * Steps use Health Connect aggregation so the user's source priority is honoured and overlapping
 * writers are not added together. Point records are deterministically deduplicated before they
 * enter HealthQuery's already-idempotent day envelopes.
 */
object HealthConnectReadPayload {
    private const val MAX_DAYS = 30L
    private const val PAGE_SIZE = 1000
    private val LOG = LoggerFactory.getLogger(HealthConnectReadPayload::class.java)

    suspend fun build(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        zone: ZoneId,
        grantedPermissions: Set<String>
    ): List<SelfHostedHealthDay> {
        require(!from.isAfter(to))
        require(from >= to.minusSeconds(MAX_DAYS * 24L * 60L * 60L))

        val bodies = linkedMapOf<LocalDate, JSONObject>()
        if (canRead<StepsRecord>(grantedPermissions)) {
            var date = from.atZone(zone).toLocalDate()
            val lastDate = to.atZone(zone).toLocalDate()
            while (!date.isAfter(lastDate)) {
                val start = maxOf(from, date.atStartOfDay(zone).toInstant())
                val end = minOf(to, date.plusDays(1).atStartOfDay(zone).toInstant())
                if (start.isBefore(end)) {
                    val count = client.aggregate(
                        AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
                    )[StepsRecord.COUNT_TOTAL]
                    if (count != null) body(bodies, date).put("steps", JSONObject().put("total", count))
                }
                date = date.plusDays(1)
            }
        }

        if (canRead<HeartRateRecord>(grantedPermissions)) {
            val heartPoints = linkedMapOf<Instant, Long>()
            readAll(client, HeartRateRecord::class.java.kotlin, from, to).forEach { record ->
                record.samples.forEach { sample ->
                    if (sample.beatsPerMinute in 1..300) heartPoints.putIfAbsent(sample.time, sample.beatsPerMinute)
                }
            }
            heartPoints.entries.sortedBy { it.key }.forEach { (time, bpm) ->
                val array = body(bodies, time.atZone(zone).toLocalDate()).optJSONArray("heart_rate")
                    ?: JSONArray().also { body(bodies, time.atZone(zone).toLocalDate()).put("heart_rate", it) }
                array.put(JSONObject().put("timestamp", format(time, zone)).put("value", bpm))
            }
        }

        if (canRead<SleepSessionRecord>(grantedPermissions)) {
            dedupeSleep(readAll(client, SleepSessionRecord::class.java.kotlin, from, to)).forEach { session ->
                val stages = JSONArray()
                session.stages.sortedBy { it.startTime }.forEach { stage ->
                    stages.put(JSONObject()
                        .put("stage", stageName(stage.stage))
                        .put("start_time", format(stage.startTime, zone))
                        .put("end_time", format(stage.endTime, zone)))
                }
                val item = JSONObject()
                    .put("session_start_time", format(session.startTime, zone))
                    .put("session_end_time", format(session.endTime, zone))
                    .put("duration_seconds", session.endTime.epochSecond - session.startTime.epochSecond)
                    .put("stages", stages)
                val day = session.endTime.atZone(zone).toLocalDate()
                val array = body(bodies, day).optJSONArray("sleep")
                    ?: JSONArray().also { body(bodies, day).put("sleep", it) }
                array.put(item)
            }
        }

        if (canRead<ExerciseSessionRecord>(grantedPermissions)) {
            readAll(client, ExerciseSessionRecord::class.java.kotlin, from, to)
                .distinctBy { "${it.startTime}:${it.endTime}:${it.exerciseType}" }
                .sortedBy { it.startTime }
                .forEach { workout ->
                    val day = workout.endTime.atZone(zone).toLocalDate()
                    val array = body(bodies, day).optJSONArray("workouts")
                        ?: JSONArray().also { body(bodies, day).put("workouts", it) }
                    array.put(JSONObject()
                        .put("id", "hc:${workout.startTime}:${workout.endTime}:${workout.exerciseType}")
                        .put("start_time", format(workout.startTime, zone))
                        .put("end_time", format(workout.endTime, zone))
                        .put("raw_type", workout.exerciseType))
                }
        }

        if (canRead<ActiveCaloriesBurnedRecord>(grantedPermissions)) {
            readAll(client, ActiveCaloriesBurnedRecord::class.java.kotlin, from, to).forEach { record ->
                val day = record.endTime.atZone(zone).toLocalDate()
                val array = body(bodies, day).optJSONArray("active_calories")
                    ?: JSONArray().also { body(bodies, day).put("active_calories", it) }
                array.put(JSONObject()
                    .put("start_time", format(record.startTime, zone))
                    .put("end_time", format(record.endTime, zone))
                    .put("calories", record.energy.inKilocalories))
            }
        }

        if (canRead<OxygenSaturationRecord>(grantedPermissions)) {
            readAll(client, OxygenSaturationRecord::class.java.kotlin, from, to).forEach { record ->
                val day = record.time.atZone(zone).toLocalDate()
                val array = body(bodies, day).optJSONArray("oxygen_saturation")
                    ?: JSONArray().also { body(bodies, day).put("oxygen_saturation", it) }
                array.put(JSONObject()
                    .put("time", format(record.time, zone))
                    .put("percentage", record.percentage.value))
            }
        }

        if (canRead<MenstruationPeriodRecord>(grantedPermissions)) {
            val cycleFrom = to.minusSeconds(90L * 24L * 60L * 60L)
            readAll(client, MenstruationPeriodRecord::class.java.kotlin, minOf(from, cycleFrom), to).forEach { record ->
                val day = record.startTime.atZone(zone).toLocalDate()
                val array = body(bodies, day).optJSONArray("menstruation_periods")
                    ?: JSONArray().also { body(bodies, day).put("menstruation_periods", it) }
                array.put(JSONObject()
                    .put("start_time", format(record.startTime, zone))
                    .put("end_time", format(record.endTime, zone))
                    .put("duration_days", java.time.Duration.between(record.startTime, record.endTime).toDays().coerceAtLeast(1)))
            }
        }

        HealthConnectRecordCatalog.entries
            .asSequence()
            .filterNot { it.type in HealthConnectRecordCatalog.curatedTypes }
            .filter { it.permission in grantedPermissions }
            .forEach { entry ->
                val recordFrom = if (entry.lookbackDays > MAX_DAYS) {
                    minOf(from, to.minusSeconds(entry.lookbackDays * 24L * 60L * 60L))
                } else from
                val records = try {
                    readAllUntyped(client, entry.type, recordFrom, to)
                } catch (e: SecurityException) {
                    LOG.info("Health Connect permission changed while reading {}", entry.type.simpleName)
                    emptyList()
                } catch (e: Exception) {
                    LOG.warn("Health Connect record read failed for {} ({})", entry.type.simpleName, e.javaClass.simpleName)
                    emptyList()
                }
                records.forEach { record ->
                    val serialized = HealthConnectRecordSerializer.serialize(record, zone)
                    val array = body(bodies, serialized.day).optJSONArray("health_connect_records")
                        ?: JSONArray().also { body(bodies, serialized.day).put("health_connect_records", it) }
                    array.put(JSONObject()
                        .put("record_type", serialized.recordType)
                        .put("record_id", serialized.recordId)
                        .put("payload", serialized.payload))
                }
            }

        return bodies.entries.filter { it.value.length() > 1 }
            .map { SelfHostedHealthDay(it.key.toString(), it.value) }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        client: HealthConnectClient,
        type: kotlin.reflect.KClass<T>,
        from: Instant,
        to: Instant
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = PAGE_SIZE,
                    pageToken = token
                )
            )
            out.addAll(response.records)
            token = response.pageToken
        } while (token != null)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readAllUntyped(
        client: HealthConnectClient,
        type: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>,
        from: Instant,
        to: Instant
    ): List<androidx.health.connect.client.records.Record> = readAll(
        client,
        type as kotlin.reflect.KClass<androidx.health.connect.client.records.Record>,
        from,
        to
    )

    internal fun dedupeSleep(records: List<SleepSessionRecord>): List<SleepSessionRecord> {
        val sorted = records.sortedWith(compareBy<SleepSessionRecord> { it.startTime }.thenBy { it.endTime })
        val groups = mutableListOf<MutableList<SleepSessionRecord>>()
        for (record in sorted) {
            val group = groups.lastOrNull()
            if (group != null && record.startTime < group.maxOf { it.endTime }) group.add(record)
            else groups.add(mutableListOf(record))
        }
        return groups.map { group ->
            group.maxWith(compareBy<SleepSessionRecord> { it.stages.size }
                .thenBy { it.endTime.epochSecond - it.startTime.epochSecond })
        }
    }

    private fun body(map: MutableMap<LocalDate, JSONObject>, date: LocalDate): JSONObject =
        map.getOrPut(date) { JSONObject().put("date", date.toString()) }

    private inline fun <reified T : androidx.health.connect.client.records.Record> canRead(
        grantedPermissions: Set<String>
    ): Boolean = HealthPermission.getReadPermission(T::class) in grantedPermissions

    private fun format(time: Instant, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.atZone(zone))

    private fun stageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        else -> "unknown"
    }
}
