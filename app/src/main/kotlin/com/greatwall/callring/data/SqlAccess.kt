package com.greatwall.callring.data

import android.database.Cursor
import android.database.CursorWrapper
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/** Fixed labels only: observations never retain SQL, bind values, or exception messages. */
enum class QueryTag { SNAPSHOT_ROWS, LOAD_ROWS, NEXT_ID_MAX, SEQUENCE, AGGREGATE, SCOPED_ROWS, SCOPED_TEXT, SCOPED_SETTING, WORK_ROWS, PREFLIGHT_SCHEMA, PREFLIGHT_AGGREGATE, AUDIT_ROWS, DIRECT_UPDATE, DIRECT_INSERT, DIRECT_DELETE }
enum class SqlTable(val sqlName: String) {
    CALLS("calls"), CALLERS("callers"), POSITIONS("positions"), SETTINGS("settings"), STATISTICS_INTERVALS("statistics_intervals"), CONFIG_VERSIONS("config_versions"), CALENDAR_YEARS("calendar_years"), CALENDAR_DAYS("calendar_days"), OVERRIDES("overrides"), LEAVES("leaves"), CALENDAR_AUDIT("calendar_audit"), SQLITE_SEQUENCE("sqlite_sequence"), SQLITE_MASTER("sqlite_master"), OTHER("other");
    companion object { internal fun named(name: String) = entries.firstOrNull { it.sqlName == name } ?: OTHER }
}

data class SqlAccessMeasurement(val tag: QueryTag, val tables: Set<SqlTable>, val calls: Long, val consumedRows: Long, val closed: Long, val failures: Long, val writtenRows: Long = 0)

/** Instance-owned leaf counter; no callbacks, SQL/fence access, or global database registry. */
class SqlAccessProbe {
    private data class Key(val tag: QueryTag, val tables: Set<SqlTable>)
    private class Counts(var calls: Long = 0, var rows: Long = 0, var closed: Long = 0, var failures: Long = 0, var writtenRows: Long = 0)
    private val counts = linkedMapOf<Key, Counts>()
    @Synchronized internal fun record(tag: QueryTag, tables: Set<SqlTable>, calls: Long = 0, rows: Long = 0, closed: Long = 0, failures: Long = 0, writtenRows: Long = 0) {
        val value = counts.getOrPut(Key(tag, tables.toSet())) { Counts() }
        value.calls += calls; value.rows += rows; value.closed += closed; value.failures += failures
        value.writtenRows += writtenRows
    }
    @Synchronized fun snapshot(): List<SqlAccessMeasurement> = counts.map { (key, value) ->
        SqlAccessMeasurement(key.tag, key.tables.toSet(), value.calls, value.rows, value.closed, value.failures, value.writtenRows)
    }
}

/** Keeps SQLite values and statements unchanged. Rows mean successful cursor moves, not engine scans. */
internal class SqlAccess(val database: SQLiteDatabase, private val probe: SqlAccessProbe? = null) {
    private fun write(tag: QueryTag, table: String, block: () -> Long): Long {
        val tables = setOf(SqlTable.named(table))
        probe?.record(tag, tables, calls = 1)
        return try { block().also { probe?.record(tag, tables, writtenRows = it) } }
        catch (failure: RuntimeException) { probe?.record(tag, tables, failures = 1); throw failure }
    }
    fun update(table: String, values: android.content.ContentValues, where: String, args: Array<String>): Int =
        write(QueryTag.DIRECT_UPDATE, table) { database.update(table, values, where, args).toLong() }.toInt()
    fun insert(table: String, values: android.content.ContentValues) {
        write(QueryTag.DIRECT_INSERT, table) { database.insertOrThrow(table, null, values); 1L }
    }
    fun insertReturningId(table: String, values: android.content.ContentValues): Long {
        var id = 0L
        write(QueryTag.DIRECT_INSERT, table) { id = database.insertOrThrow(table, null, values); 1L }
        return id
    }
    fun delete(table: String, where: String, args: Array<String>): Int =
        write(QueryTag.DIRECT_DELETE, table) { database.delete(table, where, args).toLong() }.toInt()
    private fun cursor(tag: QueryTag, tables: Set<SqlTable>, open: () -> Cursor): Cursor {
        val observer = probe ?: return open()
        val labels = tables.toSet()
        observer.record(tag, labels, calls = 1)
        val source = try { open() } catch (e: RuntimeException) {
            observer.record(tag, labels, failures = 1); throw e
        }
        return object : CursorWrapper(source) {
            private var closedOnce = false
            private fun move(block: () -> Boolean): Boolean = try {
                block().also { if (it) observer.record(tag, labels, rows = 1) }
            } catch (e: RuntimeException) { observer.record(tag, labels, failures = 1); throw e }
            override fun moveToFirst() = move { source.moveToFirst() }
            override fun moveToNext() = move { source.moveToNext() }
            override fun moveToLast() = move { source.moveToLast() }
            override fun moveToPrevious() = move { source.moveToPrevious() }
            override fun moveToPosition(position: Int) = move { source.moveToPosition(position) }
            override fun move(offset: Int) = move { source.move(offset) }
            override fun close() {
                if (closedOnce) return
                closedOnce = true
                try { source.close() } finally { observer.record(tag, labels, closed = 1) }
            }
        }
    }
    fun rawQuery(tag: QueryTag, tables: Set<SqlTable>, sql: String, parameters: Array<String>?): Cursor =
        cursor(tag, tables) { database.rawQuery(sql, parameters) }
    fun query(tag: QueryTag, table: String, columns: Array<String>?, selection: String?, args: Array<String>?, groupBy: String?, having: String?, orderBy: String?): Cursor =
        cursor(tag, setOf(SqlTable.named(table))) { database.query(table, columns, selection, args, groupBy, having, orderBy) }
    fun scalar(tag: QueryTag, tables: Set<SqlTable>, sql: String, parameters: Array<String>? = null): Long =
        rawQuery(tag, tables, sql, parameters).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0 }
    // DatabaseUtils uses SQLiteStatement, so observe its single result without replacing the operation.
    private fun <T> value(tag: QueryTag, tables: Set<SqlTable>, read: () -> T): T {
        val observer = probe ?: return read()
        observer.record(tag, tables, calls = 1)
        return try { read().also { observer.record(tag, tables, rows = 1) } }
        catch (e: RuntimeException) { observer.record(tag, tables, failures = 1); throw e }
    }
    fun longForQuery(tag: QueryTag, tables: Set<SqlTable>, sql: String, args: Array<String>?) =
        value(tag, tables) { DatabaseUtils.longForQuery(database, sql, args) }
    fun stringForQuery(tag: QueryTag, tables: Set<SqlTable>, sql: String, args: Array<String>?) =
        value(tag, tables) { DatabaseUtils.stringForQuery(database, sql, args) }
    fun nextId(table: String): Long = maxOf(
        scalar(QueryTag.NEXT_ID_MAX, setOf(SqlTable.named(table)), "SELECT COALESCE(MAX(id),0) FROM $table"),
        scalar(QueryTag.SEQUENCE, setOf(SqlTable.SQLITE_SEQUENCE), "SELECT seq FROM sqlite_sequence WHERE name=?", args(table)),
    ) + 1
}
