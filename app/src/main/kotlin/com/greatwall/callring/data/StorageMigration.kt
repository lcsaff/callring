package com.greatwall.callring.data

import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallSource
import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.domain.CallCorrelation
import com.greatwall.callring.domain.WorkClassifier

/** Migrations preserve screening IDs, original observations, profiles and audit history. */
internal object StorageMigration {
    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "Database requires an explicit migration" }
        if (oldVersion == 1) splitMergedRows(db)
        val session = StorageSession(db)
        val state = session.load()
        // Version 2 may contain current classifications evaluated at the provider timestamp.
        state.calls.toMap().forEach { (id, call) ->
            if (call.logId == null) {
                state.calls[id] =
                    call.copy(classification = WorkClassifier.classify(state, call.time))
            }
        }
        if (oldVersion == 1)
            state.calls.values
                .map { it.number }
                .distinct()
                .forEach { CallCorrelation.reconcile(state, it) }
        session.saveCalls(state)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS calls_visible_time ON calls(time DESC,id DESC) WHERE hidden=0"
        )
    }

    private fun splitMergedRows(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE calls ADD COLUMN account TEXT")
        db.execSQL(
            "ALTER TABLE calls ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN(0,1))"
        )
        db.execSQL(
            "ALTER TABLE calls ADD COLUMN linked_screen_id INTEGER REFERENCES calls(id) ON DELETE RESTRICT"
        )
        val merged = mutableListOf<ContentValues>()
        db.rawQuery(
                "SELECT * FROM calls WHERE log_id IS NOT NULL AND screening_key IS NOT NULL",
                null,
            )
            .use { cursor ->
                while (cursor.moveToNext()) merged.add(
                    ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
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
                            com.greatwall.callring.domain.model.CallDecision.SYSTEM_LOG_ONLY
                        ),
                    )
                    put(
                        "source",
                        LegacyValueCodec.encodeSource(
                            com.greatwall.callring.domain.model.CallSource.SYSTEM_CALL_LOG
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
                            com.greatwall.callring.domain.model.CallSource.SCREENING
                        ),
                    "result" to
                        LegacyValueCodec.encodeResult(
                            com.greatwall.callring.domain.model.CallResult.PENDING
                        ),
                    "counted" to 1,
                )
            check(db.update("calls", screen, "id=?", args(screenId)) == 1)
            check(db.insertOrThrow("calls", null, provider) != -1L)
        }
        db.execSQL(
            "CREATE UNIQUE INDEX calls_screen_link ON calls(linked_screen_id) WHERE linked_screen_id IS NOT NULL"
        )
    }
}
