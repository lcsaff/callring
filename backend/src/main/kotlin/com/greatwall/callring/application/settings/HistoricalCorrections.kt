package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.domain.WorkClassifier

/** Existing corrections repair every stored current fact, including hidden/uncounted/stale rows.
 * Batches bound materialization; the caller owns one atomic database transaction for all batches. */
internal fun <T> correctedDelta(writer: HistoryCorrectionWriter, value: T, keys: Set<String>, capture: (WorkHistory) -> Unit): MutationDelta<T> {
    val inputs = writer.workHistory()
    capture(inputs)
    val changedKeys = keys.toMutableSet()
    val numbers = linkedSetOf<String>()
    var after = 0L
    while (true) {
        val batch = writer.classificationBatch(after, 256)
        if (batch.isEmpty()) break
        batch.forEach { row ->
            val corrected = WorkClassifier.classify(inputs, row.time)
            if (corrected != row.current) {
                writer.updateClassification(row, corrected)
                changedKeys += "calls"; changedKeys += "call:${row.id}"; changedKeys += "calls:${row.number}"
                numbers += row.number
            }
        }
        after = batch.last().id
    }
    val domains = mutableMapOf<DataDomain, Set<String>?>(DataDomain.WORK_CALENDAR to setOf(if ("leaves" in keys) "leaves" else "calendar"))
    if (numbers.isNotEmpty()) {
        domains[DataDomain.CALLS] = numbers
        domains[DataDomain.REPORTS] = numbers
    }
    return MutationDelta(value, changedKeys, ChangeImpact(domains), true)
}
