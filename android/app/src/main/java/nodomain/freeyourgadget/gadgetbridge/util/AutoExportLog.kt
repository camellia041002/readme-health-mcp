/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or (at your
    option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/** A small persistent history for the database export, including its trigger source. */
data class AutoExportLogEntry(
    val timestampMs: Long,
    val trigger: String,
    val success: Boolean,
    val message: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestampMs)
        put("trigger", trigger)
        put("success", success)
        put("message", message)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): AutoExportLogEntry = AutoExportLogEntry(
            timestampMs = json.optLong("timestamp"),
            trigger = json.optString("trigger", PeriodicExporter.TRIGGER_PERIODIC),
            success = json.optBoolean("success"),
            message = if (json.isNull("message")) null else json.optString("message").ifEmpty { null }
        )
    }
}

object AutoExportLog {
    private val LOG = LoggerFactory.getLogger(AutoExportLog::class.java)
    private const val FILE_NAME = "auto_export_log.json"
    const val MAX_ENTRIES = 100

    @JvmStatic
    fun serialize(entries: List<AutoExportLogEntry>): String = JSONArray().apply {
        entries.forEach { put(it.toJson()) }
    }.toString()

    @JvmStatic
    fun deserialize(json: String?): List<AutoExportLogEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { AutoExportLogEntry.fromJson(it) }
            }
        } catch (e: JSONException) {
            LOG.warn("Could not parse auto-export log, starting fresh", e)
            emptyList()
        }
    }

    @JvmStatic
    fun merge(
        existing: List<AutoExportLogEntry>,
        batch: List<AutoExportLogEntry>,
        max: Int = MAX_ENTRIES
    ): List<AutoExportLogEntry> = (batch + existing).sortedByDescending { it.timestampMs }.take(max)

    @JvmStatic
    @Synchronized
    fun read(context: Context): List<AutoExportLogEntry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            deserialize(file.readText())
        } catch (e: Exception) {
            LOG.warn("Could not read auto-export log", e)
            emptyList()
        }
    }

    @JvmStatic
    @Synchronized
    fun append(context: Context, entry: AutoExportLogEntry) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            file.writeText(serialize(merge(read(context), listOf(entry))))
        } catch (e: Exception) {
            LOG.warn("Could not write auto-export log", e)
        }
    }
}
