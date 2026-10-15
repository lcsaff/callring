package com.greatwall.callring.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.greatwall.callring.application.ports.defaultWorkSchedule

/** Exact shipped version-3 schema. Unsupported versions fail without deleting user data. */
internal class CallringDatabase(private val context: Context, name: String) :
    SQLiteOpenHelper(context, name, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE positions(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,level INTEGER NOT NULL CHECK(level>0),active INTEGER NOT NULL CHECK(active IN(0,1)))"
        )
        db.execSQL(
            "CREATE TABLE callers(number TEXT PRIMARY KEY,department TEXT NOT NULL DEFAULT '',name TEXT NOT NULL DEFAULT '',position_id INTEGER REFERENCES positions(id) ON DELETE RESTRICT,whitelist INTEGER NOT NULL DEFAULT 0 CHECK(whitelist IN(0,1)))"
        )
        db.execSQL(
            "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT,position_id INTEGER REFERENCES positions(id) ON DELETE RESTRICT)"
        )
        db.execSQL(
            "CREATE TABLE config_versions(id INTEGER PRIMARY KEY AUTOINCREMENT,effective INTEGER NOT NULL UNIQUE,snapshot TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE statistics_intervals(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER,CHECK(end IS NULL OR end>=start))"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX statistics_one_open ON statistics_intervals((1)) WHERE end IS NULL"
        )
        db.execSQL("CREATE INDEX statistics_range ON statistics_intervals(start,end)")
        db.execSQL(
            "CREATE TABLE calendar_years(year INTEGER PRIMARY KEY,source TEXT NOT NULL,published TEXT NOT NULL,version TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE calendar_days(date TEXT PRIMARY KEY,year INTEGER NOT NULL REFERENCES calendar_years(year) ON DELETE RESTRICT,working INTEGER NOT NULL CHECK(working IN(0,1)),name TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX calendar_year ON calendar_days(year)")
        db.execSQL("CREATE TABLE overrides(date TEXT PRIMARY KEY,windows TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE leaves(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL CHECK(end>start),note TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX leaves_range ON leaves(start,end)")
        db.execSQL(
            "CREATE TABLE calendar_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,target TEXT NOT NULL,raw TEXT NOT NULL,created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TRIGGER calendar_audit_no_update BEFORE UPDATE ON calendar_audit BEGIN SELECT RAISE(ABORT,'Audit is immutable'); END"
        )
        db.execSQL(
            "CREATE TRIGGER calendar_audit_no_delete BEFORE DELETE ON calendar_audit BEGIN SELECT RAISE(ABORT,'Audit is immutable'); END"
        )
        db.execSQL(
            "CREATE TABLE calls(id INTEGER PRIMARY KEY AUTOINCREMENT,number TEXT NOT NULL REFERENCES callers(number) ON DELETE RESTRICT,time INTEGER NOT NULL,duration INTEGER CHECK(duration IS NULL OR duration>=0),result TEXT NOT NULL,decision TEXT NOT NULL,source TEXT NOT NULL,log_id TEXT UNIQUE,screening_key TEXT UNIQUE,counted INTEGER NOT NULL CHECK(counted IN(0,1)),working INTEGER NOT NULL,verified INTEGER NOT NULL,reason TEXT NOT NULL,original_working INTEGER NOT NULL,original_verified INTEGER NOT NULL,original_reason TEXT NOT NULL,config_version INTEGER NOT NULL REFERENCES config_versions(id),original_config_version INTEGER NOT NULL REFERENCES config_versions(id),calendar_version TEXT NOT NULL,original_calendar_version TEXT NOT NULL,leave_version INTEGER NOT NULL,original_leave_version INTEGER NOT NULL,zone TEXT NOT NULL,original_zone TEXT NOT NULL,account TEXT,hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN(0,1)),linked_screen_id INTEGER REFERENCES calls(id) ON DELETE RESTRICT)"
        )
        db.execSQL("CREATE INDEX calls_number_time ON calls(number,time)")
        db.execSQL("CREATE INDEX calls_visible_time ON calls(time DESC,id DESC) WHERE hidden=0")
        db.execSQL("CREATE INDEX calls_pending ON calls(number,time) WHERE log_id IS NULL")
        db.execSQL(
            "CREATE UNIQUE INDEX calls_screen_link ON calls(linked_screen_id) WHERE linked_screen_id IS NOT NULL"
        )
        check(
            db.insertOrThrow(
                "config_versions",
                null,
                values(
                    "effective" to 0L,
                    "snapshot" to WorkConfigCodec.encode(defaultWorkSchedule()),
                ),
            ) != -1L
        ) {
            "Initial configuration insert was ignored"
        }
        context.assets.open("holidays-2026.json").use { stream ->
            val raw = WorkConfigCodec.readLimited(stream)
            val data = WorkConfigCodec.calendar(raw)
            val session = StorageSession(db)
            val state = session.load()
            state.calendarYears[data.metadata.year] = data.metadata
            state.holidays.putAll(data.holidays)
            state.makeupDates.addAll(data.makeupDates)
            state.pendingAudits.add(
                com.greatwall.callring.application.ports.AuditRecord(
                    "calendar",
                    data.metadata.year.toString(),
                    raw,
                    System.currentTimeMillis(),
                )
            )
            session.save(state)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        StorageMigration.upgrade(db, oldVersion, newVersion)
    }
}
