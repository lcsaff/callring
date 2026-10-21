package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.recording.RecordingPlaybackObservation
import com.greatwall.callring.application.recording.model.RecordingActivityObservation
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.RuntimeStatus
import kotlinx.coroutines.runBlocking

data class ScheduledJobObservation(val service: String, val jobId: Int)
data class ScheduledJobsObservation(val available: Boolean, val pending: List<ScheduledJobObservation>)

fun scheduledJobsValue(value: ScheduledJobsObservation): AiJson.Obj =
    if (!value.available) aiObject("available" to aiBool(false)) else aiObject(
        "available" to aiBool(true), "pending" to AiJson.Arr(value.pending.map {
            aiObject("service" to aiString(it.service), "jobId" to aiNumber(it.jobId.toLong()))
        }))

fun recordingPlaybackValue(value: RecordingPlaybackObservation) = aiObject(
    "mode" to aiString(value.status.mode.name), "state" to aiString(value.status.state.name),
    "callId" to (value.callId?.let(::aiNumber) ?: AiJson.Null))

class RuntimeQueryProjection(
    private val runtime: suspend () -> ApiResult<RuntimeStatus>,
    private val playback: () -> RecordingPlaybackObservation,
    private val activity: () -> RecordingActivityObservation,
    private val jobs: () -> ScheduledJobsObservation,
) {
    fun rows(spec: QuerySpec) = currentQueryRow(spec) {
        aiObject("runtime" to runBlocking { safeValue(runtime().requiredValue()) },
            "recordingPlayback" to recordingPlaybackValue(playback()),
            "processing" to activity().let { aiObject("running" to aiNumber(it.running),
                "activeClients" to aiNumber(it.activeClients), "cancelledOperations" to aiNumber(it.cancelledOperations)) },
            "scheduledJobs" to scheduledJobsValue(jobs()))
    }
}
