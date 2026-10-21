package com.greatwall.callring.application.recording

import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.calling.RecordingCallLink
import com.greatwall.callring.domain.calling.resolveRecordingCall

/** The entire target/visible-link closure is consumed under the same storage lease. */
fun findRecordingCall(reads: com.greatwall.callring.application.ports.store.CallingTransactions, id: Long): RecordingCall? =
    reads.readCalling { reader ->
        val requested = reader.callingCall(id) ?: return@readCalling null
        fun com.greatwall.callring.domain.model.RawCallRecord.link() = RecordingCallLink(this.id, number, time, duration, hidden, linkedScreenId)
        resolveRecordingCall(requested.link(), if (requested.hidden) reader.visibleRecordingLinks(id).map { it.link() } else emptyList())
    }
