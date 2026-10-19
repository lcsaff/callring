package com.greatwall.callring.bootstrap

import android.app.*
import android.content.Intent
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.platform.*
import com.greatwall.callring.platform.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CompleteSnapshotAssemblyTest {
    @Test fun realStoresRetainLegacySnapshotButLazyChatStartsWithDiscoveryAndNoBusinessReads() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val configuration = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM), databaseName = "small_snapshot.db", ringPreferencesName = "small_ring", widgetPreferencesName = "small_widgets", appearancePreferencesName = "small_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        val graph = BackendGraph(context, configuration, notifications, object : IncomingCallUiSink { override fun show(request: IncomingCallUiRequest) = Unit })
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(context, "small_recording", cipher, graph.coordinator)
        val connection = AiConnection(url = "https://example.com/v1", model = "model", apiKey = "unique-secret-sentinel")
        store.saveSettings(RecordingSettings(analysis = connection))
        val runtime = AndroidRecordingRuntime(context, "small_recording", { null }, { null }, {}, { store }, coordinator = graph.coordinator)
        graph.backend.callers.saveProfile(SaveCallerProfile("999", "person", "unit", null, false))
        val call = store.registerIncoming("session", "999", 1, null)
        val old = store.get(call.id)!!
        store.compareAndSet(call.id, old.generation, old.copy(generation = old.generation + 1, transcript = "short transcript FULL-TAIL"))
        val snapshots = completeSnapshotApi(graph.backend, runtime, graph.diagnostics, graph.coordinator, ForegroundResourceRegistry(), configuration.clock)
        val result = snapshots.captureComplete()
        assertTrue(result.toString(), result is ApiResult.Success)
        val snapshot = (result as ApiResult.Success).value
        assertEquals(21, snapshot.domains.size)
        assertTrue(snapshot.json().encode().contains("FULL-TAIL"))
        assertFalse(snapshot.json().encode().contains(connection.apiKey))
        var sent = 0
        val output=ByteArrayOutputStream()
        val http = object : HttpURLConnection(URL("https://example.com")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream(): OutputStream { sent++; return output }
            override fun getResponseCode() = 200
            override fun getInputStream(): InputStream = ByteArrayInputStream("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}""".toByteArray())
        }
        val client = CompatibleConversationClient { http }
        val conversation=ConversationService({graph.api},graph.queries,{store.settings()},graph.coordinator,{client})
        conversation.send(conversation.state.value.sessionId,"你好")
        withTimeout(5000){conversation.state.first{!it.busy}}
        assertEquals(ConversationPhase.COMPLETED,conversation.state.value.phase)
        assertEquals(0L,graph.queries.sourceQueries.get())
        val encoded=output.toByteArray()
        val estimate = ContextBudget.check(encoded, ConversationBudget())
        assertEquals(1, sent)
        assertEquals(1,org.json.JSONObject(String(encoded)).getJSONArray("tools").length())
        assertFalse(String(encoded).contains("FULL-TAIL"))
        println("REAL_DEFAULT_PROOF bytes=${encoded.size} estimate=$estimate tools=1 sourceQueries=0 http=$sent")
        conversation.close();runtime.close(); graph.close()
    }
}
