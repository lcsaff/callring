package com.greatwall.callring

import android.app.KeyguardManager
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.recording.*
import java.io.File

/** Explicit run after locking the device. Synthetic audio and AI replies; no network or SIM call. */
class LockedRecordingPipelineDeviceTest : InstrumentationTestCase() {
    fun testLockedAudioDecodeAnalysisAndDurableArchive() {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        assertTrue("Run this test with keyguard locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val namespace = "locked_pipeline_${System.nanoTime()}"
        var transcriptions = 0
        var analyses = 0
        val transcript = "我是测试单位的测试姓名，负责测试职位，请明天回电。"
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection, transcription: Boolean) = Unit
            override fun transcribe(connection: AiConnection, reference: AudioReference): String {
                assertTrue(File(reference.location).length() > 44)
                transcriptions++
                return transcript
            }
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                assertEquals("我是测试单位的测试姓名，负责测试职位，请明天回电。", transcript)
                analyses++
                return CallAnalysis(name = "测试姓名", organization = "测试单位", summary = "请求明天回电")
            }
        }
        fun assembly() = RecordingAssembly(context, namespace, findCall = { error("No statistics dependency") },
            activeCallId = { null }, schedule = {}, clientFactory = { client }, operationActive = { true })
        var runtime = assembly()
        try {
            val connection = AiConnection(url = "https://fixture.invalid/v1", apiKey = "synthetic-device-test", model = "fixture")
            runtime.store.persistSettings(RecordingSettings(recordingEnabled = true, automaticAnalysis = true,
                transcription = connection, analysis = connection))
            val call = requireNotNull(runtime.service.observeIncoming(namespace, "100", System.currentTimeMillis(), null))
            assertTrue(runtime.directory.mkdirs() || runtime.directory.isDirectory)
            val audio = File(runtime.directory, "fixture.wav")
            context.assets.open("ai-test.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
            assertEquals(ArchiveStatus.QUEUED, runtime.service.attachCapture(call, AudioReference(audio.absolutePath)).status)
            runtime.service.runScheduled { false }
            val archive = requireNotNull(runtime.store.get(call.id))
            assertEquals("Failure reason: ${archive.error}", ArchiveStatus.COMPLETE, archive.status)
            assertEquals(1, transcriptions)
            assertEquals(1, analyses)
            assertEquals(transcript, archive.transcript)
            assertEquals("测试姓名", archive.analysis!!.name)
            assertEquals("测试单位", archive.analysis!!.organization)
            runtime.close()
            runtime = assembly()
            assertEquals(archive, runtime.store.get(call.id))
            assertEquals("synthetic-device-test", runtime.store.settings().transcription.apiKey)
            runtime.service.runScheduled { false }
            assertEquals(1, transcriptions)
            assertEquals(1, analyses)
            assertTrue(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        } finally {
            runtime.close()
            // Only this test's unique namespace is removed; never touch production storage.
            File(context.filesDir, namespace + "_recordings").listFiles()?.forEach { it.delete() }
            File(context.filesDir, namespace + "_recordings").delete()
            context.deleteDatabase(namespace + "_recording.db")
            context.deleteSharedPreferences(namespace + "_recording")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry("callring.recording.$namespace")
            }
        }
    }
}
