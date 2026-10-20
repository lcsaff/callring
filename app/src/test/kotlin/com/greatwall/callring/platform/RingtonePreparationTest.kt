package com.greatwall.callring.platform

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ringtone.RingtoneService
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RingtonePreparationTest {
    private fun <T> ApiResult<T>.ok() = (this as ApiResult.Success).value
    private class Documents : DocumentStore {
        var beforeRead: (DocumentRef) -> Unit = {}
        override fun <T> read(source: DocumentRef, block: (InputStream) -> T): T {
            beforeRead(source)
            return ByteArrayInputStream(source.uri.toByteArray()).use(block)
        }
        override fun <T> write(destination: DocumentRef, block: (OutputStream) -> T) = error("unused")
        override fun readCalendar(source: DocumentRef) = error("unused")
    }
    private fun status() = RuntimeStatus(Capability.entries.associateWith { AccessState.GRANTED }, Audibility.AUDIBLE,
        PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 0)

    @Test fun realPreparedFileMetadataRollbackAndSameLabelReplacementPublishExactlyOnce() = runBlocking {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "ring-preparation-${System.nanoTime()}")
        val preferences = MemoryPreferences()
        val coordinator = BusinessRevisionCoordinator()
        val documents = Documents()
        documents.beforeRead = { assertFalse(Thread.holdsLock(coordinator.fence)) }
        AndroidAudioController(context, preferences, { directory }, documents).use { audio ->
            val service = RingtoneService(audio, ::status, coordinator, Dispatchers.IO)
            val first = service.importAudio(DocumentRef("first")).ok()
            val second = service.importAudio(DocumentRef("second")).ok()
            assertEquals(first, second)
            assertEquals("second", File(directory, "selected_audio").readText())
            assertEquals(2L, coordinator.marker())
            assertEquals(2L, coordinator.capture().values.getValue("ringtone"))
            assertEquals(0L, coordinator.capture().values.getValue("core"))
            preferences.failNext = true
            assertTrue(service.importAudio(DocumentRef("failed")) is ApiResult.Failure)
            assertEquals("second", File(directory, "selected_audio").readText())
            assertEquals(second, audio.getSettings())
            assertEquals(2L, coordinator.marker())
            service.restoreBuiltIn().ok()
            service.restoreBuiltIn().ok()
            service.setEnabled(false).ok()
            assertEquals(3L, coordinator.marker())
            assertFalse(directory.listFiles()!!.any { it.name.startsWith("audio-backup-") || it.name.startsWith("audio-import-") })
        }
    }

    @Test fun newerImportOrRestoreWhileOlderPreparationRunsPreventsOldRollbackOverwritingNewSelection() = runBlocking {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        for (restore in listOf(false, true)) {
            val context = RuntimeEnvironment.getApplication()
            val directory = File(context.cacheDir, "ring-race-${System.nanoTime()}")
            val preferences = MemoryPreferences()
            val documents = Documents()
            val coordinator = BusinessRevisionCoordinator()
            AndroidAudioController(context, preferences, { directory }, documents).use { audio ->
                val service = RingtoneService(audio, ::status, coordinator, Dispatchers.IO)
                service.importAudio(DocumentRef("initial")).ok()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                documents.beforeRead = { if (it.uri == "older") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                } }
                val old = async(Dispatchers.Default) { audio.prepareAudio(DocumentRef("older")) {} }
                try {
                    check(entered.await(10, TimeUnit.SECONDS))
                    // This token owns a completed backup of initial audio, while the first token
                    // is still copying. Both must lose to the later selection before metadata IO.
                    val backedUpOld = audio.prepareAudio(DocumentRef("backed-up-old")) {}
                    if (restore) service.restoreBuiltIn().ok() else service.importAudio(DocumentRef("newer")).ok()
                    backedUpOld.use { prepared ->
                        preferences.failNext = true
                        val failure = assertThrows(BackendFailure::class.java) { prepared.commit {} }
                        assertEquals("audio.import_superseded", failure.error.message)
                    }
                    release.countDown()
                    old.await().use { prepared ->
                        preferences.failNext = true
                        val failure = assertThrows(BackendFailure::class.java) { prepared.commit {} }
                        assertEquals("audio.import_superseded", failure.error.message)
                    }
                    assertEquals(if (restore) "initial" else "newer", File(directory, "selected_audio").readText())
                    assertEquals(if (restore) AudioSource.BUILT_IN else AudioSource.IMPORTED, audio.getSettings().source)
                    assertEquals(2L, coordinator.marker())
                } finally { release.countDown() }
            }
        }
    }
}
