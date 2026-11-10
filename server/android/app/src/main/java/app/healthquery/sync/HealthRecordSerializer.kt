package app.healthquery.sync

import androidx.health.connect.client.records.Record
import kotlinx.serialization.json.*
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.IdentityHashMap

/**
 * Reflection is deliberate here: Health Connect record fields
 * evolve independently of this app. Serializing every non-static declared field recursively
 * retains metadata, origins/device IDs, offsets, units, samples, stages, sessions and routes.
 *
 * This serializer fails closed. Silently dropping an inaccessible field would turn an apparently
 * successful sync into a lossy one, which is worse than retrying and surfacing the SDK/provider
 * incompatibility to the user.
 */
object HealthRecordSerializer {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    fun payload(record: Record): JsonObject = toElement(record, IdentityHashMap()) as JsonObject

    fun envelope(record: Record): HealthRecordEnvelope = HealthRecordEnvelope(
        operation = "upsert",
        record_type = record::class.qualifiedName ?: record::class.java.name,
        record_id = record.metadata.id,
        payload = payload(record),
    )

    /**
     * Writes a record payload directly to [out] without first constructing a JsonElement tree.
     * Health Connect pages may contain exercise routes with thousands of locations; retaining
     * both that object graph and an equivalent JsonObject can briefly multiply heap usage. The
     * lossless reflection rules intentionally mirror [payload], while this path only retains the
     * output buffer and the current recursion stack.
     */
    fun writePayload(record: Record, out: Appendable) {
        writeValue(record, out, IdentityHashMap())
    }

    private fun writeValue(value: Any?, out: Appendable, seen: IdentityHashMap<Any, Boolean>) {
        when (value) {
            null -> out.append("null")
            is JsonElement -> out.append(value.toString())
            is String, is Char -> out.append(JsonPrimitive(value.toString()).toString())
            is Boolean -> out.append(value.toString())
            is Byte, is Short, is Int, is Long, is Float, is Double ->
                out.append(JsonPrimitive(value as Number).toString())
            is Instant, is ZoneOffset, is Duration, is Enum<*> ->
                out.append(JsonPrimitive(if (value is Enum<*>) value.name else value.toString()).toString())
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeValue(item, out, seen)
                }
                out.append(']')
            }
            is Array<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeValue(item, out, seen)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    out.append(JsonPrimitive(key.toString()).toString()).append(':')
                    writeValue(item, out, seen)
                }
                out.append('}')
            }
            else -> {
                if (value.javaClass.isArray) {
                    out.append('[')
                    val length = java.lang.reflect.Array.getLength(value)
                    for (index in 0 until length) {
                        if (index > 0) out.append(',')
                        writeValue(java.lang.reflect.Array.get(value, index), out, seen)
                    }
                    out.append(']')
                    return
                }
                if (seen.put(value, true) != null) error("Cycle while serializing ${value.javaClass.name}")
                try {
                    out.append('{')
                    var first = true
                    val fields = HashSet<String>()
                    var current: Class<*>? = value.javaClass
                    while (current != null && current != Any::class.java) {
                        current.declaredFields
                            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                            .forEach { field ->
                                try {
                                    field.isAccessible = true
                                    // Match payload()'s putIfAbsent behavior across class hierarchy.
                                    if (fields.add(field.name)) {
                                        val fieldValue = field.get(value)
                                        if (!first) out.append(',') else first = false
                                        out.append(JsonPrimitive(field.name).toString()).append(':')
                                        writeValue(fieldValue, out, seen)
                                    }
                                } catch (e: Exception) {
                                    throw IllegalStateException("Unable to serialize ${value.javaClass.name}.${field.name}", e)
                                }
                            }
                        current = current.superclass
                    }
                    out.append('}')
                } finally {
                    seen.remove(value)
                }
            }
        }
    }

    private fun toElement(value: Any?, seen: IdentityHashMap<Any, Boolean>): JsonElement {
        if (value == null) return JsonNull
        when (value) {
            is JsonElement -> return value
            is String, is Char -> return JsonPrimitive(value.toString())
            is Boolean -> return JsonPrimitive(value)
            is Byte, is Short, is Int, is Long, is Float, is Double -> return JsonPrimitive(value as Number)
            is Instant -> return JsonPrimitive(value.toString())
            is ZoneOffset -> return JsonPrimitive(value.id)
            is Duration -> return JsonPrimitive(value.toString())
            is Enum<*> -> return JsonPrimitive(value.name)
            is Iterable<*> -> return JsonArray(value.map { toElement(it, seen) })
            is Array<*> -> return JsonArray(value.map { toElement(it, seen) })
            is Map<*, *> -> return JsonObject(value.entries.associate { (key, item) -> key.toString() to toElement(item, seen) })
        }
        if (value.javaClass.isArray) {
            val length = java.lang.reflect.Array.getLength(value)
            return JsonArray((0 until length).map { toElement(java.lang.reflect.Array.get(value, it), seen) })
        }
        if (seen.put(value, true) != null) {
            error("Cycle while serializing ${value.javaClass.name}")
        }
        try {
            val fields = linkedMapOf<String, JsonElement>()
            var current: Class<*>? = value.javaClass
            while (current != null && current != Any::class.java) {
                current.declaredFields
                    .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                    .forEach { field ->
                        try {
                            field.isAccessible = true
                            fields.putIfAbsent(field.name, toElement(field.get(value), seen))
                        } catch (e: Exception) {
                            throw IllegalStateException(
                                "Unable to serialize ${value.javaClass.name}.${field.name}", e
                            )
                        }
                    }
                current = current.superclass
            }
            return JsonObject(fields)
        } finally {
            seen.remove(value)
        }
    }
}
