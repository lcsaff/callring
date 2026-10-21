package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue

import com.greatwall.callring.application.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.contracts.widget.WidgetBinding
import java.util.UUID

interface NumberDeletionRecordings {
    suspend fun archives(number: String): ApiResult<List<NumberRecordingMetadata>>
    suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit>
}

/** One plan, one continuation, strictly archives -> core transaction -> stored bindings. */
class NumberDeletionService(
    private val coreImpact: suspend (String) -> ApiResult<NumberCoreImpact>,
    private val deleteCore: suspend (String, Int, CommandContext) -> ApiResult<Unit>,
    private val recordings: NumberDeletionRecordings,
    private val bindings: suspend (String) -> ApiResult<List<WidgetBinding>>,
    private val removeBinding: suspend (WidgetBinding, CommandContext) -> ApiResult<Unit>,
    private val coordinator: BusinessRevisionCoordinator,
    private val command: CommandContext? = null,
    private val active: () -> Boolean,
) : NumberDeletionApi {
    private suspend fun <T> result(block: suspend () -> T): ApiResult<T> = try { ApiResult.Success(block()) }
        catch (failure: BackendFailure) { ApiResult.Failure(failure.error) }
    private suspend fun baseline(number: String): CommandContext {
        val n = callerNumber(number)
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        return coordinator.guard(command) {
            val scopes = coordinator.expect(setOf("profile:$n", "profile:$n:exists", "calls:$n", "recording", "preferences"))
            command?.copy(continueOwnCommits = true, active = { command.active() && active() && caller?.isActive != false },
                expectedScopes = command.expectedScopes?.let { scopes + it }) ?: CommandContext(
                UUID.randomUUID().toString(), coordinator.capture(), 0, expectedScopes = scopes,
                continueOwnCommits = true, active = { active() && caller?.isActive != false })
        }
    }
    private data class Plan(val impact: NumberDeletionImpact, val archives: List<NumberRecordingMetadata>, val bindings: List<WidgetBinding>)
    private suspend fun plan(number: String, context: CommandContext): Plan {
        coordinator.check(context)
        val core = coreImpact(number).requiredValue()
        val archives = recordings.archives(core.number).requiredValue().sortedWith(
            compareByDescending<NumberRecordingMetadata> { it.startedAtEpochMillis }.thenBy { it.callId })
        val widgets = bindings(core.number).requiredValue()
        coordinator.check(context)
        return Plan(NumberDeletionImpact(core.number, core.profileExists, core.callCount, archives.size,
            archives.sumOf { it.audioBytes }, widgets.size,
            archives.any { it.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING) }), archives, widgets)
    }
    override suspend fun impact(number: String) = result { plan(number, baseline(number)).impact }
    override suspend fun delete(command: DeleteNumberCommand) = result {
        val context = baseline(command.number)
        val plan = plan(command.number, context)
        val current = plan.impact
        if (!current.profileExists && current.callCount == 0 && current.archiveCount == 0 && current.widgetBindingCount == 0)
            throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "number.not_found"))
        if (current.callCount != command.expectedCallCount || current.archiveCount != command.expectedArchiveCount ||
            current.widgetBindingCount != command.expectedWidgetBindingCount)
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "number.delete_conflict", retryable = true))
        if (current.busy) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "number.delete_busy", retryable = true))
        for (archive in plan.archives) recordings.delete(archive.callId, archive.generation, context).requiredValue()
        // Recheck even for archive-only remnants: a new profile/call must not authorize later deletion.
        coordinator.check(context)
        if (current.profileExists || current.callCount > 0)
            deleteCore(current.number, command.expectedCallCount, context).requiredValue()
        val remaining = bindings(current.number).requiredValue()
        coordinator.check(context)
        if (remaining != plan.bindings) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
        for (binding in plan.bindings) removeBinding(binding, context).requiredValue()
        coordinator.check(context)
        Unit
    }
}
