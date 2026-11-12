package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.SystemRecordingDiscovery
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.recording.*
import com.greatwall.callring.domain.calling.*
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
    @Test fun existingHistoricalCallImportsOutsideWindowAndDoesNotReimport() {
        Fixture("historical-call").use { f ->
            f.calls = listOf(f.knownCall())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("a")!!.id)
            assertTrue(f.store.systemSourcePage(null, 64).all { it.second.id > 0 })
            f.discovery.discover { false }
            assertEquals(1, f.imports)
            f.store.delete(42)
            f.discovery.discover { false }
            assertNull(f.store.get(42)); assertEquals(1, f.imports)
        }
    }
    @Test fun existingCallImportsDespiteCurrentNumberRules() {
        Fixture("existing-rejected-rule").use { f ->
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "示例(12345)_20261104120000.mp3"))
            f.calls = listOf(f.knownCall().copy(number = "12345"))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("a")!!.id)
        }
    }
    @Test fun existingHistoricalCallImportsWithEmptyRules() {
        Fixture("existing-empty-rules").use { f ->
            f.installRules(emptyList())
            f.calls = listOf(f.knownCall())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("a")!!.id)
        }
    }
    @Test fun historicalMatchReusesExistingCanonicalRecordingIdentity() {
        Fixture("history-canonical").use { f ->
            val known = f.knownCall()
            val original = f.store.insertIncoming("existing-session", known.number, known.startedAt, null) {
                CallArchive(it.id, it.number, it.startedAt, generation = 1)
            }.call
            assertTrue(f.store.insertStatisticsLink(known.id, "existing-session"))
            f.calls = listOf(known)
            f.installRules(emptyList())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(original.id, f.store.sourceBinding("a")!!.id)
            assertNotNull(f.store.get(original.id)?.audioPath)
            assertNull(f.store.get(known.id))
        }
    }
    @Test fun historicalMatchingCoversMultipleCandidatePagesAndSkipsMissingFiles() {
        Fixture("history-pages").use { f ->
            val start = f.knownCall().startedAt
            val formatter = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"))
            f.files.clear()
            f.calls = (0..130).map { RecordingCall(42L + it, "31234", start + it * 60_000L, null) }
            f.calls.dropLast(1).forEach { call ->
                val key = "file-${call.id}"
                f.files += ScannedSystemRecording(key, RecordingFile("content://$key", "31234_${formatter.format(Instant.ofEpochMilli(call.startedAt))}.mp3", 1, 128))
            }
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(130, f.imports)
            f.calls.dropLast(1).forEach { assertNotNull(f.store.get(it.id)?.audioPath) }
            assertNull(f.store.get(f.calls.last().id))
            f.discovery.discover { false }
            assertEquals(130, f.imports)
        }
    }
    @Test fun historicalFileWithoutExistingCallIsSkipped() {
        Fixture("history-unmatched").use { f ->
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun acceptedIndependentFileCanAgeOutsideWindowWhileCallIsActive() {
        Fixture("independent-window-edge").use { f ->
            f.time = f.knownCall().startedAt + 7L * 86_400_000 - 15_000
            f.activeRecordingCallId = 99
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            f.time += 10_000; f.activeRecordingCallId = null
            f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertTrue(f.store.sourceBinding("a")!!.id < 0)
        }
    }
    @Test fun historicalTargetRemovedAfterEnumerationCannotCreateIndependentArchive() {
        Fixture("history-removed").use { f ->
            f.calls = listOf(f.knownCall())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000
            f.scanEnd = { f.calls = emptyList() }
            f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun historicalTargetRemovedDuringCopyCannotCommitAudio() {
        Fixture("history-copy-removed").use { f ->
            f.calls = listOf(f.knownCall())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000
            f.beforeCommit = { f.calls = emptyList() }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertEquals(0, f.imports)
            assertNull(f.store.get(42)?.audioPath)
        }
    }
    @Test fun historicalDuplicateFilesOrCallsAreNeverGuessed() {
        Fixture("history-ambiguous-files").use { f ->
            f.calls = listOf(f.knownCall())
            f.files += f.files.single().copy(sourceKey = "z")
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
        Fixture("history-ambiguous-calls").use { f ->
            f.calls = listOf(f.knownCall(), f.knownCall().copy(id = 43))
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
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
            f.files += ScannedSystemRecording("old", RecordingFile("content://old", "示例(31234)_20261001120000.mp3", 1, 128))
            f.files += ScannedSystemRecording("future", RecordingFile("content://future", "示例(31234)_20261105120000.mp3", 1, 128))
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
            f.calls = listOf(com.greatwall.callring.domain.calling.RecordingCall(42,"31234",Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(),null))
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
            f.store.compareAndSet(42,null,CallArchive(42,"31234",started,generation=1,status=ArchiveStatus.COMPLETE,audioPath="owned",transcript="already analyzed"))
            android.database.sqlite.SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("discovery-legacy-index_recording.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM archive_projection")
                db.execSQL("UPDATE archive_list_state SET ready=0")
            }
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(42L,f.store.sourceBinding("a")!!.id)
            assertEquals(0,f.imports)
        }
    }
    @Test fun inWindowNumberOutsideConfiguredRulesCreatesNoSourceOrAudio() {
        Fixture("number-rejected").use { f ->
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "示例(12345)_20261104120000.mp3"))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertNull("unmatched number must not register a source", f.store.sourceBinding("a"))
            assertEquals(0, f.imports)
            assertTrue(f.store.systemSourcePage(null,64).isEmpty())
        }
    }
    @Test fun emptyRulesRejectEveryNumberAndOrRulesAcceptOnlyMatches() {
        Fixture("number-or").use { f ->
            f.installRules(emptyList())
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertNull(f.store.sourceBinding("a")); assertEquals(0,f.imports)
            f.installRules(listOf(NumberRule("00",5),NumberRule("31",5)))
            f.files += ScannedSystemRecording("leading-zero",RecordingFile("content://zero","示例(00123)_20261104120000.mp3",1,128))
            f.files += ScannedSystemRecording("wrong-prefix",RecordingFile("content://prefix","示例(12345)_20261104120000.mp3",1,128))
            f.files += ScannedSystemRecording("wrong-length",RecordingFile("content://length","示例(312345)_20261104120000.mp3",1,128))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(2,f.imports)
            assertNotNull(f.store.sourceBinding("a")); assertNotNull(f.store.sourceBinding("leading-zero"))
            assertNull(f.store.sourceBinding("wrong-prefix")); assertNull(f.store.sourceBinding("wrong-length"))
        }
    }
    @Test fun changingRulesDuringEnumerationCancelsBeforeIdentityRegistration() {
        Fixture("rule-enumeration-race").use { f ->
            f.discovery.discover { false }; f.time += 10_000
            f.scanEnd = { f.installRules(emptyList()) }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertNull(f.store.sourceBinding("a")); assertEquals(0,f.imports)
            f.scanEnd = {}
            f.discovery.discover { false }
            assertNull(f.store.sourceBinding("a")); assertEquals(0,f.imports)
        }
    }
    @Test fun changingRulesDuringCopyCannotCommitPreparedAudio() {
        Fixture("rule-copy-race").use { f ->
            f.discovery.discover { false }; f.time += 10_000
            f.beforeCommit = { f.installRules(emptyList()) }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertEquals(0,f.imports)
            assertNull(f.store.get(f.store.sourceBinding("a")!!.id)!!.audioPath)
            f.beforeCommit = {}
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0,f.imports)
        }
    }
    private class Fixture(name: String) : AutoCloseable {
        var rules = NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0)
        val coordinator = BusinessRevisionCoordinator()
        fun installRules(values: List<NumberRule>) = coordinator.guard {
            rules = NumberRuleSnapshot(values,rules.revision + 1)
            coordinator.publishCore(setOf("numberRule"),com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        }
        var time = Instant.parse("2026-11-04T05:00:00Z").toEpochMilli()
        var imports = 0
        var beforeCommit: () -> Unit = {}
        var enumerationFailure = false
        var scanEnd: () -> Unit = {}
        var calls = emptyList<com.greatwall.callring.domain.calling.RecordingCall>()
        fun knownCall() = RecordingCall(42, "31234", Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(), null)
        var onFind: () -> Unit = {}
        var revision = 0L
        var decryptions = 0
        var onInspect: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var activeRecordingCallId: Long? = null
        val files = mutableListOf(ScannedSystemRecording("a", RecordingFile("content://a", "示例(31234)_20261104120000.mp3", 1, 128)))
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
            coordinator = coordinator, numberRules = { rules }, numberMatcher = PrefixLengthNumberMatcher, operationActive = { true }, activeCallId = { activeRecordingCallId },
            findCalls = { number, at -> calls.filter { it.number == number && kotlin.math.abs(it.startedAt - at) <= 3000 }.also { onFind() } }, now = { time }, zone = { ZoneId.of("Asia/Shanghai") },
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
