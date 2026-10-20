package com.greatwall.callring.application.coordination

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.command.CommandContext

internal class CommandCommitter(private val coordinator: BusinessRevisionCoordinator) {
    fun <T> commit(command: CommandContext?, transaction: () -> MutationDelta<T>, afterCommit: (MutationDelta<T>) -> Unit): T =
        coordinator.guard(command) {
            val delta = transaction()
            if (delta.changed) {
                coordinator.publishCore(delta.conflictKeys, delta.impact)
                afterCommit(delta)
            }
            coordinator.record(command)
            delta.value
        }
}
