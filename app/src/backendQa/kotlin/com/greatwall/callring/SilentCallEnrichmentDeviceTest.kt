package com.greatwall.callring

import android.content.ComponentName
import android.content.pm.PackageManager
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.callers.CallerEnrichmentService
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import java.io.File

/** Isolated synthetic audio/AI; real decoding and storage, without microphone, network or SIM. */
class SilentCallEnrichmentDeviceTest : InstrumentationTestCase() {
    fun testSilentAnalysisPersistsCallerFieldsWithoutOverlayComponents() {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        val namespace = "silent_${System.nanoTime()}"
        val transcript = "我是李明，在财务部工作，职位是经理，所属地区是杭州。"
        var transcriptions = 0; var analyses = 0
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection, transcription: Boolean) = Unit
            override fun transcribe(connection: AiConnection, reference: AudioReference): String {
                assertTrue(File(reference.location).length() > 44); transcriptions++; return transcript
            }
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                analyses++
                return CallAnalysis(name = "李明", department = "财务部", position = "经理", region = "杭州",
                    summary = "自述工作信息", fieldEvidence = mapOf("name" to "我是李明", "department" to "在财务部工作",
                        "selfReportedPosition" to "职位是经理", "region" to "所属地区是杭州"))
            }
        }
        val coordinator = BusinessRevisionCoordinator()
        var core = SqliteCoreStorageResource(context, "$namespace.db")
        fun assembly() = RecordingAssembly(context, namespace, { null }, { null }, {}, coordinator = coordinator,
            clientFactory = { client }, operationActive = { true }, findCalls = { _, _ -> emptyList() }, enrichment = CallerEnrichmentService(core.sessions, coordinator) { true })
        var runtime = assembly()
        try {
            val connection = AiConnection(url = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture")
            runtime.store.persistSettings(RecordingSettings(recordingEnabled = true, automaticAnalysis = true, analysis = connection, transcription = connection))
            val call = requireNotNull(runtime.service.observeIncoming(namespace, "31234", System.currentTimeMillis(), null))
            runtime.directory.mkdirs()
            val audio = File(runtime.directory, "fixture.wav")
            context.assets.open("ai-test.wav").use { source -> audio.outputStream().use { source.copyTo(it) } }
            runtime.service.attachCapture(call, AudioReference(audio.path))
            runtime.service.runScheduled { false }
            assertEquals(ArchiveStatus.COMPLETE, runtime.store.get(call.id)!!.status)
            val caller = core.sessions.readCaller { it.caller("31234") }!!
            assertEquals("李明", caller.name); assertEquals("财务部", caller.department)
            assertEquals("经理", caller.selfReportedPosition); assertEquals("杭州", caller.region)
            assertFalse(caller.whitelisted); assertNull(caller.positionId)
            assertEquals(call.id, caller.identityMetadata.getValue("region").archiveId)
            val unsupported = requireNotNull(runtime.service.observeIncoming(namespace + "-unsupported", "１２３", System.currentTimeMillis(), null))
            val otherAudio = File(runtime.directory, "unsupported.wav")
            context.assets.open("ai-test.wav").use { source -> otherAudio.outputStream().use { source.copyTo(it) } }
            runtime.service.attachCapture(unsupported, AudioReference(otherAudio.path))
            runtime.service.runScheduled { false }
            assertEquals(ArchiveStatus.COMPLETE, runtime.store.get(unsupported.id)!!.status)
            assertFalse(runtime.store.get(unsupported.id)!!.enrichmentBaseline!!.eligible)
            assertNull(core.sessions.readCaller { it.caller("１２３") })
            runtime.close(); core.close()
            core = SqliteCoreStorageResource(context, "$namespace.db"); runtime = assembly()
            assertEquals(caller, core.sessions.readCaller { it.caller("31234") })
            runtime.service.runScheduled { false }
            assertEquals(2, transcriptions); assertEquals(2, analyses)
            for (name in listOf("com.greatwall.callring.ui.overlay.CallringOverlayService", "com.greatwall.callring.ui.overlay.LockScreenOverlayActivity")) {
                try {
                    val component = ComponentName(context.packageName, name)
                    if (name.endsWith("Service")) context.packageManager.getServiceInfo(component, 0) else context.packageManager.getActivityInfo(component, 0)
                    fail("Obsolete component remains: $name")
                } catch (_: PackageManager.NameNotFoundException) { }
            }
        } finally {
            runtime.close(); core.close()
            File(context.filesDir, namespace + "_recordings").listFiles()?.forEach { it.delete() }
            File(context.filesDir, namespace + "_recordings").delete()
            context.deleteDatabase("$namespace.db"); context.deleteDatabase(namespace + "_recording.db")
            context.deleteSharedPreferences(namespace + "_recording")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("callring.recording.$namespace") }
        }
    }
}
