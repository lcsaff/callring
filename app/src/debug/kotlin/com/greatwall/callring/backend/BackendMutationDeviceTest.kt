package com.greatwall.callring.backend

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.SystemCallRecord
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * API mutations against real SQLite; every trigger and record belongs to this disposable fixture.
 */
class BackendMutationDeviceTest : InstrumentationTestCase() {
    private lateinit var fixture: BackendApiFixture

    override fun setUp() {
        super.setUp()
        fixture = BackendApiFixture(instrumentation.targetContext)
    }

    override fun tearDown() {
        try {
            fixture.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> success(result: ApiResult<T>): T =
        when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError("Unexpected API failure: ${result.error}")
        }

    private fun assertStorageFailure(result: ApiResult<*>) {
        assertTrue("Expected API failure, received $result", result is ApiResult.Failure)
        assertEquals(ErrorCode.STORAGE_FAILURE, (result as ApiResult.Failure).error.code)
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(
                fixture.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use(block)

    private fun settingsRows(): List<List<String?>> = database { db ->
        db.rawQuery("SELECT key,value,position_id FROM settings ORDER BY key", null).use { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.moveToNext()) rows +=
                (0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                }
            rows
        }
    }

    private data class StoredCall(
        val id: Long,
        val number: String,
        val time: Long,
        val logId: String?,
        val hidden: Boolean,
        val counted: Boolean,
        val linkedScreenId: Long?,
    )

    private fun storedCalls(): List<StoredCall> = database { db ->
        db.rawQuery(
                "SELECT id,number,time,log_id,hidden,counted,linked_screen_id FROM calls ORDER BY id",
                null,
            )
            .use { cursor ->
                val rows = mutableListOf<StoredCall>()
                while (cursor.moveToNext()) rows +=
                    StoredCall(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        if (cursor.isNull(3)) null else cursor.getString(3),
                        cursor.getInt(4) == 1,
                        cursor.getInt(5) == 1,
                        if (cursor.isNull(6)) null else cursor.getLong(6),
                    )
                rows
            }
    }

    private fun originalFacts(): List<List<String?>> = database { db ->
        db.rawQuery(
                "SELECT id,original_working,original_verified,original_reason,original_config_version,original_calendar_version,original_leave_version,original_zone FROM calls ORDER BY id",
                null,
            )
            .use { cursor ->
                val rows = mutableListOf<List<String?>>()
                while (cursor.moveToNext()) rows +=
                    (0 until cursor.columnCount).map {
                        if (cursor.isNull(it)) null else cursor.getString(it)
                    }
                rows
            }
    }

    private suspend fun configureWorkingStatistics() {
        val windows = listOf(TimeWindow(540, 1020))
        success(
            fixture.graph.api.settings.saveWorkSchedule(
                SaveWorkSchedule("UTC", (1..5).associateWith { windows }, windows)
            )
        )
        success(fixture.graph.api.settings.setStatisticsEnabled(true))
    }

    private suspend fun importCalls(records: List<SystemCallRecord>): Set<String> =
        withContext(Dispatchers.IO) { fixture.graph.backend.importSystemCalls(records) }

    private suspend fun report(number: String, range: DateRange): CallerReport =
        success(
            fixture.graph.api.reports.load(ReportQuery(number, range, page = PageRequest(0, 200)))
        )

    private fun recordElapsed(label: String, elapsedMillis: Long) {
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("stream", "\nqa.bulk.$label=${elapsedMillis}ms\n") },
        )
    }

