package com.greatwall.callring.application.runtime

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.completeRuntimeStatus
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.SyncScheduler
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal fun requireCapability(status: RuntimeStatus, capability: Capability, role: Boolean = false) {
    val access = status.capabilities[capability] ?: AccessState.UNKNOWN
    if (access != AccessState.GRANTED && access != AccessState.NOT_REQUIRED) throw BackendFailure(ApiError(
        if (role) ErrorCode.ROLE_REQUIRED else ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(capability)))
}

class RuntimeService(
    private val status: () -> RuntimeStatus,
    private val diagnostics: () -> List<DiagnosticEntry>,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val coordinator: BusinessRevisionCoordinator,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) : RuntimeApi {
    private val runner = UseCaseRunner(dispatcher)
    fun bound(command: CommandContext) = RuntimeService(status, diagnostics, syncScheduler, clock, dispatcher, coordinator, command, operationActive = operationActive)
    override suspend fun getStatus() = runner.io { requireOperationActive(operationActive); completeRuntimeStatus(status()) }
    override suspend fun getDiagnostics() = runner.io { requireOperationActive(operationActive); java.util.Collections.unmodifiableList(ArrayList(diagnostics())) }
    override suspend fun requestCallLogSync() = runner.io { requireOperationActive(operationActive);
        coordinator.guard(command) {
            requireOperationActive(operationActive)
        }
        requireCapability(completeRuntimeStatus(status()), Capability.CALL_LOG)
        coordinator.guard(command) { requireOperationActive(operationActive) }
        syncScheduler.requestSync(clock.millis())
    }
}
