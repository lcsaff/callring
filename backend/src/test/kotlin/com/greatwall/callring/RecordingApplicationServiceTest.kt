package com.greatwall.callring

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.SystemRecordingDiscoveryRunner
import com.greatwall.callring.application.ports.recording.SystemRecordingCandidate
import com.greatwall.callring.application.ports.recording.ScannedSystemRecording
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.ports.recording.NumberRecordingMetadata
import com.greatwall.callring.application.ports.recording.RecordingArchiveMetadata
import com.greatwall.callring.application.ports.recording.RecordingCandidate
import com.greatwall.callring.domain.calling.RecordingCall

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
    @Test fun discoveredOutgoingAudioNeverQueuesAutomaticAi() {
        val f = Fixture().seed()
        f.config = f.config.copy(automaticAnalysis = true)
        val call = f.events.getValue(-1).copy(direction = com.greatwall.callring.domain.model.CallDirection.OUTGOING)
        f.events[-1] = call
        val candidate = discoveredCandidate(call)
        f.service.importDiscovered(call, candidate, {}, { true }, { candidate.entry.file })
        assertEquals(ArchiveStatus.READY, f.entries.getValue(-1).status)
        assertEquals(com.greatwall.callring.contracts.callers.CallDirection.OUTGOING, f.entries.getValue(-1).direction)
        assertTrue(f.scheduled.isEmpty())
    }
    @Test fun discoveredCopyUsesLightweightCancellationAndOnlyTwoFullValidations() {
        val f = Fixture().seed()
        f.copyCancellationChecks = 640
        val call = f.events.getValue(-1)
        val file = RecordingFile("content://audio", "31234_19700101000001.wav", 1, 10_485_760)
        val candidate = SystemRecordingCandidate(ScannedSystemRecording("fixture", file),
            com.greatwall.callring.domain.recording.ParsedSystemRecording(call.number, call.startedAt), 0, 0)
        val validationFences = mutableListOf<Boolean>()
        var inspections = 0
        f.service.importDiscovered(call, candidate, validate = {
            validationFences += Thread.holdsLock(f.coordinator.fence)
        }, copyActive = { true }, inspect = { inspections++; file })
        assertEquals(640, f.copyCancellationChecksRun)
        assertEquals(listOf(false, true), validationFences)
        assertEquals(2, inspections)
        assertEquals("new", f.entries.getValue(-1).audioPath)
    }

    @Test fun discoveredCopyCancellationStopsBeforeCommitWithoutFullValidationPerChunk() {
        val f = Fixture().seed()
        f.copyCancellationChecks = 640
        val before = f.entries.getValue(-1)
        val call = f.events.getValue(-1)
        val candidate = discoveredCandidate(call)
        var active = true
        var validations = 0
        var inspections = 0
        f.beforePrepared = { active = false }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            f.service.importDiscovered(call, candidate, { validations++ }, { active }, {
                inspections++; candidate.entry.file
            })
        }
        assertEquals(1, validations)
        assertEquals(1, inspections)
        assertEquals(1, f.copyCancellationChecksRun)
        assertEquals(before, f.entries[-1])
        assertFalse("rename" in f.effects || "retain" in f.effects)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(0L, f.coordinator.marker())
    }

    @Test fun cancellationDuringGuardedFinalInspectionDiscardsPreparedAudio() {
        val f = Fixture().seed()
        f.copyCancellationChecks = 640
        val before = f.entries.getValue(-1)
        val call = f.events.getValue(-1)
        val candidate = discoveredCandidate(call)
        var active = true
        var validations = 0
        var inspections = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            f.service.importDiscovered(call, candidate, { validations++ }, { active }, {
                inspections++
                if (inspections == 2) {
                    assertTrue(Thread.holdsLock(f.coordinator.fence))
                    active = false
                }
                candidate.entry.file
            })
        }
        assertEquals(2, validations)
        assertEquals(2, inspections)
        assertEquals(before, f.entries[-1])
        assertEquals(listOf("prepare", "discard"), f.effects)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(0L, f.coordinator.marker())
    }

    @Test fun metadataChangesDuringCopyDiscardPreparedAudioBeforeCommit() {
        val call = RecordingCall(-1, "31234", 1, null)
        val candidate = discoveredCandidate(call)
        val file = candidate.entry.file
        for (changed in listOf(null, file.copy(size = 2), file.copy(modifiedAt = 2),
            file.copy(modifiedKnown = false), file.copy(name = "renamed.wav"))) {
            val f = Fixture().seed()
            f.copyCancellationChecks = 640
            val before = f.entries.getValue(-1)
            var inspections = 0
            assertThrows(Exception::class.java) {
                f.service.importDiscovered(call, candidate, {}, { true }, {
                    inspections++
                    if (inspections == 1) file else changed
                })
            }
            assertEquals(2, inspections)
            assertEquals(before, f.entries[-1])
            assertEquals(listOf("prepare", "discard"), f.effects)
            assertTrue(f.scheduled.isEmpty())
            assertEquals(0L, f.coordinator.marker())
        }
    }

    @Test fun generationChangedByFinalValidationIsRecheckedBeforeAttachment() {
        val f = Fixture().seed()
        f.copyCancellationChecks = 640
        val call = f.events.getValue(-1)
        val candidate = discoveredCandidate(call)
        var validations = 0
        assertThrows(IllegalStateException::class.java) {
            f.service.importDiscovered(call, candidate, {
                validations++
                if (validations == 2) {
                    assertTrue(Thread.holdsLock(f.coordinator.fence))
                    f.entries[-1] = f.entries.getValue(-1).copy(generation = 2)
                }
            }, { true }, { candidate.entry.file })
        }
        assertEquals(2, validations)
        assertEquals(2L, f.entries.getValue(-1).generation)
        assertEquals("old", f.entries.getValue(-1).audioPath)
        assertEquals(listOf("prepare", "discard"), f.effects)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(0L, f.coordinator.marker())
    }

    private fun discoveredCandidate(call: RecordingCall): SystemRecordingCandidate {
        val file = RecordingFile("content://audio", "31234_19700101000001.wav", 1, 10_485_760)
        return SystemRecordingCandidate(ScannedSystemRecording("fixture", file),
            com.greatwall.callring.domain.recording.ParsedSystemRecording(call.number, call.startedAt), 0, 0)
    }

    @Test fun deletingAnotherArchiveDoesNotInvalidatePreparedPlayback() = runBlocking {
        val f=Fixture()
        f.events[-3]=RecordingCall(-3,"300",3,null)
        f.events[-4]=RecordingCall(-4,"400",4,null)
        f.entries[-3]=CallArchive(-3,"300",3,1,audioPath="playing")
        f.entries[-4]=CallArchive(-4,"400",4,1,audioPath="selected")
        f.beforePlayback={ runBlocking { f.service.delete(-4).requiredValue() } }
        f.service.play(-3).requiredValue()
        assertTrue(f.playing); assertEquals(-3L,f.playingCallId)
        assertFalse("stop" in f.effects)
        assertFalse(-4L in f.entries)
    }
    @Test fun lowSpaceImportReportsSpecificFailureWithoutChangingArchive() = runBlocking {
        val f=Fixture().seed()
        val before=f.entries.toMap()
        f.prepareFailure=IOException("storage.insufficient_space")
        val result=f.service.importRecording(-1,"31234",1,DocumentRef("content://audio")) as ApiResult.Failure
        assertEquals("recording.insufficient_space",result.error.message)
        assertEquals(before,f.entries)
    }
    @Test fun deletionUsesScalarAudioAndNeverReadsFullArchive() = runBlocking {
        val f = Fixture().seed()
        f.service.delete(-1).requiredValue()
        assertEquals(0, f.fullReads)
        assertEquals(listOf("delete:old"), f.effects)
        assertTrue(-1L in f.deleted)
        assertFalse(-1L in f.entries)
    }
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
            analysis = AiConnection(url = "https://example.test/v1", apiKey = "key", model = "model"))
        f.entries[-1] = f.entries.getValue(-1).copy(status = ArchiveStatus.QUEUED)
        f.failDirectory = true
        f.service.runScheduled { false }
        assertEquals(1, f.transcriptions)
        assertEquals(ArchiveStatus.COMPLETE, f.entries[-1]?.status)
    }
    @Test fun offlineQueuedAudioResumesExactlyOnceWhenOnline() {
        val f = Fixture().seed()
        f.config = f.config.copy(recordingEnabled = true, automaticAnalysis = true,
            analysis = AiConnection(url = "https://example.test/v1", apiKey = "key", model = "model"))
        f.entries[-1] = f.entries.getValue(-1).copy(status = ArchiveStatus.QUEUED)
        f.online = false
        f.service.runScheduled { false }
        assertEquals(0, f.transcriptions)
        assertEquals(ArchiveStatus.QUEUED, f.entries[-1]!!.status)
        f.online = true
        f.service.runScheduled { false }
        assertEquals(1, f.transcriptions)
        assertEquals(ArchiveStatus.COMPLETE, f.entries[-1]!!.status)
        f.service.runScheduled { false }
        assertEquals(1, f.transcriptions)
    }
    @Test fun configurationInvalidationDoesNotDropDurableAudioProcessing() {
        val f = Fixture().seed()
        f.config = f.config.copy(recordingEnabled=true,systemFolder="content://folder",automaticAnalysis=true,
            analysis=AiConnection(url="https://example.test/v1",apiKey="key",model="model"))
        f.entries[-1] = f.entries.getValue(-1).copy(status=ArchiveStatus.QUEUED)
        f.discoveryInvalidated = true
        f.service.runScheduled { false }
        assertEquals(1,f.transcriptions)
        assertEquals(ArchiveStatus.COMPLETE,f.entries[-1]!!.status)
    }
    @Test fun invalidatedDiscoveryWithoutPendingWorkRequestsAnotherScan() {
        val f = Fixture()
        f.discoveryInvalidated = true
        val before = System.currentTimeMillis()
        assertTrue(requireNotNull(f.service.runScheduled { false }) >= before + 1000)
        assertEquals(0,f.transcriptions)
        assertNull(f.service.runScheduled { true })
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
        assertEquals(listOf(2L, 1L, 3L), f.service.queryArchives().requiredValue().items.map { it.callId })
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
    @Test fun reauthorizingSameFolderRestartsDiscoveryWithoutSettingsVersionChange() = runBlocking {
        val f = Fixture()
        f.config = f.config.copy(recordingEnabled=true,systemFolder="content://folder")
        f.service.setSystemFolder(DocumentRef("content://folder")).requiredValue()
        assertEquals(listOf(0L),f.scheduled)
        assertEquals(0L,f.coordinator.marker())
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
        var discoveryInvalidated = false
        var online = true
        var transcriptions = 0
        var playing = false
        var playingCallId: Long? = -1
        var beforePlayback: () -> Unit = {}
        var failCas = false
        var failDelete = false
        var failStart = false
        var fullReads = 0
        var capturing=false
        var prepareFailure: Exception? = null
        val scheduled = mutableListOf<Long>()
        var beforePrepared: () -> Unit = {}
        var copyCancellationChecks = 0
        var copyCancellationChecksRun = 0
        private val commands = RecordingArchiveCommands(this, this, coordinator)
        private val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection) = Unit

            override fun analyze(connection: AiConnection, transcript: String) = CallAnalysis(summary = "done")
        }
        private val workflow = RecordingWorkflow(this, commands, client, directionResolver = { it.direction }, enrichment = testEnrichment, offlineTranscriber={_,_,_->transcriptions++; com.greatwall.callring.application.ports.recording.OfflineTranscript("fixture transcript","local")})
        private val ai = AiSettingsService(this, coordinator, { true }, { _ -> }, folderView = { error("unused") }, operationActive = { true })
        val service: RecordingService = RecordingService(this, this, ::settings, { workflow }, commands, RecordingRegistrationService(this, coordinator), ai,
            this, this, this, { legacyReads += it; legacy }, { -1 }, { scheduled += it }, coordinator, operationActive = { true }, captureBusy = { capturing }, networkAvailable = { online },
            discovery = object : SystemRecordingDiscoveryRunner {
                override fun discover(cancelled: () -> Boolean): Long? {
                    if(discoveryInvalidated) throw kotlinx.coroutines.CancellationException("recording.configuration_changed")
                    if(!config.recordingEnabled || config.systemFolder == null) return null
                    if(failDirectory) throw SecurityException()
                    val file = directoryFiles.singleOrNull() ?: return null
                    val call = events.values.singleOrNull() ?: return null
                    service.importDiscovered(call,SystemRecordingCandidate(ScannedSystemRecording("fixture",file),
                        com.greatwall.callring.domain.recording.ParsedSystemRecording(call.number,call.startedAt),0,0),
                        validate = { if(cancelled()) throw kotlinx.coroutines.CancellationException() }, copyActive = { !cancelled() }, inspect = { inspect(config.systemFolder!!,DocumentRef(file.uri)) })
                    return null
                }
            })
        fun seed() = apply {
            events[-1] = RecordingCall(-1, "31234", 1, null)
            entries[-1] = CallArchive(-1, "31234", 1, 1, audioPath = "old")
        }
        override fun settings() = config
        override fun persistSettings(value: RecordingSettings) { config = value }
        override fun get(callId: Long) = entries[callId].also { fullReads++ }
        override fun generation(callId: Long) = entries[callId]?.generation
        override fun isDeleted(callId: Long) = callId in deleted
        override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
            if (failCas) throw IOException("forced")
            if (generation(callId) != expectedGeneration) return false
            entries[callId] = value
            return true
        }
        override fun delete(callId: Long): Boolean { deleted += callId; return entries.remove(callId) != null }
        override fun findRecording(id: Long) = events[id]
        override fun linkedRecording(statisticsId: Long) = links[statisticsId]
        override fun metadata(callId: Long, cancelled: () -> Boolean) = entries[callId]?.let {
            RecordingArchiveMetadata(it.callId, it.generation, it.number, it.startedAtEpochMillis, it.status, it.source, it.durationMillis, it.audioPath != null, it.nextAttemptAt)
        }
        override fun archiveStamp() = "fixture:1"
        override fun ownedAudio(callId: Long, cancelled: () -> Boolean) = entries[callId]?.audioPath?.let(::AudioReference)
        override fun queryArchives(query: ArchiveListQuery, cancelled: () -> Boolean) = ArchiveListPage(entries.values.sortedWith(compareByDescending<CallArchive> { it.startedAtEpochMillis }.thenBy { it.callId }).take(query.limit).map { ArchiveListItem(it.callId,it.generation,it.number,it.startedAtEpochMillis,it.status,it.source,it.durationMillis,it.audioPath!=null, it.analysis?.summary.orEmpty()) }, null, true)
        override fun numberMetadata(number: String, cancelled: () -> Boolean) = emptyList<NumberRecordingMetadata>()
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
            repeat(copyCancellationChecks) {
                copyCancellationChecksRun++
                if (cancelled()) throw kotlinx.coroutines.CancellationException("recording.cancelled")
            }
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
            beforePlayback()
            effects += "prepare-play"
            return object : PreparedRecordingPlayback { override fun close() { effects += "release" } }
        }
        override fun activate(prepared: PreparedRecordingPlayback, callId: Long) { effects += "start"; if (failStart) throw IOException("forced"); playing = true; playingCallId=callId }
        override fun stop(): Boolean { if (!playing) return false; effects += "stop"; playing = false; return true }
        override fun status() = PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE)
        override fun observation() = com.greatwall.callring.application.ports.recording.RecordingPlaybackObservation(status(), if(playing) playingCallId else null)
        override fun authorize(document: DocumentRef) = Unit
        override fun list(folder: String): List<RecordingFile> { if (failDirectory) throw SecurityException(); return directoryFiles }
        override fun inspect(folder: String, document: DocumentRef): RecordingFile? = directoryFiles.singleOrNull { it.uri == document.uri }
    }
}

private val testEnrichment = object : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

    override fun capture(number: String, recordedAt: Long) = CallerIdentityBaseline("test", emptyMap())
    override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long, generation: Long, candidates: Map<String, Pair<String, String>>) = Unit
}
