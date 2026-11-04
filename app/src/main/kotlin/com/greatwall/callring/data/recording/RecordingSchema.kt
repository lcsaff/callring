package com.greatwall.callring.data.recording

import android.database.sqlite.SQLiteDatabase

/** One migration path for the live database and private legacy backup candidates. */
internal object RecordingSchema {
    fun upgrade(db: SQLiteDatabase, old: Int) {
        require(old in 1..6)
        if (old < 2) {
            db.execSQL("CREATE TABLE recording_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, number TEXT NOT NULL, started INTEGER NOT NULL, account TEXT)")
            db.execSQL("CREATE TABLE recording_statistics_links (statistics_id INTEGER PRIMARY KEY, session_id TEXT NOT NULL)")
        }
        if (old < 3) ArchiveProjectionIndex.create(db)
        if (old < 4) ArchiveProjectionIndex.createProgress(db)
        if (old < 5) RecordingArchivePages.create(db)
        if (old < 6) SystemRecordingImports.create(db)
    }
}
