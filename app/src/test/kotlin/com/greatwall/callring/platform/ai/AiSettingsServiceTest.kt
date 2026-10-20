package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.recording.AiSettingsService
import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.greatwall.callring.ui.feature.ai.CredentialDraft
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AiSettingsServiceTest {
    private val folder: (String?) -> RecordingFolderView = { RecordingFolderView(null, if (it == null) com.greatwall.callring.contracts.runtime.AccessState.NOT_REQUIRED else com.greatwall.callring.contracts.runtime.AccessState.GRANTED) }


    @Test fun combinedPublishesOneMarkerAndNoopDoesNotPublish() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "combined-single-marker", cipher, coordinator)
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> }, folderView = folder)
        try {
            api.patchCombined(api.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(true)),
                api.getAiView().ok(), AiSettingsPatch(budget = PatchField.Set(ConversationBudget(65536, 4096))), LocalSecretChange.Keep, LocalSecretChange.Keep).ok()
            assertEquals(1L, coordinator.marker())
            assertEquals(1L, coordinator.capture().values.getValue("recordingConfig"))
            assertEquals(1L, coordinator.capture().values.getValue("aiConfig"))
            api.patchCombined(api.getRecordingView().ok(), RecordingSettingsPatch(), api.getAiView().ok(), AiSettingsPatch(), LocalSecretChange.Keep, LocalSecretChange.Keep).ok()
            assertEquals(1L, coordinator.marker())
        } finally { store.close() }
    }

    @Test fun recordingReturnKeepsItsCommittedVersionDuringAfterSave() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "version-after-save", cipher, coordinator)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> }, afterSave = {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }, folderView = folder)
        val other = AiSettingsService(store, coordinator, { true }, { _, _ -> }, folderView = folder)
        try {
            val first = async(Dispatchers.Default) { api.patchRecording(api.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).ok() }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = other.patchRecording(other.getRecordingView().ok(), RecordingSettingsPatch(recordingEnabled = PatchField.Set(false))).ok()
            release.countDown()
            val saved = first.await()
            assertTrue(saved.recordingEnabled)
            assertTrue(saved.revision < second.revision)
        } finally { release.countDown(); store.close() }
    }

    @Test fun legacyFastCompatibilityIsMigratedAndNeverBlocksModelChange()=runBlocking {
        val coordinator=BusinessRevisionCoordinator();val store=AndroidRecordingStore(RuntimeEnvironment.getApplication(),"unsupported-fast-model",cipher,coordinator)
        val original=RecordingSettings(analysis=AiConnection(url="https://example.com/v1",model="qwen3.8-flash",apiKey="fixture-private"),conversationCompatibility=ConversationCompatibility.QWEN_FAST,conversationBudget=ConversationBudget(65536,8192))
        store.persistSettings(original)
        assertEquals(ConversationCompatibility.AUTO,store.settings().conversationCompatibility)
        val api=AiSettingsService(store,coordinator,{true},{_,_->}, folderView = folder)
        try{
            val saved=api.patchAi(api.getAiView().ok(),AiSettingsPatch(analysis=ConnectionPatch(model=PatchField.Set("deepseek-chat")))).ok()
            assertEquals("deepseek-chat",saved.analysis.model)
            assertEquals(ProviderProfile.STANDARD,saved.analysis.resolvedProfile)
            assertEquals(original.conversationBudget,saved.budget);assertTrue(saved.analysis.keyConfigured)
            assertEquals(ConversationCompatibility.AUTO,store.settings().conversationCompatibility)
        }finally{store.close()}
    }
    @Test fun unrelatedBudgetDoesNotInvalidateKeyEditOrConnectionCopy()=runBlocking {
        val coordinator=BusinessRevisionCoordinator();val store=AndroidRecordingStore(RuntimeEnvironment.getApplication(),"ai_scope_fields",cipher,coordinator)
        val connection=AiConnection(url="https://example.com/v1",model="model",apiKey="fixture-private")
        store.persistSettings(RecordingSettings(analysis=connection,transcription=connection))
        val api=AiSettingsService(store,coordinator,{true},{_,_->}, folderView = folder)
        val base=api.getAiView().ok()
        api.patchAi(base,AiSettingsPatch(budget=PatchField.Set(ConversationBudget(65536,4096)))).ok()
        api.patchAi(base,AiSettingsPatch(),analysisSecret=LocalSecretChange.Replace("rotated-fixture")).ok()
        val copyBase=api.getAiView().ok()
        api.patchAi(copyBase,AiSettingsPatch(budget=PatchField.Set(ConversationBudget(32768,4096)))).ok()
        api.copyConnection(AiSlot.ANALYSIS,AiSlot.TRANSCRIPTION,copyBase.revision).ok()
        assertEquals("rotated-fixture",store.settings().transcription.apiKey)
        store.close()
    }
    @Test fun compatibilityIsPersistentSafeAndResetOnlyWhenCopyingIntoAnalysis() = runBlocking {
        val coordinator=BusinessRevisionCoordinator()
        val store=AndroidRecordingStore(RuntimeEnvironment.getApplication(),"profile_copy_matrix",cipher,coordinator)
        val qwen=AiConnection(url="https://dashscope.aliyuncs.com/compatible-mode/v1",model="qwen3.8-flash",apiKey="fixture-private")
        store.persistSettings(RecordingSettings(analysis=qwen,transcription=qwen,conversationCompatibility=ConversationCompatibility.STANDARD))
        assertEquals(ConversationCompatibility.STANDARD,store.settings().conversationCompatibility)
        val api=AiSettingsService(store,coordinator,{true},{_,_->}, folderView = folder)
        assertEquals(ProviderProfile.STANDARD,api.getAiView().ok().analysis.resolvedProfile)
        assertEquals(ProviderProfile.QWEN_FAST,api.getAiView().ok().analysis.copyToAnalysisProfile)
        assertEquals(ProviderProfile.QWEN_FAST,api.getAiView().ok().transcription.copyToAnalysisProfile)
        api.copyConnection(AiSlot.ANALYSIS,AiSlot.TRANSCRIPTION,api.getAiView().ok().revision).ok()
        assertEquals(ConversationCompatibility.STANDARD,store.settings().conversationCompatibility)
        val result=api.copyConnection(AiSlot.TRANSCRIPTION,AiSlot.ANALYSIS,api.getAiView().ok().revision).ok()
        assertEquals(ConversationCompatibility.AUTO,store.settings().conversationCompatibility)
        assertEquals(ProviderProfile.QWEN_FAST,result.analysis.resolvedProfile)
        assertEquals("fixture-private",store.settings().analysis.apiKey)
        assertFalse(result.toString().contains("fixture-private"))
        assertEquals(ConversationCompatibility.AUTO,RecordingJson.settings(RecordingJson.settings(store.settings()).apply {getJSONObject("conversation").remove("compatibility")}).conversationCompatibility)
        store.close()
    }
    @Test fun coldStartPatchCombinedWritesOnlyRequestedSlotAndRejectsStaleBase() = runBlocking {
        for(slot in AiSlot.entries) {
            val coordinator = BusinessRevisionCoordinator()
            val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(),"cold_page_$slot",cipher,coordinator)
            val service = AiSettingsService(store,coordinator,{ true },{ _,_ -> }, folderView = folder)
            try {
                val aiBase = service.getAiView().ok()
                val rec = service.getRecordingView().ok()
                val connectionPatch = ConnectionPatch(url = PatchField.Set("https://first.example/v1"), model = PatchField.Set("first"))
                val patch = if(slot == AiSlot.ANALYSIS) AiSettingsPatch(analysis = connectionPatch)
                            else AiSettingsPatch(transcription = connectionPatch.copy(protocol = PatchField.Set(AiProtocol.OPENAI)))
                service.patchCombined(rec,RecordingSettingsPatch(recordingEnabled = PatchField.Set(true)),aiBase,patch,
                    if(slot == AiSlot.ANALYSIS) LocalSecretChange.Replace("first-private") else LocalSecretChange.Keep,
                    if(slot == AiSlot.TRANSCRIPTION) LocalSecretChange.Replace("first-private") else LocalSecretChange.Keep).ok()
                val saved = store.settings()
                assertTrue(saved.recordingEnabled)
                assertEquals("first-private",(if(slot == AiSlot.ANALYSIS) saved.analysis else saved.transcription).apiKey)
                assertTrue((if(slot == AiSlot.ANALYSIS) saved.transcription else saved.analysis).apiKey.isEmpty())
                assertEquals(49152L,saved.conversationBudget.context)
                assertFalse(service.getAiView().ok().toString().contains("first-private"))
                // 过期 base 提交必须整体失败，不留下部分写入。
                assertTrue(service.patchCombined(rec,RecordingSettingsPatch(),aiBase,patch,LocalSecretChange.Replace("stale"),LocalSecretChange.Keep) is ApiResult.Failure)
                assertEquals("first-private",(if(slot == AiSlot.ANALYSIS) store.settings().analysis else store.settings().transcription).apiKey)
            } finally { store.close() }
        }
    }
    @Test fun draftConnectionTestUsesUnsavedFieldsAndOldFixturesReceiveNewDefaultWithoutOverwritingExplicitBudget() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "draft_connection", cipher, coordinator)
        val saved = AiConnection(url = "https://example.com", apiKey = "secret", model = "saved")
        store.persistSettings(RecordingSettings(analysis = saved))
        var tested: AiConnection? = null
        val api = AiSettingsService(store,coordinator,{ true },{ connection, _ -> tested = connection }, folderView = folder)
        val base = api.getAiView().ok()
        api.testDraft(base,AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("unsaved"))),AiSlot.ANALYSIS).ok()
        assertEquals("unsaved",tested!!.model)
        assertEquals("secret",tested!!.apiKey)
        assertEquals("saved",store.settings().analysis.model)
        val legacy = RecordingJson.settings(RecordingJson.settings(store.settings()).apply { remove("conversation") })
        assertEquals(49152L,legacy.conversationBudget.context)
        assertEquals(32768L,RecordingJson.settings(RecordingJson.settings(store.settings().copy(conversationBudget = ConversationBudget(32768,4096)))).conversationBudget.context)
    }
    private val cipher = object : RecordingCipher {
        override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value))
    }
    private fun <T> ApiResult<T>.ok(): T = (this as ApiResult.Success).value
    @Test fun interleavedPagesPreserveLatestOtherPageAndBothKeys() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "page_merge", cipher, coordinator)
        val old = AiConnection(url = "https://example.com/v1", model = "model", apiKey = "keep-both-keys")
        store.persistSettings(RecordingSettings(analysis = old, transcription = old))
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> }, folderView = folder)
        val recording = api.getRecordingView().ok()
        val ai = api.getAiView().ok()
        api.patchAi(ai, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("new-model")))).ok()
        api.patchRecording(recording, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).ok()
        assertTrue(store.settings().recordingEnabled)
        assertEquals("new-model", store.settings().analysis.model)
        assertEquals(old.apiKey, store.settings().analysis.apiKey)
        assertEquals(old.apiKey, store.settings().transcription.apiKey)
    }
    @Test fun sameFieldConflictAndInvalidClearDoNotCommit() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "page_conflict", cipher, coordinator)
        val old = AiConnection(url = "https://example.com/v1", model = "model", apiKey = "key")
        store.persistSettings(RecordingSettings(analysis = old))
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> }, folderView = folder)
        val base = api.getAiView().ok()
        api.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("first")))).ok()
        assertTrue(api.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("stale")))) is ApiResult.Failure)
        assertTrue(api.patchAi(api.getAiView().ok(), AiSettingsPatch(), LocalSecretChange.Clear) is ApiResult.Failure)
        assertEquals("first", store.settings().analysis.model)
        assertEquals("key", store.settings().analysis.apiKey)
    }
}
