package com.greatwall.callring.data.database

import com.greatwall.callring.data.query.SqliteScopedReadAdapter

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Owns one SQLite lifecycle and exposes explicit storage capabilities. */
class SqliteCoreStorageResource
constructor(
    context: Context,
    databaseName: String,
    sqlAccessProbe: SqlAccessProbe? = null,
) : com.greatwall.callring.application.ports.store.CoreStorageResource {
    private val helper = CallringDatabase(context.applicationContext, databaseName)
    private val db: SQLiteDatabase
    private val sql: SqlAccess
    val completeCaptures = java.util.concurrent.atomic.AtomicLong()
    override val fullSnapshot: com.greatwall.callring.application.ports.store.FullSnapshotReader by lazy {
        val reader = SqliteFullSnapshotReader(db, sql, sessions)
        com.greatwall.callring.application.ports.store.FullSnapshotReader {
            completeCaptures.incrementAndGet()
            reader.capture()
        }
    }

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

    override val scoped: com.greatwall.callring.application.ports.query.ScopedReadPort by lazy {
        com.greatwall.callring.application.conversation.query.CoreScopedReadSource(SqliteScopedReadAdapter(db, { 0L }, sql = sql))
    }

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
    override fun close() {
        helper.close()
    }
}
