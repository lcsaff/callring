package com.greatwall.callring

import android.provider.Settings
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.AudioReference
import androidx.core.content.FileProvider
import com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment
import com.greatwall.callring.application.recording.model.AiConnection
import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.contracts.recording.CallerIdentityBaseline
import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Stage the public Mandarin fixture as offline-probe.wav in the QA package's files directory. */
class BundledOfflineTranscriptionDeviceTest : InstrumentationTestCase() {
    fun testBundledModelTranscribesMandarinInAirplaneMode() {
        val context = instrumentation.targetContext
        assertEquals("com.greatwall.callring.backendqa", context.packageName)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val audio = File(context.filesDir, "offline-probe.wav")
        assertTrue("Stage the offline probe fixture before running this test", audio.isFile)

        val namespace = "offline_probe_${System.nanoTime()}"
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean): AiClient = error("Unexpected online operation")
            override fun cancel() = Unit
            override fun analyze(connection: AiConnection, transcript: String): Nothing = error("Unexpected online analysis")
            override fun test(connection: AiConnection) = error("Unexpected online connection test")
        }
        val enrichment = object : RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

            override fun capture(number: String, recordedAt: Long): CallerIdentityBaseline = error("Unexpected enrichment")
            override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long,
                generation: Long, candidates: Map<String, Pair<String, String>>) = error("Unexpected enrichment")
        }
        fun assembly() = RecordingAssembly(context, namespace, findCall = { null },
            activeCallId = { null }, schedule = { error("Unexpected scheduling") },
            clientFactory = { client }, operationActive = { true },
            numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT), 0) },
            numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = enrichment)
        var runtime = assembly()
        val staged = File(requireNotNull(context.getExternalFilesDir("Download/update")), "$namespace.wav")
        try {
            val call = runtime.registrations.registerIncoming(namespace, "31234", System.currentTimeMillis(), null, null)
            audio.copyTo(staged)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
            val archive = runBlocking {
                when (val imported = runtime.service.importRecording(call.id, call.number, call.startedAt,
                    DocumentRef(uri.toString()))) {
                    is ApiResult.Success -> imported.value
                    is ApiResult.Failure -> error("Offline import failed: ${imported.error}")
                }
            }
            assertEquals(ArchiveStatus.READY, archive.status)
            runBlocking {
                assertTrue(runtime.service.transcribeOffline(archive.callId) is ApiResult.Success)
                withTimeout(30_000) {
                    while (runtime.store.get(archive.callId)?.transcript.isNullOrBlank()) delay(20)
                }
            }
            val saved = requireNotNull(runtime.store.get(archive.callId))
            assertEquals(ArchiveStatus.READY, saved.status)
            assertTrue("Expected the Mandarin fixture phrase", saved.transcript.contains("开饭时间"))
            assertEquals("sensevoice-int8-2024-07-17", saved.transcriptionModel)
            runtime.close()
            runtime = assembly()
            assertEquals(saved, runtime.store.get(archive.callId))
        } finally {
            runtime.close()
            staged.delete()
            File(context.filesDir, namespace + "_recordings").listFiles()?.forEach { it.delete() }
            File(context.filesDir, namespace + "_recordings").delete()
            context.deleteDatabase(namespace + "_recording.db")
            context.deleteSharedPreferences(namespace + "_recording")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("callring.recording.$namespace")
            }
        }
    }
}
