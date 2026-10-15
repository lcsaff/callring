package com.greatwall.callring.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.BackendStore
import com.greatwall.callring.domain.BackendState

/** Android persistence adapter; all business rules live behind the BackendStore port. */
class SqliteBackendStore
@JvmOverloads
constructor(context: Context, databaseName: String = "callring.db") : BackendStore, AutoCloseable {
    private val helper = CallringDatabase(context.applicationContext, databaseName)
    private val db: SQLiteDatabase

    init {
        helper.setWriteAheadLoggingEnabled(true)
        db = helper.writableDatabase
    }

    @Synchronized
    override fun <T> read(block: (BackendState) -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val state = StorageSession(db).load()
            val result = block(state)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun <T> transaction(block: (BackendState) -> T): T {
        // Capture after acquiring SQLite's transaction lock, including across separate adapters.
        db.beginTransaction()
        try {
            val session = StorageSession(db)
            val state = session.load()
            val result = block(state)
            session.save(state)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun close() {
        helper.close()
    }
}

/** Coordinates table adapters and foreign-key ordering, without implementing business decisions. */
internal class StorageSession(private val db: SQLiteDatabase) {
    private val profiles = ProfileStore(db)
    private val settings = SettingsStore(db)
    private val schedules = ScheduleStore(db)
    private val calls = CallStore(db)

    fun load(): BackendState =
        BackendState().also {
            profiles.load(it)
            settings.load(it)
            schedules.load(it)
            calls.load(it)
        }

    fun save(state: BackendState) {
        validateKeys(state)
        profiles.saveReferences(state)
        schedules.save(state)
        settings.save(state)
        calls.save(state)
        profiles.deleteUnreferenced(state)
        advanceSequence("positions", state.nextPositionId)
        advanceSequence("leaves", state.nextLeaveId)
        advanceSequence("calls", state.nextCallId)
        advanceSequence("config_versions", state.nextConfigVersion)
    }

    fun saveCalls(state: BackendState) {
        calls.save(state)
    }

    private fun validateKeys(state: BackendState) {
        require(state.positions.all { (id, row) -> id > 0 && id == row.id }) {
            "Invalid position primary key"
        }
        require(state.leaves.all { (id, row) -> id > 0 && id == row.id }) {
            "Invalid leave primary key"
        }
        require(state.calls.all { (id, row) -> id > 0 && id == row.id }) {
            "Invalid call primary key"
        }
        require(state.profiles.all { (number, row) -> number == row.number }) {
            "Invalid profile primary key"
        }
        require(
            state.schedules.all { it.configVersion > 0 } &&
                state.schedules.map { it.configVersion }.toSet().size == state.schedules.size
        ) {
            "Invalid configuration primary key"
        }
        require(state.nextPositionId > (state.positions.keys.maxOrNull() ?: 0)) {
            "Invalid next position ID"
        }
        require(state.nextLeaveId > (state.leaves.keys.maxOrNull() ?: 0)) {
            "Invalid next leave ID"
        }
        require(state.nextCallId > (state.calls.keys.maxOrNull() ?: 0)) { "Invalid next call ID" }
        require(state.nextConfigVersion > (state.schedules.maxOfOrNull { it.configVersion } ?: 0)) {
            "Invalid next configuration ID"
        }
    }

    private fun advanceSequence(table: String, next: Long) {
        val sequence = next - 1
        if (sequence <= db.scalar("SELECT seq FROM sqlite_sequence WHERE name=?", args(table)))
            return
        val existing = db.scalar("SELECT COUNT(*) FROM sqlite_sequence WHERE name=?", args(table))
        check(existing <= 1L) { "Duplicate sequence rows for $table" }
        if (existing == 0L)
            check(
                db.insertOrThrow(
                    "sqlite_sequence",
                    null,
                    values("name" to table, "seq" to sequence),
                ) != -1L
            ) {
                "Sequence insert was ignored for $table"
            }
        else
            check(
                db.update("sqlite_sequence", values("seq" to sequence), "name=?", args(table)) == 1
            ) {
                "Sequence update was ignored for $table"
            }
    }
}
