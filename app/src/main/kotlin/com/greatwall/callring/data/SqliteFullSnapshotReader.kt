package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.QuietMode

/** Same resource/session/SQL connection; preflight runs before complete rows are allocated. */
internal class SqliteFullSnapshotReader(
    private val db: SQLiteDatabase,
    private val sql: SqlAccess,
    private val sessions: CoreUnitOfWork,
) : FullSnapshotReader {
    override fun capture(): FullSnapshotFacts = sessions.readSession { reader ->
        checkSnapshotSize(db, sql)
        val calls =
            sql.query(QueryTag.LOAD_ROWS, "calls", null, null, null, null, null, "id").use { c ->
                buildMap {
                    while (c.moveToNext()) {
                        val row = CallingRowCodec.decode(c)
                        put(row.id, row)
                    }
                }
            }
        val intervals =
            sql.query(
                    QueryTag.LOAD_ROWS,
                    "statistics_intervals",
                    arrayOf("start", "end"),
                    null,
                    null,
                    null,
                    null,
                    "id",
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            StatisticsInterval(
                                c.getLong(0),
                                if (c.isNull(1)) null else c.getLong(1),
                            ),
                        )
                    }
                }
        val audits =
            sql.query(
                    QueryTag.AUDIT_ROWS,
                    "calendar_audit",
                    arrayOf("kind", "target", "raw", "created"),
                    null,
                    null,
                    null,
                    null,
                    "id",
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            AuditRecord(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getLong(3),
                            ),
                        )
                    }
                }
        val quiet = reader.quiet()
        FullSnapshotFacts(
            reader.callerPage(0, 20000).associateBy { it.number },
            reader.positions().associateBy { it.id },
            calls,
            reader.workHistory(),
            intervals,
            reader.myPositionId(),
            quiet.settings,
            quiet.settings.mode.takeUnless { it == QuietMode.OFF } ?: quiet.lastMode,
            reader.score(),
            reader.overlay(),
            audits,
        )
    }
}
