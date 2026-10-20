package com.greatwall.callring.domain

import com.greatwall.callring.domain.model.*

/** Candidate degrees always use the complete number index, beyond the bounded write set. */
object CallCorrelation {
    fun reconcile(
        calls: List<RawCallRecord>,
        centers: Collection<Long>? = null,
    ): List<Pair<RawCallRecord, RawCallRecord>> {
        if (centers != null && centers.isEmpty()) return emptyList()
        require(calls.map { it.number }.distinct().size <= 1)
        val rows = calls.sortedWith(compareBy<RawCallRecord> { it.time }.thenBy { it.id })
        val screens = rows.filter { it.logId == null }
        val providers = rows.filter { it.logId != null }
        val cache = HashMap<Long, List<RawCallRecord>>()
        fun candidates(row: RawCallRecord): List<RawCallRecord> =
            cache.getOrPut(row.id) {
                val opposite = if (row.logId == null) providers else screens
                val low = lower(row.time, 5000)
                val high = upper(row.time, 5000)
                var left = 0
                var right = opposite.size
                while (left < right) {
                    val middle = (left + right).ushr(1)
                    if (opposite[middle].time < low) left = middle + 1 else right = middle
                }
                val found = ArrayList<RawCallRecord>(2)
                var index = left
                while (index < opposite.size && opposite[index].time <= high && found.size < 2) {
                    val candidate = opposite[index++]
                    if (
                        row.account == null ||
                            candidate.account == null ||
                            row.account == candidate.account
                    )
                        found.add(candidate)
                }
                found
            }
        val intervals = centers?.sorted()?.map { lower(it, 10000)..upper(it, 10000) }
        var intervalIndex = 0
        val affected =
            if (intervals == null) rows
            else
                rows.filter { row ->
                    while (
                        intervalIndex < intervals.size && intervals[intervalIndex].last < row.time
                    ) intervalIndex++
                    intervalIndex < intervals.size && row.time >= intervals[intervalIndex].first
                }
        return buildList {
            for (row in affected) {
                val matches = candidates(row)
                val linked = matches.size == 1 && candidates(matches[0]).size == 1
                val changed =
                    if (row.logId != null)
                        row.copy(linkedScreenId = if (linked) matches[0].id else null)
                    else
                        row.copy(
                            hidden = linked,
                            counted = matches.isEmpty(),
                            result =
                                if (matches.isEmpty() || linked) CallResult.PENDING
                                else CallResult.UNRESOLVED,
                            legacyResult =
                                if (
                                    row.result ==
                                        (if (matches.isEmpty() || linked) CallResult.PENDING
                                        else CallResult.UNRESOLVED)
                                )
                                    row.legacyResult
                                else null,
                        )
                if (changed != row) add(row to changed)
            }
        }
    }

    private fun lower(at: Long, amount: Long) =
        if (at < Long.MIN_VALUE + amount) Long.MIN_VALUE else at - amount

    private fun upper(at: Long, amount: Long) =
        if (at > Long.MAX_VALUE - amount) Long.MAX_VALUE else at + amount
}
