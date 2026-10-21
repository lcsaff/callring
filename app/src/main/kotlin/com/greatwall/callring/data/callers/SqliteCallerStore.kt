package com.greatwall.callring.data.callers

import com.greatwall.callring.data.codec.LegacyValueCodec
import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.values
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord

import android.database.Cursor
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.data.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.NumberIdentity

internal class SqliteCallerStore(private val sql: SqlAccess, private val lease: (Boolean) -> Unit) :
    CallerWriter, CallerCallReader {
    private val profileColumns = "number,name,department,position_id,whitelist"

    private fun profile(c: Cursor) =
        ProfileRecord(
            c.getString(0),
            c.getString(1),
            c.getString(2),
            if (c.isNull(3)) null else c.getLong(3),
            c.getInt(4) != 0,
        )

    private fun profiles(where: String, parameters: Array<String>) =
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLERS),
                "SELECT $profileColumns FROM callers $where",
                parameters,
            )
            .use { c -> buildList { while (c.moveToNext()) add(profile(c)) } }

    fun callerPositions(ids: Set<Long>): List<PositionRecord> {
        lease(false)
        if (ids.isEmpty()) return emptyList()
        return sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.POSITIONS),
                "SELECT id,name,level,active FROM positions WHERE id IN (${ids.joinToString(","){"?"}})",
                ids.map { it.toString() }.toTypedArray(),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        PositionRecord(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) != 0),
                    )
                }
            }
    }

    override fun caller(number: String): ProfileRecord? {
        lease(false)
        return profiles("WHERE number=?", arrayOf(number)).firstOrNull()
    }

    override fun callerCount(): Long {
        lease(false)
        return sql.scalar(
            QueryTag.AGGREGATE,
            setOf(SqlTable.CALLERS),
            "SELECT COUNT(*) FROM callers",
        )
    }

    override fun callerPage(offset: Long, limit: Int): List<ProfileRecord> {
        lease(false)
        return profiles(
            "ORDER BY number LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        )
    }

    override fun callers(numbers: List<String>): List<ProfileRecord> {
        lease(false)
        return if (numbers.isEmpty()) emptyList()
        else profiles("WHERE number IN (${numbers.joinToString(","){"?"}})", numbers.toTypedArray())
    }

    override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit) {
        lease(false)
        var offset = 0L
        do {
            var count = 0
            sql.rawQuery(
                    QueryTag.SCOPED_ROWS,
                    setOf(SqlTable.CALLERS),
                    "SELECT number,name,department FROM callers ORDER BY number LIMIT 256 OFFSET ?",
                    arrayOf(offset.toString()),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        accept(CallerSearchRow(c.getString(0), c.getString(1), c.getString(2)))
                        count++
                    }
                }
            offset += count
        } while (count == 256)
    }

    override fun putCaller(value: ProfileRecord, insert: Boolean) {
        lease(true)
        if (insert) {
            val v =
                values(
                    "number" to value.number,
                    "name" to value.name,
                    "department" to value.department,
                    "position_id" to value.positionId,
                    "whitelist" to value.whitelisted,
                )
            check(sql.insertReturningId("callers", v) != -1L) { "storage.caller.insert" }
        } else {
            val existing = checkNotNull(caller(value.number)) { "storage.caller.update" }
            val changed = values().apply {
                if (existing.name != value.name) put("name", value.name)
                if (existing.department != value.department) put("department", value.department)
                if (existing.positionId != value.positionId) {
                    if (value.positionId == null) putNull("position_id")
                    else put("position_id", value.positionId)
                }
                if (existing.whitelisted != value.whitelisted)
                    put("whitelist", if (value.whitelisted) 1 else 0)
            }
            if (changed.size() == 0) return
            check(sql.update("callers", changed, "number=?", arrayOf(value.number)) == 1) {
                "storage.caller.update"
            }
        }
    }

    override fun removeCaller(number: String) {
        lease(true)
        check(sql.delete("callers", "number=?", arrayOf(number)) == 1) { "storage.caller.delete" }
    }

    override fun matchingCallNumbers(number: String): List<String> {
        lease(false)
        val pattern = number.toCharArray().joinToString("*", prefix = "*", postfix = "*")
        return sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLS),
                "SELECT DISTINCT number FROM calls WHERE number GLOB ?",
                arrayOf(pattern),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val raw = c.getString(0)
                        if (NumberIdentity.parse(raw).normalized == number) add(raw)
                    }
                }
            }
    }

    private fun predicate(batch: List<String>) = "number IN (${batch.joinToString(","){"?"}})"

    override fun callCount(numbers: List<String>): Long {
        lease(false)
        return numbers.chunked(400).sumOf {
            sql.scalar(
                QueryTag.AGGREGATE,
                setOf(SqlTable.CALLS),
                "SELECT COUNT(*) FROM calls WHERE ${predicate(it)}",
                it.toTypedArray(),
            )
        }
    }

    override fun visitCallerCallIdentities(numbers: List<String>, accept: (Long, String) -> Unit) {
        lease(false)
        numbers.chunked(400).forEach { batch ->
            sql.rawQuery(
                    QueryTag.SCOPED_ROWS,
                    setOf(SqlTable.CALLS),
                    "SELECT id,number FROM calls WHERE ${predicate(batch)}",
                    batch.toTypedArray(),
                )
                .use { c -> while (c.moveToNext()) accept(c.getLong(0), c.getString(1)) }
        }
    }

    override fun removeCallerCalls(numbers: List<String>, expectedCount: Long) {
        lease(true)
        // Unlink every target before deleting any batch. External references deliberately retain FK
        // protection.
        numbers.chunked(400).forEach { batch ->
            val expected =
                sql.scalar(
                    QueryTag.AGGREGATE,
                    setOf(SqlTable.CALLS),
                    "SELECT COUNT(*) FROM calls WHERE ${predicate(batch)} AND linked_screen_id IS NOT NULL",
                    batch.toTypedArray(),
                )
            if (expected > 0)
                check(
                    sql.update(
                            "calls",
                            values("linked_screen_id" to null),
                            "${predicate(batch)} AND linked_screen_id IS NOT NULL",
                            batch.toTypedArray(),
                        )
                        .toLong() == expected,
                ) {
                    "storage.caller.unlink"
                }
        }
        val removed =
            numbers.chunked(400).sumOf {
                sql.delete("calls", predicate(it), it.toTypedArray()).toLong()
            }
        check(removed == expectedCount) { "storage.caller.calls.delete" }
    }

    private fun one(where: String, params: Array<String>): RawCallRecord? =
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLS),
                "SELECT ${CallerRowCodec.callColumns} FROM calls $where",
                params,
            )
            .use { if (it.moveToFirst()) CallerRowCodec.raw(it) else null }

    override fun callerCall(id: Long): RawCallRecord? {
        lease(false)
        return one("WHERE id=?", arrayOf(id.toString()))
    }

    override fun latestCallerCall(): RawCallRecord? {
        lease(false)
        return one("WHERE hidden=0 ORDER BY time DESC,id DESC LIMIT 1", emptyArray())
    }

    override fun visitVisibleCallerCalls(number: String, accept: (CallerOverviewFact) -> Unit) {
        lease(false)
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLS),
                "SELECT id,time,duration,result,counted,working,verified FROM calls WHERE number=? AND hidden=0 ORDER BY time,id",
                arrayOf(number),
            )
            .use { c ->
                while (c.moveToNext()) accept(
                    CallerOverviewFact(
                        c.getLong(0),
                        c.getLong(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        LegacyValueCodec.result(c.getString(3)),
                        c.getInt(4) != 0,
                        c.getInt(5) != 0,
                        c.getInt(6) != 0,
                    ),
                )
            }
    }
}
