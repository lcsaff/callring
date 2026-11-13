package com.greatwall.callring.application.recording

import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.calling.RecordingCallLink
import com.greatwall.callring.domain.calling.resolveRecordingCall
import com.greatwall.callring.domain.recording.SystemRecordingAssociation

/** The entire target/visible-link closure is consumed under the same storage lease. */
fun findRecordingCall(reads: com.greatwall.callring.application.ports.store.CallingTransactions, id: Long): RecordingCall? =
    reads.readCalling { reader ->
        val requested = reader.callingCall(id) ?: return@readCalling null
        fun com.greatwall.callring.domain.model.RawCallRecord.link() = RecordingCallLink(this.id, number, time, duration, hidden, linkedScreenId, direction)
        resolveRecordingCall(requested.link(), if (requested.hidden) reader.visibleRecordingLinks(id).map { it.link() } else emptyList())
    }

fun findSystemRecordingCalls(reads: com.greatwall.callring.application.ports.store.CallingTransactions, number: String, at: Long): List<RecordingCall> =
    reads.readCalling { reader ->
        val normalized = number.filter(Char::isDigit)
        if (normalized.isEmpty()) return@readCalling emptyList()
        val keys = listOf(normalized, "+$normalized")
        fun matches(from: Long, to: Long): List<RecordingCall> = keys.flatMap { key ->
            reader.systemRecordingCalls(key, from, to, null).map { row ->
                RecordingCall(com.greatwall.callring.domain.calling.stableRecordingCallId(row.id, row.linkedScreenId),
                    row.number, row.time, row.duration, row.direction)
            }
        }.distinctBy { it.id }.take(2)
        val range = SystemRecordingAssociation.callStarts(at)
        val close = matches(SystemRecordingAssociation.recordingStarts(at).first, range.last)
        close.ifEmpty { matches(range.first, range.last) }
    }
