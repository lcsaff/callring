package com.greatwall.callring.data.report

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
        accept: (ReportCallFact) -> Unit,
    ) {
        lease(false)
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                setOf(SqlTable.CALLS),
                "SELECT $columns FROM calls c LEFT JOIN calls s ON s.id=c.linked_screen_id WHERE c.hidden=0 $where ORDER BY $order",
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
            "AND c.number=? AND c.time>=? AND c.time<?",
            arrayOf(number, start.toString(), end.toString()),
            "c.time $direction,c.id $direction",
            accept,
        )
    }

    fun latestVisibleReportCall(): ReportCallFact? {
        var result: ReportCallFact? = null
        visit("", emptyArray(), "c.time DESC,c.id DESC LIMIT 1") { result = it }
        return result
    }
}
