package com.greatwall.callring.data.database

import com.greatwall.callring.data.calling.CallingRowCodec
import com.greatwall.callring.data.codec.LegacyValueCodec

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.domain.calling.CallCorrelation
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallSource

/** Migrations preserve screening IDs, original observations, profiles and audit history. */
internal object StorageMigration {
    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "Database requires an explicit migration" }
        if (oldVersion == 1) splitMergedRows(db)
        // onUpgrade already owns the database transaction; do not open another resource/session.
        val sql = SqlAccess(db)
        val calls = com.greatwall.callring.data.calling.SqliteCallStore(sql) {}
        val history = calls.callingWorkHistory()
        val corrections =
            sql.query(QueryTag.SCOPED_ROWS, "calls", null, "log_id IS NULL", null, null, null, "id")
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val before = CallingRowCodec.decode(c)
                            val current = WorkClassifier.classify(history, before.time)
                            if (current != before.classification)
                                add(before to before.copy(classification = current))
                        }
                    }
                }
        calls.updateCallingCalls(corrections)
        if (oldVersion == 1) {
            val numbers =
                sql.rawQuery(
                        QueryTag.SCOPED_ROWS,
                        setOf(SqlTable.CALLS),
                        "SELECT DISTINCT number FROM calls ORDER BY number",
                        null,
                    )
                    .use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
            numbers.forEach { n ->
                calls.updateCallingCalls(CallCorrelation.reconcile(calls.correlationCalls(n)))
            }
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS calls_visible_time ON calls(time DESC,id DESC) WHERE hidden=0",
        )
    }

    private fun splitMergedRows(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE calls ADD COLUMN account TEXT")
        db.execSQL(
            "ALTER TABLE calls ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN(0,1))",
        )
        db.execSQL(
            "ALTER TABLE calls ADD COLUMN linked_screen_id INTEGER REFERENCES calls(id) ON DELETE RESTRICT",
        )
        val merged = mutableListOf<ContentValues>()
        db.rawQuery(
                "SELECT * FROM calls WHERE log_id IS NOT NULL AND screening_key IS NOT NULL",
                null,
            )
            .use { cursor ->
                while (cursor.moveToNext()) merged.add(
                    ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) },
                )
            }
        for (row in merged) {
            val screenId = row.getAsLong("id")
            val screeningKey = row.getAsString("screening_key")
            val screenTime = screeningKey.substringAfterLast(':').toLong()
            val provider =
                ContentValues(row).apply {
                    remove("id")
                    putNull("screening_key")
                    put(
                        "decision",
                        LegacyValueCodec.encodeDecision(
                            com.greatwall.callring.domain.model.CallDecision.SYSTEM_LOG_ONLY,
                        ),
                    )
                    put(
                        "source",
                        LegacyValueCodec.encodeSource(
                            com.greatwall.callring.domain.model.CallSource.SYSTEM_CALL_LOG,
                        ),
                    )
                    put("counted", 1)
                }
            val screen =
                values(
                    "log_id" to null,
                    "duration" to null,
                    "time" to screenTime,
                    "source" to
                        LegacyValueCodec.encodeSource(
                            com.greatwall.callring.domain.model.CallSource.SCREENING,
                        ),
                    "result" to
                        LegacyValueCodec.encodeResult(
                            com.greatwall.callring.domain.model.CallResult.PENDING,
                        ),
                    "counted" to 1,
                )
            check(db.update("calls", screen, "id=?", args(screenId)) == 1)
            check(db.insertOrThrow("calls", null, provider) != -1L)
        }
        db.execSQL(
            "CREATE UNIQUE INDEX calls_screen_link ON calls(linked_screen_id) WHERE linked_screen_id IS NOT NULL",
        )
    }
}
