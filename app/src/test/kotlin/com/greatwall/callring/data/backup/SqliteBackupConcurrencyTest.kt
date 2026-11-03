package com.greatwall.callring.data.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.recording.RecordingRegistrationService
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteBackupConcurrencyTest {
    @Test fun copyAllowsWrites() {
        val context = RuntimeEnvironment.getApplication()
        val namespace = "bc-${UUID.randomUUID()}"
        val source = context.getDatabasePath(namespace + "_recording.db")
        val destination = File(context.cacheDir, "cp-${UUID.randomUUID()}.db")
        val cipher = object : RecordingCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val workers = Executors.newFixedThreadPool(2)
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0 }).use { store ->
            val coordinator = BusinessRevisionCoordinator()
            val registrations = RecordingRegistrationService(store, coordinator)
            val original = registrations.registerIncoming("before", "31234", 100, null, null)
            val originalArchive = store.get(original.id)
            assertNotNull(originalArchive)
            fun sourceJournal(): String = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
                DatabaseUtils.stringForQuery(it, "PRAGMA journal_mode", null).lowercase()
            }
            assertEquals("wal", sourceJournal())
            val copy = workers.submit<Unit> {
                SqliteBackupCopy.copy(source, destination, configureDestination = { output ->
                    assertEquals("wal", DatabaseUtils.stringForQuery(output, "PRAGMA backup_source.journal_mode", null).lowercase())
                    // TEMP objects are absent from the persisted schema comparison.
                    output.setCustomScalarFunction("backup_pause") { value ->
                        if (first.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "copy pause was not released" }
                        }
                        value
                    }
                    output.execSQL("CREATE TEMP TRIGGER pause_archive_copy BEFORE INSERT ON main.archives BEGIN SELECT backup_pause(NEW.call_id); END")
                }) {}
            }
            try {
                assertTrue("INSERT SELECT never reached its first source row", entered.await(5, TimeUnit.SECONDS))
                // The callback runs after consuming a source row, while that SELECT snapshot is open.
                val incoming = workers.submit<com.greatwall.callring.domain.calling.RecordingCall> {
                    registrations.registerIncoming("during", "35678", 200, null, null)
                }.get(2, TimeUnit.SECONDS)
                assertEquals(1L, release.count)
                assertNotNull(store.get(incoming.id))
                assertEquals("wal", sourceJournal())
                release.countDown()
                copy.get(10, TimeUnit.SECONDS)
                SQLiteDatabase.openDatabase(destination.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                    assertEquals(1L, DatabaseUtils.longForQuery(snapshot, "SELECT count(*) FROM archives", null))
                    assertEquals(1L, DatabaseUtils.longForQuery(snapshot, "SELECT count(*) FROM archives WHERE call_id=?", arrayOf(original.id.toString())))
                    assertEquals(0L, DatabaseUtils.longForQuery(snapshot, "SELECT count(*) FROM archives WHERE call_id=?", arrayOf(incoming.id.toString())))
                }
                assertEquals(originalArchive, store.get(original.id))
                assertEquals("wal", sourceJournal())
                // The complete export's existing marker check rejects cross-table source changes.
                assertTrue(coordinator.marker() > 1)
            } finally {
                release.countDown()
                try { copy.get(15, TimeUnit.SECONDS) } finally {
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
                    SQLiteDatabase.deleteDatabase(destination)
                }
            }
        }
    }
}
