package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.contracts.recording.CallArchive
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.recording.ParsedSystemRecording

data class SystemRecordingScan(val id: Long, val at: Long, val days: Int, val zone: String)
data class SystemRecordingCandidate(val entry: ScannedSystemRecording, val parsed: ParsedSystemRecording,
    val stableSince: Long, val observedAt: Long)

/** Local discovery inventory is separate from durable canonical source bindings. */
interface SystemRecordingImportStore {
    fun isDeleted(callId: Long): Boolean
    fun prepareSystemMatches(cancelled: () -> Boolean): Boolean
    fun beginSystemScan(signature: String, at: Long, days: Int, zone: String): SystemRecordingScan
    fun acceptedSystemFile(signature: String, key: String, parsed: ParsedSystemRecording): Boolean
    fun observeSystemFile(scanId: Long, entry: ScannedSystemRecording, parsed: ParsedSystemRecording, at: Long)
    fun finishSystemScan(scanId: Long)
    fun systemCandidates(scanId: Long, afterKey: String?, limit: Int): List<SystemRecordingCandidate>
    fun hasConflictingSystemSource(scanId: Long, sourceKey: String, parsed: ParsedSystemRecording, target: RecordingCall?): Boolean
    fun hasConflictingSystemLink(sourceKey: String, source: RecordingCall, target: RecordingCall): Boolean
    fun releaseUnimportedSystemSource(sourceKey: String, callId: Long): Boolean
    fun sourceBinding(sourceKey: String): RecordingCall?
    fun sourceCompleted(sourceKey: String): Boolean
    fun completeSystemSource(sourceKey: String)
    fun matchingRecordingEvents(parsed: ParsedSystemRecording): List<RecordingCall>
    fun registerSystemSource(sourceKey: String, parsed: ParsedSystemRecording, existing: RecordingCall?, initial: (RecordingCall) -> CallArchive): RecordingRegistration
    fun systemRetry(at: Long?): Long?
    fun systemSourcePage(afterKey: String?, limit: Int): List<Pair<String, RecordingCall>>
    fun linkSystemStatistics(sourceKey: String, target: RecordingCall): Boolean
}
