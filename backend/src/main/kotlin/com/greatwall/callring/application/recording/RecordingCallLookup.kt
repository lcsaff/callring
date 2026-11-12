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

fun findSystemRecordingCalls(reads: com.greatwall.callring.application.ports.store.CallingTransactions, number: String, at: Long): List<RecordingCall> =
    reads.readCalling { reader ->
        reader.systemRecordingCalls(number.filter(Char::isDigit),at - 3000,at + 3000).map { row ->
            RecordingCall(com.greatwall.callring.domain.calling.stableRecordingCallId(row.id, row.linkedScreenId),row.number,row.time,row.duration)
        }.distinctBy { it.id }
    }
