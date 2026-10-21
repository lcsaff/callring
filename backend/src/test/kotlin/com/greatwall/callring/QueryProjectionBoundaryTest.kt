package com.greatwall.callring

import com.greatwall.callring.application.conversation.query.WidgetQueryProjection
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.recording.RecordingPlaybackObservation
import com.greatwall.callring.application.recording.model.RecordingActivityObservation
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueryProjectionBoundaryTest {
    private val playback = PlaybackStatus(PlaybackMode.PREVIEW, PlaybackState.PLAYING)
    private val runtime = RuntimeStatus(emptyMap(), Audibility.AUDIBLE, PhoneActivity.IDLE, playback, true, 123)

    @Test fun scopedRuntimeIncludesLiveFactsWhileFullRuntimeKeepsItsDistinctShape() = runBlocking {
        val calls = mutableListOf<String>()
        val scoped = RuntimeQueryProjection(
            { calls += "runtime"; ApiResult.Success(runtime) },
            { calls += "playback"; RecordingPlaybackObservation(playback, 42) },
            { calls += "activity"; RecordingActivityObservation(3, 2, 1) },
            { calls += "jobs"; ScheduledJobsObservation(false, emptyList()) },
        ).rows(QuerySpec(AiDomain.D19)).single().value
        assertEquals(listOf("runtime", "playback", "activity", "jobs"), calls)
        assertEquals(setOf("runtime", "recordingPlayback", "processing", "scheduledJobs"), scoped.fields.keys)
        assertEquals(aiNumber(42), (scoped["recordingPlayback"] as AiJson.Obj)["callId"])
        assertEquals(setOf("available"), (scoped["scheduledJobs"] as AiJson.Obj).fields.keys)
        val full = ObservedCompleteSnapshotSource(
            { ApiResult.Success(WidgetBindingsListing(emptyList(), WidgetInstallState(emptyList(), AccessState.GRANTED))) },
            { ApiResult.Success(runtime) }, { playback }, { emptyList() },
        ).capture(123)
        assertEquals(1L, full.getValue("D18").count)
        assertEquals(0L, full.getValue("D20").count)
        val fullRuntime = full.getValue("D19").data as AiJson.Obj
        assertEquals(setOf("runtime", "recordingPlayback"), fullRuntime.fields.keys)
        assertEquals(setOf("mode", "state"), (fullRuntime["recordingPlayback"] as AiJson.Obj).fields.keys)
    }

    @Test fun fullCountsIncludeEveryCollectionAndFailureKeepsGroupAndStructuredFields() = runBlocking {
        val two = AiJson.Arr(listOf(aiString("a"), aiString("b")))
        assertEquals(6L, snapshotSourceCount("D15", aiObject("events" to two, "links" to two, "deleted" to two)))
        assertEquals(6L, snapshotSourceCount("D07", aiObject("years" to two, "holidays" to two, "makeupDates" to two)))
        assertEquals(4L, snapshotSourceCount("D05", aiObject("intervals" to two, "numbers" to two)))
        assertEquals(2L, snapshotSourceCount("D02", aiObject("options" to two)))
        assertEquals(2L, snapshotSourceCount("D06", aiObject("history" to two)))
        assertEquals(2L, snapshotSourceCount("D09", aiObject("entries" to two)))
        val original = ApiError(ErrorCode.PERMISSION_REQUIRED, "denied", mapOf("field" to "detail"), setOf(Capability.CALL_LOG), true)
        val source = ObservedCompleteSnapshotSource(
            { ApiResult.Failure(original) }, { error("must not continue after widgets failure") },
            { error("must not read playback") }, { error("must not read diagnostics") },
        )
        val failure = try { source.capture(0); error("expected failure") } catch (e: BackendFailure) { e }
        assertEquals(original.copy(fieldErrors = original.fieldErrors + ("domains" to "D18-D20")), failure.error)
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        try { withSnapshotSource("D17") { throw cancellation } } catch (e: kotlinx.coroutines.CancellationException) { assertSame(cancellation, e) }
    }

    @Test fun widgetBranchesSelectBeforeReadingAndEmptyInstallationStillHasObservation() {
        var stored = 0
        var installed = 0
        val projection = WidgetQueryProjection(
            { stored++; sequenceOf(WidgetBinding(9, "00123")) },
            { installed++; WidgetInstallState(emptyList(), AccessState.GRANTED) },
        )
        assertEquals(0, stored + installed)
        val observation = projection.rows(QuerySpec(AiDomain.D18, "installed")).toList()
        assertEquals(0, stored)
        assertEquals(1, installed)
        assertEquals(listOf("installation-observation"), observation.map { it.key })
        assertEquals("installation_observation", observation.single().value["kind"].string())
        val bindings = projection.rows(QuerySpec(AiDomain.D18, "bindings")).toList()
        assertEquals(1, stored)
        assertEquals(1, installed)
        assertEquals("00123", bindings.single().value["number"].string())
    }
}