    fun testWhitelistAndNullableProfileSaveUpdateOnlyTheirOwnedColumns() = runBlocking {
        val api = fixture.graph.api
        val position =
            success(
                api.settings.savePosition(SavePosition(name = "position", level = 2, active = true))
            )
        val original =
            success(
                api.callers.saveProfile(
                    SaveCallerProfile(
                        "31234",
                        "original name",
                        "original department",
                        position.id,
                        false,
                    )
                )
            )
        database { db ->
            db.execSQL(
                "CREATE TRIGGER qa_whitelist_only BEFORE UPDATE OF number,name,department,position_id ON callers WHEN OLD.number='31234' BEGIN SELECT RAISE(ABORT,'profile columns must not be written'); END"
            )
        }
        val enabled = success(api.callers.setWhitelisted("3-1234", true))
        assertEquals(original.copy(whitelisted = true), enabled)
        val enabledDraft = success(api.callers.getEditor("31234")).draft
        assertTrue(enabledDraft.whitelisted)
        assertEquals("original name", enabledDraft.name)
        assertEquals("original department", enabledDraft.department)
        assertEquals(position.id, enabledDraft.positionId)

        val staleSnapshot = fixture.graph.backend.decisionSnapshot()
        assertTrue(staleSnapshot.evaluate("31234", fixture.clock.millis()).whitelisted)
        database { db ->
            db.execSQL("DROP TRIGGER qa_whitelist_only")
            // A separate writer changes the latest committed whitelist after the cache was
            // captured.
            db.execSQL("UPDATE callers SET whitelist=0 WHERE number='31234'")
            db.execSQL(
                "CREATE TRIGGER qa_profile_only BEFORE UPDATE OF whitelist ON callers WHEN OLD.number='31234' BEGIN SELECT RAISE(ABORT,'whitelist must not be written'); END"
            )
        }
        val edited =
            success(
                api.callers.saveProfile(
                    SaveCallerProfile(
                        "31234",
                        "edited name",
                        "edited department",
                        position.id,
                        null,
                    )
                )
            )
        assertFalse(edited.whitelisted)
        assertEquals("edited name", edited.name)
        assertEquals("edited department", edited.department)
        val persisted = success(api.callers.getEditor("31234")).draft
        assertFalse(persisted.whitelisted)
        assertEquals("edited name", persisted.name)
        assertEquals("edited department", persisted.department)
        assertEquals(position.id, persisted.positionId)
        assertFalse(
            fixture.graph.backend
                .decisionSnapshot()
                .evaluate("31234", fixture.clock.millis())
                .whitelisted
        )
        assertTrue(
            "The captured old snapshot must remain detached",
            staleSnapshot.evaluate("31234", fixture.clock.millis()).whitelisted,
        )
    }

    fun testIgnoredMyPositionWritePreservesReferenceSnapshotAndChangeRevision() = runBlocking {
        val api = fixture.graph.api
        val first =
            success(
                api.settings.savePosition(SavePosition(name = "first", level = 1, active = true))
            )
        val second =
            success(
                api.settings.savePosition(SavePosition(name = "second", level = 2, active = true))
            )
        success(api.settings.setMyPosition(first.id))
        val positionsBefore = success(api.settings.getPositions())
        val rowsBefore = settingsRows()
        val snapshotBefore = fixture.graph.backend.decisionSnapshot()
        val revisionBefore = api.changes.observe().first().dataRevision
        database { db ->
            // Current settings persistence uses INSERT OR REPLACE. Guard UPDATE as well so the
            // assertion still exercises ignored writes if persistence switches to UPDATE later.
            db.execSQL(
                "CREATE TRIGGER qa_ignore_my_position_insert BEFORE INSERT ON settings WHEN NEW.key='my_position' BEGIN SELECT RAISE(IGNORE); END"
            )
            db.execSQL(
                "CREATE TRIGGER qa_ignore_my_position_update BEFORE UPDATE ON settings WHEN NEW.key='my_position' BEGIN SELECT RAISE(IGNORE); END"
            )
        }
        assertStorageFailure(api.settings.setMyPosition(second.id))
        assertEquals(positionsBefore, success(api.settings.getPositions()))
        assertEquals(first.id, success(api.settings.getPositions()).myPositionId)
        assertEquals(rowsBefore, settingsRows())
        assertSame(snapshotBefore, fixture.graph.backend.decisionSnapshot())
        assertEquals(revisionBefore, api.changes.observe().first().dataRevision)
    }

