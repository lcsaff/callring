package com.greatwall.callring.data.recording

import android.database.sqlite.SQLiteDatabase

/** Reset identities and immutable provider provenance survive replacement and later resync. */
internal object RetainedRecordingHistory {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recording_retained_events(event_id INTEGER PRIMARY KEY CHECK(event_id>0),number TEXT NOT NULL,started INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX retained_events_match ON recording_retained_events(number,started)")
        db.execSQL("CREATE TABLE recording_retained_origins(log_id TEXT PRIMARY KEY,event_id INTEGER NOT NULL,number TEXT NOT NULL,call_started INTEGER NOT NULL,direction TEXT NOT NULL CHECK(direction IN('INCOMING','OUTGOING','UNKNOWN')))")
        db.execSQL("CREATE INDEX retained_origins_event ON recording_retained_origins(event_id)")
    }
}
