package com.greatwall.callring.data.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** SQLite performs row copies on disk, so a multi-megabyte TEXT row never enters the JVM heap. */
internal object SqliteBackupCopy {
    private val derived = setOf("archive_projection", "archive_text_fields", "archive_text_chunks", "archive_index_progress", "archive_raw_chunks", "archive_parse_progress")
    private data class Schema(val type: String, val name: String, val sql: String)

    fun copy(source: File, destination: File, check: () -> Unit) {
        require(!destination.exists())
        check()
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { input ->
            val schema = schema(input)
            SQLiteDatabase.openOrCreateDatabase(destination, null).use { output ->
                schema.filter { it.type == "table" && it.name != "sqlite_sequence" }.forEach { check(); output.execSQL(it.sql) }
                output.version = input.version
            }
            // Each INSERT SELECT uses an SQLite read snapshot and a destination-only write.
            // The caller holds no business lock and rejects the whole stage if its coordinator
            // marker changes between the initial and final guarded sample.
            input.execSQL("ATTACH DATABASE ? AS backup_stage", arrayOf(destination.absolutePath))
            try {
                schema.filter { it.type == "table" && it.name !in derived && it.name != "sqlite_sequence" }.forEach {
                    check()
                    input.execSQL("INSERT INTO backup_stage.${quote(it.name)} SELECT * FROM main.${quote(it.name)}")
                }
                if (schema.any { it.name == "sqlite_sequence" }) {
                    input.execSQL("DELETE FROM backup_stage.sqlite_sequence")
                    input.execSQL("INSERT INTO backup_stage.sqlite_sequence SELECT * FROM main.sqlite_sequence")
                }
            } finally { input.execSQL("DETACH DATABASE backup_stage") }
            SQLiteDatabase.openDatabase(destination.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { output ->
                schema.filter { it.type != "table" }.forEach { check(); output.execSQL(it.sql) }
            }
        }
        validate(destination, source)
    }

    fun validate(candidate: File, installed: File) {
        SQLiteDatabase.openDatabase(candidate.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { staged ->
            SQLiteDatabase.openDatabase(installed.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { current ->
                ensure(staged.version == current.version)
                ensure(schema(staged).toSet() == schema(current).toSet())
            }
            staged.rawQuery("PRAGMA integrity_check", null).use { ensure(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
            staged.rawQuery("PRAGMA foreign_key_check", null).use { ensure(!it.moveToFirst()) }
        }
    }

    fun count(file: File, table: String): Long = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
        DatabaseUtils.longForQuery(it, "SELECT count(*) FROM ${quote(table)}", null)
    }

    private fun schema(db: SQLiteDatabase): List<Schema> = db.rawQuery(
        "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND NOT(type='table' AND name='android_metadata') AND name NOT LIKE 'sqlite_autoindex%' ORDER BY type,name", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2))) } }
    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun ensure(value: Boolean) { if (!value) throw BackupFormatException() }
}
