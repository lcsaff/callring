package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.RawCallRecord

/** Legacy full-snapshot consumers only; calling commands use SqliteCallStore. */
internal class CallStore(
    private val db: SQLiteDatabase,
    private val sql: SqlAccess = SqlAccess(db),
) {
    private val calls = TableSnapshot(db, "calls", "id", sql)
    private val initial = linkedMapOf<Long, RawCallRecord>()

    fun load(state: BackendState) {
        sql.query(QueryTag.LOAD_ROWS, "calls", null, null, null, null, null, "id").use { cursor ->
            while (cursor.moveToNext()) {
                val row = CallingRowCodec.decode(cursor)
                state.calls[row.id] = row
                initial[row.id] = row
            }
        }
        state.nextCallId = sql.nextId("calls")
    }

    fun save(state: BackendState) {
        initial.forEach { (id, old) ->
            if (old.linkedScreenId != null && old.linkedScreenId != state.calls[id]?.linkedScreenId)
                check(db.update("calls", values("linked_screen_id" to null), "id=?", args(id)) == 1)
        }
        state.calls.values.forEach { row ->
            val old = initial[row.id]
            if (old != row) {
                val desired = CallingRowCodec.encode(row, old)
                if (old == null) desired.put("id", row.id)
                desired.remove("linked_screen_id")
                calls.save(row.id, desired)
            }
        }
        state.calls.values.forEach { row ->
            if (initial[row.id]?.linkedScreenId != row.linkedScreenId && row.linkedScreenId != null)
                check(
                    db.update(
                        "calls",
                        values("linked_screen_id" to row.linkedScreenId),
                        "id=?",
                        args(row.id),
                    ) == 1,
                )
        }
        calls.deleteMissing(state.calls.keys)
    }
}
