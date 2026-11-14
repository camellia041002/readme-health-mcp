/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import androidx.health.connect.client.records.Record
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Lossless-enough JSON bridge for standard Health Connect records without a curated projection. */
object HealthConnectRecordSerializer {
    private const val MAX_DEPTH = 8
    private const val MAX_COLLECTION_ITEMS = 10_000

    data class Serialized(val recordType: String, val recordId: String, val day: LocalDate, val payload: JSONObject)

    fun serialize(record: Record, zone: ZoneId): Serialized {
        val recordType = record.javaClass.simpleName
        val payload = objectToJson(record, zone, 0)
        val day = recordDay(record, zone)
        val sourceId = record.metadata.id.ifBlank { record.metadata.clientRecordId.orEmpty() }
        val identity = if (sourceId.isNotBlank()) sourceId else payload.toString()
        val recordId = "readme-${recordType.lowercase()}-${digest("$recordType\n$identity").take(32)}"
        payload.put("sourcePackage", record.metadata.dataOrigin.packageName)
        payload.put("recordingMethod", record.metadata.recordingMethod)
        return Serialized(recordType, recordId, day, payload)
    }

    private fun objectToJson(value: Any, zone: ZoneId, depth: Int): JSONObject {
        if (depth >= MAX_DEPTH) return JSONObject().put("value", value.toString())
        val out = JSONObject()
        readableGetters(value.javaClass).forEach { method ->
            val name = propertyName(method.name)
            val result = runCatching { method.invoke(value) }.getOrNull() ?: return@forEach
            out.put(name, jsonValue(result, zone, depth + 1))
        }
        return out
    }

    private fun jsonValue(value: Any?, zone: ZoneId, depth: Int): Any {
        if (value == null) return JSONObject.NULL
        return when (value) {
            is String, is Boolean, is Int, is Long, is Float, is Double -> value
            is Number -> value.toDouble()
            is Instant -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(zone))
            is ZoneOffset, is LocalDate, is Duration, is Enum<*> -> value.toString()
            is Map<*, *> -> JSONObject().also { json ->
                value.entries.take(MAX_COLLECTION_ITEMS).forEach { (key, item) ->
                    if (key != null) json.put(key.toString(), jsonValue(item, zone, depth + 1))
                }
            }
            is Iterable<*> -> JSONArray().also { json ->
                value.take(MAX_COLLECTION_ITEMS).forEach { json.put(jsonValue(it, zone, depth + 1)) }
            }
            is Array<*> -> JSONArray().also { json ->
                value.take(MAX_COLLECTION_ITEMS).forEach { json.put(jsonValue(it, zone, depth + 1)) }
            }
            else -> if (depth >= MAX_DEPTH || !value.javaClass.name.startsWith("androidx.health.connect.client")) {
                value.toString()
            } else {
                objectToJson(value, zone, depth)
            }
        }
    }

    private fun readableGetters(type: Class<*>): List<Method> = type.methods
        .asSequence()
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name.length > 3 }
        .filterNot { it.name in setOf("getClass", "getMetadata") }
        .sortedBy { it.name }
        .toList()

    private fun recordDay(record: Record, zone: ZoneId): LocalDate {
        val time = listOf("getEndTime", "getTime", "getStartTime").firstNotNullOfOrNull { name ->
            runCatching { record.javaClass.getMethod(name).invoke(record) as? Instant }.getOrNull()
        }
        return (time ?: Instant.now()).atZone(zone).toLocalDate()
    }

    private fun propertyName(getter: String): String = getter.removePrefix("get").replaceFirstChar { it.lowercase() }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

