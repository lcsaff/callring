package com.greatwall.callring.platform.ai

import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher

import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.recording.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IndependentRecordingRuntimeTest {
    @Test fun authorizedFolderDiscoveryImportsOwnEventAndRespectsDeletionWithoutStatistics() = checkFolder(false)
    @Test fun restoredPendingSourceRequiresExplicitImportThenNeverReplaysIt() = checkFolder(true)
    private fun checkFolder(paused: Boolean) = runBlocking {
        val namespace = if(paused) "folder_paused" else "folder_independent"
        val store = AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0L })
        store.persistSettings(RecordingSettings(recordingEnabled = true, systemFolder = "content://recording-fixture/tree/root"))
        val now = System.currentTimeMillis() / 1000 * 1000
        var scanTime = now + 20_000
        val displayName = "31234_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now)) + ".wav"
        val original = File(context.cacheDir, "original-recording.wav").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        var copies = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
                MatrixCursor(projection).apply { addRow(projection!!.map<String,Any> { column -> when(column) {
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "audio"
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/wav"
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED -> now
                    android.provider.DocumentsContract.Document.COLUMN_SIZE -> original.length()
                    else -> error(column)
                } }.toTypedArray()) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor { copies++; return ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "recording-fixture"; exported = true; grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("recording-fixture", provider)
        context.contentResolver.takePersistableUriPermission(Uri.parse("content://recording-fixture/tree/root"),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val runtime = RecordingAssembly(context, namespace, findCall = { error("statistics must not be read") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, metadataReader = { 1000L to "wav" }, operationActive = { true }, numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = testEnrichment, now = { scanTime })
        val call = runtime.service.observeIncoming("folder-call", "31234", now, null)!!
        val available = (runtime.service.systemRecordings() as ApiResult.Success).value
        assertEquals(listOf(displayName), available.map { it.name })
        if(paused) {
            val key = com.greatwall.callring.platform.recording.SystemRecordingScanner.sourceKey("recording-fixture","audio")
            store.registerSystemSource(key,com.greatwall.callring.domain.recording.ParsedSystemRecording(call.number,call.startedAt),call) { error("exists") }
            val old = store.get(call.id)!!
            store.compareAndSet(call.id,old.generation,old.copy(status=ArchiveStatus.FAILED,error="recording.restore_retry_required",nextAttemptAt=Long.MAX_VALUE))
        }
        runtime.service.discoverSystemRecordings()
        assertNull((runtime.service.getArchive(call.id) as ApiResult.Success).value!!.audioPath)
        scanTime += 10_000
        runtime.service.discoverSystemRecordings()
        if(paused) {
            assertNull(store.get(call.id)!!.audioPath); assertEquals(0,copies)
            assertTrue(runtime.service.importRecording(call.id,call.number,call.startedAt,DocumentRef(available.single().uri),RecordingSource.SYSTEM) is ApiResult.Success)
        }
        val imported = (runtime.service.getArchive(call.id) as ApiResult.Success).value!!
        if (imported.audioPath == null) {
            val explicit = runtime.service.importRecording(call.id, call.number, call.startedAt, DocumentRef(available.single().uri), RecordingSource.SYSTEM)
            fail("Automatic association failed; explicit import diagnostic: $explicit")
        }
        assertEquals(RecordingSource.SYSTEM, imported.source)
        assertArrayEquals(original.readBytes(), File(requireNotNull(imported.audioPath)).readBytes())
        runtime.service.discoverSystemRecordings()
        assertEquals(1,copies)
        runtime.service.delete(call.id)
        runtime.service.discoverSystemRecordings()
        assertTrue((runtime.service.queryArchives() as ApiResult.Success).value.items.isEmpty())
        assertTrue(original.isFile)
        runtime.close()
    }
    @Test fun existingPositiveArchivesRemainReadableWithoutStatisticsStorage() = runBlocking {
        val store = AndroidRecordingStore(context, "legacy_independent", cipher, recordingVersion = { 0L })
        val saved = CallArchive(42, "31234", 1000, generation = 7, transcript = "saved", analysis = CallAnalysis(summary = "legacy"))
        assertTrue(store.compareAndSet(42, null, saved))
        val runtime = RecordingAssembly(context, "legacy_independent", findCall = { error("statistics unavailable") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, operationActive = { true }, numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = testEnrichment)
        assertEquals(saved, (runtime.service.getArchive(42) as ApiResult.Success).value)
        runtime.close()
    }
    @Test fun optionalAssociationIsRetainedWhenRecordingSwitchChangesBeforeStatisticsCommit() {
        val store = AndroidRecordingStore(context, "toggle_links", cipher, recordingVersion = { 0L })
        store.persistSettings(RecordingSettings(recordingEnabled = true))
        val runtime = RecordingAssembly(context, "toggle_links", findCall = { error("statistics lookup") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, operationActive = { true }, numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = testEnrichment)
        val call = runtime.service.observeIncoming("session", "31234", 1000, null)!!
        store.persistSettings(RecordingSettings(recordingEnabled = false))
        runtime.service.linkStatistics("session", 42)
        assertEquals(call, store.linkedRecording(42))
        runtime.close()
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher {
        override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value))
    }
    @Test fun recordsListsAndReadsOwnCallsWithoutStatisticsDatabase() = runBlocking {
        val store = AndroidRecordingStore(context, "independent", cipher, recordingVersion = { 0L })
        store.persistSettings(RecordingSettings(recordingEnabled = true))
        val runtime = RecordingAssembly(context, "independent", findCall = { error("statistics must not be read") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, operationActive = { true }, numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = testEnrichment)
        val call = requireNotNull(runtime.service.observeIncoming("session", "31234", 1000, null))
        assertTrue(call.id < 0)
        val archived = (runtime.service.getArchive(call.id) as ApiResult.Success).value!!
        assertEquals(call.id, archived.callId)
        assertEquals(listOf(archived.callId), (runtime.service.queryArchives() as ApiResult.Success).value.items.map { it.callId })
        runtime.service.discoverSystemRecordings()
        runtime.service.delete(call.id)
        runtime.service.observeIncoming("session", "31234", 1000, null)
        assertNull((runtime.service.getArchive(call.id) as ApiResult.Success).value)
        runtime.close()
    }
    @Test fun disablingRecordingDoesNotCreateIndependentEvents() {
        val store = AndroidRecordingStore(context, "disabled_recording", cipher, recordingVersion = { 0L })
        val runtime = RecordingAssembly(context, "disabled_recording", findCall = { error("statistics lookup") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, operationActive = { true }, numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls = { _, _ -> emptyList() }, enrichment = testEnrichment)
        assertNull(runtime.service.observeIncoming("session", "31234", 1000, null))
        assertTrue(store.queryArchives(ArchiveListQuery()) { false }.items.isEmpty())
        runtime.close()
    }
}

private val testEnrichment = object : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

    override fun capture(number: String, recordedAt: Long) = com.greatwall.callring.contracts.recording.CallerIdentityBaseline("test", emptyMap())
    override fun apply(number: String, baseline: com.greatwall.callring.contracts.recording.CallerIdentityBaseline, archiveId: Long, generation: Long, candidates: Map<String, Pair<String, String>>) = Unit
}