    fun testIgnoredQuietWindowWriteRollsBackAllSettingsDecisionSnapshotAndChangeRevision() =
        runBlocking {
            val api = fixture.graph.api
            success(
                api.settings.saveWorkSchedule(
                    SaveWorkSchedule("UTC", mapOf(1 to listOf(TimeWindow(540, 1020))), emptyList())
                )
            )
            success(
                api.settings.saveQuietHours(
                    QuietSettings(
                        QuietMode.NON_WORK,
                        false,
                        mapOf(1 to listOf(TimeWindow(1320, 1380))),
                    )
                )
            )
            val quietBefore = success(api.settings.getQuietHours())
            val rowsBefore = settingsRows()
            val snapshotBefore = fixture.graph.backend.decisionSnapshot()
            val revisionBefore = api.changes.observe().first().dataRevision
            val at = fixture.clock.millis() // Monday 08:00 UTC, outside the configured work window.
            assertTrue(snapshotBefore.evaluate("31234", at).silence)
            assertFalse(snapshotBefore.evaluate("123", at).silence)
            database { db ->
                // quiet_mode, last_quiet_mode and all_numbers have been written by the time this
                // fails.
                db.execSQL(
                    "CREATE TRIGGER qa_ignore_quiet_windows_insert BEFORE INSERT ON settings WHEN NEW.key='quiet_windows' BEGIN SELECT RAISE(IGNORE); END"
                )
                db.execSQL(
                    "CREATE TRIGGER qa_ignore_quiet_windows_update BEFORE UPDATE ON settings WHEN NEW.key='quiet_windows' BEGIN SELECT RAISE(IGNORE); END"
                )
            }
            assertStorageFailure(
                api.settings.saveQuietHours(
                    QuietSettings(QuietMode.CUSTOM, true, mapOf(1 to listOf(TimeWindow(480, 540))))
                )
            )
            assertEquals(quietBefore, success(api.settings.getQuietHours()))
            assertEquals(rowsBefore, settingsRows())
            assertSame(snapshotBefore, fixture.graph.backend.decisionSnapshot())
            assertTrue(fixture.graph.backend.decisionSnapshot().evaluate("31234", at).silence)
            assertFalse(fixture.graph.backend.decisionSnapshot().evaluate("123", at).silence)
            assertEquals(revisionBefore, api.changes.observe().first().dataRevision)
        }

