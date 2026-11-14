/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import android.content.Context
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.ScreenTimeSummary
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectClientProvider
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthDay
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Executes one bounded Health Connect -> HealthQuery pass without touching Bluetooth or local DB. */
object HealthConnectHealthQuerySync {
    private const val CURSOR_KEY = "healthquery_health_connect_cursor"
    private const val INITIAL_WINDOW_SECONDS = 30L * 24L * 60L * 60L
    private const val LOOK_BACK_SECONDS = 24L * 60L * 60L
    private val LOG = LoggerFactory.getLogger(HealthConnectHealthQuerySync::class.java)

    internal val uploadReadPermissions = HealthConnectRecordCatalog.readPermissions

    internal fun hasUsablePermission(granted: Set<String>): Boolean =
        granted.any { it in uploadReadPermissions }

    sealed class Outcome {
        data class Success(val uploadedDays: Int) : Outcome()
        data object PermissionRequired : Outcome()
        data class Failure(val retryable: Boolean, val category: String) : Outcome()
    }

    fun run(context: Context, connection: HealthQueryConnection): Outcome = runBlocking {
        val client = HealthConnectClientProvider.healthConnectInit(context)
            ?: return@runBlocking Outcome.Failure(false, "health_connect_unavailable")
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            return@runBlocking Outcome.Failure(true, "permission_check_error")
        }
        if (!hasUsablePermission(granted)) return@runBlocking Outcome.PermissionRequired

        val prefs = GBApplication.getPrefs().preferences
        val now = Instant.now()
        val cursor = prefs.getLong(CURSOR_KEY, 0L)
        val earliest = now.minusSeconds(INITIAL_WINDOW_SECONDS)
        val from = if (cursor > 0L) {
            maxOf(earliest, Instant.ofEpochSecond(cursor).minusSeconds(LOOK_BACK_SECONDS))
        } else earliest

        val healthDays = try {
            HealthConnectReadPayload.build(client, from, now, ZoneId.systemDefault(), granted)
        } catch (e: SecurityException) {
            return@runBlocking Outcome.PermissionRequired
        } catch (e: Exception) {
            LOG.warn("Health Connect read failed ({})", e.javaClass.simpleName)
            return@runBlocking Outcome.Failure(true, "health_connect_read_error")
        }

        val zone = ZoneId.systemDefault()
        val days = appendScreenTime(context, healthDays, from, now, zone)

        var uploaded = 0
        val transport = HealthQueryTransport(context)
        for ((_, request) in HealthQueryPayloadMapper.buildDayRequests(days, zone, connection.deviceId)) {
            when (val result = transport.upload(connection, request)) {
                is HealthQueryTransportResult.Success -> uploaded++
                is HealthQueryTransportResult.Failure ->
                    return@runBlocking Outcome.Failure(result.retryable, result.category)
            }
        }
        prefs.edit().putLong(CURSOR_KEY, now.epochSecond).apply()
        Outcome.Success(uploaded)
    }

    internal fun appendScreenTime(
        context: Context,
        healthDays: List<SelfHostedHealthDay>,
        from: Instant,
        to: Instant,
        zone: ZoneId
    ): List<SelfHostedHealthDay> {
        if (!ScreenTimeSummary.hasPermission(context)) return healthDays
        val bodies = linkedMapOf<LocalDate, JSONObject>()
        healthDays.forEach { day -> bodies[LocalDate.parse(day.date)] = JSONObject(day.body.toString()) }
        var date = from.atZone(zone).toLocalDate()
        val last = to.atZone(zone).toLocalDate()
        while (!date.isAfter(last)) {
            // Re-read the whole local day so the stable per-app record replaces a
            // partial earlier snapshot instead of shrinking to the cursor look-back.
            val start = date.atStartOfDay(zone).toInstant()
            val end = minOf(to, date.plusDays(1).atStartOfDay(zone).toInstant())
            if (start.isBefore(end)) {
                val usage = ScreenTimeSummary.between(context, start.toEpochMilli(), end.toEpochMilli())
                if (usage.apps().isNotEmpty()) {
                    val items = JSONArray()
                    usage.apps().forEach { app ->
                        items.put(JSONObject()
                            .put("package", app.packageName())
                            .put("label", app.label())
                            .put("duration_minutes", app.millis() / 60_000.0)
                            .put("start_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start.atZone(zone)))
                            .put("end_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(end.atZone(zone))))
                    }
                    bodies.getOrPut(date) { JSONObject().put("date", date.toString()) }
                        .put("screen_time", items)
                }
            }
            date = date.plusDays(1)
        }
        return bodies.entries.filter { it.value.length() > 1 }
            .map { SelfHostedHealthDay(it.key.toString(), it.value) }
    }
}
