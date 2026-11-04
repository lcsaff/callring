package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.SystemRecordingDiscovery
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingDiscoveryTest {
    @Test fun preInstallationFileNeedsTwoSamplesAndRepeatedScanDoesNotReimport() {
        Fixture("historical").use { f ->
            assertEquals(f.time + 10_000, f.discovery.discover { false })
            assertEquals(0, f.imports)
            f.time += 10_000
            f.discovery.discover { false }
            assertEquals(1, f.imports)
            val id = f.store.sourceBinding("a")!!.id
            assertTrue(id < 0)
            f.discovery.discover { false }
            assertEquals(1, f.imports)
            f.store.delete(id)
            f.discovery.discover { false }
            assertNull(f.store.get(id)); assertEquals(1, f.imports)
        }
    }
    @Test fun duplicateAtEndOfLargeDirectoryCannotImportFirstFile() {
        Fixture("ambiguous").use { f ->
            f.files += f.files.single().copy(sourceKey = "z")
            repeat(2000) { i -> f.files.add(ScannedSystemRecording("skip$i", RecordingFile("content://skip$i", "unparseable.wav", 1, 1))) }
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun changingFileAndConfigurationCannotCommitAudio() {
        Fixture("changing").use { f ->
            f.discovery.discover { false }; f.time += 10_000
            f.beforeCommit = { f.store.persistSettings(f.store.settings().copy(recordingEnabled = false)); f.revision++ }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertEquals(0, f.imports)
        }
    }
    @Test fun incompleteEnumerationDoesNotCertifyUniquenessAndRetriesWithoutAiQueue() {
        Fixture("incomplete").use { f ->
            f.enumerationFailure = true
            assertNotNull(f.discovery.discover { false })
            assertEquals(0, f.imports); assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun outOfWindowAndFutureFilesNeverReachCopy() {
        Fixture("window").use { f ->
            f.files.clear()
            f.files += ScannedSystemRecording("old", RecordingFile("content://old", "示例(12345)_20261001120000.mp3", 1, 128))
            f.files += ScannedSystemRecording("future", RecordingFile("content://future", "示例(12345)_20261105120000.mp3", 1, 128))
            assertNull(f.discovery.discover { false }); f.time += 10_000; assertNull(f.discovery.discover { false })
            assertEquals(0, f.imports)
        }
    }
    @Test fun slowFirstEnumerationDoesNotSubstituteForTwoObservations() {
        Fixture("slow-scan").use { f ->
            f.scanEnd = { f.time += 20_000 }
            f.discovery.discover { false }
            assertEquals(0,f.imports)
        }
    }
    @Test fun removedDuplicateStopsBlockingAfterCompleteEnumeration() {
        Fixture("removed").use { f ->
            f.files += f.files.single().copy(sourceKey="b")
            f.enumerationFailure = true
            f.discovery.discover { false }
            f.enumerationFailure = false
            f.files.removeAt(1); f.time += 10_000
            f.discovery.discover { false }
            assertEquals(1,f.imports)
        }
    }
    @Test fun lateStatisticsLinkDoesNotDependOnCurrentWindowOrFilePresence() {
        Fixture("late-link").use { f ->
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            val call = f.store.sourceBinding("a")!!
            f.files.clear(); f.time += 30L * 86_400_000
            f.calls = listOf(call.copy(id=42))
            f.discovery.discover { false }
            assertEquals(call.id,f.store.linkedRecording(42)!!.id)
            assertEquals(1,f.imports)
        }
    }
    @Test fun changingAssociationBetweenLookupAndCommitCannotCreateAnOrphan() {
        Fixture("target-race").use { f ->
            f.discovery.discover { false }; f.time += 10_000
            f.calls = listOf(com.greatwall.callring.domain.calling.RecordingCall(42,"12345",Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(),null))
            f.onFind = { f.calls = emptyList() }
            f.discovery.discover { false }
            assertNull(f.store.sourceBinding("a")); assertEquals(0,f.imports)
        }
    }
    @Test fun metadataEnumerationDoesNotDecryptConfigurationForEveryFile() {
        Fixture("configuration-reads").use { f ->
            repeat(2000) { f.files += ScannedSystemRecording("skip$it",RecordingFile("content://skip$it","unknown.wav",1,128)) }
            f.discovery.discover { false }
            assertTrue("configuration decrypts=${f.decryptions}", f.decryptions <= 2)
        }
    }
    @Test fun ownerSealCancelsInspectionAndDrainsBeforeStoreClose() {
        Fixture("owner-drain").use { f ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            f.discovery.discover { false }; f.time += 10_000
            f.onInspect = { entered.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) }
            f.onCancel = { release.countDown() }
            val worker = Thread { try { f.discovery.discover { false } } catch(t: Throwable) { failure.set(t) } }.apply { start() }
            try {
                assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
                val closer = Thread { f.discovery.close() }.apply { start() }
                closer.join(2000); worker.join(2000)
                assertFalse(closer.isAlive); assertFalse(worker.isAlive)
                assertTrue(failure.get() is CancellationException)
                assertEquals(0,f.imports)
            } finally { release.countDown(); worker.join(3000) }
        }
    }
    @Test fun legacyArchiveIndexIsPreparedBeforeAllocatingANewIdentity() {
        Fixture("legacy-index").use { f ->
            val started = Instant.parse("2026-11-04T04:00:00Z").toEpochMilli()
            f.store.compareAndSet(42,null,CallArchive(42,"12345",started,generation=1,status=ArchiveStatus.COMPLETE,audioPath="owned",transcript="already analyzed"))
            android.database.sqlite.SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("discovery-legacy-index_recording.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM archive_projection")
                db.execSQL("UPDATE archive_list_state SET ready=0")
            }
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(42L,f.store.sourceBinding("a")!!.id)
            assertEquals(0,f.imports)
        }
    }
    private class Fixture(name: String) : AutoCloseable {
        var time = Instant.parse("2026-11-04T05:00:00Z").toEpochMilli()
        var imports = 0
        var beforeCommit: () -> Unit = {}
        var enumerationFailure = false
        var scanEnd: () -> Unit = {}
        var calls = emptyList<com.greatwall.callring.domain.calling.RecordingCall>()
        var onFind: () -> Unit = {}
        var revision = 0L
        var decryptions = 0
        var onInspect: () -> Unit = {}
        var onCancel: () -> Unit = {}
        val files = mutableListOf(ScannedSystemRecording("a", RecordingFile("content://a", "示例(12345)_20261104120000.mp3", 1, 128)))
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value.also { decryptions++ } }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "discovery-$name", cipher) { 0 }
        private val scan = object : SystemRecordingScanPort {
            override fun scan(folder: String, cancelled: () -> Boolean, visit: (ScannedSystemRecording) -> Unit) {
                files.forEach { if(cancelled()) throw CancellationException(); visit(it) }
                scanEnd()
                if(enumerationFailure) throw java.io.IOException("fixture")
            }
            override fun cancelScan() { onCancel() }
            override fun inspect(folder: String, document: com.greatwall.callring.contracts.common.DocumentRef, cancelled: () -> Boolean): RecordingFile? {
                onInspect()
                if(cancelled()) throw CancellationException()
                return files.singleOrNull { it.file.uri == document.uri }?.file
            }
        }
        val discovery = SystemRecordingDiscovery(store, store, store::settings, { revision }, scan,
            coordinator = BusinessRevisionCoordinator(), operationActive = { true }, activeCallId = { null },
            findCalls = { _, _ -> calls.also { onFind() } }, now = { time }, zone = { ZoneId.of("Asia/Shanghai") },
            importAudio = { call, _, validate, inspect ->
                inspect()
                beforeCommit(); validate()
                store.compareAndSet(call.id,store.generation(call.id), CallArchive(call.id,call.number,call.startedAt,2,status=ArchiveStatus.READY,audioPath="owned"))
                imports++
            })
        init { store.persistSettings(RecordingSettings(recordingEnabled=true,systemFolder="content://fixture/tree/root")) }
        override fun close() { discovery.close(); store.close() }
    }
}
