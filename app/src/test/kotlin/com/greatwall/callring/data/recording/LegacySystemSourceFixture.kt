package com.greatwall.callring.data.recording

import com.greatwall.callring.application.ports.recording.RecordingRegistration
import com.greatwall.callring.contracts.recording.CallArchive
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.recording.ParsedSystemRecording

/** Seeds the portable pre-removal representation, without reviving automatic discovery. */
internal fun AndroidRecordingStore.seedLegacySystemSource(key: String, parsed: ParsedSystemRecording, initial: (RecordingCall) -> CallArchive): RecordingRegistration {
    sourceBinding(key)?.let { return RecordingRegistration(it,false,false) }
    val registered = insertIncoming("system-file:$key",parsed.number,parsed.startedAtEpochMillis,null) {
        initial(it.copy(direction=com.greatwall.callring.domain.model.CallDirection.UNKNOWN))
    }
    val call = registered.call.copy(direction=com.greatwall.callring.domain.model.CallDirection.UNKNOWN)
    registerSystemSource(key,parsed,call,initial)
    return registered.copy(call=call)
}
