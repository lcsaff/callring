package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.ports.BackendFailure

/** Authoritative local data only; model-supplied labels never become confirmation evidence. */
internal suspend fun confirmationTarget(api: CallringUiApi, index: Int, arguments: AiJson.Obj): ConfirmationTarget {
    fun missing(): Nothing = throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"action.target_not_found"))
    return when(index) {
        20 -> api.settings.getPositions().requiredValue().options.firstOrNull { it.id == arguments.l("id") }?.let { ConfirmationTarget.Position(it.id,it.name,it.level,it.active) } ?: missing()
        32 -> api.settings.getLeaves().requiredValue().firstOrNull { it.id == arguments.l("id") }?.let { ConfirmationTarget.Leave(it.id,it.startInclusiveEpochMillis,it.endExclusiveEpochMillis,it.note) } ?: missing()
        53 -> {
            val slot = AiSlot.valueOf(arguments.s("slot"))
            val settings = api.aiSettings.getAiView().requiredValue()
            ConfirmationTarget.Connection(slot,if(slot == AiSlot.ANALYSIS) settings.analysis else settings.transcription)
        }
        57,63 -> {
            val archive = api.recordings.getArchive(arguments.l("callId")).requiredValue() ?: missing()
            val adoption = if(index == 63) {
                val profile = api.callers.getEditor(archive.number).requiredValue().draft
                ConfirmationTarget.Adoption(profile.name,profile.department,archive.analysis?.name?.takeIf { it.isNotBlank() },archive.analysis?.organization?.takeIf { it.isNotBlank() })
            } else null
            ConfirmationTarget.Recording(archive.callId,archive.number,archive.startedAtEpochMillis,archive.generation,adoption)
        }
        else -> throw IllegalArgumentException("action.confirmation_unsupported")
    }
}
