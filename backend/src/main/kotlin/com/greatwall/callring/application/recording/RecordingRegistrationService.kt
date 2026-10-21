package com.greatwall.callring.application.recording

import com.greatwall.callring.application.ports.recording.RecordingRegistrationStore

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.recording.CallArchive

class RecordingRegistrationService(
    private val store: RecordingRegistrationStore,
    private val coordinator: BusinessRevisionCoordinator,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun registerIncoming(sessionId: String, number: String, at: Long, account: String?, command: CommandContext?): RecordingCall = coordinator.guard(command) {
        require(sessionId.isNotBlank() && number.isNotBlank())
        val result = store.insertIncoming(sessionId, number, at, account) { call ->
            CallArchive(call.id, call.number, call.startedAt, generation = now() * 1000)
        }
        val keys = linkedSetOf<String>()
        if (result.eventInserted) keys += "recordingEvents"
        if (result.archiveInserted || result.eventInserted) keys += "archive:${result.call.id}"
        if (keys.isNotEmpty()) publish(result.call.id, keys)
        coordinator.record(command)
        result.call
    }

    fun linkStatistics(sessionId: String, statisticsId: Long, command: CommandContext?) = coordinator.guard(command) {
        require(statisticsId > 0 && sessionId.isNotBlank())
        val call = store.sessionRecording(sessionId)
        if (call != null && store.insertStatisticsLink(statisticsId, sessionId))
            coordinator.publishResource("recording", setOf("recordingLinks", "recordingLink:$statisticsId"),
                ChangeImpact(mapOf(DataDomain.RECORDING_ARCHIVES to setOf(call.id.toString(), "statistics:$statisticsId"))))
        coordinator.record(command)
    }

    private fun publish(callId: Long, keys: Set<String>) = coordinator.publishResource("recording", keys,
        ChangeImpact(mapOf(DataDomain.RECORDING_ARCHIVES to setOf(callId.toString()))))
}
