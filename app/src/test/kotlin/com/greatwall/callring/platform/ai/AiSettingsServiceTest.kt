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
    @Test fun coldStartPageDraftCreatesEitherSlotAtomicallyAndPreservesOtherPage() = runBlocking {
        for(slot in AiSlot.entries) {
            val coordinator = BusinessRevisionCoordinator()
            val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(),"cold_page_$slot",cipher,coordinator)
            val service = AiSettingsService(store,coordinator,{ true },{ _,_ -> })
            val root = java.lang.reflect.Proxy.newProxyInstance(CallringUiApi::class.java.classLoader,arrayOf(CallringUiApi::class.java)) { _,method,_ ->
                when(method.name) { "getAiSettings" -> service; "getChanges" -> object : ChangeFeed { override fun observe() = coordinator.changes }; else -> error("Unexpected API ${method.name}") }
            } as CallringUiApi
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                // UI's Android dependencies are runtime-only from app; exercise the real VM without changing module dependencies.
                val type = Class.forName("com.greatwall.callring.ui.feature.ai.AiSettingsViewModel")
                val model = type.getConstructor(CallringUiApi::class.java,CoroutineScope::class.java).newInstance(root,scope)
                @Suppress("UNCHECKED_CAST") val drafts = type.getMethod("getDraft").invoke(model) as StateFlow<AiSettingsView?>
                val initial = withTimeout(5000) { drafts.first { it != null } }!!
                val connection = AiConnectionView(AiProtocol.OPENAI,"https://first.example/v1","first",false)
                val draft = if(slot == AiSlot.ANALYSIS) initial.copy(analysis = connection) else initial.copy(transcription = connection)
                type.getMethod("edit",AiSettingsView::class.java).invoke(model,draft.copy(budget = ConversationBudget(65536,4096)))
                val input = type.getMethod("credentialInput",AiSlot::class.java).invoke(model,slot) as CredentialDraft
                val rec = service.getRecordingView().ok()
                service.patchRecording(rec,RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))).ok()
                val patch = if(slot == AiSlot.ANALYSIS) AiSettingsPatch(analysis = input.connection) else AiSettingsPatch(transcription = input.connection)
                service.patchCombined(rec,RecordingSettingsPatch(),input.base,patch,
                    if(slot == AiSlot.ANALYSIS) LocalSecretChange.Replace("first-private") else LocalSecretChange.Keep,
                    if(slot == AiSlot.TRANSCRIPTION) LocalSecretChange.Replace("first-private") else LocalSecretChange.Keep).ok()
                type.getMethod("credentialSaved",AiSlot::class.java).invoke(model,slot)
                withTimeout(5000) { drafts.first { value -> value != null && (if(slot == AiSlot.ANALYSIS) value.analysis else value.transcription).keyConfigured } }
                val saved = store.settings()
                assertTrue(saved.recordingEnabled)
                assertEquals("first-private",(if(slot == AiSlot.ANALYSIS) saved.analysis else saved.transcription).apiKey)
                assertFalse(service.getAiView().ok().toString().contains("first-private"))
                assertTrue((type.getMethod("getDirty").invoke(model) as StateFlow<*>).value as Boolean)
                assertEquals(65536L,drafts.value!!.budget.context)
                assertEquals(49152L,saved.conversationBudget.context)
                assertTrue(service.patchCombined(rec,RecordingSettingsPatch(),input.base,patch,LocalSecretChange.Replace("stale"),LocalSecretChange.Keep) is ApiResult.Failure)
            } finally { scope.cancel() }
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
