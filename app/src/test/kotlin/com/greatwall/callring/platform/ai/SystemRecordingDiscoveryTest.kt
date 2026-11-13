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
    @Test fun noVisibleCallNeverObservesOrImportsIndependentSystemAudio() {
        Fixture("no-visible-call").use { f ->
            f.calls = emptyList()
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0,f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }

    @Test fun sealingDuringFinalOutgoingObservationCannotRegisterSource() {
        Fixture("seal-final-outgoing").use { f ->
            f.calls = listOf(f.knownCall())
            f.discovery.discover { false }; f.time += 10_000
            val sealAt = f.outgoingChecks + 2
            f.onOutgoing = { if (it == sealAt) f.discovery.seal() }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun sealingDuringLateLinkObservationCannotWriteStatisticsLink() {
        Fixture("seal-final-late-link").use { f ->
            f.seedLegacy()
            f.calls = listOf(f.knownCall())
            val sealAt = f.outgoingChecks + 2
            f.onOutgoing = { if (it == sealAt) f.discovery.seal() }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertNull(f.store.linkedRecording(42))
        }
    }
    @Test fun closerOutgoingCallCannotClaimIncomingRecording() {
        Fixture("outgoing-closer").use { f ->
            f.calls = listOf(f.knownCall())
            f.outgoingCloser = true
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun outgoingRejectionDuringCopyReleasesOnlyUnimportedReservation() {
        Fixture("outgoing-copy-race").use { f ->
            f.calls = listOf(f.knownCall())
            f.discovery.discover { false }; f.time += 10_000
            f.beforeCommit = { f.outgoingCloser = true }
            assertThrows(CancellationException::class.java) { f.discovery.discover { false } }
            assertNull(f.store.sourceBinding("a"))
            assertNull(f.store.get(42)?.audioPath)
            assertFalse(f.store.isDeleted(42))
            f.beforeCommit = {}; f.outgoingCloser = false
            f.discovery.discover { false }
            assertEquals(1, f.imports)
        }
    }
    @Test fun outgoingObservationUsesCoreCallTimeBeforeCanonicalRecordingTime() {
        Fixture("outgoing-canonical-time").use { f ->
            val call = f.knownCall()
            f.store.insertIncoming("canonical", call.number, call.startedAt + 8_000, null) { CallArchive(it.id,it.number,it.startedAt,generation=1) }
            f.store.insertStatisticsLink(call.id, "canonical")
            f.calls = listOf(call)
            f.outgoingCloser = true
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "31234_20261104120008.mp3"))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertEquals(call.startedAt, f.observedIncomingAt)
        }
    }
    @Test fun completedIndependentOutgoingSourceCannotLinkToLaterIncomingHistory() {
        Fixture("outgoing-late-link").use { f ->
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "31234_20261104120008.mp3"))
            f.seedLegacy()
            assertEquals(1, f.imports)
            f.calls = listOf(f.knownCall()); f.outgoingCloser = true
            f.discovery.discover { false }
            assertNull(f.store.linkedRecording(42))
        }
    }
    @Test fun lateCallChoosesNearestIndependentSourceInsteadOfSourceKeyOrder() {
        Fixture("late-dual-source").use { f ->
            val file = f.files.single().file
            f.files[0] = ScannedSystemRecording("a", file.copy(name = "31234_20261104120036.mp3"))
            f.files += ScannedSystemRecording("z", file.copy(uri = "content://z", name = "31234_20261104120008.mp3"))
            f.seedLegacy()
            assertEquals(2, f.imports)
            val nearest = f.store.sourceBinding("z")!!.id
            f.calls = listOf(f.knownCall())
            f.discovery.discover { false }
            assertEquals(nearest, f.store.linkedRecording(42)!!.id)
            assertEquals(2, f.imports)
        }
    }
    @Test fun recordingStartingAfterAnswerMatchesEarlierIncomingCall() {
        Fixture("delayed-real-shape").use { f ->
            f.calls = listOf(f.knownCall())
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "31234_20261104120008.mp3"))
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("a")!!.id)
        }
    }
    @Test fun twoRecordingsOfOneCallSelectUniqueClosestStartAndPreserveBinding() {
        Fixture("dual-real-shape").use { f ->
            f.calls = listOf(f.knownCall())
            val file = f.files.single().file
            f.files[0] = ScannedSystemRecording("a", file.copy(name = "31234_20261104120001.mp3", size = 54432))
            f.files += ScannedSystemRecording("z", file.copy(uri = "content://z", name = "联系人(31234)_20261104120003.mp3", size = 24768))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("a")!!.id)
            assertNull(f.store.sourceBinding("z"))
            // A later-discovered alternative must not replace a completed source or retry forever.
            f.files += ScannedSystemRecording("new", file.copy(uri = "content://new"))
            assertNull(f.discovery.discover { false })
            f.time += 10_000; assertNull(f.discovery.discover { false })
            assertEquals(1, f.imports)
            assertNull(f.store.sourceBinding("new"))
        }
    }
    @Test fun closestFileMustBecomeStableBeforeFartherFileCanWin() {
        Fixture("dual-stability").use { f ->
            f.calls = listOf(f.knownCall())
            val file = f.files.single().file
            f.files[0] = ScannedSystemRecording("far", file.copy(name = "31234_20261104120003.mp3"))
            f.discovery.discover { false }; f.time += 10_000
            f.files += ScannedSystemRecording("near", file.copy(uri = "content://near", name = "31234_20261104120001.mp3"))
            assertNotNull(f.discovery.discover { false })
            assertEquals(0, f.imports)
            f.time += 10_000; f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L, f.store.sourceBinding("near")!!.id)
            assertNull(f.store.sourceBinding("far"))
        }
    }
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
    @Test fun existingCallDoesNotBypassCurrentNumberRules() {
        Fixture("existing-rejected-rule").use { f ->
            f.files[0] = f.files.single().copy(file = f.files.single().file.copy(name = "示例(12345)_20261104120000.mp3"))
            f.calls = listOf(f.knownCall().copy(number = "12345"))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun existingHistoricalCallIsRejectedWithEmptyRules() {
        Fixture("existing-empty-rules").use { f ->
            f.installRules(emptyList())
            f.calls = listOf(f.knownCall())
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
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
            f.calls = emptyList()
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun visibleHistoricalFileCanWaitWhileCallIsActive() {
        Fixture("independent-window-edge").use { f ->
            f.time = f.knownCall().startedAt + 7L * 86_400_000 - 15_000
            f.activeRecordingCallId = 99
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0, f.imports)
            f.time += 10_000; f.activeRecordingCallId = null
            f.discovery.discover { false }
            assertEquals(1, f.imports)
            assertEquals(42L,f.store.sourceBinding("a")!!.id)
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
            assertEquals(42L,id)
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
    @Test fun filesWithoutMatchingVisibleCallsNeverReachCopy() {
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
            f.seedLegacy()
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
            f.calls = listOf(f.knownCall(),f.knownCall().copy(id=43,number="00123"))
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
    @Test fun existingHistoryNeverBypassesCurrentNumberAdmission() {
        Fixture("history-rule-gate").use { f ->
            f.calls = listOf(f.knownCall())
            f.installRules(emptyList())
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertNull(f.store.sourceBinding("a"))
            assertEquals(0, f.imports)
        }
    }
    @Test fun independentRecordingConfirmedOutgoingUpdatesEveryCanonicalIdentity() {
        Fixture("late-outgoing").use { f ->
            f.seedLegacy()
            val independent = checkNotNull(f.store.sourceBinding("a"))
            assertEquals(com.greatwall.callring.domain.model.CallDirection.UNKNOWN, independent.direction)
            f.calls = listOf(f.knownCall().copy(direction = com.greatwall.callring.domain.model.CallDirection.OUTGOING))
            f.discovery.discover { false }
            assertEquals(com.greatwall.callring.domain.model.CallDirection.OUTGOING, f.store.sourceBinding("a")!!.direction)
            assertEquals(com.greatwall.callring.domain.model.CallDirection.OUTGOING, f.store.findRecording(independent.id)!!.direction)
            assertEquals(com.greatwall.callring.domain.model.CallDirection.OUTGOING, f.store.linkedRecording(42)!!.direction)
            assertEquals(com.greatwall.callring.contracts.callers.CallDirection.OUTGOING, f.store.get(independent.id)!!.direction)
            assertEquals(ArchiveStatus.READY, f.store.get(independent.id)!!.status)
        }
    }
    @Test fun outgoingHistoryHasNoAgeLimitOrSelfConflict() {
        Fixture("out-hist").use { f ->
            f.calls = listOf(f.knownCall().copy(direction = com.greatwall.callring.domain.model.CallDirection.OUTGOING))
            f.outgoingCloser = true
            f.time += 90L * 86_400_000
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(1,f.imports)
            assertEquals(42L,f.store.sourceBinding("a")!!.id)
            assertEquals(com.greatwall.callring.contracts.callers.CallDirection.OUTGOING,f.store.get(42)!!.direction)
            assertEquals(0,f.outgoingChecks)
        }
    }
    @Test fun simultaneousIncomingAndOutgoingCannotStealOneRecording() {
        Fixture("ambiguous-direction").use { f ->
            f.calls = listOf(f.knownCall(),f.knownCall().copy(id=43,direction=com.greatwall.callring.domain.model.CallDirection.OUTGOING))
            f.discovery.discover { false }; f.time += 10_000; f.discovery.discover { false }
            assertEquals(0,f.imports)
            assertNull(f.store.sourceBinding("a"))
        }
    }
    @Test fun uniqueIncomingConfirmationQueuesOnlyUnpausedIndependentAudio() {
        for (paused in listOf(false,true)) Fixture("incoming-confirm-$paused").use { f ->
            f.seedLegacy()
            val id = f.store.sourceBinding("a")!!.id
            if (paused) {
                val archive = f.store.get(id)!!
                assertTrue(f.store.compareAndSet(id,archive.generation,archive.copy(generation=archive.generation+1,nextAttemptAt=Long.MAX_VALUE)))
            }
            f.store.persistSettings(f.store.settings().copy(automaticAnalysis=true))
            f.calls = listOf(f.knownCall())
            f.discovery.discover { false }
            val archive = f.store.get(id)!!
            assertEquals(com.greatwall.callring.contracts.callers.CallDirection.INCOMING,archive.direction)
            assertEquals(if (paused) ArchiveStatus.READY else ArchiveStatus.QUEUED,archive.status)
            assertEquals(if (paused) Long.MAX_VALUE else 0L,archive.nextAttemptAt)
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
        var calls = listOf(knownCall())
        fun knownCall() = RecordingCall(42, "31234", Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(), null)
        var onFind: () -> Unit = {}
        var revision = 0L
        var decryptions = 0
        var onInspect: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var activeRecordingCallId: Long? = null
        var outgoingCloser = false
        var outgoingChecks = 0
        var onOutgoing: (Int) -> Unit = {}
        var observedIncomingAt: Long? = null
        val files = mutableListOf(ScannedSystemRecording("a", RecordingFile("content://a", "示例(31234)_20261104120000.mp3", 1, 128)))
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value.also { decryptions++ } }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "discovery-$name", cipher) { 0 }
        fun seedLegacy() {
            calls = emptyList()
            files.forEach { entry ->
                val parsed = checkNotNull(com.greatwall.callring.domain.recording.SystemRecordingNameParser.parse(entry.file.name,ZoneId.of("Asia/Shanghai")))
                val registered = store.seedLegacySystemSource(entry.sourceKey,parsed) {
                    CallArchive(it.id,it.number,it.startedAt,generation=1,status=ArchiveStatus.READY,audioPath="owned",direction=com.greatwall.callring.contracts.callers.CallDirection.UNKNOWN)
                }
                store.completeSystemSource(entry.sourceKey)
                imports++
            }
        }
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
            outgoingCallCloser = { _, _, at, _ -> observedIncomingAt = at; outgoingChecks++; onOutgoing(outgoingChecks); outgoingCloser },
            findCalls = { number, at ->
                val close = calls.filter { it.number == number && kotlin.math.abs(it.startedAt - at) <= 3000 }
                close.ifEmpty { calls.filter { it.number == number && it.startedAt in (at - 120_000)..(at + 3000) } }.also { onFind() }
            }, now = { time }, zone = { ZoneId.of("Asia/Shanghai") },
            importAudio = { call, _, validate, _, inspect ->
                inspect()
                beforeCommit(); validate()
                store.compareAndSet(call.id,store.generation(call.id), CallArchive(call.id,call.number,call.startedAt,2,status=ArchiveStatus.READY,audioPath="owned",direction=com.greatwall.callring.contracts.callers.CallDirection.valueOf(call.direction.name)))
                imports++
            })
        init { store.persistSettings(RecordingSettings(recordingEnabled=true,systemFolder="content://fixture/tree/root")) }
        override fun close() { discovery.close(); store.close() }
    }
}
