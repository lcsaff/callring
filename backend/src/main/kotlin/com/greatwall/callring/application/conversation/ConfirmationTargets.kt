package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.ports.BackendFailure

/** Authoritative local data only; model-supplied labels never become confirmation evidence. */
internal suspend fun confirmationTarget(api: CallringUiApi, operation: OperationId, arguments: AiJson.Obj): ConfirmationTarget {
    fun missing(): Nothing = throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"action.target_not_found"))
    return when(operation) {
        OperationId.SETTINGS_DELETE_POSITION -> api.settings.getPositions().requiredValue().options.firstOrNull { it.id == arguments.l("id") }?.let { ConfirmationTarget.Position(it.id,it.name,it.level,it.active) } ?: missing()
        OperationId.SETTINGS_DELETE_LEAVE -> api.settings.getLeaves().requiredValue().firstOrNull { it.id == arguments.l("id") }?.let { ConfirmationTarget.Leave(it.id,it.startInclusiveEpochMillis,it.endExclusiveEpochMillis,it.note) } ?: missing()
        OperationId.RECORDINGS_TEST_CONNECTION -> {
            val slot = AiSlot.valueOf(arguments.s("slot"))
            val settings = api.aiSettings.getAiView().requiredValue()
            ConfirmationTarget.Connection(slot,if(slot == AiSlot.ANALYSIS) settings.analysis else settings.transcription)
        }
        OperationId.RECORDINGS_DELETE, OperationId.RECORDINGS_ADOPT_IDENTITY -> {
            val archive = api.recordings.getArchive(arguments.l("callId")).requiredValue() ?: missing()
            val adoption = if(operation==OperationId.RECORDINGS_ADOPT_IDENTITY) {
                val profile = api.callers.getEditor(archive.number).requiredValue().draft
                ConfirmationTarget.Adoption(profile.name,profile.department,archive.analysis?.name?.takeIf { it.isNotBlank() },archive.analysis?.organization?.takeIf { it.isNotBlank() })
            } else null
            ConfirmationTarget.Recording(archive.callId,archive.number,archive.startedAtEpochMillis,archive.generation,adoption)
        }
        OperationId.CALLERS_DELETE_NUMBER -> {
            val impact = api.numberDeletion.impact(arguments.s("number")).requiredValue()
            ConfirmationTarget.NumberDeletion(
                impact.number, impact.profileExists, impact.callCount, impact.archiveCount,
                impact.audioBytes, impact.widgetBindingCount,
            )
        }
        else -> throw IllegalArgumentException("action.confirmation_unsupported")
    }
}
