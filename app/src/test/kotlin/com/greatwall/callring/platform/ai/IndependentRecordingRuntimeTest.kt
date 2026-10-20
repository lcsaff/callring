package com.greatwall.callring.platform.ai

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
    @Test fun authorizedFolderDiscoveryImportsOwnEventAndRespectsDeletionWithoutStatistics() = runBlocking {
        val store = AndroidRecordingStore(context, "folder_independent", cipher, recordingVersion = { 0L })
        store.persistSettings(RecordingSettings(recordingEnabled = true, systemFolder = "content://recording-fixture/tree/root"))
        val now = System.currentTimeMillis() / 1000 * 1000
        val displayName = "31234_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now)) + ".wav"
        val original = File(context.cacheDir, "original-recording.wav").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
                MatrixCursor(projection).apply { addRow(arrayOf<Any>("audio", displayName, "audio/wav", now, original.length())) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "recording-fixture"; exported = true; grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("recording-fixture", provider)
        val runtime = AndroidRecordingRuntime(context, "folder_independent", findCall = { error("statistics must not be read") },
            activeCallId = { null }, schedule = {}, storeFactory = { store }, metadataReader = { 1000L to "wav" })
        val call = runtime.observeIncoming("folder-call", "31234", now, null)!!
        val available = (runtime.systemRecordings() as ApiResult.Success).value
        assertEquals(listOf(displayName), available.map { it.name })
        runtime.discoverSystemRecordings()
        val imported = (runtime.getArchive(call.id) as ApiResult.Success).value!!
        if (imported.audioPath == null) {
            val explicit = runtime.importRecording(call.id, call.number, call.startedAt, DocumentRef(available.single().uri), RecordingSource.SYSTEM)
            fail("Automatic association failed; explicit import diagnostic: $explicit")
        }
        assertEquals(RecordingSource.SYSTEM, imported.source)
        assertArrayEquals(original.readBytes(), File(requireNotNull(imported.audioPath)).readBytes())
        runtime.delete(call.id)
        runtime.discoverSystemRecordings()
        assertTrue((runtime.listArchives() as ApiResult.Success).value.isEmpty())
        assertTrue(original.isFile)
        runtime.close()
    }
    @Test fun existingPositiveArchivesRemainReadableWithoutStatisticsStorage() = runBlocking {
        val store = AndroidRecordingStore(context, "legacy_independent", cipher, recordingVersion = { 0L })
        val saved = CallArchive(42, "31234", 1000, generation = 7, transcript = "saved", analysis = CallAnalysis(summary = "legacy"))
        assertTrue(store.compareAndSet(42, null, saved))
        val runtime = AndroidRecordingRuntime(context, "legacy_independent", findCall = { error("statistics unavailable") },
            activeCallId = { null }, schedule = {}, storeFactory = { store })
        assertEquals(saved, (runtime.getArchive(42) as ApiResult.Success).value)
        runtime.close()
    }
    @Test fun optionalAssociationIsRetainedWhenRecordingSwitchChangesBeforeStatisticsCommit() {
        val store = AndroidRecordingStore(context, "toggle_links", cipher, recordingVersion = { 0L })
        store.persistSettings(RecordingSettings(recordingEnabled = true))
        val runtime = AndroidRecordingRuntime(context, "toggle_links", findCall = { error("statistics lookup") },
            activeCallId = { null }, schedule = {}, storeFactory = { store })
        val call = runtime.observeIncoming("session", "31234", 1000, null)!!
        store.persistSettings(RecordingSettings(recordingEnabled = false))
        runtime.linkStatistics("session", 42)
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
        val runtime = AndroidRecordingRuntime(context, "independent", findCall = { error("statistics must not be read") },
            activeCallId = { null }, schedule = {}, storeFactory = { store })
        val call = requireNotNull(runtime.observeIncoming("session", "31234", 1000, null))
        assertTrue(call.id < 0)
        val archived = (runtime.getArchive(call.id) as ApiResult.Success).value!!
        assertEquals(call.id, archived.callId)
        assertEquals(listOf(archived), (runtime.listArchives() as ApiResult.Success).value)
        runtime.discoverSystemRecordings()
        runtime.delete(call.id)
        runtime.observeIncoming("session", "31234", 1000, null)
        assertNull((runtime.getArchive(call.id) as ApiResult.Success).value)
        runtime.close()
    }
    @Test fun disablingRecordingDoesNotCreateIndependentEvents() {
        val store = AndroidRecordingStore(context, "disabled_recording", cipher, recordingVersion = { 0L })
        val runtime = AndroidRecordingRuntime(context, "disabled_recording", findCall = { error("statistics lookup") },
            activeCallId = { null }, schedule = {}, storeFactory = { store })
        assertNull(runtime.observeIncoming("session", "31234", 1000, null))
        assertTrue(store.listArchives().isEmpty())
        runtime.close()
    }
}
