package com.greatwall.callring

import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.model.CallDirection
import android.Manifest
import android.content.Intent
import android.provider.DocumentsContract
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.callers.CallerEnrichmentService
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.platform.recording.SystemRecordingScanner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Real SAF metadata/copy, Android audio decoder, and SQLite; AI is an in-process fixture. */
class SystemRecordingImportDeviceTest : InstrumentationTestCase() {
    fun testSystemImport(): Unit = runBlocking {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        val namespace = "systemqa_${System.nanoTime()}"
        val authority = context.packageName + ".systemrecording.qa"
        val tree = DocumentsContract.buildTreeDocumentUri(authority,"root")
        val source = File(context.noBackupFilesDir,"$namespace-source.wav")
        val coordinator = BusinessRevisionCoordinator()
        val core = SqliteCoreStorageResource(context,"$namespace.db")
        var time = System.currentTimeMillis()
        val started = time - 60_000
        val name = "Fixture(31234)_" + DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(started)) + ".wav"
        val expectedTranscript = "Synthetic system recording."
        var transcriptions = 0; var analyses = 0
        var matchedCalls = emptyList<RecordingCall>()
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection, transcription: Boolean) = Unit
            override fun transcribe(connection: AiConnection, reference: AudioReference): String {
                val decoded = File(reference.location)
                assertTrue(decoded.isFile && decoded.length() > 44)
                decoded.inputStream().use { input -> assertEquals("RIFF", String(ByteArray(4).also { input.read(it) }, Charsets.US_ASCII)) }
                transcriptions++; return expectedTranscript
            }
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis {
                assertEquals(expectedTranscript, transcript)
                analyses++; return CallAnalysis(summary = "Synthetic summary")
            }
        }
        fun assembly() = RecordingAssembly(context,namespace,{id->matchedCalls.singleOrNull { it.id==id }},{null},{},coordinator=coordinator,
            clientFactory={client},operationActive={true},numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls={_,_->matchedCalls},now={time},
            enrichment=CallerEnrichmentService(core.sessions,coordinator){true})
        var runtime = assembly()
        var granted = false
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALL_LOG)
        try {
            SystemRecordingQaProvider.reset()
            context.assets.open("ai-test.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
            val sourceBytes = source.readBytes()
            SystemRecordingQaProvider.audio = source
            SystemRecordingQaProvider.recordingName = name
            SystemRecordingQaProvider.modifiedAt = time - 30_000
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            granted = true
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
            val connection = AiConnection(url="https://fixture.invalid/v1",apiKey="fixture",model="fixture")
            runtime.store.persistSettings(RecordingSettings(recordingEnabled=true,automaticAnalysis=true,analysis=connection,transcription=connection,systemFolder=tree.toString()))
            // A valid date/filename inside the window is insufficient without a number-rule match.
            SystemRecordingQaProvider.recordingName = name.replace("31234","12345")
            runtime.service.runScheduled { false }; time += 10_000; runtime.service.runScheduled { false }
            assertNull(runtime.store.sourceBinding(SystemRecordingScanner.sourceKey(authority,"valid")))
            assertTrue((runtime.service.queryArchives() as com.greatwall.callring.contracts.common.ApiResult.Success).value.items.isEmpty())
            assertEquals(0,SystemRecordingQaProvider.opens.get())
            assertEquals(0,transcriptions); assertEquals(0,analyses)
            SystemRecordingQaProvider.recordingName = name
            val rowsBefore = SystemRecordingQaProvider.rowsEmitted.get()
            val firstDue = runtime.service.runScheduled { false }
            assertNotNull(firstDue)
            assertEquals(0,SystemRecordingQaProvider.opens.get())
            assertEquals(2001,SystemRecordingQaProvider.rowsEmitted.get() - rowsBefore)
            time += 10_000
            runtime.service.runScheduled { false }
            runtime.workflow.processPending()
            val key = SystemRecordingScanner.sourceKey(authority,"valid")
            val pendingCall = requireNotNull(runtime.store.sourceBinding(key))
            val pending = requireNotNull(runtime.store.get(pendingCall.id))
            assertEquals(ArchiveStatus.READY, pending.status)
            assertEquals(com.greatwall.callring.contracts.callers.CallDirection.UNKNOWN, pending.direction)
            assertEquals(0,transcriptions); assertEquals(0,analyses)
            matchedCalls = listOf(RecordingCall(101, pendingCall.number, pendingCall.startedAt, 1, CallDirection.INCOMING))
            runtime.service.runScheduled { false }
            runtime.workflow.processPending()
            val call = requireNotNull(runtime.store.sourceBinding(key))
            assertTrue(call.id < 0)
            assertEquals(CallDirection.INCOMING, call.direction)
            val saved = requireNotNull(runtime.store.get(call.id))
            assertEquals(ArchiveStatus.COMPLETE,saved.status)
            assertEquals(expectedTranscript,saved.transcript)
            assertTrue(File(requireNotNull(saved.audioPath)).isFile)
            assertTrue(requireNotNull(saved.durationMillis) > 0)
            assertEquals(1,transcriptions); assertEquals(1,analyses)
            assertEquals(1,SystemRecordingQaProvider.opens.get())
            assertEquals(0,SystemRecordingQaProvider.skippedOpens.get())
            assertEquals(SystemRecordingQaProvider.childQueries.get(),SystemRecordingQaProvider.closedCursors.get())
            runtime.close(); runtime = assembly()
            assertEquals(saved,runtime.store.get(call.id))
            assertEquals(call,runtime.store.sourceBinding(key))
            runtime.service.runScheduled { false }; runtime.workflow.processPending()
            assertEquals(1,SystemRecordingQaProvider.opens.get())
            assertEquals(1,transcriptions); assertEquals(1,analyses)
            assertTrue(runtime.service.delete(call.id) is ApiResult.Success)
            time += 10_000
            runtime.service.runScheduled { false }; runtime.workflow.processPending()
            assertTrue(runtime.store.isDeleted(call.id)); assertNull(runtime.store.get(call.id))
            assertNotNull(runtime.store.sourceBinding(key))
            assertEquals(1,SystemRecordingQaProvider.opens.get())
            assertEquals(1,transcriptions); assertEquals(1,analyses)
            assertTrue(sourceBytes.contentEquals(source.readBytes()))
            assertEquals(2001*SystemRecordingQaProvider.childQueries.get(),SystemRecordingQaProvider.rowsEmitted.get())
            assertEquals(SystemRecordingQaProvider.childQueries.get(),SystemRecordingQaProvider.closedCursors.get())
            android.util.Log.i("SystemImportQa", "rows=${SystemRecordingQaProvider.rowsEmitted.get()} queries=${SystemRecordingQaProvider.childQueries.get()} opens=${SystemRecordingQaProvider.opens.get()} mockTranscriptions=$transcriptions mockAnalyses=$analyses")
        } finally {
            runtime.close(); core.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if(granted) context.contentResolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source.delete(); SystemRecordingQaProvider.reset()
            deleteQaDirectory(context.filesDir,namespace+"_recordings")
            deleteQaDirectory(context.cacheDir,namespace+"_ai_audio")
            context.deleteDatabase("$namespace.db"); context.deleteDatabase(namespace+"_recording.db")
            context.deleteSharedPreferences(namespace+"_recording")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("callring.recording.$namespace") }
        }
    }
    private fun deleteQaDirectory(parent: File, name: String) {
        check(name.startsWith("systemqa_") && name.none { it == '/' || it == '\\' })
        val directory = File(parent,name)
        val resolved = directory.canonicalFile
        check(resolved.parentFile == parent.canonicalFile && resolved.name == name)
        check(directory.deleteRecursively() || !directory.exists())
    }
}
