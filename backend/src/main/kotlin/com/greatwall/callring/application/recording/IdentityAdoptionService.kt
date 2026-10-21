package com.greatwall.callring.application.recording

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue

import com.greatwall.callring.application.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.CallArchive
import java.util.UUID

/** Adopts only self-reported fields, using the existing caller dirty-merge transaction. */
class IdentityAdoptionService(
    private val archive: suspend (Long) -> ApiResult<CallArchive?>,
    private val editor: suspend (String) -> ApiResult<CallerEditorData>,
    private val edit: suspend (CallerDraft, CallerDraft, CommandContext) -> ApiResult<CallerProfile>,
    private val coordinator: BusinessRevisionCoordinator,
    private val command: CommandContext? = null,
    private val active: () -> Boolean,
) {
    suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> = try {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val observedAt = coordinator.guard(command) { coordinator.marker() }
        // A negative ID is canonical. Positive legacy/statistics aliases also depend on links.
        val archiveScopes = coordinator.expect(setOf(if (callId < 0) "archive:$callId" else "recording"), observedAt)
        val context = coordinator.guard(command) {
            command?.copy(expectedScopes = command.expectedScopes?.let { archiveScopes + it }, active = { command.active() && active() && caller?.isActive != false }) ?:
                CommandContext(UUID.randomUUID().toString(), coordinator.capture(), 0,
                    expectedScopes = archiveScopes, active = { active() && caller?.isActive != false })
        }
        coordinator.check(context)
        val saved = archive(callId).requiredValue() ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found"))
        if (saved.generation != expectedGeneration) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.changed"))
        val analysis = saved.analysis ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "recording.analysis_missing"))
        coordinator.check(context)
        val number = com.greatwall.callring.domain.model.NumberIdentity.parse(saved.number).normalized
        val profileScopes = coordinator.expect(setOf("profile:$number:exists", "profile:$number:name", "profile:$number:department"), observedAt)
        val guarded = context.copy(expectedScopes = context.expectedScopes?.let { profileScopes + it })
        coordinator.check(guarded)
        val base = editor(saved.number).requiredValue().draft
        edit(base, base.copy(name = analysis.name.ifBlank { base.name }, department = analysis.organization.ifBlank { base.department }), guarded)
    } catch (failure: BackendFailure) { ApiResult.Failure(failure.error) }
}
