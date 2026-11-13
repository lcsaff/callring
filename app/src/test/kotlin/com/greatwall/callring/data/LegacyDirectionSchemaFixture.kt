package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase

/** Recreate shipped pre-direction schemas instead of merely lowering a current schema version. */
internal fun stripDirectionForFixture(db: SQLiteDatabase) {
    stripResetForFixture(db)
    db.setForeignKeyConstraintsEnabled(false)
    for (table in listOf("calls", "recording_events", "archive_projection", "system_recording_sources")) {
        val schema = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
        val columns = db.rawQuery("PRAGMA table_info($table)",null).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(1)) } }
        if ("direction" !in columns) continue
        val indexes = db.rawQuery("SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN('index','trigger') AND sql IS NOT NULL",arrayOf(table)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        val sequence = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?",arrayOf(table)).use { if (it.moveToFirst()) it.getLong(0) else null }
        val previous = "legacy_direction_$table"
        val withoutDirection = schema.replace(Regex(",\\s*direction\\s+TEXT\\s+NOT\\s+NULL\\s+DEFAULT\\s+'INCOMING'(?:\\s+CHECK\\(direction IN\\([^)]*\\)\\))?"), "")
        check(withoutDirection != schema)
        db.execSQL(withoutDirection.replaceFirst(Regex("(?i)CREATE TABLE\\s+\"?$table\"?"),"CREATE TABLE $previous"))
        val retained = columns.filter { it != "direction" }.joinToString(",")
        db.execSQL("INSERT INTO $previous($retained) SELECT $retained FROM $table")
        db.execSQL("DROP TABLE $table")
        db.execSQL("ALTER TABLE $previous RENAME TO $table")
        indexes.forEach(db::execSQL)
        if (sequence != null) {
            db.execSQL("DELETE FROM sqlite_sequence WHERE name=?",arrayOf(table))
            db.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)",arrayOf<Any>(table,sequence))
        }
    }
}

/** Schema 7 has direction but predates reset provenance and the private pause projection. */
internal fun stripResetForFixture(db: SQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS recording_retained_origins")
    db.execSQL("DROP TABLE IF EXISTS recording_retained_events")
    val hasPause=db.rawQuery("PRAGMA table_info(archive_projection)",null).use { c -> var found=false;while(c.moveToNext())if(c.getString(1)=="reset_paused")found=true;found }
    if(hasPause) {
        val schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE name='archive_projection'",null).use {it.moveToFirst();it.getString(0)}
        val indexes=db.rawQuery("SELECT sql FROM sqlite_master WHERE tbl_name='archive_projection' AND type='index' AND sql IS NOT NULL",null).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        val columns=db.rawQuery("PRAGMA table_info(archive_projection)",null).use {c->buildList {while(c.moveToNext())if(c.getString(1)!="reset_paused")add(c.getString(1))}}.joinToString(",")
        val old=schema.replace(Regex(",\\s*reset_paused\\s+INTEGER\\s+NOT\\s+NULL\\s+DEFAULT\\s+0"),"")
        check(old!=schema)
        db.execSQL(old.replaceFirst(Regex("(?i)CREATE TABLE\\s+\"?archive_projection\"?"),"CREATE TABLE reset_legacy_projection"))
        db.execSQL("INSERT INTO reset_legacy_projection($columns) SELECT $columns FROM archive_projection")
        db.execSQL("DROP TABLE archive_projection")
        db.execSQL("ALTER TABLE reset_legacy_projection RENAME TO archive_projection")
        indexes.forEach(db::execSQL)
    }
}
