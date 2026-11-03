package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase

/** A real shipped callers table, usable on the project's Android 33 SQLite version. */
internal fun stripCallerEnrichmentForFixture(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(false)
    db.execSQL("CREATE TABLE legacy_callers(number TEXT PRIMARY KEY,department TEXT NOT NULL DEFAULT '',name TEXT NOT NULL DEFAULT '',position_id INTEGER REFERENCES positions(id) ON DELETE RESTRICT,whitelist INTEGER NOT NULL DEFAULT 0 CHECK(whitelist IN(0,1)))")
    db.execSQL("INSERT INTO legacy_callers(number,department,name,position_id,whitelist) SELECT number,department,name,position_id,whitelist FROM callers")
    db.execSQL("DROP TABLE callers")
    db.execSQL("ALTER TABLE legacy_callers RENAME TO callers")
}
