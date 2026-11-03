package com.greatwall.callring.data.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** SQLite performs row copies on disk, so a multi-megabyte TEXT row never enters the JVM heap. */
internal object SqliteBackupCopy {
    private val derived = setOf("archive_projection", "archive_text_fields", "archive_text_chunks", "archive_index_progress", "archive_raw_chunks", "archive_parse_progress")
    private data class Schema(val type: String, val name: String, val sql: String)

    fun copy(source: File, destination: File, configureDestination: (SQLiteDatabase) -> Unit = {},
        check: () -> Unit) {
        require(!destination.exists())
        check()
        // A read-only metadata handle cannot reconfigure the live owner's journal mode.
        val (schema, version) = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            schema(it) to it.version
        }
        SQLiteDatabase.openOrCreateDatabase(destination, null).use { output ->
            schema.filter { it.type == "table" && it.name != "sqlite_sequence" }.forEach { check(); output.execSQL(it.sql) }
            output.version = version
            // Android disables WAL on the connection's main database when ATTACH executes.
            // Keep that main database private; the attached live source remains in WAL mode.
            output.execSQL("ATTACH DATABASE ? AS backup_source", arrayOf(source.absolutePath))
            try {
                configureDestination(output)
                // Each statement has a source read snapshot and a destination-only write.
                // The caller rejects the stage if the guarded source marker has changed.
                schema.filter { it.type == "table" && it.name !in derived && it.name != "sqlite_sequence" }.forEach {
                    check()
                    output.execSQL("INSERT INTO main.${quote(it.name)} SELECT * FROM backup_source.${quote(it.name)}")
                }
                if (schema.any { it.name == "sqlite_sequence" }) {
                    output.execSQL("DELETE FROM main.sqlite_sequence")
                    output.execSQL("INSERT INTO main.sqlite_sequence SELECT * FROM backup_source.sqlite_sequence")
                }
            } finally { output.execSQL("DETACH DATABASE backup_source") }
            schema.filter { it.type != "table" }.forEach { check(); output.execSQL(it.sql) }
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