    fun testProviderFirstLifecyclePreservesScreeningIdentityAcrossAmbiguityAndProviderMove() =
        runBlocking {
            configureWorkingStatistics()
            val api = fixture.graph.api
            val at = Instant.parse("2026-06-01T10:00:00Z").toEpochMilli()
            val day = LocalDate.of(2026, 6, 1)
            val range = DateRange(day, day, "UTC")
            val provider =
                SystemCallRecord(
                    "provider-first",
                    "31234",
                    at + 1000,
                    10,
                    CallResult.ANSWERED,
                    "account-a",
                )
            assertEquals(setOf("31234"), importCalls(listOf(provider)))
            val providerId = storedCalls().single().id
            val providerOriginal = originalFacts().single()
            val screenId =
                withContext(Dispatchers.IO) {
                    fixture.graph.backend.recordIncoming(
                        "31234",
                        at,
                        CallDecision.NORMAL_RINGING,
                        "account-a",
                    )
                }
            assertTrue(screenId > 0)
            assertEquals(screenId, storedCalls().single { it.id == providerId }.linkedScreenId)
            assertTrue(storedCalls().single { it.id == screenId }.hidden)
            withContext(Dispatchers.IO) {
                fixture.graph.backend.updateDecision(screenId, CallDecision.QUIET_SILENCED)
            }
            val merged = success(api.callers.getLatestCall())!!
            assertEquals(providerId, merged.id)
            assertEquals(at, merged.screeningAtEpochMillis)
            assertEquals(CallDecision.QUIET_SILENCED, merged.decision)
            assertEquals(CallSource.MERGED, merged.source)
            assertEquals(listOf(merged), report("31234", range).details.items)

            val duplicateId =
                withContext(Dispatchers.IO) {
                    fixture.graph.backend.recordIncoming(
                        "(31)234",
                        at,
                        CallDecision.SYSTEM_RINGTONE_FALLBACK,
                        "account-a",
                    )
                }
            assertEquals(screenId, duplicateId)
            assertEquals(2, storedCalls().size)
            assertEquals(
                CallDecision.QUIET_SILENCED,
                success(api.callers.getLatestCall())!!.decision,
            )

            val candidate =
                SystemCallRecord(
                    "second-candidate",
                    "31234",
                    at + 2000,
                    20,
                    CallResult.MISSED,
                    "account-a",
                )
            importCalls(listOf(candidate))
            val ambiguousRows = storedCalls()
            assertEquals(3, ambiguousRows.size)
            assertTrue(ambiguousRows.none { it.hidden })
            assertFalse(ambiguousRows.single { it.id == screenId }.counted)
            assertTrue(
                ambiguousRows
                    .filter { it.logId != null }
                    .all { it.counted && it.linkedScreenId == null }
            )
            val ambiguous = report("31234", range)
            assertEquals(3L, ambiguous.details.totalItems)
            assertEquals(2L, ambiguous.totals.totalCalls)
            assertEquals(30L, ambiguous.totals.knownDurationSeconds)
            assertEquals(0L, ambiguous.totals.unknownDurationCount)
            assertEquals(1L, ambiguous.totals.pendingCount)
            assertEquals(
                CallResult.UNRESOLVED,
                ambiguous.details.items.single { it.id == screenId }.result,
            )
            assertTrue(
                ambiguous.details.items
                    .filter { it.id != screenId }
                    .all { it.decision == CallDecision.SYSTEM_LOG_ONLY }
            )

            // Restore a real old association first: a move directly from ambiguity would only clear
            // null.
            importCalls(listOf(candidate.copy(startedAtEpochMillis = at + 120000)))
            assertEquals(screenId, storedCalls().single { it.id == providerId }.linkedScreenId)
            val destinationAt = at + 60000
            val destinationId =
                withContext(Dispatchers.IO) {
                    fixture.graph.backend.recordIncoming(
                        "31235",
                        destinationAt,
                        CallDecision.WHITELIST_ALLOWED,
                        "account-b",
                    )
                }
            val moved =
                provider.copy(
                    number = "31235",
                    startedAtEpochMillis = destinationAt + 1000,
                    durationSeconds = 40,
                    account = "account-b",
                )
            assertEquals(setOf("31234", "31235"), importCalls(listOf(moved)))
            val finalRows = storedCalls()
            assertEquals(4, finalRows.size)
            val movedRow = finalRows.single { it.logId == provider.logId }
            assertEquals(providerId, movedRow.id)
            assertEquals("31235", movedRow.number)
            assertEquals(destinationAt + 1000, movedRow.time)
            assertEquals(destinationId, movedRow.linkedScreenId)
            assertTrue(movedRow.counted)
            assertFalse(movedRow.hidden)
            assertTrue(finalRows.single { it.id == destinationId }.hidden)
            val restored = finalRows.single { it.id == screenId }
            assertFalse(restored.hidden)
            assertTrue(restored.counted)
            assertNull(restored.linkedScreenId)
            assertEquals(
                providerOriginal,
                originalFacts().single { it.first() == providerId.toString() },
            )

            val oldNumber = report("31234", range)
            assertEquals(2L, oldNumber.totals.totalCalls)
            assertEquals(1L, oldNumber.totals.pendingCount)
            val restoredDetail = oldNumber.details.items.single { it.id == screenId }
            assertEquals(CallResult.PENDING, restoredDetail.result)
            assertEquals(CallDecision.QUIET_SILENCED, restoredDetail.decision)
            val destination = report("31235", range)
            assertEquals(1L, destination.totals.totalCalls)
            assertEquals(0L, destination.totals.pendingCount)
            val destinationDetail = destination.details.items.single()
            assertEquals(providerId, destinationDetail.id)
            assertEquals(destinationAt, destinationDetail.screeningAtEpochMillis)
            assertEquals(CallDecision.WHITELIST_ALLOWED, destinationDetail.decision)
            assertEquals(CallSource.MERGED, destinationDetail.source)
            assertEquals(
                destinationDetail,
                success(api.callers.getOverview(OverviewQuery("31235"))).latestCall,
            )
            val globalLatest = success(api.callers.getLatestCall())!!
            assertEquals(
                "second-candidate",
                storedCalls().single { it.id == globalLatest.id }.logId,
            )
        }

