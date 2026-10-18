package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AiSettingsServiceTest {
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
