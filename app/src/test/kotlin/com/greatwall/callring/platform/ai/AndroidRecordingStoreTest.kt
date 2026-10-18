package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidRecordingStoreTest {
    @Test fun firstIndependentIdentityCanBeReimportedAfterDeletion() {
        val store = AndroidRecordingStore(context, "negative_reimport", cipher)
        val call = store.registerIncoming("first", "31234", 1000, null)
        assertEquals(-1L, call.id)
        store.delete(call.id)
        val archive = CallArchive(call.id, call.number, call.startedAt, generation = 10, status = ArchiveStatus.READY)
        assertTrue(store.compareAndSet(call.id, null, archive))
        assertEquals(archive, store.get(call.id))
        assertFalse(store.isDeleted(call.id))
    }
    @Test fun independentRegistrationIsAtomicIdempotentAndDoesNotMergeDifferentSessions() {
        val store = AndroidRecordingStore(context, "own_calls", cipher)
        val first = store.registerIncoming("session-a", "31234", 1000, "sim-a")
        assertTrue(first.id < 0)
        assertEquals(first, store.registerIncoming("session-a", "31234", 1000, "sim-a"))
        val second = store.registerIncoming("session-b", "31234", 1000, "sim-b")
        assertNotEquals(first.id, second.id)
        assertEquals(ArchiveStatus.WAITING_RECORDING, store.get(first.id)!!.status)
        assertEquals(2, store.all().size)
        store.delete(first.id)
        store.registerIncoming("session-a", "31234", 1000, "sim-a")
        assertNull(store.get(first.id))
        assertTrue(store.isDeleted(first.id))
    }
    @Test fun statisticalLinksUseExactSessionAndCanArriveBeforeRegistration() {
        val store = AndroidRecordingStore(context, "links", cipher)
        store.linkStatistics(42, "session-a")
        assertNull(store.linkedRecording(42))
        val first = store.registerIncoming("session-a", "31234", 1000, null)
        val second = store.registerIncoming("session-b", "31234", 1000, null)
        store.linkStatistics(43, "session-b")
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
        val store = AndroidRecordingStore(context, "migration", cipher)
        assertEquals(entry, store.get(42))
        assertTrue(store.isDeleted(43))
        assertTrue(store.registerIncoming("new", "31234", 2000, null).id < 0)
        assertEquals(entry, store.get(42))
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher {
        override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value))
    }
    @Test fun settingsAreEncryptedAndNamespacesAreIsolated() {
        val a = AndroidRecordingStore(context, "a", cipher)
        val b = AndroidRecordingStore(context, "b", cipher)
        val connection = AiConnection(url = "https://example.com/prefix/v1", apiKey = "unique-private-key", model = "custom")
        a.saveSettings(RecordingSettings(analysis = connection, transcription = connection))
        assertFalse(context.getSharedPreferences("a_recording", 0).all.toString().contains("unique-private-key"))
        assertEquals("unique-private-key", AndroidRecordingStore(context, "a", cipher).settings().analysis.apiKey)
        assertEquals("", b.settings().analysis.apiKey)
    }
    @Test fun archiveCompareAndSetSurvivesReopenAndRejectsLateWrites() {
        val store = AndroidRecordingStore(context, "archive", cipher)
        val entry = CallArchive(1, "31234", 100, generation = 123, transcript = "test", analysis = CallAnalysis(summary = "summary"))
        assertTrue(store.compareAndSet(1, null, entry))
        val reopened = AndroidRecordingStore(context, "archive", cipher)
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
