package com.greatwall.callring

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.recording.PreparedRecordingAudio
import com.greatwall.callring.application.ports.recording.PreparedRecordingPlayback
import com.greatwall.callring.application.ports.recording.RecordingArchiveMutations
import com.greatwall.callring.application.ports.recording.RecordingArchiveState
import com.greatwall.callring.application.ports.recording.RecordingAudioFiles
import com.greatwall.callring.application.ports.recording.RecordingDirectory
import com.greatwall.callring.application.ports.recording.RecordingIdentityReads
import com.greatwall.callring.application.ports.recording.RecordingPlayback
import com.greatwall.callring.application.ports.recording.RecordingRegistration
import com.greatwall.callring.application.ports.recording.RecordingRegistrationStore
import com.greatwall.callring.application.ports.recording.RecordingSettingsStore
import com.greatwall.callring.application.ports.recording.RecordingWorkflowReads
import com.greatwall.callring.application.recording.RecordingWorkflow

import com.greatwall.callring.application.*
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** No Android resource, filesystem, full backend context, or alternate archive publisher. */
class RecordingApplicationServiceTest {
    @Test fun stoppingIdleRecordsCommandWithoutPublishing() = runBlocking {
        val f = Fixture()
        val command = com.greatwall.callring.contracts.command.CommandContext("stop", f.coordinator.capture(), 0) { true }
        f.service.bound(command).stop().requiredValue()
        assertEquals(0L, f.coordinator.marker())
        assertEquals(f.coordinator.capture(), f.coordinator.committed("stop"))
    }
    @Test fun inaccessibleDirectoryCannotBlockAlreadyImportedPendingWork() {
        val f = Fixture().seed()
        f.config = f.config.copy(recordingEnabled = true, systemFolder = "content://folder", automaticAnalysis = true,
            analysis = AiConnection(url = "https://example.test/v1", apiKey = "key", model = "model"),
            transcription = AiConnection(url = "https://example.test/v1", apiKey = "key", model = "model"))
        f.entries[-1] = f.entries.getValue(-1).copy(status = ArchiveStatus.QUEUED)
        f.failDirectory = true
        f.service.runScheduled { false }
        assertEquals(1, f.transcriptions)
        assertEquals(ArchiveStatus.COMPLETE, f.entries[-1]?.status)
    }
    @Test fun cancelledScheduledDiscoveryCannotAttachPreparedAudio() {
        val f = Fixture().seed()
        val now = System.currentTimeMillis() / 1000 * 1000
        f.events[-1] = f.events.getValue(-1).copy(startedAt = now)
        f.entries[-1] = f.entries.getValue(-1).copy(startedAtEpochMillis = now, source = RecordingSource.MICROPHONE)
        val before = f.entries[-1]
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(now))
        f.directoryFiles = listOf(RecordingFile("content://audio", "31234_" + stamp + ".wav", now))
        f.config = f.config.copy(recordingEnabled = true, systemFolder = "content://folder")
        var cancelled = false
        f.beforePrepared = { cancelled = true }
        f.service.runScheduled { cancelled }
        assertEquals(before, f.entries[-1])
        assertEquals(listOf("prepare", "discard"), f.effects)
        assertEquals(0L, f.coordinator.marker())
    }
    @Test fun identityUsesNegativeEventThenPositiveLinkThenMetadataThenLegacyLink() = runBlocking {
        val f = Fixture()
        f.events[-1] = RecordingCall(-1, "31234", 1, null)
        f.links[7] = f.events.getValue(-1)
        assertEquals(-1L, f.service.getIdentity(-1).requiredValue().callId)
        assertEquals(-1L, f.service.getIdentity(7).requiredValue().callId)
        f.entries[7] = CallArchive(7, "30000", 2)
        f.links.clear()
        assertEquals("30000", f.service.getIdentity(7).requiredValue().number)
        f.entries.clear()
        f.legacy = RecordingCall(8, "40000", 3, null)
        f.links[8] = f.events.getValue(-1)
        assertEquals(-1L, f.service.getIdentity(7).requiredValue().callId)
        assertEquals(listOf(7L), f.legacyReads)
        assertNull(f.service.getArchive(99).requiredValue())
    }
    @Test fun listIsStableForEqualStartTimes() = runBlocking {
        val f = Fixture()
        f.entries[1] = CallArchive(1, "1", 2)
        f.entries[2] = CallArchive(2, "2", 3)
        f.entries[3] = CallArchive(3, "3", 2)
        assertEquals(listOf(2L, 1L, 3L), f.service.listArchives().requiredValue().map { it.callId })
    }
    @Test fun changedGenerationDuringPreparationDiscardsOpaqueAudioWithoutStoppingPlayer() = runBlocking {
        val f = Fixture().seed()
        f.beforePrepared = { f.entries[-1] = f.entries.getValue(-1).copy(generation = 2) }
        assertTrue(f.service.importRecording(-1, "31234", 1, DocumentRef("content://audio")) is ApiResult.Failure)
        assertEquals(listOf("prepare", "discard"), f.effects)
        assertEquals(2L, f.entries.getValue(-1).generation)
        assertEquals(0L, f.coordinator.marker())
    }
    @Test fun failedAttachmentKeepsPreviousArchiveAndDiscardsNewAudioAfterActualStop() = runBlocking {
        val f = Fixture().seed()
        val before = f.entries[-1]
        f.playing = true
        f.failCas = true
        assertTrue(f.service.importRecording(-1, "31234", 1, DocumentRef("content://audio")) is ApiResult.Failure)
        assertEquals(before, f.entries[-1])
        assertEquals(listOf("prepare", "rename", "stop", "discard"), f.effects)
        assertEquals(setOf(DataDomain.RUNTIME), f.coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys })
        assertEquals(1L, f.coordinator.marker())
    }
    @Test fun successfulAttachmentCommitsOnceThenRemovesOnlyPreviousOwnedAudio() = runBlocking {
        val f = Fixture().seed()
        f.playing = true
        val next = f.service.importRecording(-1, "31234", 1, DocumentRef("content://audio")).requiredValue()
        assertEquals("new", next.audioPath)
        assertEquals(listOf("prepare", "rename", "stop", "retain", "delete:old"), f.effects)
        assertEquals(1L, f.coordinator.marker())
        assertEquals(setOf(DataDomain.RECORDING_ARCHIVES, DataDomain.RUNTIME), f.coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys })
    }
    @Test fun failedPlayerReplacementPublishesOnlyTheRealStop() = runBlocking {
        val f = Fixture().seed()
        f.playing = true
        f.failStart = true
        assertTrue(f.service.play(-1) is ApiResult.Failure)
        assertEquals(listOf("prepare-play", "stop", "start", "release"), f.effects)
        assertEquals(setOf(DataDomain.RUNTIME), f.coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys })
        assertEquals(1L, f.coordinator.marker())
        assertEquals("old", f.entries[-1]?.audioPath)
    }
    @Test fun failedOwnedDeletionRetainsRetryPathAndDoesNotTombstone() = runBlocking {
        val f = Fixture().seed()
        f.failDelete = true
        assertTrue(f.service.delete(-1) is ApiResult.Failure)
        assertEquals("old", f.entries[-1]?.audioPath)
        assertTrue(f.deleted.isEmpty())
        assertEquals(0L, f.coordinator.marker())
        f.failDelete = false
        f.service.delete(-1).requiredValue()
        assertNull(f.entries[-1])
        assertTrue(-1L in f.deleted)
    }
    @Test fun capturePlanPreservesForegroundAndRecorderPermissionRules() {
        val f = Fixture().seed()
        f.config = f.config.copy(recordingEnabled = true)
        assertNull(f.service.capturePlan(false, true, true))
        assertNotNull(f.service.capturePlan(true, true, true))
        assertNull(f.service.capturePlan(true, false, true))
        assertNotNull(f.service.capturePlan(true, false, false))
        f.config = f.config.copy(captureMode = CaptureMode.MICROPHONE_EXPERIMENTAL)
        assertFalse(f.service.capturePlan(false, true, true)!!.voiceCall)
        f.config = f.config.copy(recordingEnabled = false)
        assertNull(f.service.capturePlan(true, true, false))
    }
    @Test fun missingFolderAndPermissionFailuresRetainSpecificApiErrors() = runBlocking {
        val f = Fixture()
        val missing = f.service.inspectSystemRecording(DocumentRef("content://audio")) as ApiResult.Failure
        assertEquals("recording.folder_required", missing.error.message)
        f.prepareFailure = SecurityException()
        f.seed()
        val denied = f.service.importRecording(-1, "31234", 1, DocumentRef("content://audio")) as ApiResult.Failure
        assertEquals(ErrorCode.PERMISSION_REQUIRED, denied.error.code)
    }

    private class Fixture : RecordingIdentityReads, RecordingArchiveState, RecordingArchiveMutations,
        RecordingWorkflowReads, RecordingSettingsStore, RecordingRegistrationStore, RecordingAudioFiles, RecordingPlayback, RecordingDirectory {
        val coordinator = BusinessRevisionCoordinator()
        val entries = linkedMapOf<Long, CallArchive>()
        val events = mutableMapOf<Long, RecordingCall>()
        val links = mutableMapOf<Long, RecordingCall>()
        val deleted = mutableSetOf<Long>()
        val effects = mutableListOf<String>()
        val legacyReads = mutableListOf<Long>()
        var legacy: RecordingCall? = null
        var config = RecordingSettings()
        var directoryFiles = emptyList<RecordingFile>()
        var failDirectory = false
        var transcriptions = 0
        var playing = false
        var failCas = false
        var failDelete = false
        var failStart = false
        var prepareFailure: Exception? = null
        var beforePrepared: () -> Unit = {}
        private val commands = RecordingArchiveCommands(this, this, coordinator)
        private val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection, transcription: Boolean) = Unit
            override fun transcribe(connection: AiConnection, reference: AudioReference): String { transcriptions++; return "call text" }
            override fun analyze(connection: AiConnection, transcript: String) = CallAnalysis(summary = "done")
        }
        private val workflow = RecordingWorkflow(this, commands, client)
        private val ai = AiSettingsService(this, coordinator, { true }, { _, _ -> }, folderView = { error("unused") }, operationActive = { true })
        val service = RecordingService(this, this, ::settings, { workflow }, commands, RecordingRegistrationService(this, coordinator), ai,
            this, this, this, { legacyReads += it; legacy }, { -1 }, {}, coordinator, operationActive = { true })
        fun seed() = apply {
            events[-1] = RecordingCall(-1, "31234", 1, null)
            entries[-1] = CallArchive(-1, "31234", 1, 1, audioPath = "old")
        }
        override fun settings() = config
        override fun persistSettings(value: RecordingSettings) { config = value }
        override fun get(callId: Long) = entries[callId]
        override fun generation(callId: Long) = entries[callId]?.generation
        override fun isDeleted(callId: Long) = callId in deleted
        override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
            if (failCas) throw IOException("forced")
            if (generation(callId) != expectedGeneration) return false
            entries[callId] = value
            return true
        }
        override fun delete(callId: Long): CallArchive? { deleted += callId; return entries.remove(callId) }
        override fun findRecording(id: Long) = events[id]
        override fun linkedRecording(statisticsId: Long) = links[statisticsId]
        override fun metadata(callId: Long, cancelled: () -> Boolean) = entries[callId]?.let {
            RecordingArchiveMetadata(it.callId, it.generation, it.number, it.startedAtEpochMillis, it.status, it.source, it.durationMillis, it.audioPath != null, it.nextAttemptAt)
        }
        override fun listArchives() = entries.values.toList()
        override fun numberMetadata(number: String, cancelled: () -> Boolean) = emptyList<NumberRecordingMetadata>()
        override fun recentMetadata(since: Long, cancelled: () -> Boolean) = entries.values.filter { it.startedAtEpochMillis >= since }.mapNotNull { metadata(it.callId, cancelled) }
        override fun earliestPendingAt(cancelled: () -> Boolean): Long? = null
        override fun dueCandidates(at: Long, afterId: Long?, limit: Int, cancelled: () -> Boolean) = entries.values
            .filter { it.status == ArchiveStatus.QUEUED && (afterId == null || it.callId > afterId) }.take(limit).map { RecordingCandidate(it.callId, it.generation) }
        override fun insertIncoming(sessionId: String, number: String, at: Long, account: String?, initial: (RecordingCall) -> CallArchive): RecordingRegistration = error("unused")
        override fun sessionRecording(sessionId: String): RecordingCall? = null
        override fun insertStatisticsLink(statisticsId: Long, sessionId: String) = false
        override fun prepareImport(document: DocumentRef, cancelled: () -> Boolean): PreparedRecordingAudio {
            prepareFailure?.let { throw it }
            assertFalse("Copy must be outside commit fence", Thread.holdsLock(coordinator.fence))
            effects += "prepare"
            beforePrepared()
            return object : PreparedRecordingAudio {
                override val durationMillis = 1000L
                private var retained = false
                override fun commit(): AudioReference { assertTrue(Thread.holdsLock(coordinator.fence)); effects += "rename"; return AudioReference("new") }
                override fun retain() { retained = true; effects += "retain" }
                override fun close() { if (!retained) effects += "discard" }
            }
        }
        override fun prepareCapture(reference: AudioReference) = prepareImport(DocumentRef(reference.location)) { false }
        override fun deleteOwned(reference: AudioReference) { effects += "delete:" + reference.location; if (failDelete) throw IOException("forced") }
        override fun prepare(reference: AudioReference): PreparedRecordingPlayback {
            assertFalse(Thread.holdsLock(coordinator.fence))
            effects += "prepare-play"
            return object : PreparedRecordingPlayback { override fun close() { effects += "release" } }
        }
        override fun activate(prepared: PreparedRecordingPlayback, callId: Long) { effects += "start"; if (failStart) throw IOException("forced"); playing = true }
        override fun stop(): Boolean { if (!playing) return false; effects += "stop"; playing = false; return true }
        override fun status() = PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE)
        override fun snapshot() = aiObject()
        override fun authorize(document: DocumentRef) = Unit
        override fun list(folder: String): List<RecordingFile> { if (failDirectory) throw SecurityException(); return directoryFiles }
        override fun inspect(folder: String, document: DocumentRef): RecordingFile? = null
    }
}
