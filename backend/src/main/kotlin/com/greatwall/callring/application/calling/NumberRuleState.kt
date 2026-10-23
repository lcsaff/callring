package com.greatwall.callring.application.calling

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.domain.calling.NumberRule

data class NumberRuleSnapshot(val rule: NumberRule, val revision: Long)

/** Shares the commit fence; snapshots are prepared before any durable mutation. */
class NumberRuleState(private val coordinator: BusinessRevisionCoordinator) {
    private var current: NumberRuleSnapshot? = null
    fun snapshot(): NumberRuleSnapshot = synchronized(coordinator.fence) {
        checkNotNull(current) { "number_rule.unprepared" }
    }
    internal fun initialized(): Boolean = synchronized(coordinator.fence) { current != null }
    internal fun install(value: NumberRuleSnapshot) = synchronized(coordinator.fence) { current = value }
}
