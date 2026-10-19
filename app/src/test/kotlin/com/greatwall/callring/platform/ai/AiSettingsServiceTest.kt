package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
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

    @Test fun changingModelWithFastSelectedGivesRecoverableProfileFeedbackAndKeepsStoredValues()=runBlocking {
        val coordinator=BusinessRevisionCoordinator();val store=AndroidRecordingStore(RuntimeEnvironment.getApplication(),"unsupported-fast-model",cipher,coordinator)
        val original=RecordingSettings(analysis=AiConnection(url="https://example.com/v1",model="qwen3.8-flash",apiKey="fixture-private"),conversationCompatibility=ConversationCompatibility.QWEN_FAST,conversationBudget=ConversationBudget(65536,8192))
        store.saveSettings(original);val api=AiSettingsService(store,coordinator,{true},{_,_->})
        try{
            val base=api.getAiView().ok();val result=api.patchAi(base,AiSettingsPatch(analysis=ConnectionPatch(model=PatchField.Set("deepseek-chat")))) as ApiResult.Failure
            assertEquals("ai.profile_invalid",result.error.message);assertEquals("ai.fast_model_required",result.error.fieldErrors["analysis.model"])
            assertEquals(original,store.settings())
            val saved=api.patchAi(base,AiSettingsPatch(analysis=ConnectionPatch(model=PatchField.Set("deepseek-chat")),compatibility=PatchField.Set(ConversationCompatibility.STANDARD))).ok()
            assertEquals(ProviderProfile.STANDARD,saved.analysis.resolvedProfile);assertEquals(original.conversationBudget,saved.budget);assertTrue(saved.analysis.keyConfigured)
        }finally{store.close()}
    }
    @Test fun unrelatedBudgetDoesNotInvalidateKeyEditOrConnectionCopy()=runBlocking {
        val coordinator=BusinessRevisionCoordinator();val store=AndroidRecordingStore(RuntimeEnvironment.getApplication(),"ai_scope_fields",cipher,coordinator)
        val connection=AiConnection(url="https://example.com/v1",model="model",apiKey="fixture-private")
        store.saveSettings(RecordingSettings(analysis=connection,transcription=connection))
        val api=AiSettingsService(store,coordinator,{true},{_,_->})
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
        store.saveSettings(RecordingSettings(analysis=qwen,transcription=qwen,conversationCompatibility=ConversationCompatibility.STANDARD))
        assertEquals(ConversationCompatibility.STANDARD,store.settings().conversationCompatibility)
        val api=AiSettingsService(store,coordinator,{true},{_,_->})
        assertEquals(ProviderProfile.STANDARD,api.getAiView().ok().analysis.resolvedProfile)
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
            val service = AiSettingsService(store,coordinator,{ true },{ _,_ -> })
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
        store.saveSettings(RecordingSettings(analysis = saved))
        var tested: AiConnection? = null
        val api = AiSettingsService(store,coordinator,{ true },{ connection, _ -> tested = connection })
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
        store.saveSettings(RecordingSettings(analysis = old, transcription = old))
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> })
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
        store.saveSettings(RecordingSettings(analysis = old))
        val api = AiSettingsService(store, coordinator, { true }, { _, _ -> })
        val base = api.getAiView().ok()
        api.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("first")))).ok()
        assertTrue(api.patchAi(base, AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("stale")))) is ApiResult.Failure)
        assertTrue(api.patchAi(api.getAiView().ok(), AiSettingsPatch(), LocalSecretChange.Clear) is ApiResult.Failure)
        assertEquals("first", store.settings().analysis.model)
        assertEquals("key", store.settings().analysis.apiKey)
    }
}
