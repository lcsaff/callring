package com.greatwall.callring.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

internal fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))

internal fun Cursor.optionalText(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

internal fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

internal fun Cursor.optionalLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

internal fun Cursor.flag(column: String): Boolean = long(column) == 1L

internal fun args(vararg values: Any): Array<String> = values.map { it.toString() }.toTypedArray()

internal fun values(vararg entries: Pair<String, Any?>): ContentValues =
    ContentValues().apply {
        entries.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, if (value) 1 else 0)
                else -> error("Unsupported SQLite value: ${value.javaClass}")
            }
        }
    }

internal fun SQLiteDatabase.scalar(sql: String, parameters: Array<String>? = null): Long =
    rawQuery(sql, parameters).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0 }

internal fun SQLiteDatabase.nextId(table: String): Long =
    maxOf(
        scalar("SELECT COALESCE(MAX(id),0) FROM $table"),
        scalar("SELECT seq FROM sqlite_sequence WHERE name=?", args(table)),
    ) + 1

/** Captures original SQL values, preserving unknown legacy strings for untouched fields. */
internal class TableSnapshot(
    private val db: SQLiteDatabase,
    private val table: String,
    private val key: String,
) {
    private val original = linkedMapOf<String, ContentValues>()

    init {
        db.query(table, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                // DatabaseUtils.cursorRowToContentValues converts every non-blob value to String.
                // Keep SQLite types so unchanged numeric columns stay out of UPDATE statements.
                val row = ContentValues()
                for (column in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(column)
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> row.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(column))
                        Cursor.FIELD_TYPE_BLOB -> row.put(name, cursor.getBlob(column))
                        else -> row.put(name, cursor.getString(column))
                    }
                }
                original[row.getAsString(key)] = row
            }
        }
    }

    fun save(id: Any, desired: ContentValues) {
        val previous = original[id.toString()]
        if (previous == null) {
            check(db.insertOrThrow(table, null, desired) != -1L) { "$table insert was ignored" }
        } else {
            val changes = ContentValues()
            desired.valueSet().forEach { (column, value) ->
                val old = previous.get(column)
                if (!sameValue(old, value)) copyValue(changes, desired, column)
            }
            if (changes.size() > 0)
                check(db.update(table, changes, "$key=?", args(id)) == 1) {
                    "$table update was ignored"
                }
        }
    }

    fun deleteMissing(ids: Set<*>) {
        val keys = ids.map { it.toString() }.toSet()
        original.keys
            .filterNot { it in keys }
            .forEach {
                check(db.delete(table, "$key=?", args(it)) == 1) { "$table delete was ignored" }
            }
    }

    fun saveReplacing(id: Any, desired: ContentValues) {
        val previous = original[id.toString()]
        if (
            previous == null ||
                desired.valueSet().any { (column, value) ->
                    !sameValue(previous.get(column), value)
                }
        ) {
            check(
                db.insertWithOnConflict(table, null, desired, SQLiteDatabase.CONFLICT_REPLACE) !=
                    -1L
            ) {
                "$table insert was ignored"
            }
        }
    }

    fun previous(id: Any): ContentValues? = original[id.toString()]

    companion object {
        private fun sameValue(left: Any?, right: Any?): Boolean =
            if (left is Number && right is Number) left.toLong() == right.toLong()
            else left == right

        private fun copyValue(target: ContentValues, source: ContentValues, column: String) {
            when (val value = source.get(column)) {
                null -> target.putNull(column)
                is String -> target.put(column, value)
                is Long -> target.put(column, value)
                is Int -> target.put(column, value)
                is Boolean -> target.put(column, value)
                else -> error("Unsupported value $column")
            }
        }
    }
}
