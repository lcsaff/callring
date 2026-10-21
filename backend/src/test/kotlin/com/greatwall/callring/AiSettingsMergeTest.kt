package com.greatwall.callring

import com.greatwall.callring.application.ports.recording.RecordingSettingsStore

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiSettingsMergeTest {
    private class Settings : RecordingSettingsStore {
        var current = RecordingSettings()
        var reads = 0
        var writes = 0
        var fail = false
        var onRead: () -> Unit = {}
        var onPersist: () -> Unit = {}
        override fun settings() = current.also { reads++; onRead() }
        override fun persistSettings(value: RecordingSettings) {
            if (fail) throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE, "fixture.write_failed"))
            writes++
            current = value
            onPersist()
        }
    }
    private class Fixture {
        val settings = Settings()
        val coordinator = BusinessRevisionCoordinator()
        var permission = true
        var networkOutsideFence = false
        val service = AiSettingsService(settings, coordinator, { permission }, { _, _ ->
            networkOutsideFence = !Thread.holdsLock(coordinator.fence)
        }, folderView = { RecordingFolderView(null, AccessState.NOT_REQUIRED) }, operationActive = { true })
    }
    private fun <T> ApiResult<T>.ok(): T = (this as ApiResult.Success).value
    private fun ApiResult<*>.failure() = (this as ApiResult.Failure).error
    private fun valid() = AiConnection(url = "https://example.com/v1", model = "fixture", apiKey = "private-fixture")

    @Test fun platformFolderReobserveUsesExistingUriWithoutReReadingOrDecryptingConfiguration() = runBlocking {
        val settings = Settings().apply { current = current.copy(systemFolder = "content://fixture/tree") }
        val coordinator = BusinessRevisionCoordinator(); var probes = 0; var access = AccessState.GRANTED
        val service = AiSettingsService(settings, coordinator, { true }, { _, _ -> },
            folderView = { uri -> assertEquals("content://fixture/tree", uri); probes++; RecordingFolderView("folder", access) }, operationActive = { true })
        service.reobserveFolder(); assertEquals(0, settings.reads); assertEquals(0, probes)
        service.getRecordingView().ok(); assertEquals(1, settings.reads); assertEquals(1, probes)
        service.reobserveFolder(); assertEquals(1, settings.reads); assertEquals(0L, coordinator.marker())
        access = AccessState.DENIED; service.reobserveFolder()
        assertEquals(1, settings.reads); assertEquals(1L, coordinator.marker())
        val runtime = coordinator.invalidations.snapshots.value.domains.getValue(com.greatwall.callring.contracts.change.DataDomain.RUNTIME)
        assertEquals(setOf("recordingFolder"), runtime.entityRevisions.keys)
        val bound = service.bound(CommandContext("bound", coordinator.capture(), 0) { true })
        bound.reobserveFolder(); assertEquals(1L, coordinator.marker()); assertEquals(1, settings.reads)
        assertEquals(0L, coordinator.capture().values.getValue("recordingConfig"))
    }
    @Test fun cancelledFolderObservationCannotPublishItsProviderResult() = runBlocking {
        val settings = Settings().apply { current = current.copy(systemFolder = "content://fixture/tree") }
        val coordinator = BusinessRevisionCoordinator()
        var cancelProbe = false
        var probe: Job? = null
        val service = AiSettingsService(settings, coordinator, { true }, { _, _ -> },
            folderView = {
                assertFalse("provider I/O must remain outside the publication fence", Thread.holdsLock(coordinator.fence))
                if (cancelProbe) probe!!.cancel()
                RecordingFolderView("folder", if (cancelProbe) AccessState.DENIED else AccessState.GRANTED)
            }, operationActive = { true })
        service.getRecordingView().ok()
        cancelProbe = true
        probe = launch(start = CoroutineStart.LAZY) { service.reobserveFolder() }
        probe.start(); probe.join()
        assertTrue(probe.isCancelled)
        assertEquals("cancelled observation must not advance the shared baseline/marker", 0L, coordinator.marker())
    }
    @Test fun callerCancellationWhileReadingBeforePersistenceDoesNotCommit() = runBlocking {
        val f = Fixture()
        val base = f.service.getAiView().ok()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.settings.onRead = {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val task = launch(Dispatchers.Default) { f.service.patchAi(base, AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096)))) }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            task.cancel()
            release.countDown()
            task.join()
            assertEquals(0, f.settings.writes)
            assertEquals(0L, f.coordinator.marker())
        } finally { release.countDown() }
    }

    @Test fun callerCancellationAfterPersistenceCannotLosePublication() = runBlocking {
        val f = Fixture()
        val base = f.service.getAiView().ok()
        lateinit var task: Job
        task = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            f.service.patchAi(base, AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096))))
        }
        f.settings.onPersist = { task.cancel() }
        task.start()
        task.join()
        assertEquals(1, f.settings.writes)
        assertEquals(1L, f.coordinator.marker())
        assertEquals(1L, f.coordinator.capture().values.getValue("aiConfig"))
    }

    @Test fun folderObservationOutsideFenceCannotRetagOldSettingsWithAnotherCommitVersion() = runBlocking {
        val f = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val service = AiSettingsService(f.settings, f.coordinator, { true }, { _, _ -> }, folderView = {
            assertFalse(Thread.holdsLock(f.coordinator.fence))
            if (block.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            RecordingFolderView("fixture", AccessState.GRANTED)
        }, operationActive = { true })
        val base = service.getRecordingView().ok()
        block.set(true)
        val task = async(Dispatchers.Default) { service.patchRecording(base, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).ok() }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val newer = f.service.patchRecording(f.service.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(false))).ok()
            release.countDown()
            val older = task.await()
            assertTrue(older.recordingEnabled)
            assertEquals(1L, older.revision)
            assertEquals(2L, newer.revision)
        } finally { release.countDown() }
    }

    @Test fun recordingAfterSaveFailureReportsExistingFailureAfterCommitButCombinedIsBestEffort() = runBlocking {
        val f = Fixture()
        val service = AiSettingsService(f.settings, f.coordinator, { true }, { _, _ -> }, afterSave = { error("fixture.after_save") }, folderView = { RecordingFolderView(null, AccessState.NOT_REQUIRED) }, operationActive = { true })
        assertEquals(ErrorCode.STORAGE_FAILURE, service.patchRecording(service.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).failure().code)
        assertTrue(f.settings.current.recordingEnabled)
        assertEquals(1L, f.coordinator.marker())
        service.patchCombined(service.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(false)), service.getAiView().ok(), AiSettingsPatch(), LocalSecretChange.Keep, LocalSecretChange.Keep).ok()
        assertFalse(f.settings.current.recordingEnabled)
        assertEquals(2L, f.coordinator.marker())
    }

    @Test fun sameConfiguredBooleanDoesNotPermitStaleSecretReplacementOrClear() = runBlocking {
        val f = Fixture()
        f.settings.current = RecordingSettings(analysis = valid())
        val base = f.service.getAiView().ok()
        f.service.patchAi(base, AiSettingsPatch(), LocalSecretChange.Replace("rotated-private"), LocalSecretChange.Keep).ok()
        assertTrue(f.service.getAiView().ok().analysis.keyConfigured)
        assertEquals(ErrorCode.CONFLICT, f.service.patchAi(base, AiSettingsPatch(), LocalSecretChange.Replace("stale-private"), LocalSecretChange.Keep).failure().code)
        assertEquals(ErrorCode.CONFLICT, f.service.patchAi(base, AiSettingsPatch(), LocalSecretChange.Clear, LocalSecretChange.Keep).failure().code)
        assertEquals("rotated-private", f.settings.current.analysis.apiKey)
        assertEquals(1L, f.coordinator.marker())
    }

    @Test fun keepUsesCurrentAndOnlySetFieldsConflictAndUnrelatedBudgetAllowsCopy() = runBlocking {
        val f = Fixture()
        f.settings.current = RecordingSettings(analysis = valid(), transcription = valid())
        val base = f.service.getAiView().ok()
        f.service.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("new")))).ok()
        val changed = f.service.patchAi(base, AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096)))).ok()
        assertEquals("new", changed.analysis.model)
        assertEquals(ErrorCode.CONFLICT, f.service.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("stale")))).failure().code)
        assertEquals(ErrorCode.CONFLICT, f.service.copyConnection(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION, base.revision).failure().code)
        val copyBase = f.service.getAiView().ok()
        f.service.patchAi(copyBase, AiSettingsPatch(budget = PatchField.Set(ConversationBudget(32768, 4096)))).ok()
        f.service.copyConnection(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION, copyBase.revision).ok()
        assertEquals("new", f.settings.current.transcription.model)
    }

    @Test fun combinedFailureIsAtomicNoopRecordsCommandAndOwnCommitCanContinue() = runBlocking {
        val f = Fixture()
        val ai = f.service.getAiView().ok()
        val recording = f.service.getRecordingView().ok()
        val command = CommandContext("combined", f.coordinator.capture(), 1, continueOwnCommits = true) { true }
        val bound = f.service.bound(command)
        bound.patchCombined(recording, RecordingSettingsPatch(), ai, AiSettingsPatch(), LocalSecretChange.Keep, LocalSecretChange.Keep).ok()
        assertEquals(0L, f.coordinator.committedMarker("combined"))
        assertEquals(0, f.settings.writes)
        f.settings.fail = true
        assertEquals(ErrorCode.STORAGE_FAILURE, bound.patchCombined(recording, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true)), ai,
            AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096))), LocalSecretChange.Keep, LocalSecretChange.Keep).failure().code)
        assertEquals(RecordingSettings(), f.settings.current)
        assertEquals(0L, f.coordinator.marker())
        f.settings.fail = false
        bound.patchCombined(recording, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true)), ai,
            AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096))), LocalSecretChange.Keep, LocalSecretChange.Keep).ok()
        assertEquals(1L, f.coordinator.marker())
        assertEquals(1L, f.coordinator.capture().values.getValue("aiConfig"))
        assertEquals(1L, f.coordinator.capture().values.getValue("recordingConfig"))
        bound.patchAi(bound.getAiView().ok(), AiSettingsPatch()).ok()
        val foreign = f.service.getAiView().ok()
        f.service.patchAi(foreign, AiSettingsPatch(budget = PatchField.Set(ConversationBudget(32768, 4096)))).ok()
        assertEquals(ErrorCode.CONFLICT, bound.patchAi(bound.getAiView().ok(), AiSettingsPatch()).failure().code)
    }

    @Test fun stoppedAndUnpermittedCommandsCannotReadOrPersistForConnectionTest() = runBlocking {
        val f = Fixture()
        f.settings.current = RecordingSettings(analysis = valid())
        for (permitted in listOf(false, true)) {
            val command = CommandContext("stopped", f.coordinator.capture(), 7, mayCommit = { permitted }) { !permitted }
            val reads = f.settings.reads
            assertEquals(ErrorCode.CONFLICT, f.service.bound(command).testSlot(AiSlot.ANALYSIS).failure().code)
            assertEquals(reads, f.settings.reads)
        }
        f.service.testSlot(AiSlot.ANALYSIS).ok()
        assertTrue(f.networkOutsideFence)
    }

    @Test fun microphonePermissionPrecedesRecordingEnabledConflictAndCombinedValidationWritesNothing() = runBlocking {
        val f = Fixture()
        val base = f.service.getRecordingView().ok()
        f.service.patchRecording(base, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).ok()
        f.permission = false
        assertEquals(ErrorCode.PERMISSION_REQUIRED, f.service.patchRecording(base, RecordingSettingsPatch(
            recordingEnabled = PatchField.Set(false), captureMode = PatchField.Set(CaptureMode.MICROPHONE_EXPERIMENTAL))).failure().code)
        val writes = f.settings.writes
        assertEquals(ErrorCode.VALIDATION, f.service.patchCombined(f.service.getRecordingView().ok(), RecordingSettingsPatch(automaticAnalysis = PatchField.Set(true)),
            f.service.getAiView().ok(), AiSettingsPatch(), LocalSecretChange.Keep, LocalSecretChange.Keep).failure().code)
        assertEquals(writes, f.settings.writes)
        assertEquals(1L, f.coordinator.marker())
    }

    @Test fun copyResetsAnalysisCompatibilityAndThinkingAndForcesTranscriptionOpenAiWithoutLeakingSecrets() = runBlocking {
        val f = Fixture()
        f.settings.current = RecordingSettings(analysis = valid().copy(protocol = AiProtocol.ANTHROPIC, thinkingMode = ThinkingMode.ENABLED),
            transcription = valid(), conversationCompatibility = ConversationCompatibility.STANDARD)
        var base = f.service.getAiView().ok()
        val intoTranscription = f.service.copyConnection(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION, base.revision).ok()
        assertEquals(AiProtocol.OPENAI, intoTranscription.transcription.protocol)
        assertEquals(ThinkingMode.DISABLED, intoTranscription.transcription.thinkingMode)
        assertEquals(ConversationCompatibility.STANDARD, intoTranscription.compatibility)
        base = f.service.getAiView().ok()
        val intoAnalysis = f.service.copyConnection(AiSlot.TRANSCRIPTION, AiSlot.ANALYSIS, base.revision).ok()
        assertEquals(ConversationCompatibility.AUTO, intoAnalysis.compatibility)
        assertEquals(ThinkingMode.DISABLED, intoAnalysis.analysis.thinkingMode)
        assertEquals(intoAnalysis.analysis.resolvedProfile, intoAnalysis.analysis.copyToAnalysisProfile)
        assertFalse(intoAnalysis.toString().contains("private-fixture"))
    }

}
