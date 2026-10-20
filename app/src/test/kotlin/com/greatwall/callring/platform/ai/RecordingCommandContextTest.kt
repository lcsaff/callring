package com.greatwall.callring.platform.ai

import com.greatwall.callring.contracts.command.*
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.BusinessRevisionCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingCommandContextTest {
    @Test fun playbackFinalGenerationCheckDoesNotDecodeArchiveAgain() = runBlocking {
        val store = AndroidRecordingStore(context, "play-generation", cipher, recordingVersion = { 0L })
        val runtime = AndroidRecordingRuntime(context, "play-generation", { null }, { null }, {}, { store })
        runtime.directory.mkdirs()
        val audio = File(runtime.directory, "play.wav").apply { writeBytes(ByteArray(32)) }
        store.compareAndSet(-7, null, com.greatwall.callring.contracts.recording.CallArchive(-7, "31234", 1, generation = 4, audioPath = audio.absolutePath))
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(1000, 0) }
        val before = store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0
        try {
            val result = runtime.play(-7)
            assertTrue(result.toString(), result is ApiResult.Success)
            assertEquals(1L, (store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0) - before)
            assertEquals(4L, store.generation(-7))
        } finally { runtime.close() }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
    @Test fun deleteDoesNotWaitForImportPreparationAndLateImportCannotResurrectArchive() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(context, "import_race", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        store.persistSettings(RecordingSettings(recordingEnabled = true))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val file = File(context.cacheDir, "import-race.wav").apply { writeBytes(ByteArray(32)) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "import-race"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("import-race", provider)
        val runtime = AndroidRecordingRuntime(context, "import_race", { null }, { null }, {}, { store }, metadataReader = { entered.countDown(); release.await(5, TimeUnit.SECONDS); 1000L to "wav" }, coordinator = coordinator)
        val call = runtime.observeIncoming("session", "31234", 1, null)!!
        val importing = async(Dispatchers.IO) { runtime.importRecording(call.id, call.number, call.startedAt, DocumentRef("content://import-race/audio")) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val deleting = async(Dispatchers.IO) { runtime.delete(call.id) }
        val earlyDelete = withTimeoutOrNull(500) { deleting.await() }
        release.countDown()
        val imported = importing.await(); deleting.await()
        assertNotNull("Delete must not wait for media preparation", earlyDelete)
        assertTrue(imported is ApiResult.Failure)
        assertNull(store.get(call.id))
        assertTrue(store.isDeleted(call.id))
        runtime.close()
    }
    @Test fun staleRecordingCommandCannotRetryOrAdvanceArchiveGeneration() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val store = AndroidRecordingStore(context, "record_command", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val call = com.greatwall.callring.application.recording.RecordingRegistrationService(store, coordinator).registerIncoming("one", "31234", 1, null, null)
        val old = store.get(call.id)!!
        val runtime = AndroidRecordingRuntime(context, "record_command", { null }, { null }, {}, { store }, coordinator = coordinator)
        val stale = CommandContext("op", coordinator.capture(), 1) { true }
        coordinator.commit("core") { Unit }
        assertTrue(runtime.bound(stale).retry(call.id) is ApiResult.Failure)
        assertEquals(old.generation, store.get(call.id)!!.generation)
        runtime.close()
    }
}