    fun testThousandCallBatchReplayIsWriteFreeAndCalendarCorrectionPreservesOriginalColumns() =
        runBlocking {
            configureWorkingStatistics()
            val at = Instant.parse("2026-06-01T10:00:00Z").toEpochMilli()
            val records =
                (0 until 1000).map { index ->
                    SystemCallRecord(
                        "bulk-$index",
                        "31234",
                        at + index * 60000L,
                        1,
                        CallResult.ANSWERED,
                        "bulk-account",
                    )
                }
            database { db ->
                db.execSQL("CREATE TABLE qa_call_update_count(value INTEGER NOT NULL)")
                db.execSQL("INSERT INTO qa_call_update_count VALUES(0)")
                db.execSQL(
                    "CREATE TRIGGER qa_count_call_updates AFTER UPDATE ON calls BEGIN UPDATE qa_call_update_count SET value=value+1; END"
                )
            }
            fun updateCount(): Long = database { db ->
                db.rawQuery("SELECT value FROM qa_call_update_count", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            var started = SystemClock.elapsedRealtime()
            assertEquals(setOf("31234"), importCalls(records))
            recordElapsed("initial_1000_import", SystemClock.elapsedRealtime() - started)
            assertEquals(1000, storedCalls().size)
            assertEquals(0L, updateCount())
            val originals = originalFacts()

            started = SystemClock.elapsedRealtime()
            assertTrue(importCalls(records).isEmpty())
            recordElapsed("stable_1000_replay", SystemClock.elapsedRealtime() - started)
            assertEquals("A stable batch must not rewrite any call row", 0L, updateCount())
            assertEquals(originals, originalFacts())

            started = SystemClock.elapsedRealtime()
            assertEquals(
                setOf("31234"),
                importCalls(listOf(records[500].copy(durationSeconds = 20))),
            )
            recordElapsed("single_duration_correction", SystemClock.elapsedRealtime() - started)
            assertEquals(
                "A duration correction must update only its provider row",
                1L,
                updateCount(),
            )
            assertEquals(originals, originalFacts())
            database { db ->
                db.execSQL(
                    "CREATE TRIGGER qa_protect_original_call_facts BEFORE UPDATE OF original_working,original_verified,original_reason,original_config_version,original_calendar_version,original_leave_version,original_zone ON calls BEGIN SELECT RAISE(ABORT,'original call facts must not be updated'); END"
                )
            }
            val calendar =
                """{"year":2026,"source":"bulk fixture","published":"2026-01-01","version":"bulk-corrected","holidays":[{"start":"2026-06-01","end":"2026-06-02","name":"bulk holiday"}],"workdays":[]}"""
            started = SystemClock.elapsedRealtime()
            success(
                fixture.graph.api.settings.importCalendar(
                    fixture.document("bulk-calendar.json", calendar)
                )
            )
            recordElapsed("historical_1000_correction", SystemClock.elapsedRealtime() - started)
            assertEquals("Every current calendar version changes once", 1001L, updateCount())
            assertEquals(originals, originalFacts())
            val summary =
                report(
                    "31234",
                    DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "UTC"),
                )
            assertEquals(1000L, summary.totals.totalCalls)
            assertEquals(1000L, summary.details.totalItems)
            assertEquals(1019L, summary.totals.knownDurationSeconds)
            assertEquals(0L, summary.totals.unknownDurationCount)
            assertEquals(0L, summary.totals.pendingCount)
            assertTrue(
                summary.details.items.all {
                    it.classification.reason == WorkReason.HOLIDAY &&
                        it.classification.calendarVersion == "bulk-corrected"
                }
            )
        }
}
