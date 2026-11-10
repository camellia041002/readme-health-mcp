package nodomain.freeyourgadget.gadgetbridge.database

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.GBDatabaseManager
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.AutoExportLog
import nodomain.freeyourgadget.gadgetbridge.util.AutoExportLogEntry
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import nodomain.freeyourgadget.gadgetbridge.util.PeriodicExporter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DatabaseExportWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : Worker(mContext, workerParams) {
    override fun doWork(): Result {
        val trigger = inputData.getString(PeriodicExporter.INPUT_TRIGGER)
            ?: PeriodicExporter.TRIGGER_PERIODIC
        val enabled = GBApplication.getPrefs().getBoolean(GBPrefs.AUTO_EXPORT_DB_ENABLED, false)
        if (!enabled) {
            LOG.warn("DB export started, but is disabled")
            // Should not need i18n, this should never happen
            GB.updateExportFailedNotification("DB export started, but is disabled", mContext)
            recordLog(trigger, false, "DB export started, but is disabled")
            return Result.failure()
        }

        val dst = GBApplication.getPrefs().getString(GBPrefs.AUTO_EXPORT_DB_LOCATION, "")

        LOG.info("Starting DB export, dst={}", dst)

        if (dst == null) {
            LOG.warn("Unable to export DB, export location not set")
            GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_location_not_set), mContext)
            broadcastSuccess(false)
            recordLog(trigger, false, mContext.getString(R.string.notif_export_location_not_set))
            return Result.failure()
        }

        val exportDirectory = File(mContext.cacheDir, "database-export")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            val message = "Unable to create database export cache directory"
            LOG.error(message)
            GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_failed_title), mContext)
            broadcastSuccess(false)
            recordLog(trigger, false, message)
            return Result.failure()
        }

        val currentDatabase = File.createTempFile("current-", ".db", exportDirectory)
        val previousDatabase = File.createTempFile("previous-", ".db", exportDirectory)

        try {
            val destination = dst.toUri()
            val hasPreviousExport = copyPreviousExport(destination, previousDatabase)

            GBDatabaseManager.exportDB(currentDatabase)

            if (hasPreviousExport) {
                val preservedRows = IncrementalDatabaseExport.mergePreviousIntoCurrent(
                    currentDatabase,
                    previousDatabase
                )
                LOG.info("Preserved {} rows that only existed in the previous DB export", preservedRows)
            }

            FileUtils.copyFileToURI(mContext, currentDatabase, destination)

            GBApplication.getPrefs().preferences.edit {
                putLong(GBPrefs.AUTO_EXPORT_DB_LAST_EXECUTION, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_failed_title), mContext)
            LOG.error("Exception while exporting DB", e)
            broadcastSuccess(false)
            recordLog(trigger, false, e.message ?: e.javaClass.simpleName)
            return Result.failure()
        } finally {
            if (!currentDatabase.delete() && currentDatabase.exists()) {
                LOG.warn("Unable to delete temporary current database export {}", currentDatabase)
            }
            if (!previousDatabase.delete() && previousDatabase.exists()) {
                LOG.warn("Unable to delete temporary previous database export {}", previousDatabase)
            }
        }

        LOG.info("DB export completed")
        recordLog(trigger, true, null)

        broadcastSuccess(true)

        return Result.success()
    }

    private fun copyPreviousExport(destination: android.net.Uri, target: File): Boolean {
        val input = try {
            mContext.contentResolver.openInputStream(destination)
        } catch (_: java.io.FileNotFoundException) {
            return false
        }
        if (input == null) {
            return false
        }
        input.use { source ->
            FileOutputStream(target).use { output ->
                source.copyTo(output)
            }
        }
        return target.length() > 0L
    }

    private fun recordLog(trigger: String, success: Boolean, message: String?) {
        AutoExportLog.append(
            mContext,
            AutoExportLogEntry(System.currentTimeMillis(), trigger, success, message)
        )
        LOG.info("DB export log recorded: trigger={}, success={}", trigger, success)
    }

    private fun broadcastSuccess(success: Boolean) {
        if (!GBApplication.getPrefs().getBoolean(GBPrefs.INTENT_API_BROADCAST_EXPORT_DB, false)) {
            return
        }

        LOG.info("Broadcasting database export success={}", success)

        val action: String = if (success) ACTION_DATABASE_EXPORT_SUCCESS else ACTION_DATABASE_EXPORT_FAIL
        val exportedNotifyIntent = Intent(action)
        mContext.sendBroadcast(exportedNotifyIntent)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(DatabaseExportWorker::class.java)

        const val ACTION_DATABASE_EXPORT_SUCCESS: String =
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS"
        const val ACTION_DATABASE_EXPORT_FAIL: String =
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_FAIL"
    }
}

