package com.greatwall.callring

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.ports.recording.OfflineTranscript
import com.greatwall.callring.application.ports.recording.RecordingCandidate

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.RecordingArchiveMutations
import com.greatwall.callring.application.ports.recording.RecordingArchiveState
import com.greatwall.callring.application.ports.recording.RecordingWorkflowReads
import com.greatwall.callring.application.recording.RecordingWorkflow

import com.greatwall.callring.application.*
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.recording.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingWorkflowTest {
    @Test fun transcriptOnlyAnalysisAndCoreReceiptRecoverAfterArchiveWritebackFailure() {
        configure(auto = false)
        val coordinator = BusinessRevisionCoordinator()
        val archiveCommands = RecordingArchiveCommands(store, store, coordinator)
        val core = MemoryCoreUnitOfWork()
        fun newOwner() = RecordingWorkflow(store, archiveCommands, client, { 1000L },
            enrichment = com.greatwall.callring.application.callers.CallerEnrichmentService(core, coordinator) { true })
        val first = newOwner()
        val seed = CallArchive(-1, "31234", 100, generation = 1, status = ArchiveStatus.READY,
            transcript = "Caller says hello")
        assertTrue(store.compareAndSet(seed.callId, null, seed))
        analysisResult = CallAnalysis(name = "Caller", fieldEvidence = mapOf("name" to "Caller says hello"))
        first.queueTranscriptAnalysis(seed.callId) {}
        val afterQueue = newOwner()
        afterQueue.processPending()
        assertEquals(0, transcriptions)
        assertEquals(1, analyses)
        assertTrue(core.people.isEmpty())
        val pending = store.get(seed.callId)!!
        assertEquals(ManualAnalysisState.PENDING_CONFIRMATION, pending.manualAnalysisState)
        val refused = assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) {
            afterQueue.retry(seed.callId, null)
        }
        assertEquals(com.greatwall.callring.contracts.common.ErrorCode.CONFLICT, refused.error.code)
        assertEquals(pending, store.get(seed.callId))
        val proposal = checkNotNull(afterQueue.callerSuggestions(seed.callId))
        val command = ConfirmCallerSuggestions(seed.callId, proposal.token, setOf(CallerSuggestionField.NAME))
        store.rejectConfirmationState = true
        assertEquals(CallerSuggestionConfirmation.Applied, afterQueue.confirmCallerSuggestions(command) {})
        assertEquals(pending, store.get(seed.callId))
        val confirmed = core.people.getValue("31234")

        store.rejectConfirmationState = false
        val restarted = newOwner()
        assertEquals(ManualAnalysisState.CONFIRMED, restarted.confirmedView(pending).manualAnalysisState)
        assertEquals(CallerSuggestionConfirmation.Applied, restarted.confirmCallerSuggestions(command) {})
        assertEquals(ManualAnalysisState.CONFIRMED, store.get(seed.callId)!!.manualAnalysisState)
        restarted.processPending()
        assertEquals(confirmed, core.people.getValue("31234"))
        assertEquals(1, analyses)
    }

    @Test fun failedReanalysisKeepsPriorResultAndSuccessfulRerunReplacesIt() {
        configure(auto = false)
        val coordinator = BusinessRevisionCoordinator()
        val core = MemoryCoreUnitOfWork()
        val workflow = RecordingWorkflow(store, RecordingArchiveCommands(store, store, coordinator), client, { 1000L },
            enrichment = com.greatwall.callring.application.callers.CallerEnrichmentService(core, coordinator) { true })
        val seed = CallArchive(-1, "31234", 100, generation = 1, status = ArchiveStatus.READY,
            transcript = "Caller says hello")
        assertTrue(store.compareAndSet(seed.callId, null, seed))
        analysisResult = CallAnalysis(name = "Caller", fieldEvidence = mapOf("name" to "Caller says hello"))
        workflow.queueTranscriptAnalysis(seed.callId) {}
        workflow.processPending()
        val previous = checkNotNull(workflow.callerSuggestions(seed.callId))
        val confirmation = ConfirmCallerSuggestions(seed.callId, previous.token, setOf(CallerSuggestionField.NAME))

        workflow.queueTranscriptAnalysis(seed.callId) {}
        assertNull(workflow.callerSuggestions(seed.callId))
        assertEquals(CallerSuggestionConfirmation.Conflict(null), workflow.confirmCallerSuggestions(confirmation) {})
        fail = true
        workflow.processPending()
        assertEquals(ArchiveStatus.FAILED, store.get(seed.callId)!!.status)
        assertEquals(previous, workflow.callerSuggestions(seed.callId))

        fail = false
        workflow.retry(seed.callId, null)
        workflow.processPending()
        val replacement = checkNotNull(workflow.callerSuggestions(seed.callId))
        assertNotEquals(previous.resultVersion, replacement.resultVersion)
        assertEquals(CallerSuggestionConfirmation.Conflict(replacement), workflow.confirmCallerSuggestions(confirmation) {})
        assertTrue(core.people.isEmpty())
    }

    @Test fun ownerCloseCancelsAndDrainsAdmittedWorkWithoutPersistingLateResults() {
        configure()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val blocking = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun cancel() { cancelled.countDown() }
            override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return "late transcript"
            }
        }
        val owned = RecordingWorkflow(store, commands, blocking, enrichment = testEnrichment)
        owned.attach(1,"31234",100,"/private/test.m4a",RecordingSource.IMPORTED,1000,null,false)
        val worker = Thread { owned.processPending() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val before = store.get(1)
        owned.seal()
        owned.cancelProcessing()
        val closer = Thread { owned.close() }.apply { start() }
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertTrue(closer.isAlive)
            release.countDown(); closer.join(2000); worker.join(2000)
            assertFalse(closer.isAlive); assertFalse(worker.isAlive)
            assertEquals(before, store.get(1))
            owned.processPending()
            assertEquals(before, store.get(1))
        } finally { release.countDown(); closer.join(2000); worker.join(2000) }
    }

    @Test fun cleanupReservationPreventsPendingCandidateFromStartingBeforeDeletionCompletes() {
        configure()
        workflow.attach(1,"31234",100,"/private/test.m4a",RecordingSource.IMPORTED,1000,null,false)
        val candidate=CountDownLatch(1)
        val finished=CountDownLatch(1)
        store.candidatesObserved={candidate.countDown()}
        val thread=Thread { try { workflow.processPending() } finally { finished.countDown() } }
        try {
            workflow.withIdle(1) {
                thread.start()
                assertTrue(candidate.await(3,TimeUnit.SECONDS))
                assertFalse("Worker must not start while cleanup owns the target",finished.await(1,TimeUnit.SECONDS))
                workflow.delete(1,null,false)
            }
            assertTrue(finished.await(3,TimeUnit.SECONDS))
            assertEquals(0,transcriptions)
            assertEquals(0,analyses)
        } finally { thread.join(3000) }
    }
    @Test fun candidatePagesCoverPositiveAndNegativeIdsWithoutHoldingStorageDuringNetwork() {
        configure()
        for (id in -70L..70L) if (id != 0L) workflow.attach(id, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(140, transcriptions)
        assertEquals(140, analyses)
        assertTrue(store.candidatePageSizes.all { it <= 64 })
        assertEquals(listOf(64, 64, 12, 0), store.candidatePageSizes)
        assertNull(workflow.nextRunAt())
        assertFalse(RecordingWorkflowReads::class.java.methods.any { it.name == "listArchives" })
    }
    @Test fun schedulingDoesNotMaterializeArchiveList() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        assertEquals(0L, workflow.nextRunAt())
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
        assertFalse(RecordingWorkflowReads::class.java.methods.any { it.name == "listArchives" })
    }
    @Test fun aiAudioBoundaryIsDataOnly() {
        assertFalse(AiClient::class.java.methods.any { method -> method.parameterTypes.contains(java.io.File::class.java) })
    }

    @Test fun independentArchiveIdentityCanRunThroughAnalysisWithoutStatistics() {
        configure()
        workflow.attach(-7, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(-7)!!.status)
        assertEquals("A brief call", store.get(-7)!!.analysis!!.summary)
    }
    @Test fun localFailureRetriesWithoutCallingModelAgain() {
        configure()
        localFailure = true
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(ArchiveStatus.ENRICHMENT_FAILED, store.get(1)!!.status)
        assertNotNull(store.get(1)!!.analysis)
        localFailure = false
        workflow.retry(1, null)
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
        assertEquals(1, analyses)
        assertEquals(1, transcriptions)
        assertEquals(2, localAttempts)
    }
    @Test fun failureToPersistLocalFailureNeverReplaysPaidAnalysis() {
        configure()
        localFailure = true
        store.rejectLocalFailureState = true
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(ArchiveStatus.ENRICHING, store.get(1)!!.status)
        localFailure = false
        store.rejectLocalFailureState = false
        workflow.retry(1, null)
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
        assertEquals(1, analyses)
        assertEquals(1, transcriptions)
    }
    @Test fun baselineIsDurableBeforeNetworkAndDeletedArchiveCannotCaptureAgain() {
        configure()
        beforeTranscription = {
            assertEquals(1, localCaptures)
            assertNotNull(store.get(1)!!.enrichmentBaseline)
            workflow.delete(1, null, false)
        }
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertNull(store.get(1)); assertEquals(1, localCaptures); assertEquals(0, localAttempts)
    }
    @Test fun onlyClaimsWithLiteralTranscriptEvidenceReachEnrichment() {
        configure()
        analysisResult = CallAnalysis(name = "Caller", region = "杭州", position = "CEO",
            fieldEvidence = mapOf("name" to "Caller says hello", "region" to "我在杭州", "selfReportedPosition" to "Caller says hello"))
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(setOf("name"), lastCandidates.keys)
        assertEquals("Caller", lastCandidates.getValue("name").first)
    }
    private var localCaptures = 0
    private var beforeTranscription: () -> Unit = {}
    private var analysisResult = CallAnalysis(summary = "A brief call")
    private var lastCandidates = emptyMap<String, Pair<String, String>>()
    @Test fun explicitReanalysisNeverCapturesOrAppliesCallerEnrichment() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        workflow.retry(1, null)
        val restarted = RecordingWorkflow(store, commands, client, { 1000L }, enrichment = localEnrichment)
        restarted.processPending()
        assertEquals(1, localCaptures)
        assertEquals(1, localAttempts)
        assertEquals(2, analyses)
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
    }
    @Test fun ineligibleNumberStillTranscribesAndAnalyzesWithoutProfileWrites() {
        configure(); eligibleNumber = false
        workflow.attach(1, "１２３", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
        assertEquals(1, analyses); assertEquals(1, transcriptions); assertEquals(0, localAttempts)
        assertFalse(store.get(1)!!.enrichmentBaseline!!.eligible)
    }
    private var eligibleNumber = true
    private var localFailure = false
    private var localAttempts = 0
    private val localEnrichment = object : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

        override fun capture(number: String, recordedAt: Long): CallerIdentityBaseline { localCaptures++; return CallerIdentityBaseline(if (eligibleNumber) "test" else "", emptyMap(), eligibleNumber) }
        override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long, generation: Long, candidates: Map<String, Pair<String, String>>) {
            localAttempts++; lastCandidates = candidates
            if (localFailure) error("synthetic storage failure")
        }
    }
    private val connection = AiConnection(url = "https://example.com/v1", apiKey = "secret", model = "model")
    private val store = MemoryStore()
    private var transcriptions = 0
    private var analyses = 0
    private var beforeAnalysis: () -> Unit = {}
    private var fail = false
    private val client = object : AiClient {
        override fun operation(cancelled: () -> Boolean): AiClient = this
        override fun cancel() = Unit
        override fun test(connection: AiConnection, transcription: Boolean) {}
        override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String { transcriptions++; beforeTranscription(); return "Caller says hello" }
        override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
            analyses++; beforeAnalysis()
            if (fail) throw AiFailure("ai.temporary", true)
            return analysisResult
        }
    }
    private val commands = RecordingArchiveCommands(store, store, BusinessRevisionCoordinator())
    private val workflow = RecordingWorkflow(store, commands, client, { 1000L }, enrichment = localEnrichment)
    private fun configure(auto: Boolean = true) {
        store.seedSettings(RecordingSettings(automaticAnalysis = auto, analysis = connection, transcription = connection))
    }
    @Test fun offlineImportAndTranscriptionNeverRequireAnOnlineConnection() {
        val local = RecordingWorkflow(store, commands, client, { 1000L }, enrichment = localEnrichment,
            offlineTranscriber = { _, _, decoding ->
                decoding()
                OfflineTranscript("本地全文", "fixture-offline")
            })
        try {
            configure()
            assertEquals(ArchiveStatus.READY, local.attach(1, "31234", 100, "/private/test.wav",
                RecordingSource.IMPORTED, 1000, null, false, offlineOnly = true).status)
            store.seedSettings(RecordingSettings())
            local.startOffline(1)
            waitForOffline(local)
            val saved = requireNotNull(store.get(1))
            assertEquals("本地全文", saved.transcript)
            assertEquals("fixture-offline", saved.transcriptionModel)
            assertEquals(0, transcriptions)
            assertEquals(0, analyses)
            store.seedSettings(RecordingSettings(analysis = connection))
            local.retry(1, null)
            local.processPending()
            assertEquals(0, transcriptions)
            assertEquals(1, analyses)
            assertEquals(ArchiveStatus.COMPLETE, store.get(1)?.status)
        } finally {
            local.close()
        }
    }

    @Test fun offlineCancellationDiscardsLateResultsAndDoesNotAffectTheNextRun() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val local = RecordingWorkflow(store, commands, client, { 1000L }, enrichment = localEnrichment,
            offlineTranscriber = { _, _, decoding ->
                decoding()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                OfflineTranscript("本地全文", "fixture-offline")
            })
        try {
            local.attach(1, "31234", 100, "/private/test.wav", RecordingSource.IMPORTED, 1000, null, false)
            val first = local.startOffline(1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            local.cancelOffline(1, first)
            assertEquals(OfflineTranscriptionPhase.CANCELLING, local.offlineState(1)?.phase)
            release.countDown()
            waitForOffline(local)
            assertEquals("", store.get(1)?.transcript)
            val second = local.startOffline(1)
            assertNotEquals(first, second)
            local.cancelOffline(1, first)
            waitForOffline(local)
            assertEquals("本地全文", store.get(1)?.transcript)
        } finally {
            release.countDown()
            local.close()
        }
    }

    private fun waitForOffline(workflow: RecordingWorkflow) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (workflow.isRunning(1) && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("Offline task did not drain", workflow.isRunning(1))
    }
    @Test fun disabledAutomaticAnalysisRetainsAudioWithoutSendingIt() {
        configure(false)
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        workflow.processPending()
        assertEquals(0, transcriptions)
        assertEquals(ArchiveStatus.READY, store.get(1)!!.status)
    }
    @Test fun analysisRetryReusesCompletedTranscription() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        fail = true
        workflow.processPending()
        assertEquals(ArchiveStatus.FAILED, store.get(1)!!.status)
        assertEquals(1, transcriptions)
        fail = false
        workflow.retry(1, null)
        workflow.processPending()
        assertEquals(1, transcriptions)
        assertEquals(2, analyses)
        assertEquals(ArchiveStatus.COMPLETE, store.get(1)!!.status)
    }
    @Test fun deletionAndReplacementInvalidateLateResponse() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        beforeAnalysis = {
            workflow.delete(1, null, false)
            workflow.attach(1, "31234", 100, "/private/new.m4a", RecordingSource.IMPORTED, 2000, null, false)
        }
        workflow.processPending()
        assertNull(store.get(1)!!.analysis)
        assertEquals("/private/new.m4a", store.get(1)!!.audioPath)
    }
    @Test fun concurrentRetryDoesNotCreateDuplicateAnalysis() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        beforeAnalysis = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val worker = Thread { workflow.processPending() }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { workflow.retry(1, null) }
        workflow.processPending()
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, analyses)
    }
    @Test fun permanentErrorsWaitForUserInsteadOfLooping() {
        configure()
        val brokenClient = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String = throw AiFailure("ai.authentication")
        }
        val brokenWorkflow = RecordingWorkflow(store, commands, brokenClient, { 1000L }, enrichment = testEnrichment)
        brokenWorkflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        brokenWorkflow.processPending()
        assertEquals(Long.MAX_VALUE, store.get(1)!!.nextAttemptAt)
    }
    @Test fun disablingAutomaticAnalysisBeforeJobRunsStopsPendingUploads() {
        configure()
        workflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        configure(false)
        workflow.processPending()
        assertEquals(0, transcriptions)
        assertEquals(ArchiveStatus.READY, store.get(1)!!.status)
        workflow.retry(1, null)
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
                deleting.delete(1, null, false)
                return CallAnalysis(summary = "late")
            }
            lateinit var deleting: RecordingWorkflow
        }
        val deletingWorkflow = RecordingWorkflow(store, commands, cancelClient, enrichment = testEnrichment)
        cancelClient.deleting = deletingWorkflow
        deletingWorkflow.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        deletingWorkflow.processPending()
        assertTrue(wasCancelled)
        assertNull(store.get(1))
    }
    @Test fun operatingSystemCancellationKeepsJobResumableWithoutConsumingAttempt() {
        configure()
        var stopped = false
        val cancellable = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String {
                stopped = true
                throw AiFailure("recording.cancelled")
            }
        }
        val resumable = RecordingWorkflow(store, commands, cancellable, enrichment = testEnrichment)
        resumable.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        resumable.processPending(cancelled = { stopped })
        assertEquals(ArchiveStatus.QUEUED, store.get(1)!!.status)
        assertEquals(0, store.get(1)!!.attempts)
        assertEquals(0L, store.get(1)!!.nextAttemptAt)
    }

    @Test fun cancellationAfterNetworkReturnsDoesNotCommitLateAnalysis() {
        configure()
        var stopped = false
        val late = object : AiClient by client {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                stopped = true
                return CallAnalysis(summary = "late result")
            }
        }
        val resumable = RecordingWorkflow(store, commands, late, enrichment = testEnrichment)
        resumable.attach(1, "31234", 100, "/private/test.m4a", RecordingSource.IMPORTED, 1000, null, false)
        resumable.processPending(cancelled = { stopped })
        assertEquals(ArchiveStatus.QUEUED, store.get(1)!!.status)
        assertNull(store.get(1)!!.analysis)
        assertEquals(0, store.get(1)!!.attempts)
        assertEquals(0L, store.get(1)!!.nextAttemptAt)
    }

    private class MemoryStore : RecordingWorkflowReads, RecordingArchiveState, RecordingArchiveMutations {
        private var config = RecordingSettings()
        private val entries = mutableMapOf<Long, CallArchive>()
        @Synchronized override fun settings() = config
        @Synchronized fun seedSettings(value: RecordingSettings) { config = value }
        @Synchronized override fun get(callId: Long) = entries[callId]
        override fun generation(callId: Long) = entries[callId]?.generation
        override fun isDeleted(callId: Long) = false
        override fun earliestPendingAt(cancelled: () -> Boolean) = entries.values.filter { it.status in pending && it.nextAttemptAt != Long.MAX_VALUE }.minOfOrNull { it.nextAttemptAt }
        val candidatePageSizes = mutableListOf<Int>()
        var candidatesObserved: () -> Unit = {}
        override fun dueCandidates(at: Long, afterId: Long?, limit: Int, cancelled: () -> Boolean) = entries.values.filter {
            (afterId == null || it.callId > afterId) && it.status in pending && it.nextAttemptAt <= at
        }.sortedBy { it.callId }.take(limit).map { RecordingCandidate(it.callId, it.generation) }.also { candidatePageSizes += it.size; candidatesObserved() }
        private val pending = setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING, ArchiveStatus.FAILED, ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED)
        var rejectConfirmationState = false
        var rejectLocalFailureState = false
        @Synchronized override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
            if (rejectConfirmationState && value.manualAnalysisState == ManualAnalysisState.CONFIRMED)
                error("synthetic archive confirmation writeback failure")
            if (rejectLocalFailureState && value.status == ArchiveStatus.ENRICHMENT_FAILED) error("synthetic failure saving local failure")
            if (entries[callId]?.generation != expectedGeneration) return false
            entries[callId] = value; return true
        }
        @Synchronized override fun delete(callId: Long) = entries.remove(callId) != null
    }
}

private val testEnrichment = object : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

    override fun capture(number: String, recordedAt: Long) = CallerIdentityBaseline("test", emptyMap())
    override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long, generation: Long, candidates: Map<String, Pair<String, String>>) = Unit
}
