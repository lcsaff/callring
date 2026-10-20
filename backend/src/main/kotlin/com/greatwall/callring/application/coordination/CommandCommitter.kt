package com.greatwall.callring.application.coordination

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.command.CommandContext

/** Public owners stop at close; persistence owners remain active only through incoming drain. */
internal class CommandCommitter(
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
) {
    fun <T> commit(command: CommandContext?, transaction: () -> MutationDelta<T>, afterCommit: (MutationDelta<T>) -> Unit): T {
        requireOperationActive(operationActive)
        return coordinator.guard(command) {
            requireOperationActive(operationActive)
            val delta = transaction()
            if (delta.changed) {
                coordinator.publishCore(delta.conflictKeys, delta.impact)
                afterCommit(delta)
            }
            coordinator.record(command)
            delta.value
        }
    }
}
