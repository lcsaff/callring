package com.greatwall.callring.application.calling

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.calling.DecisionSnapshot
import com.greatwall.callring.domain.model.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Publication shares the input mutation fence, so a cold capture cannot overwrite a newer patch.
 */
internal class DecisionQueryService(
    private val inputs: DecisionInputsTransactions,
    private val calls: CallingTransactions,
    private val coordinator: BusinessRevisionCoordinator,
    private val snapshots: AtomicReference<DecisionSnapshot?>,
) {
    fun refresh(): DecisionSnapshot =
        coordinator.guard(null) {
            inputs
                .readDecisionInputs { DecisionSnapshot.capture(it.decisionInputs()) }
                .also(snapshots::set)
        }

    fun snapshot(): DecisionSnapshot = snapshots.get() ?: error("decision.snapshot.uninitialized")

    fun statisticsStart(): Long =
        consistent(setOf("statistics")) { calls.readCalling { it.statisticsStart() } }

    fun hasRecentPending(now: Long): Boolean =
        consistent(setOf("calls")) {
            calls.readCalling {
                it.hasRecentPending(saturatedSubtract(now, 300000), saturatedAdd(now, 1))
            }
        }

    private fun <T> consistent(keys: Set<String>, read: () -> T): T {
        repeat(3) {
            val before = coordinator.marker()
            val result = read()
            if (coordinator.unchanged(keys, before)) return result
        }
        throw com.greatwall.callring.application.ports.BackendFailure(
            com.greatwall.callring.contracts.common.ApiError(
                com.greatwall.callring.contracts.common.ErrorCode.CONFLICT,
                "business.changed",
                retryable = true,
            ),
        )
    }
}
