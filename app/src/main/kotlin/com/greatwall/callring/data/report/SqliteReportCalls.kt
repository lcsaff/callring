package com.greatwall.callring.data.report

import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable

import com.greatwall.callring.application.ports.store.CallHistoryBoundary
import com.greatwall.callring.application.ports.store.ReportCallFact
import com.greatwall.callring.data.*
import com.greatwall.callring.data.callers.CallerRowCodec

/** Filtering, ordering and direct-origin projection only; all report formulas live in backend. */
internal class SqliteReportCalls(private val sql: SqlAccess, private val lease: (Boolean) -> Unit) {
    private val columns =
        CallerRowCodec.callColumns
            .split(",")
            .flatMap { column ->
                listOf("c.$column AS c_$column", "s.$column AS s_$column")
            }
            .joinToString(",")

    private fun visit(
        where: String,
        parameters: Array<String>,
        order: String,
        index: String = "",
        accept: (ReportCallFact) -> Unit,
    ) {
        lease(false)
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLS),
                "SELECT $columns FROM calls c$index LEFT JOIN calls s ON s.id=c.linked_screen_id WHERE c.hidden=0 $where ORDER BY $order",
                parameters,
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    accept(
                        ReportCallFact(
                            CallerRowCodec.raw(cursor, "c_"),
                            if (cursor.isNull(cursor.getColumnIndexOrThrow("s_id"))) null
                            else CallerRowCodec.raw(cursor, "s_"),
                        ),
                    )
                }
            }
    }

    fun visitReportCalls(
        number: String,
        start: Long,
        end: Long,
        ascending: Boolean,
        accept: (ReportCallFact) -> Unit,
    ) {
        val direction = if (ascending) "ASC" else "DESC"
        visit(
            "AND c.number=? AND c.time>=? AND c.time<? AND c.direction='INCOMING'",
            arrayOf(number, start.toString(), end.toString()),
            "c.time $direction,c.id $direction",
            accept = accept,
        )
    }

    fun visibleCallHistory(number: String?, after: CallHistoryBoundary?, limit: Int): List<ReportCallFact> = buildList {
        require(limit in 1..101)
        val scope = if (number == null) "" else "AND c.number=?"
        val parameters = if (number == null) emptyArray() else arrayOf(number)
        val index = if (number == null) "" else " INDEXED BY calls_visible_number_time"
        // Seek into the two disjoint ranges without scanning the skipped prefix.
        if (after != null) {
            visit(
                "$scope AND c.time=? AND c.id<?",
                parameters + arrayOf(after.time.toString(), after.id.toString()),
                "c.time DESC,c.id DESC LIMIT $limit",
                index,
                ::add,
            )
        }
        val remaining = limit - size
        if (remaining > 0) {
            visit(
                if (after == null) scope else "$scope AND c.time<?",
                if (after == null) parameters else parameters + after.time.toString(),
                "c.time DESC,c.id DESC LIMIT $remaining",
                index,
                ::add,
            )
        }
    }

    fun visibleCallHistoryDetail(id: Long): ReportCallFact? {
        var result: ReportCallFact? = null
        visit("AND c.id=?", arrayOf(id.toString()), "c.id") { result = it }
        return result
    }

    fun latestVisibleReportCall(): ReportCallFact? {
        var result: ReportCallFact? = null
        visit("", emptyArray(), "c.time DESC,c.id DESC LIMIT 1") { result = it }
        return result
    }
}
