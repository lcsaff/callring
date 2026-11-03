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
    @Test fun sealStopsCancelReset() {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(context, "seal-drain", object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val connection = AiConnection(url = "https://example.com", apiKey = "secret", model = "model")
        store.persistSettings(RecordingSettings(automaticAnalysis = true, analysis = connection, transcription = connection))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean): AiClient = this
            override fun cancel() { release.countDown() }
            override fun transcribe(connection: AiConnection, reference: AudioReference): String = error("Unexpected transcription")
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return CallAnalysis(summary = "late")
            }
            override fun test(connection: AiConnection, transcription: Boolean) = error("Unexpected connection test")
        }
        val assembly = RecordingAssembly(context, "seal-drain", { null }, { null }, {}, storeFactory = { store }, coordinator = coordinator, clientFactory = { client }, operationActive = { true })
        val workflow = assembly.workflow
        val attached = workflow.attach(1, "31234", 1, "/private/audio.m4a", com.greatwall.callring.contracts.recording.RecordingSource.IMPORTED, 1000, null, false)
        assertTrue(assembly.archiveCommands.compareAndSet(1, attached.generation, attached.copy(generation = attached.generation + 1, transcript = "retained transcript"), null, false))
        val worker = Thread { workflow.processPending() }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val before = store.get(1)
            val marker = coordinator.marker()
            assembly.cancelPendingWork()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(before, store.get(1))
            assertEquals(marker, coordinator.marker())
        } finally { release.countDown(); worker.join(2000); assembly.close() }
    }
}
