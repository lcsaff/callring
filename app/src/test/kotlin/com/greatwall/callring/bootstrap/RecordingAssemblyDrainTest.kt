package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.contracts.ai.AiSlot
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.CallAnalysis
import com.greatwall.callring.data.recording.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingAssemblyDrainTest {
    @Test fun rootConnectionTestIsCancelledAndDrainedBeforeStoreCloses() {
        val context = RuntimeEnvironment.getApplication()
        val store = AndroidRecordingStore(context, "root-drain", object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }, recordingVersion = { 0 })
        store.persistSettings(RecordingSettings(analysis = AiConnection(url = "https://example.com", apiKey = "secret", model = "model")))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean): AiClient = error("Unexpected workflow")
            override fun cancel() { cancelled.countDown() }
            override fun transcribe(connection: AiConnection, reference: AudioReference): String = error("Unexpected transcription")
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis = error("Unexpected analysis")
            override fun test(connection: AiConnection, transcription: Boolean) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val assembly = RecordingAssembly(context, "root-drain", { null }, { null }, {}, storeFactory = { store }, clientFactory = { client }, operationActive = { true })
        val api = assembly.aiSettings
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<ApiResult<Unit>> { runBlocking { api.testSlot(AiSlot.ANALYSIS) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { assembly.close() }.apply { start() }
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(closer.isAlive)
            release.countDown(); closer.join(2000)
            assertFalse(closer.isAlive); assertTrue(result.get(2, TimeUnit.SECONDS) is ApiResult.Failure)
            assertThrows(IllegalStateException::class.java) { store.settings() }
        } finally { release.countDown(); closer.join(2000); executor.shutdownNow(); assembly.close() }
    }
}
