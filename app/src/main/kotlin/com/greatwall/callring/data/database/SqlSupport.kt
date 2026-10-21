package com.greatwall.callring.data.database

import android.content.ContentValues
import android.database.Cursor

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
