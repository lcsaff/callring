package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.recording.*
import com.greatwall.callring.domain.recording.ParsedSystemRecording
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingImportStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher {
        override fun encrypt(value: String) = value
        override fun decrypt(value: String) = value
    }
    @Test fun sourceRegistrationIsAtomicDurableAndCannotReviveDeletion() {
        val name = "system-source"
        val first = AndroidRecordingStore(context, name, cipher) { 0 }
        val registration = first.seedLegacySystemSource("source", ParsedSystemRecording("12345", 1000)) { call -> CallArchive(call.id, call.number, call.startedAt, generation = 1) }
        assertTrue(registration.call.id < 0)
        first.close()
        AndroidRecordingStore(context, name, cipher) { 0 }.use { store ->
            val again = store.seedLegacySystemSource("source", ParsedSystemRecording("12345", 1000)) { error("must not insert") }
            assertEquals(registration.call, again.call)
            assertFalse(again.eventInserted); assertFalse(again.archiveInserted)
            store.delete(again.call.id)
            val deleted = store.seedLegacySystemSource("source", ParsedSystemRecording("12345", 1000)) { error("deleted") }
            assertEquals(again.call, deleted.call); assertNull(store.get(deleted.call.id))
        }
    }
    @Test fun failedInitialArchiveRollsBackIdentityAndBinding() {
        AndroidRecordingStore(context, "rollback-source", cipher) { 0 }.use { store ->
            assertThrows(IllegalStateException::class.java) {
                store.seedLegacySystemSource("source", ParsedSystemRecording("12345", 1000)) { error("failure") }
            }
            assertNull(store.sourceBinding("source"))
            assertNull(store.sessionRecording("system-file:source"))
        }
    }
    @Test fun observationNeedsTwoStableSamplesAndDuplicateDetectionUsesWholeScan() {
        AndroidRecordingStore(context, "source-observations", cipher) { 0 }.use { store ->
            val scan = store.beginSystemScan("settings", 100_000, "UTC")
            val file = ScannedSystemRecording("a", RecordingFile("content://a", "a.wav", 10, 128))
            val parsed = ParsedSystemRecording("12345", 1000)
            store.observeSystemFile(scan.id, file, parsed, 100_000)
            store.finishSystemScan(scan.id)
            val initial = store.systemCandidates(scan.id, null, 64).single()
            assertEquals(100_000L, initial.stableSince)
            val next = store.beginSystemScan("settings", 110_000, "UTC")
            store.observeSystemFile(next.id, file, parsed, 110_000)
            store.observeSystemFile(next.id, file.copy(sourceKey = "b"), parsed, 110_000)
            store.finishSystemScan(next.id)
            assertEquals(100_000L, store.systemCandidates(next.id, null, 64).first().stableSince)
            assertTrue(store.hasConflictingSystemSource(next.id, "a", parsed, com.greatwall.callring.domain.calling.RecordingCall(42,parsed.number,parsed.startedAtEpochMillis,null)))
            assertEquals(1, store.systemCandidates(next.id, null, 1).size)
        }
    }
    @Test fun twoSourcesCannotReserveOneCanonicalArchive() {
        AndroidRecordingStore(context, "reverse-binding", cipher) { 0 }.use { store ->
            val parsed = ParsedSystemRecording("12345",1000)
            val first = store.seedLegacySystemSource("a",parsed) { CallArchive(it.id,it.number,it.startedAt,generation=1) }
            assertThrows(IllegalStateException::class.java) {
                store.registerSystemSource("b",parsed,first.call) { error("must not insert") }
            }
            assertNull(store.sourceBinding("b"))
        }
    }
}
