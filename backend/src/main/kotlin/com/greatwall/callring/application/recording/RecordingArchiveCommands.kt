package com.greatwall.callring.application.recording

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.recording.CallArchive

/** The sole publisher for durable archive transitions, including background AI work. */
class RecordingArchiveCommands(
    private val state: RecordingArchiveState,
    private val mutations: RecordingArchiveMutations,
    private val coordinator: BusinessRevisionCoordinator,
) {
    fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive, command: CommandContext?, playbackStopped: Boolean): Boolean = coordinator.guard(command) {
        require(callId == value.callId)
        if (state.generation(callId) != expectedGeneration) return@guard false
        if (expectedGeneration == value.generation && state.get(callId) == value) {
            if (playbackStopped) coordinator.publishRuntime(setOf("runtime:recordingPlayback"))
            coordinator.record(command)
            return@guard true
        }
        if (!mutations.compareAndSet(callId, expectedGeneration, value)) return@guard false
        publish(callId, playbackStopped)
        coordinator.record(command)
        true
    }

    fun delete(callId: Long, command: CommandContext?, playbackStopped: Boolean): CallArchive? = coordinator.guard(command) {
        val deleted = state.isDeleted(callId)
        val previous = mutations.delete(callId)
        if (!deleted || previous != null) publish(callId, playbackStopped)
        else if (playbackStopped) coordinator.publishRuntime(setOf("runtime:recordingPlayback"))
        coordinator.record(command)
        previous
    }

    private fun publish(callId: Long, playbackStopped: Boolean) {
        val keys = linkedSetOf("archive:$callId")
        val impact = linkedMapOf<DataDomain, Set<String>?>(DataDomain.RECORDING_ARCHIVES to setOf(callId.toString()))
        if (playbackStopped) { keys += "runtime:recordingPlayback"; impact[DataDomain.RUNTIME] = null }
        coordinator.publishResource("recording", keys, ChangeImpact(impact))
    }
}
