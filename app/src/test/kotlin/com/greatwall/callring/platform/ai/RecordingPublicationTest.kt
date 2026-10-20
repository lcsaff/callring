package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.recording.RecordingArchiveCommands
import com.greatwall.callring.application.recording.RecordingRegistrationService
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.CallArchive
import kotlinx.coroutines.runBlocking
import java.io.File
import com.greatwall.callring.contracts.change.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingPublicationTest {
    @Test fun firstNegativeArchiveIsReportedAndIndexedEvenWhenItsRowIdIsMinusOne() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val context = RuntimeEnvironment.getApplication()
        val namespace = "first-negative-insert-result"
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { coordinator.capture().values.getValue("recording") }).use { store ->
            val result = store.insertIncoming("first", "31234", 1, null) { CallArchive(it.id, it.number, it.startedAt, 1) }
            assertEquals(-1L, result.call.id)
            assertTrue(result.eventInserted)
            assertTrue("A legitimate rowid -1 is a successful archive insertion", result.archiveInserted)
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archive_projection WHERE call_id=-1 AND generation=1", null))
            }
            assertEquals(0L, coordinator.marker())
        }
    }

    @Test fun ignoredInitialArchiveInsertionRollsBackTheEventAndPublishesNothing() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val context = RuntimeEnvironment.getApplication()
        val namespace = "ignored-initial-archive"
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { coordinator.capture().values.getValue("recording") }).use { store ->
            store.scopedReads()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER ignore_initial BEFORE INSERT ON archives BEGIN SELECT RAISE(IGNORE); END")
                val registrations = RecordingRegistrationService(store, coordinator)
                assertTrue("An ignored initial archive cannot commit a new event", runCatching { registrations.registerIncoming("first", "31234", 1, null, null) }.isFailure)
                assertNull(store.sessionRecording("first"))
                assertEquals(0L, android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM recording_events", null))
                assertEquals(0L, android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archives", null))
                assertEquals(0L, coordinator.marker())
            }
        }
    }

    @Test fun failedCaptureCasPublishesStoppedPlaybackWithoutArchiveAndCleansNewFile() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val context = RuntimeEnvironment.getApplication()
        val namespace = "failed-capture-cas"
        val store = AndroidRecordingStore(context, namespace, cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val runtime = AndroidRecordingRuntime(context, namespace, { null }, { null }, {}, { store }, metadataReader = { 1000L to "wav" }, coordinator = coordinator)
        runtime.directory.mkdirs()
        val oldAudio = File(runtime.directory, "old.wav").apply { writeText("old contents") }
        val newAudio = File(runtime.directory, "new.wav").apply { writeText("new contents") }
        val archive = CallArchive(-1, "31234", 1, 1, audioPath = oldAudio.absolutePath)
        store.compareAndSet(-1, null, archive)
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10000, 0) }
        try {
            runtime.play(-1).requiredValue()
            val before = coordinator.marker()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER reject_capture BEFORE UPDATE ON archives BEGIN SELECT RAISE(ABORT,'forced failure'); END")
                assertTrue(runCatching { runtime.attachCapture(com.greatwall.callring.application.RecordingCall(-1, "31234", 1, null), newAudio) }.isFailure)
            }
            assertEquals(before + 1, coordinator.marker())
            assertEquals(setOf(DataDomain.RUNTIME), coordinator.changes.value.domains)
            assertEquals(0L, coordinator.capture().values.getValue("recording"))
            assertEquals(archive, store.get(-1))
            assertEquals("old contents", oldAudio.readText())
            assertFalse(newAudio.exists())
        } finally { runtime.close() }
    }

    @Test fun deletingPlayingArchiveCombinesActualRuntimeStopWithArchiveCommit() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val context = RuntimeEnvironment.getApplication()
        val store = AndroidRecordingStore(context, "delete-playing", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val runtime = AndroidRecordingRuntime(context, "delete-playing", { null }, { null }, {}, { store }, coordinator = coordinator)
        runtime.directory.mkdirs()
        val file = File(runtime.directory, "owned.wav").apply { writeBytes(ByteArray(32)) }
        store.compareAndSet(-1, null, CallArchive(-1, "31234", 1, 1, audioPath = file.absolutePath))
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10000, 0) }
        try {
            runtime.play(-1).requiredValue()
            val before = coordinator.marker()
            runtime.delete(-1).requiredValue()
            assertEquals(before + 1, coordinator.marker())
            assertEquals(setOf(DataDomain.RECORDING_ARCHIVES, DataDomain.RUNTIME), coordinator.changes.value.domains)
            assertEquals(coordinator.marker(), coordinator.lastChange(setOf("runtime:recordingPlayback")))
            assertEquals(1L, coordinator.capture().values.getValue("recording"))
            assertFalse(file.exists())
        } finally { runtime.close() }
    }

    @Test fun failedOwnedDeletePublishesOnlyActualRuntimeStopAndRetainsRetryPath() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val context = RuntimeEnvironment.getApplication()
        val store = AndroidRecordingStore(context, "failed-delete-playing", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val runtime = AndroidRecordingRuntime(context, "failed-delete-playing", { null }, { null }, {}, { store }, coordinator = coordinator)
        runtime.directory.mkdirs()
        val blocked = File(runtime.directory, "owned.wav").apply { mkdirs(); File(this, "child").writeText("retained") }
        val archive = CallArchive(-1, "31234", 1, 1, audioPath = blocked.absolutePath)
        store.compareAndSet(-1, null, archive)
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10000, 0) }
        try {
            runtime.play(-1).requiredValue()
            val before = coordinator.marker()
            assertTrue(runtime.delete(-1) is ApiResult.Failure)
            assertEquals(before + 1, coordinator.marker())
            assertEquals(setOf(DataDomain.RUNTIME), coordinator.changes.value.domains)
            assertEquals(0L, coordinator.capture().values.getValue("recording"))
            assertEquals(archive, store.get(-1))
            assertFalse(store.isDeleted(-1))
            assertTrue(blocked.exists())
        } finally { runtime.close() }
    }

    @Test fun initialArchiveFailureRollsBackEventAndPublishesNothing() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        AndroidRecordingStore(RuntimeEnvironment.getApplication(), "registration-rollback", cipher,
            recordingVersion = { coordinator.capture().values.getValue("recording") }).use { store ->
            assertTrue(runCatching { store.insertIncoming("failed", "31234", 1, null) { error("codec failed") } }.isFailure)
            assertNull(store.sessionRecording("failed"))
            assertEquals(0L, coordinator.marker())
            val registrations = RecordingRegistrationService(store, coordinator)
            val call = registrations.registerIncoming("one", "31234", 1, null, null)
            val before = coordinator.marker()
            assertTrue(runCatching { registrations.registerIncoming("one", "other", 1, null, null) }.isFailure)
            assertEquals(before, coordinator.marker())
            assertEquals("31234", store.get(call.id)!!.number)
            val source = store.scopedReads()
            assertEquals(1L, source.version(com.greatwall.callring.contracts.ai.AiDomain.D15))
            assertEquals(1L, source.version(com.greatwall.callring.contracts.ai.AiDomain.D16))
        }
    }
    @Test fun rawDataWritesNeverPublishBusinessRevisions() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "raw-publication", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        try {
            val before = coordinator.capture()
            val call = store.insertIncoming("raw", "31234", 1000, null) { com.greatwall.callring.contracts.recording.CallArchive(it.id, it.number, it.startedAt, 1) }.call
            assertEquals("Raw registration must not publish", before, coordinator.capture())
            store.insertStatisticsLink(42, "raw")
            val archive = store.get(call.id)!!
            assertTrue(store.compareAndSet(call.id, archive.generation, archive.copy(generation = archive.generation + 1)))
            store.delete(call.id)
            assertEquals(before, coordinator.capture())
            assertEquals(0L, coordinator.marker())
        } finally { store.close() }
    }
    @Test fun businessRegistrationAndWorkflowUseOneMarkerAndExactArchiveEntity() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "one-marker", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        store.persistSettings(com.greatwall.callring.application.recording.model.RecordingSettings(recordingEnabled = true))
        val runtime = AndroidRecordingRuntime(RuntimeEnvironment.getApplication(), "one-marker", { null }, { null }, {}, { store }, coordinator = coordinator)
        try {
            val call = runtime.observeIncoming("one", "31234", 1000, null)!!
            assertEquals("One logical registration has one marker", 1L, coordinator.marker())
            assertEquals(setOf(DataDomain.RECORDING_ARCHIVES), coordinator.changes.value.domains)
            assertEquals(setOf(call.id.toString()), coordinator.invalidations.snapshots.value.domains.getValue(DataDomain.RECORDING_ARCHIVES).entityRevisions.keys)
            runtime.observeIncoming("one", "31234", 1000, null)
            assertEquals(1L, coordinator.marker())
            runtime.linkStatistics("one", 42)
            assertEquals(2L, coordinator.marker())
            runtime.linkStatistics("one", 42)
            assertEquals(2L, coordinator.marker())
        } finally { runtime.close() }
    }
    @Test fun independentIdentityBackgroundCasAndDeletePublishOnlyCommittedChanges() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "publication", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val before = coordinator.capture()
        val registrations = com.greatwall.callring.application.recording.RecordingRegistrationService(store, coordinator)
        val commands = com.greatwall.callring.application.recording.RecordingArchiveCommands(store, store, coordinator)
        val call = registrations.registerIncoming("a", "31234", 1000, null, null)
        assertNotEquals(before, coordinator.capture())
        val afterRegistration = coordinator.capture()
        com.greatwall.callring.application.recording.RecordingRegistrationService(store, coordinator).registerIncoming("a", "31234", 1000, null, null)
        assertEquals(afterRegistration, coordinator.capture())
        val archive = store.get(call.id)!!
        assertFalse(commands.compareAndSet(call.id, archive.generation + 1, archive, null, false))
        assertEquals(afterRegistration, coordinator.capture())
        assertTrue(commands.compareAndSet(call.id, archive.generation, archive.copy(generation = archive.generation + 1, transcript = "complete-tail"), null, false))
        assertNotEquals(afterRegistration, coordinator.capture())
        commands.delete(call.id, null, false)
        assertTrue(store.isDeleted(call.id))
        assertEquals(setOf(DataDomain.RECORDING_ARCHIVES), coordinator.changes.value.domains)
    }
}
