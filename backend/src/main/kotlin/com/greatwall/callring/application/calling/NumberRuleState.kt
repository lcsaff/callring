package com.greatwall.callring.application.calling

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.application.mapping.toSnapshot
import com.greatwall.callring.application.ports.store.NumberRuleTransactions
import com.greatwall.callring.application.coordination.requireOperationActive

/** Shares the commit fence; snapshots are prepared before any durable mutation. */
class NumberRuleState(private val coordinator: BusinessRevisionCoordinator) {
    private var current: NumberRuleSnapshot? = null
    fun snapshot(): NumberRuleSnapshot = synchronized(coordinator.fence) {
        checkNotNull(current) { "number_rule.unprepared" }
    }
    internal fun prepare(transactions: NumberRuleTransactions,
        operationActive: () -> Boolean): NumberRuleSnapshot = synchronized(coordinator.fence) {
        requireOperationActive(operationActive)
        current ?: transactions.readNumberRule { it.numberRule().toSnapshot() }.also {
            requireOperationActive(operationActive)
            current = it
        }
    }
    internal fun initialized(): Boolean = synchronized(coordinator.fence) { current != null }
    internal fun install(value: NumberRuleSnapshot) = synchronized(coordinator.fence) { current = value }
}
