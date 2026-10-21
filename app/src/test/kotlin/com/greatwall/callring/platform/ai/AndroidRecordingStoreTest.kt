package com.greatwall.callring.platform.ai

import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import com.greatwall.callring.data.recording.RecordingJson

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.recording.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidRecordingStoreTest {
    @Test fun retainedStoreCannotReopenDatabaseAfterClose() {
        val store = AndroidRecordingStore(context, "terminal-store", cipher, recordingVersion = { 0L })
        store.captureRecordingText()
        val helper = store.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(store) as android.database.sqlite.SQLiteOpenHelper
        val database = helper.writableDatabase
        store.close()
        assertFalse(database.isOpen)
        assertThrows(IllegalStateException::class.java) { store.captureRecordingText() }
        assertThrows(IllegalStateException::class.java) { store.scopedReads() }
        assertFalse(database.isOpen)
        store.close()
    }

    @Test fun firstIndependentIdentityCanBeReimportedAfterDeletion() {
        val store = AndroidRecordingStore(context, "negative_reimport", cipher, recordingVersion = { 0L })
        val call = com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("first", "31234", 1000, null, null)
        assertEquals(-1L, call.id)
        store.delete(call.id)
        val archive = CallArchive(call.id, call.number, call.startedAt, generation = 10, status = ArchiveStatus.READY)
        assertTrue(store.compareAndSet(call.id, null, archive))
        assertEquals(archive, store.get(call.id))
        assertFalse(store.isDeleted(call.id))
    }
    @Test fun independentRegistrationIsAtomicIdempotentAndDoesNotMergeDifferentSessions() {
        val store = AndroidRecordingStore(context, "own_calls", cipher, recordingVersion = { 0L })
        val first = com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-a", "31234", 1000, "sim-a", null)
        assertTrue(first.id < 0)
        assertEquals(first, com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-a", "31234", 1000, "sim-a", null))
        val second = com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-b", "31234", 1000, "sim-b", null)
        assertNotEquals(first.id, second.id)
        assertEquals(ArchiveStatus.WAITING_RECORDING, store.get(first.id)!!.status)
        assertEquals(2, store.listArchives().size)
        store.delete(first.id)
        com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-a", "31234", 1000, "sim-a", null)
        assertNull(store.get(first.id))
        assertTrue(store.isDeleted(first.id))
    }
    @Test fun statisticalLinksUseExactSessionAndCanArriveBeforeRegistration() {
        val store = AndroidRecordingStore(context, "links", cipher, recordingVersion = { 0L })
        store.insertStatisticsLink(42, "session-a")
        assertNull(store.linkedRecording(42))
        val first = com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-a", "31234", 1000, null, null)
        val second = com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("session-b", "31234", 1000, null, null)
        store.insertStatisticsLink(43, "session-b")
        assertEquals(first, store.linkedRecording(42))
        assertEquals(second, store.linkedRecording(43))
        assertEquals(first, store.findRecording(first.id))
    }
    @Test fun v1UpgradePreservesExistingArchivesAndDeletedMarkers() {
        val entry = CallArchive(42, "31234", 1000, generation = 7, transcript = "legacy", analysis = CallAnalysis(summary = "saved"))
        context.openOrCreateDatabase("migration_recording.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE archives (call_id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE deleted_archives (call_id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO archives VALUES (?,?,?)", arrayOf<Any>(42L, 7L, RecordingJson.archive(entry).toString()))
            db.execSQL("INSERT INTO deleted_archives VALUES (43)")
            db.version = 1
        }
        val store = AndroidRecordingStore(context, "migration", cipher, recordingVersion = { 0L })
        assertEquals(entry, store.get(42))
        assertTrue(store.isDeleted(43))
        assertTrue(com.greatwall.callring.application.recording.RecordingRegistrationService(store, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("new", "31234", 2000, null, null).id < 0)
        assertEquals(entry, store.get(42))
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher {
        override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value))
    }
    @Test fun settingsAreEncryptedAndNamespacesAreIsolated() {
        val a = AndroidRecordingStore(context, "a", cipher, recordingVersion = { 0L })
        val b = AndroidRecordingStore(context, "b", cipher, recordingVersion = { 0L })
        val connection = AiConnection(url = "https://example.com/prefix/v1", apiKey = "unique-private-key", model = "custom")
        a.persistSettings(RecordingSettings(analysis = connection, transcription = connection))
        assertFalse(context.getSharedPreferences("a_recording", 0).all.toString().contains("unique-private-key"))
        assertEquals("unique-private-key", AndroidRecordingStore(context, "a", cipher, recordingVersion = { 0L }).settings().analysis.apiKey)
        assertEquals("", b.settings().analysis.apiKey)
    }
    @Test fun archiveCompareAndSetSurvivesReopenAndRejectsLateWrites() {
        val store = AndroidRecordingStore(context, "archive", cipher, recordingVersion = { 0L })
        val entry = CallArchive(1, "31234", 100, generation = 123, transcript = "test", analysis = CallAnalysis(summary = "summary"))
        assertTrue(store.compareAndSet(1, null, entry))
        val reopened = AndroidRecordingStore(context, "archive", cipher, recordingVersion = { 0L })
        assertEquals(entry, reopened.get(1))
        assertFalse(reopened.compareAndSet(1, 999, entry.copy(generation = 1000)))
        reopened.delete(1)
        assertTrue(store.isDeleted(1))
        assertFalse(store.compareAndSet(1, 123, entry.copy(generation = 1001)))
        assertNull(store.get(1))
        assertTrue(store.compareAndSet(1, null, entry.copy(generation = 1002)))
        assertFalse(store.isDeleted(1))
    }
}
