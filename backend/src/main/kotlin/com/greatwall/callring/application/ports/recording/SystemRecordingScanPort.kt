package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.contracts.recording.RecordingFile

data class ScannedSystemRecording(val sourceKey: String, val file: RecordingFile)

interface SystemRecordingScanPort {
    /** Stream metadata only; returning normally certifies complete enumeration. */
    fun scan(folder: String, cancelled: () -> Boolean, visit: (ScannedSystemRecording) -> Unit)
    fun inspect(folder: String, document: com.greatwall.callring.contracts.common.DocumentRef, cancelled: () -> Boolean): RecordingFile?
    fun cancelScan()
}

interface SystemRecordingDiscoveryRunner {
    fun discover(cancelled: () -> Boolean): Long?
}
