package com.greatwall.callring.data

import com.greatwall.callring.application.conversation.query.sourceVersion
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.BackendStore
import com.greatwall.callring.domain.BackendState

/** Android persistence adapter; all business rules live behind the BackendStore port. */
class SqliteBackendStore
@JvmOverloads
constructor(
    context: Context,
    databaseName: String = "callring.db",
    sqlAccessProbe: SqlAccessProbe? = null,
) : BackendStore, com.greatwall.callring.application.ports.store.CoreStorageResource {
    private val helper = CallringDatabase(context.applicationContext, databaseName)
    private val db: SQLiteDatabase
    private val sql: SqlAccess
    val stateLoads = java.util.concurrent.atomic.AtomicLong()
    override val legacy: BackendStore
        get() = this

    override val fullSnapshot: com.greatwall.callring.application.ports.store.FullSnapshotReader
        get() = SqliteFullSnapshotReader(db, sql, sessions)

    private val sessionsDelegate = lazy { SqliteCoreUnitOfWork(db, sql) }
    override val sessions: com.greatwall.callring.application.ports.store.CoreUnitOfWork
        get() = sessionsDelegate.value

    override fun narrowAccessCounts(): Map<String, Long> =
        if (sessionsDelegate.isInitialized())
            mapOf(
                "quietReads" to sessionsDelegate.value.quietReads.get(),
                "quietWrites" to sessionsDelegate.value.quietWrites.get(),
            ) + sessionsDelegate.value.callingAccessCounts()
        else emptyMap()

    override val scoped: com.greatwall.callring.application.ports.ScopedReadPort by lazy {
        com.greatwall.callring.application.conversation.query.CoreScopedReadSource(SqliteScopedReadAdapter(db, { 0L }, sql = sql))
    }

    override fun stateLoadCount() = stateLoads.get()

    fun scopedReads(
        coordinator: com.greatwall.callring.application.BusinessRevisionCoordinator,
    ): com.greatwall.callring.application.ports.ScopedReadPort =
        com.greatwall.callring.application.conversation.query.CoreScopedReadSource(SqliteScopedReadAdapter(db, { domain -> coordinator.sourceVersion(domain) }, sql = sql))

    init {
        helper.setWriteAheadLoggingEnabled(true)
        db =
            try {
                helper.writableDatabase
            } catch (failure: Throwable) {
                helper.close()
                throw failure
            }
        sql = SqlAccess(db, sqlAccessProbe)
    }

    @Synchronized
    override fun <T> read(block: (BackendState) -> T): T {
        db.beginTransactionNonExclusive()
        try {
            stateLoads.incrementAndGet()
            val state = StorageSession(db, sql).load()
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
            val session = StorageSession(db, sql)
            stateLoads.incrementAndGet()
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
internal class StorageSession(
    private val db: SQLiteDatabase,
    private val sql: SqlAccess = SqlAccess(db),
) {
    private val profiles = ProfileStore(db, sql)
    private val settings = SettingsStore(db, sql)
    private val schedules = ScheduleStore(db, sql)
    private val calls = CallStore(db, sql)

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
                state.schedules.map { it.configVersion }.toSet().size == state.schedules.size,
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
        if (
            sequence <=
                sql.scalar(
                    QueryTag.SEQUENCE,
                    setOf(SqlTable.SQLITE_SEQUENCE),
                    "SELECT seq FROM sqlite_sequence WHERE name=?",
                    args(table),
                )
        )
            return
        val existing =
            sql.scalar(
                QueryTag.AGGREGATE,
                setOf(SqlTable.SQLITE_SEQUENCE),
                "SELECT COUNT(*) FROM sqlite_sequence WHERE name=?",
                args(table),
            )
        check(existing <= 1L) { "Duplicate sequence rows for $table" }
        if (existing == 0L)
            check(
                db.insertOrThrow(
                    "sqlite_sequence",
                    null,
                    values("name" to table, "seq" to sequence),
                ) != -1L,
            ) {
                "Sequence insert was ignored for $table"
            }
        else
            check(
                db.update("sqlite_sequence", values("seq" to sequence), "name=?", args(table)) == 1,
            ) {
                "Sequence update was ignored for $table"
            }
    }
}
