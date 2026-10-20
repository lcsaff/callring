package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.recording.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingWorkflowTest {
    @Test fun independentArchiveIdentityCanRunThroughAnalysisWithoutStatistics() {
        configure()
        workflow.attach(-7, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(-7)!!.status)
        assertEquals("A brief call", store.get(-7)!!.analysis!!.summary)
    }
    private val connection = AiConnection(url = "https://example.com/v1", apiKey = "secret", model = "model")
    private val store = MemoryStore()
    private var transcriptions = 0
    private var analyses = 0
    private var beforeAnalysis: () -> Unit = {}
    private var fail = false
    private val client = object : AiClient {
        override fun test(connection: AiConnection, transcription: Boolean) {}
        override fun transcribe(connection: AiConnection, audio: File): String { transcriptions++; return "Caller says hello" }
        override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
            analyses++; beforeAnalysis()
            if (fail) throw AiFailure("ai.temporary", true)
            return CallAnalysis(summary = "A brief call")
        }
    }
    private val workflow = RecordingWorkflow(store, client, { 1000L })
    private fun configure(auto: Boolean = true) {
        store.seedSettings(RecordingSettings(automaticAnalysis = auto, analysis = connection, transcription = connection))
    }
    @Test fun disabledAutomaticAnalysisRetainsAudioWithoutSendingIt() {
        configure(false)
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        workflow.processPending()
        assertEquals(0, transcriptions)
        assertEquals(ArchiveStatus.READY, store.get(1)!!.status)
    }
    @Test fun analysisRetryReusesCompletedTranscription() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        fail = true
        workflow.processPending()
        assertEquals(ArchiveStatus.FAILED, store.get(1)!!.status)
        assertEquals(1, transcriptions)
        fail = false
        workflow.retry(1)
        workflow.processPending()
        assertEquals(1, transcriptions)
        assertEquals(2, analyses)
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
    }
    @Test fun deletionAndReplacementInvalidateLateResponse() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        beforeAnalysis = {
            workflow.delete(1)
            workflow.attach(1, "31234", 100, "/private/new.m4a", RecordingSource.IMPORTED, 2000)
        }
        workflow.processPending()
        assertNull(store.get(1)!!.analysis)
        assertEquals("/private/new.m4a", store.get(1)!!.audioPath)
    }
    @Test fun concurrentRetryDoesNotCreateDuplicateAnalysis() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        beforeAnalysis = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val worker = Thread { workflow.processPending() }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { workflow.retry(1) }
        workflow.processPending()
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, analyses)
    }
    @Test fun permanentErrorsWaitForUserInsteadOfLooping() {
        configure()
        val brokenClient = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun transcribe(connection: AiConnection, audio: File): String = throw AiFailure("ai.authentication")
        }
        val brokenWorkflow = RecordingWorkflow(store, brokenClient, { 1000L })
        brokenWorkflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        brokenWorkflow.processPending()
        assertEquals(Long.MAX_VALUE, store.get(1)!!.nextAttemptAt)
    }
    @Test fun disablingAutomaticAnalysisBeforeJobRunsStopsPendingUploads() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        configure(false)
        workflow.processPending()
        assertEquals(0, transcriptions)
        assertEquals(ArchiveStatus.READY, store.get(1)!!.status)
        workflow.retry(1)
        workflow.processPending()
        assertEquals(1, transcriptions)
    }
    @Test fun deletingActiveArchiveCancelsItsClient() {
        configure()
        var wasCancelled = false
        val cancelClient = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun cancel() { wasCancelled = true }
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                deleting.delete(1)
                return CallAnalysis(summary = "late")
            }
            lateinit var deleting: RecordingWorkflow
        }
        val deletingWorkflow = RecordingWorkflow(store, cancelClient)
        cancelClient.deleting = deletingWorkflow
        deletingWorkflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        deletingWorkflow.processPending()
        assertTrue(wasCancelled)
        assertNull(store.get(1))
    }
    @Test fun operatingSystemCancellationKeepsJobResumableWithoutConsumingAttempt() {
        configure()
        var stopped = false
        val cancellable = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun transcribe(connection: AiConnection, audio: File): String {
                stopped = true
                throw AiFailure("recording.cancelled")
            }
        }
        val resumable = RecordingWorkflow(store, cancellable)
        resumable.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000)
        resumable.processPending { stopped }
        assertEquals(ArchiveStatus.QUEUED, store.get(1)!!.status)
        assertEquals(0, store.get(1)!!.attempts)
        assertEquals(0L, store.get(1)!!.nextAttemptAt)
    }

    private class MemoryStore : RecordingStore {
        private var config = RecordingSettings()
        private val entries = mutableMapOf<Long, CallArchive>()
        @Synchronized override fun settings() = config
        @Synchronized fun seedSettings(value: RecordingSettings) { config = value }
        @Synchronized override fun get(callId: Long) = entries[callId]
        @Synchronized override fun all() = entries.values.toList()
        @Synchronized override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
            if (entries[callId]?.generation != expectedGeneration) return false
            entries[callId] = value; return true
        }
        @Synchronized override fun delete(callId: Long) = entries.remove(callId)
    }
}
