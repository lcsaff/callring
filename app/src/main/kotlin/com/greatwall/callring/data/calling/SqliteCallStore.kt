package com.greatwall.callring.data.calling

import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.args
import com.greatwall.callring.data.database.values

import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.data.*
import com.greatwall.callring.domain.model.RawCallRecord

internal class SqliteCallStore(
    private val sql: SqlAccess,
    private val checkActive: (Boolean) -> Unit,
) : CallingWriter {
    private fun rows(where: String, parameters: Array<String>): List<RawCallRecord> {
        checkActive(false)
        return sql.query(
                QueryTag.SCOPED_ROWS,
                "calls",
                null,
                where,
                parameters,
                null,
                null,
                "time,id",
            )
            .use { c ->
                buildList { while (c.moveToNext()) add(CallingRowCodec.decode(c)) }
            }
    }

    override fun screeningCall(key: String) = rows("screening_key=?", arrayOf(key)).singleOrNull()

    override fun callingCall(id: Long) = rows("id=?", args(id)).singleOrNull()

    override fun visibleRecordingLinks(screenId: Long): List<RawCallRecord> {
        checkActive(false)
        return sql.rawQuery(QueryTag.SCOPED_ROWS, setOf(SqlTable.CALLS),
            "SELECT * FROM calls WHERE hidden=0 AND linked_screen_id=? ORDER BY id LIMIT 2", args(screenId)).use { c ->
            buildList { while (c.moveToNext()) add(CallingRowCodec.decode(c)) }
        }
    }

    override fun providerCalls(logIds: Set<String>): List<RawCallRecord> {
        checkActive(false)
        return logIds.toList().chunked(400).flatMap { batch ->
            rows("log_id IN (${batch.joinToString(",") { "?" }})", batch.toTypedArray())
        }
    }

    override fun correlationCalls(number: String) = rows("number=?", arrayOf(number))

    override fun statisticsContains(at: Long): Boolean {
        checkActive(false)
        return sql.scalar(
            QueryTag.AGGREGATE,
            setOf(SqlTable.STATISTICS_INTERVALS),
            "SELECT EXISTS(SELECT 1 FROM statistics_intervals WHERE start<=? AND (end IS NULL OR ?<end))",
            args(at, at),
        ) != 0L
    }

    override fun statisticsStart(): Long {
        checkActive(false)
        return sql.scalar(
            QueryTag.AGGREGATE,
            setOf(SqlTable.STATISTICS_INTERVALS),
            "SELECT MIN(start) FROM statistics_intervals",
        )
    }

    override fun hasRecentPending(start: Long, end: Long): Boolean {
        checkActive(false)
        return sql.scalar(
            QueryTag.AGGREGATE,
            setOf(SqlTable.CALLS),
            "SELECT EXISTS(SELECT 1 FROM calls WHERE hidden=0 AND counted=1 AND log_id IS NULL AND time>=? AND time<?)",
            args(start, end),
        ) != 0L
    }

    override fun callingWorkHistory(): WorkHistory {
        checkActive(false)
        return com.greatwall.callring.data.settings
            .SqliteHistorySettings(sql, checkActive)
            .workHistory()
    }

    override fun ensureCallingProfile(number: String): Boolean {
        checkActive(true)
        val exists =
            sql.scalar(
                QueryTag.AGGREGATE,
                setOf(SqlTable.CALLERS),
                "SELECT EXISTS(SELECT 1 FROM callers WHERE number=?)",
                arrayOf(number),
            ) != 0L
        if (exists) return false
        check(
            sql.insertReturningId(
                "callers",
                values(
                    "number" to number,
                    "name" to "",
                    "department" to "",
                    "position_id" to null,
                    "whitelist" to false,
                ),
            ) != -1L,
        ) {
            "storage.insert.ignored"
        }
        return true
    }

    override fun insertCallingCall(row: RawCallRecord): RawCallRecord {
        checkActive(true)
        val id =
            sql.insertReturningId("calls", CallingRowCodec.encode(row.copy(linkedScreenId = null)))
        check(id != -1L) { "storage.insert.ignored" }
        return row.copy(id = id)
    }

    override fun updateCallingCalls(changes: List<Pair<RawCallRecord, RawCallRecord>>) {
        checkActive(true)
        changes.forEach { (before, after) ->
            if (before.linkedScreenId != null && before.linkedScreenId != after.linkedScreenId)
                check(
                    sql.update(
                        "calls",
                        values("linked_screen_id" to null),
                        "id=?",
                        args(before.id),
                    ) == 1,
                ) {
                    "storage.update.ignored"
                }
        }
        changes.forEach { (before, after) ->
            val fields = CallingRowCodec.encode(after, before)
            fields.remove("linked_screen_id")
            if (fields.size() > 0)
                check(sql.update("calls", fields, "id=?", args(before.id)) == 1) {
                    "storage.update.ignored"
                }
        }
        changes.forEach { (before, after) ->
            if (before.linkedScreenId != after.linkedScreenId && after.linkedScreenId != null)
                check(
                    sql.update(
                        "calls",
                        values("linked_screen_id" to after.linkedScreenId),
                        "id=?",
                        args(after.id),
                    ) == 1,
                ) {
                    "storage.update.ignored"
                }
        }
    }
}
