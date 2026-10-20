package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.runtime.Capability

/** A launched platform action may change its declared target while the user is in system UI. */
class ForegroundCommandValidator(private val coordinator: BusinessRevisionCoordinator) {
    private fun rebased(command: CommandContext, keys: Set<String>) = command.copy(
        expectedScopes = command.expectedScopes?.mapValues { (key, at) ->
            if (key in keys) maxOf(at, coordinator.lastChange(setOf(key))) else at
        })
    fun check(ticket: ForegroundTicket, result: ForegroundHostResult?): Boolean {
        val command = ticket.commandContext ?: return true
        if (coordinator.committed(command.operationId) != null) return true
        return runCatching {
            val allowed = if (ticket.launched && result?.status == ForegroundResultStatus.VERIFIED) when (ticket.kind) {
                ForegroundActionKind.CAPABILITY -> setOf("runtime:capability:" + Capability.valueOf(ticket.parameters["capability"].string()).name)
                ForegroundActionKind.APP_SETTINGS -> setOf("runtime:status")
                else -> emptySet()
            } else emptySet()
            coordinator.guard(rebased(command, allowed).copy(mayCommit = { true })) {}
        }.isSuccess
    }
    /** Called only after actual runtime readback proves this launched capability action succeeded. */
    fun recordCapability(command: CommandContext, capability: Capability, observedAt: Long) = coordinator.guard {
        val key = "runtime:capability:$capability"
        if (!coordinator.unchanged(setOf(key), observedAt)) throw com.greatwall.callring.application.ports.BackendFailure(
            com.greatwall.callring.contracts.common.ApiError(com.greatwall.callring.contracts.common.ErrorCode.CONFLICT, "business.changed", retryable = true))
        val accepted = command.copy(expectedScopes = command.expectedScopes?.mapValues { (scope, at) ->
            if (scope == key) maxOf(at, observedAt) else at
        })
        coordinator.check(accepted)
        coordinator.record(accepted)
    }
}