/**
 * Keeps rows that only exist in an older export while retaining the current app database as the
 * authoritative copy for rows with the same primary key. The current database also supplies the
 * schema, so old exports are upgraded naturally when columns are added.
 */
internal object IncrementalDatabaseExport {
    private const val PREVIOUS_SCHEMA = "previous_export"

    @Throws(IOException::class)
    fun mergePreviousIntoCurrent(currentDatabase: File, previousDatabase: File): Long {
        val database = SQLiteDatabase.openDatabase(
            currentDatabase.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        var attached = false

        try {
            database.execSQL(
                "ATTACH DATABASE ? AS $PREVIOUS_SCHEMA",
                arrayOf<Any>(previousDatabase.absolutePath)
            )
            attached = true

            checkIntegrity(database, PREVIOUS_SCHEMA)

            val currentTables = getTables(database, "main")
            val previousTables = getTables(database, PREVIOUS_SCHEMA)
            val deviceIdMap = buildIdMap(database, "DEVICE", listOf("IDENTIFIER"))
            val userIdMap = buildIdMap(database, "USER", listOf("NAME", "BIRTHDAY", "GENDER"))
            var preservedRows = 0L

            database.execSQL("PRAGMA foreign_keys = OFF")
            database.beginTransaction()
            try {
                for (table in currentTables.intersect(previousTables)) {
                    val currentColumns = getColumns(database, "main", table)
                    val previousColumns = getColumns(database, PREVIOUS_SCHEMA, table).toSet()
                    val commonColumns = currentColumns.filter(previousColumns::contains)
                    if (commonColumns.isEmpty()) {
                        continue
                    }

                    val quotedTable = quoteIdentifier(table)
                    val quotedColumns = commonColumns.joinToString(", ") { quoteIdentifier(it) }
                    val selectedColumns = commonColumns.joinToString(", ") { column ->
                        when {
                            table.equals("DEVICE", ignoreCase = true) &&
                                column.equals("_id", ignoreCase = true) ->
                                remapExpression(column, deviceIdMap)
                            table.equals("USER", ignoreCase = true) &&
                                column.equals("_id", ignoreCase = true) ->
                                remapExpression(column, userIdMap)
                            column.equals("DEVICE_ID", ignoreCase = true) ->
                                remapExpression(column, deviceIdMap)
                            column.equals("USER_ID", ignoreCase = true) ->
                                remapExpression(column, userIdMap)
                            else -> quoteIdentifier(column)
                        }
                    }
                    database.execSQL(
                        "INSERT OR IGNORE INTO main.$quotedTable ($quotedColumns) " +
                            "SELECT $selectedColumns FROM $PREVIOUS_SCHEMA.$quotedTable"
                    )
                    preservedRows += queryLong(database, "SELECT changes()")
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            database.execSQL("DETACH DATABASE $PREVIOUS_SCHEMA")
            attached = false
            checkIntegrity(database, "main")
            return preservedRows
        } catch (e: Exception) {
            throw IOException("Unable to merge the previous database export", e)
        } finally {
            if (attached) {
                try {
                    database.execSQL("DETACH DATABASE $PREVIOUS_SCHEMA")
                } catch (_: Exception) {
                    // The database close below also detaches it.
                }
            }
            database.close()
        }
    }

    private fun getTables(database: SQLiteDatabase, schema: String): Set<String> {
        val tables = linkedSetOf<String>()
        database.rawQuery(
            "SELECT name FROM $schema.sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name != 'android_metadata' ORDER BY name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }
        }
        return tables
    }

    private fun getColumns(database: SQLiteDatabase, schema: String, table: String): List<String> {
        val columns = mutableListOf<String>()
        database.rawQuery(
            "PRAGMA $schema.table_info(${quoteIdentifier(table)})",
            null
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }

    private fun buildIdMap(
        database: SQLiteDatabase,
        table: String,
        identityColumns: List<String>
    ): Map<Long, Long> {
        val currentColumns = getColumns(database, "main", table)
        val previousColumns = getColumns(database, PREVIOUS_SCHEMA, table)
        val idColumn = currentColumns.firstOrNull { it.equals("_id", ignoreCase = true) }
            ?: return emptyMap()
        if (previousColumns.none { it.equals(idColumn, ignoreCase = true) }) {
            return emptyMap()
        }

        val commonIdentityColumns = identityColumns.mapNotNull { expected ->
            currentColumns.firstOrNull { it.equals(expected, ignoreCase = true) }
                ?.takeIf { current -> previousColumns.any { it.equals(current, ignoreCase = true) } }
        }
        if (commonIdentityColumns.isEmpty()) {
            return emptyMap()
        }

        val currentRows = readIdentityRows(database, "main", table, idColumn, commonIdentityColumns)
        val previousRows = readIdentityRows(
            database,
            PREVIOUS_SCHEMA,
            table,
            idColumn,
            commonIdentityColumns
        )
        val currentByIdentity = currentRows.associateBy(IdentityRow::identity)
        val usedIds = currentRows.mapTo(mutableSetOf(), IdentityRow::id)
        var nextId = (currentRows.asSequence() + previousRows.asSequence())
            .maxOfOrNull(IdentityRow::id)
            ?.plus(1L)
            ?: 1L

        return buildMap {
            for (previousRow in previousRows) {
                val matchingCurrent = currentByIdentity[previousRow.identity]
                val mappedId = when {
                    matchingCurrent != null -> matchingCurrent.id
                    usedIds.add(previousRow.id) -> previousRow.id
                    else -> {
                        while (!usedIds.add(nextId)) {
                            nextId++
                        }
                        nextId++
                        nextId - 1L
                    }
                }
                put(previousRow.id, mappedId)
            }
        }
    }

    private fun readIdentityRows(
        database: SQLiteDatabase,
        schema: String,
        table: String,
        idColumn: String,
        identityColumns: List<String>
    ): List<IdentityRow> {
        val selectedColumns = (listOf(idColumn) + identityColumns)
            .joinToString(", ") { quoteIdentifier(it) }
        val rows = mutableListOf<IdentityRow>()
        database.rawQuery(
            "SELECT $selectedColumns FROM $schema.${quoteIdentifier(table)} ORDER BY ${quoteIdentifier(idColumn)}",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val identity = buildString {
                    for (index in identityColumns.indices) {
                        if (index > 0) append('\u0000')
                        if (cursor.isNull(index + 1)) {
                            append("<null>")
                        } else {
                            append(cursor.getString(index + 1))
                        }
                    }
                }
                rows.add(IdentityRow(cursor.getLong(0), identity))
            }
        }
        return rows
    }

    private fun remapExpression(column: String, idMap: Map<Long, Long>): String {
        val quotedColumn = quoteIdentifier(column)
        val changedIds = idMap.filter { (oldId, newId) -> oldId != newId }
        if (changedIds.isEmpty()) {
            return quotedColumn
        }
        return buildString {
            append("CASE ")
            append(quotedColumn)
            for ((oldId, newId) in changedIds) {
                append(" WHEN ")
                append(oldId)
                append(" THEN ")
                append(newId)
            }
            append(" ELSE ")
            append(quotedColumn)
            append(" END")
        }
    }

    private fun checkIntegrity(database: SQLiteDatabase, schema: String) {
        database.rawQuery("PRAGMA $schema.integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                throw IOException("Database integrity check failed for $schema")
            }
        }
    }

    private fun queryLong(database: SQLiteDatabase, query: String): Long {
        database.rawQuery(query, null).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException("Query returned no rows: $query")
            }
            return cursor.getLong(0)
        }
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    private data class IdentityRow(val id: Long, val identity: String)
}
