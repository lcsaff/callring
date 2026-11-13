package com.greatwall.callring.application.recording

import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.ports.store.CallingTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.model.*
import com.greatwall.callring.domain.recording.SystemRecordingAssociation
import java.util.concurrent.CancellationException

/** Repairs cross-database aliases on every rescan, including after a process died after core commit. */
class RetainedRecordingReconciler(private val calls: CallingTransactions, private val recordings: RetainedRecordingLinks,
    private val coordinator: BusinessRevisionCoordinator, private val active: () -> Boolean) : RecordingHistoryReconciler {
    private fun check() { if(!active()||Thread.currentThread().isInterrupted)throw CancellationException("recording.closed") }
    override fun reconcile(records: List<SystemCallRecord>) {
        check()
        if(!recordings.hasRetainedHistory())return
        records.map { it.logId }.distinct().chunked(200).forEach { ids ->
            check()
            coordinator.guard {
                check()
                val matches=calls.readCalling { reader ->
                    reader.providerCalls(ids.toSet()).filter { !it.hidden }.mapNotNull { core ->
                        val origin=recordings.retainedOrigin(requireNotNull(core.logId))
                        if(origin!=null) {
                            if(origin.number==core.number&&origin.started==core.time&&origin.direction==core.direction) Triple(core.id,-origin.eventId,core.direction) else null
                        } else {
                            fun compatible(call:RecordingCall)=call.direction==core.direction||call.direction==CallDirection.UNKNOWN
                            val all=recordings.retainedCandidates(core.number,core.time,core.direction).filter(::compatible)
                            val candidates=all.filter { kotlin.math.abs(it.startedAt-core.time)<=SystemRecordingAssociation.CLOCK_SKEW_MILLIS }.ifEmpty { all }
                            val candidate=candidates.singleOrNull()?:return@mapNotNull null
                            val range=SystemRecordingAssociation.callStarts(candidate.startedAt)
                            fun near(from:Long,to:Long)=(listOf(core.number,"+${core.number}").flatMap { reader.systemRecordingCalls(it,from,to,candidate.direction.takeUnless { d -> d==CallDirection.UNKNOWN }) }).distinctBy { it.id }
                                .filter { candidate.direction==CallDirection.UNKNOWN||it.direction==candidate.direction }
                            // Query the close window first: a bounded broad query must not hide
                            // an exact later match behind two earlier calls.
                            val closest=near(SystemRecordingAssociation.recordingStarts(candidate.startedAt).first,range.last)
                                .ifEmpty {near(range.first,range.last)}
                            if(closest.singleOrNull()?.id==core.id)Triple(core.id,candidate.id,core.direction) else null
                        }
                    }
                }
                matches.groupBy { it.second }.values.filter { it.size==1 }.map { it.single() }.forEach { (coreId,canonical,direction) ->
                    check()
                    if(recordings.linkRetained(coreId,-canonical,direction)) coordinator.publishResource("recording",setOf("recordingLinks","recordingLink:$coreId"),
                        ChangeImpact(mapOf(DataDomain.RECORDING_ARCHIVES to setOf(canonical.toString(),"statistics:$coreId"))))
                }
            }
        }
    }
}
