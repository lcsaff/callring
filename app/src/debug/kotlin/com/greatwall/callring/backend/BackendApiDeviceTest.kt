package com.greatwall.callring.backend

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.recording.policy.*
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.greatwall.callring.R
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.domain.SystemCallRecord
import com.greatwall.callring.platform.DiagnosticEvent
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Vertical API contracts against the actual graph/adapters. Run explicitly on a disposable device.
 * Storage migration/SQL constraints are covered separately by SqliteCoreStorageResourceDeviceTest.
 */
class BackendApiDeviceTest : InstrumentationTestCase() {
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
            is ApiResult.Failure ->
                throw AssertionError(
                    "Unexpected API failure: ${result.error.code}/${result.error.message}"
                )
        }

    private fun failure(result: ApiResult<*>, code: ErrorCode) {
        assertTrue("Expected $code failure", result is ApiResult.Failure)
        assertEquals(code, (result as ApiResult.Failure).error.code)
    }

    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()

    private val date
        get() = LocalDate.of(2026, 6, 1)

    private val range
        get() = DateRange(date, date, "UTC")

    private suspend fun configureWorkingDay() {
        val windows = listOf(TimeWindow(9 * 60, 17 * 60))
        success(
            fixture.graph.api.settings.saveWorkSchedule(
                SaveWorkSchedule("UTC", mapOf(1 to windows), windows)
            )
        )
        success(fixture.graph.api.settings.setStatisticsEnabled(true))
    }

    private suspend fun record(
        number: String,
        at: Long,
        decision: CallDecision = CallDecision.NORMAL_RINGING,
    ): Long =
        withContext(Dispatchers.IO) {
            fixture.graph.backend.recordIncoming(number, at, decision, "fixture-account")
        }

    private suspend fun report(
        number: String = "31234",
        filter: CallFilter = CallFilter.ALL,
    ): CallerReport = success(fixture.graph.api.reports.load(ReportQuery(number, range, filter)))

    fun testQuietScoreOverlayReopenAndEmptyCustomValidationPreserveCommittedState() = runBlocking {
        val api = fixture.graph.api
        val quiet = QuietSettings(QuietMode.CUSTOM, true, (1..7).associateWith { day ->
            if (day == 1) listOf(TimeWindow(1439, 0)) else emptyList()
        })
        val score = ScoreSettings(7, 4, 8, 45)
        success(api.settings.saveQuietHours(quiet))
        success(api.settings.saveScoreSettings(score))
        success(api.settings.setOverlayEnabled(false))
        val revision = api.changes.snapshots().value.revision
        val snapshot = fixture.graph.backend.decisionSnapshot()
        val raw = SQLiteDatabase.openDatabase(fixture.databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT key,value,position_id FROM settings ORDER BY key", null).use { rows ->
                buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map { if (rows.isNull(it)) null else rows.getString(it) }) }
            }
        }
        failure(api.settings.saveQuietHours(QuietSettings(QuietMode.CUSTOM, false, emptyMap())), ErrorCode.VALIDATION)
        assertEquals(quiet, success(api.settings.getQuietHours()))
        assertEquals(revision, api.changes.snapshots().value.revision)
        assertSame(snapshot, fixture.graph.backend.decisionSnapshot())
        SQLiteDatabase.openDatabase(fixture.databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT key,value,position_id FROM settings ORDER BY key", null).use { rows ->
                val after = buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map { if (rows.isNull(it)) null else rows.getString(it) }) }
                assertEquals(raw, after)
            }
        }
        fixture.reopen()
        assertEquals(quiet, success(fixture.graph.api.settings.getQuietHours()))
        assertEquals(score, success(fixture.graph.api.settings.getScoreSettings()))
        assertFalse(success(fixture.graph.api.settings.getSummary()).overlayEnabled)
    }

    fun testPositionReferenceProtectionTrimmedNamesAndActiveEditorOptions() = runBlocking {
        val api = fixture.graph.api
        val mine = success(api.settings.savePosition(SavePosition(name = " mine ", level = 2, active = true)))
        assertEquals("mine", mine.name)
        success(api.settings.setMyPosition(mine.id))
        val revision = api.changes.snapshots().value.revision
        failure(api.settings.deletePosition(mine.id), ErrorCode.IN_USE)
        failure(api.settings.savePosition(SavePosition(name = "mine ", level = 1, active = true)), ErrorCode.VALIDATION)
        assertEquals(revision, api.changes.snapshots().value.revision)
        val inactive = success(api.settings.savePosition(SavePosition(name = "retired", level = 3, active = false)))
        val active = success(api.settings.savePosition(SavePosition(name = "active", level = 1, active = true)))
        val blankEditor = success(api.callers.getEditor(null))
        assertTrue(blankEditor.selectablePositions.any { it.id == active.id })
        assertFalse(blankEditor.selectablePositions.any { it.id == inactive.id })
        val white = success(api.callers.setWhitelisted("31234", true))
        assertTrue(white.stored)
        assertEquals("", white.name)
        assertEquals("", white.department)
        assertNull(white.position)
        val unwhite = success(api.callers.setWhitelisted("31234", false))
        assertEquals(white.copy(whitelisted = false), unwhite)
        assertEquals(1L, success(api.callers.search(CallerSearch())).totalItems)
    }

    fun testCsvExportKeepsLeadingZeroNumbersAndNullCellsThroughRealDocuments() = runBlocking {
        val api = fixture.graph.api
        success(api.callers.saveProfile(SaveCallerProfile("03123", "", "", null)))
        val receipt = success(api.reports.exportCsv(ReportExportQuery("03123", range), fixture.output("zero.csv")))
        assertEquals(0L, receipt.recordCount)
        val bytes = fixture.documentBytes("zero.csv")
        assertTrue(bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())))
        val rows = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n")
        assertTrue(rows[1].startsWith("\"'03123\",\"\",\"\",\"\",\"UNKNOWN\",\"\",\"\",\"\","))
        assertEquals("\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"0\",\"\"", rows[3])
    }

    fun testStatisticsIntervalsHaveExactEndpointsAndIndependentSameTimeLogIds() = runBlocking {
        configureWorkingDay()
        val api = fixture.graph.api
        fixture.clock.at("2026-06-01T11:00:00Z")
        success(api.settings.setStatisticsEnabled(false))
        fixture.clock.at("2026-06-01T12:00:00Z")
        success(api.settings.setStatisticsEnabled(true))
        val times = listOf("10:59:59.999", "11:00:00", "11:59:59.999", "12:00:00")
        withContext(Dispatchers.IO) {
            fixture.graph.backend.importSystemCalls(times.mapIndexed { index, time ->
                SystemCallRecord("endpoint-$index", "31234", millis("2026-06-01T${time}Z"), 1, CallResult.ANSWERED.toDomain(), "fixture-account")
            } + listOf(
                SystemCallRecord("same-time-a", "31234", millis("2026-06-01T12:30:00Z"), 2, CallResult.ANSWERED.toDomain(), "fixture-account"),
                SystemCallRecord("same-time-b", "31234", millis("2026-06-01T12:30:00Z"), 3, CallResult.MISSED.toDomain(), "fixture-account"),
            ))
        }
        val report = report()
        assertEquals(4L, report.totals.totalCalls)
        assertEquals(7L, report.totals.knownDurationSeconds)
        assertEquals(4L, report.details.totalItems)
        assertEquals(2, report.details.items.count { it.startedAtEpochMillis == millis("2026-06-01T12:30:00Z") })
        assertNull(success(api.callers.getOverview(OverviewQuery("39999"))).latestCall)
    }

    fun testProviderFirstThenSecondScreeningCreatesAmbiguityWithoutDoubleCounting() = runBlocking {
        configureWorkingDay()
        val at = millis("2026-06-01T10:00:00Z")
        withContext(Dispatchers.IO) {
            fixture.graph.backend.importSystemCalls(listOf(SystemCallRecord("first-provider", "31234", at + 1000, 12, CallResult.ANSWERED.toDomain(), "fixture-account")))
        }
        val first = record("31234", at)
        assertTrue(first > 0)
        assertEquals(CallSource.MERGED, report().details.items.single().source)
        val second = record("31234", at + 2000)
        assertTrue(second > first)
        val report = report()
        assertEquals(1L, report.totals.totalCalls)
        assertEquals(12L, report.totals.knownDurationSeconds)
        assertEquals(0L, report.totals.unknownDurationCount)
        assertEquals(3L, report.details.totalItems)
        val unresolved = report.details.items.filter { it.id == first || it.id == second }
        assertEquals(2, unresolved.size)
        assertTrue(unresolved.all { !it.counted && it.result == CallResult.UNRESOLVED })
    }

    fun testActualCallsInsertFailurePreservesQuietAndDisabledAudioWhitelistBypass() = runBlocking {
        configureWorkingDay()
        val api = fixture.graph.api
        success(api.ringtone.setEnabled(false))
        success(api.settings.saveQuietHours(QuietSettings(QuietMode.NON_WORK, false, emptyMap())))
        SQLiteDatabase.openDatabase(fixture.databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER qa_reject_incoming BEFORE INSERT ON calls BEGIN SELECT RAISE(ABORT,'fixture rejection'); END")
        }
        val coordinator = fixture.graph.incomingCalls
        val session = coordinator.inspect("failed-insert", "31234", millis("2026-06-01T22:00:00Z"), "fixture-account")
        assertTrue(session.assessment.silence)
        assertEquals(CallDecision.QUIET_SILENCED, session.initialDecision)
        withContext(Dispatchers.IO) { coordinator.complete(session, CallDecision.QUIET_SILENCED).get(5, TimeUnit.SECONDS) }
        assertTrue(coordinator.inspectAgain(session).silence)
        assertEquals(0L, report().totals.totalCalls)
        success(api.callers.setWhitelisted("31234", true))
        val white = coordinator.inspect("white-disabled-audio", "31234", millis("2026-06-01T22:00:00Z"), "fixture-account")
        assertTrue(white.assessment.whitelisted)
        assertFalse(white.assessment.silence)
        assertEquals(CallDecision.WHITELIST_ALLOWED, white.initialDecision)
        withContext(Dispatchers.IO) { coordinator.complete(white, CallDecision.WHITELIST_ALLOWED).get(5, TimeUnit.SECONDS) }
        assertFalse(success(api.ringtone.getSettings()).enabled)
    }

    fun testGraphAndApiConstructionAreLazyAndFixturePersistenceIsIsolated() = runBlocking {
        fixture.reopen(fixture.configuration.copy(audioDirectory = null))
        val mainThreadApi = AtomicReference<CallringUiApi>()
        instrumentation.runOnMainSync { mainThreadApi.set(fixture.graph.api) }
        val api = mainThreadApi.get()
        assertEquals("31234", api.checkNumber("3-1234").normalized)
        assertEquals(fixture.defaultAppearance, success(api.settings.getAppearance()))
        success(api.ringtone.getSettings())
        assertEquals(0, fixture.context.filesDirectoryReads.get())
        assertEquals(0, fixture.context.databaseOpens.get())
        assertFalse(fixture.databaseFile.exists())
        fixture.graph.close()
        assertFalse(fixture.databaseFile.exists())

        fixture.reopen()
        success(
            fixture.graph.api.callers.saveProfile(
                SaveCallerProfile("31234", "isolated", "test", null)
            )
        )
        success(fixture.graph.api.widgets.saveBinding(41, "31234"))
        BackendApiFixture(instrumentation.targetContext).use { other ->
            assertFalse(other.databaseFile.exists())
            assertEquals(0L, success(other.graph.api.callers.search(CallerSearch())).totalItems)
            assertNull(success(other.graph.api.widgets.getBinding(41)))
            assertEquals(other.defaultAppearance, success(other.graph.api.settings.getAppearance()))
            assertNotSame(fixture.graph, other.graph)
        }
        assertEquals("isolated", success(fixture.graph.api.callers.getEditor("31234")).draft.name)
        assertEquals(WidgetBinding(41, "31234"), success(fixture.graph.api.widgets.getBinding(41)))
    }

    fun testProfileReferencesStatisticsIntervalsChangeFeedAndReopenAreWired() = runBlocking {
        val api = fixture.graph.api
        val initialRevision = api.changes.snapshots().value.revision
        val mine =
            success(
                api.settings.savePosition(
                    SavePosition(name = "my position", level = 3, active = true)
                )
            )
        val manager =
            success(
                api.settings.savePosition(SavePosition(name = "manager", level = 1, active = true))
            )
        success(api.settings.setMyPosition(mine.id))
        val profile =
            success(
                api.callers.saveProfile(
                    SaveCallerProfile("3-1234", " =caller", "department", manager.id, true)
                )
            )
        assertEquals(PositionRelation.HIGHER, profile.relation)
        val committedRevision = api.changes.snapshots().value.revision
        assertTrue(committedRevision > initialRevision)
        failure(api.settings.deletePosition(manager.id), ErrorCode.IN_USE)
        assertEquals(committedRevision, api.changes.snapshots().value.revision)

        configureWorkingDay()
        assertTrue(record("31234", millis("2026-06-01T10:00:00Z")) > 0)
        fixture.clock.at("2026-06-01T11:00:00Z")
        success(api.settings.setStatisticsEnabled(false))
        assertEquals(-1L, record("31234", millis("2026-06-01T11:30:00Z")))
        fixture.clock.at("2026-06-01T12:00:00Z")
        success(api.settings.setStatisticsEnabled(true))
        assertTrue(record("31234", millis("2026-06-01T12:30:00Z")) > 0)
        fixture.clock.at("2026-06-01T13:00:00Z")
        val expectedAppearance = fixture.defaultAppearance.copy(colorMode = ColorMode.DARK)
        success(api.settings.saveAppearance(expectedAppearance))
        success(api.widgets.saveBinding(41, "31234"))
        assertEquals(2L, report().totals.totalCalls)
        assertEquals(2L, report().totals.unknownDurationCount)
        assertEquals(2L, report().totals.pendingCount)

        fixture.reopen()
        val reopened = fixture.graph.api
        val overview = success(reopened.callers.getOverview(OverviewQuery("31234")))
        assertTrue(overview.profile.whitelisted)
        assertEquals(manager.id, overview.profile.position?.id)
        assertEquals(PositionRelation.HIGHER, overview.profile.relation)
        assertEquals(2L, overview.lifetimeCallCount)
        assertEquals(expectedAppearance, success(reopened.settings.getAppearance()))
        assertEquals(WidgetBinding(41, "31234"), success(reopened.widgets.getBinding(41)))
        assertTrue(success(reopened.settings.getSummary()).statisticsEnabled)
        assertEquals(2L, report().details.totalItems)
    }

    fun testCalendarOverrideAndLeaveReclassifyReportsWithoutChangingRawCallFacts() = runBlocking {
        configureWorkingDay()
        val at = millis("2026-06-01T10:00:00Z")
        assertTrue(record("31234", at, CallDecision.WHITELIST_ALLOWED) > 0)
        withContext(Dispatchers.IO) {
            fixture.graph.backend.importSystemCalls(
                listOf(
                    SystemCallRecord(
                        "fixture-log",
                        "31234",
                        at + 1000,
                        23,
                        CallResult.ANSWERED.toDomain(),
                        "fixture-account",
                    )
                )
            )
        }
        val original = report().details.items.single()
        assertEquals(1L, report().totals.totalCalls)
        assertEquals(23L, original.durationSeconds)
        assertEquals(CallResult.ANSWERED, original.result)
        assertEquals(CallDecision.WHITELIST_ALLOWED, original.decision)
        assertEquals(CallSource.MERGED, original.source)
        assertTrue(original.classification.working)
        val rawFacts = fixture.rawFacts()
        assertEquals(2, rawFacts.size)

        val calendarJson =
            """{"year":2026,"source":"fixture source","published":"2026-01-01","version":"fixture-2026.2","holidays":[{"start":"2026-06-01","end":"2026-06-01","name":"fixture holiday"}],"workdays":[]}"""
        val imported =
            success(
                fixture.graph.api.settings.importCalendar(
                    fixture.document("calendar.json", calendarJson)
                )
            )
        assertEquals("fixture-2026.2", imported.years.single { it.year == 2026 }.version)
        assertEquals(WorkReason.HOLIDAY, report().details.items.single().classification.reason)
        assertEquals(1L, report(filter = CallFilter.HOLIDAY).details.totalItems)
        success(
            fixture.graph.api.settings.saveDateOverride(
                DateOverride(date, DateOverrideKind.WORK, listOf(TimeWindow(540, 1020)))
            )
        )
        assertTrue(report().details.items.single().classification.working)
        val leave =
            success(
                fixture.graph.api.settings.saveLeave(
                    SaveLeave(
                        period = LeavePeriod.Timed(at - 1000, at + 60000),
                        note = "fixture leave",
                    )
                )
            )
        val duringLeave = report().details.items.single()
        assertEquals(WorkReason.PERSONAL_LEAVE, duringLeave.classification.reason)
        assertEquals(original.originalClassification, duringLeave.originalClassification)
        assertTrue(duringLeave.corrected)
        assertEquals(1L, report(filter = CallFilter.PERSONAL_LEAVE).details.totalItems)
        assertEquals(0L, report(filter = CallFilter.WORKING).details.totalItems)
        assertEquals(rawFacts, fixture.rawFacts())
        success(fixture.graph.api.settings.deleteLeave(leave.id))
        assertTrue(report().details.items.single().classification.working)
        success(fixture.graph.api.settings.deleteDateOverride(date))
        assertEquals(WorkReason.HOLIDAY, report().details.items.single().classification.reason)
        assertEquals(rawFacts, fixture.rawFacts())

        val output = fixture.output("report.csv")
        val receipt =
            success(fixture.graph.api.reports.exportCsv(ReportExportQuery("31234", range), output))
        assertEquals(1L, receipt.recordCount)
        val csv = fixture.documentBytes("report.csv")
        assertTrue(
            csv.take(3)
                .toByteArray()
                .contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        )
        assertTrue(csv.toString(Charsets.UTF_8).contains("ANSWERED"))
        fixture.reopen()
        assertEquals(WorkReason.HOLIDAY, report().details.items.single().classification.reason)
        assertEquals(
            original.originalClassification,
            report().details.items.single().originalClassification,
        )
        assertEquals(rawFacts, fixture.rawFacts())
    }

    fun testAudioPublicationFailureRollsBackRealFilePreferencesRevisionAndEnabledMirror() =
        runBlocking {
            val bytes =
                instrumentation.targetContext.resources.openRawResource(R.raw.short_melody).use {
                    it.readBytes()
                }
            fixture.ringPreferences.edit().putBoolean("enabled", true).commit()
            assertFalse(fixture.graph.audio.enabledForCalls())
            assertTrue(success(fixture.graph.api.ringtone.getSettings()).enabled)
            assertTrue(fixture.graph.audio.enabledForCalls())
            val first =
                success(
                    fixture.graph.api.ringtone.importAudio(fixture.document("first.wav", bytes))
                )
            assertEquals(AudioSource.IMPORTED, first.source)
            assertEquals("first.wav", first.displayName)
            val selected = File(fixture.audioDirectory, "selected_audio")
            assertTrue(bytes.contentEquals(selected.readBytes()))
            val revision = fixture.graph.api.changes.snapshots().value.revision
            val changedBytes =
                bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            fixture.ringPreferences.failNextCommit.set(true)
            failure(
                fixture.graph.api.ringtone.importAudio(
                    fixture.document("second.wav", changedBytes)
                ),
                ErrorCode.STORAGE_FAILURE,
            )
            assertEquals(first, success(fixture.graph.api.ringtone.getSettings()))
            assertTrue(bytes.contentEquals(selected.readBytes()))
            assertEquals(revision, fixture.graph.api.changes.snapshots().value.revision)
            assertTrue(fixture.graph.audio.enabledForCalls())
            failure(
                fixture.graph.api.ringtone.importAudio(
                    fixture.document("invalid.wav", "not audio")
                ),
                ErrorCode.VALIDATION,
            )
            assertTrue(bytes.contentEquals(selected.readBytes()))
            assertFalse(
                fixture.audioDirectory.listFiles()!!.any {
                    it.name.startsWith("audio-import-") || it.name.startsWith("audio-backup-")
                }
            )

            val monitorHeld = CountDownLatch(1)
            val releaseMonitor = CountDownLatch(1)
            val holder = Thread {
                synchronized(fixture.ringPreferences) {
                    monitorHeld.countDown()
                    check(releaseMonitor.await(5, TimeUnit.SECONDS))
                }
            }
            holder.start()
            try {
                assertTrue(monitorHeld.await(5, TimeUnit.SECONDS))
                val elapsed = AtomicReference<Long>()
                instrumentation.runOnMainSync {
                    val start = SystemClock.elapsedRealtime()
                    assertTrue(fixture.graph.audio.enabledForCalls())
                    elapsed.set(SystemClock.elapsedRealtime() - start)
                }
                assertTrue("Deadline path waited for preferences", elapsed.get() < 250)
            } finally {
                releaseMonitor.countDown()
                holder.join(5000)
            }
            success(fixture.graph.api.ringtone.restoreBuiltIn())
            fixture.reopen()
            assertEquals(
                AudioSource.BUILT_IN,
                success(fixture.graph.api.ringtone.getSettings()).source,
            )
            assertTrue(bytes.contentEquals(selected.readBytes()))
        }

    fun testGraphCloseDrainsQueuedCallCompletionBeforeClosingSqlite() = runBlocking {
        configureWorkingDay()
        val coordinator = fixture.graph.incomingCalls
        val blocker =
            SQLiteDatabase.openDatabase(
                fixture.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        blocker.beginTransaction()
        try {
            val session =
                coordinator.inspect(
                    "fixture-session",
                    "31234",
                    millis("2026-06-01T10:00:00Z"),
                    "fixture-account",
                )
            val completion = coordinator.complete(session, CallDecision.CUSTOM_RINGTONE)
            coordinator.show(
                session,
                CallDecision.CUSTOM_RINGTONE,
                session.assessment.copy(overlayEnabled = true),
            )
            assertEquals("fixture-session", fixture.ui.requests.single().sessionId)
            Thread {
                    closeStarted.countDown()
                    try {
                        fixture.graph.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeFinished.countDown()
                    }
                }
                .start()
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "Close discarded pending persistence",
                closeFinished.await(150, TimeUnit.MILLISECONDS),
            )
            blocker.endTransaction()
            assertTrue(
                "Close did not drain pending persistence",
                closeFinished.await(10, TimeUnit.SECONDS),
            )
            closeFailure.get()?.let { throw AssertionError("Graph close failed", it) }
            assertTrue(completion.isDone)
        } finally {
            if (blocker.inTransaction()) blocker.endTransaction()
            blocker.close()
            closeFinished.await(10, TimeUnit.SECONDS)
        }
        fixture.reopen()
        assertEquals(
            CallDecision.CUSTOM_RINGTONE,
            success(fixture.graph.api.callers.getLatestCall())?.decision,
        )
    }

    fun testDiagnosticEnqueueNeverWaitsForCommitAndGraphCloseDrainsIt() = runBlocking {
        val diagnostics = fixture.graph.diagnostics
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.ringPreferences.commitEntered = entered
        fixture.ringPreferences.allowCommit = release
        try {
            assertTrue(diagnostics.enqueue(DiagnosticEvent.AUDIO_PREPARING))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.clock.at("2026-06-01T09:00:00Z")
            val elapsed = AtomicReference<Long>()
            instrumentation.runOnMainSync {
                val start = SystemClock.elapsedRealtime()
                assertTrue(diagnostics.enqueue(DiagnosticEvent.AUDIO_PLAYING))
                elapsed.set(SystemClock.elapsedRealtime() - start)
            }
            assertTrue("Enqueue waited for a preference commit", elapsed.get() < 250)
            fixture.clock.at("2026-06-01T10:00:00Z")
        } finally {
            release.countDown()
        }
        fixture.graph.close()
        assertFalse(diagnostics.enqueue(DiagnosticEvent.AUDIO_STOPPED))
        fixture.reopen()
        val entries = success(fixture.graph.api.runtime.getDiagnostics())
        assertEquals(listOf("audio.playing", "audio.preparing"), entries.map { it.message })
        assertEquals(
            listOf(millis("2026-06-01T09:00:00Z"), millis("2026-06-01T08:00:00Z")),
            entries.map { it.occurredAtEpochMillis },
        )
        assertTrue(entries.all { it.legacyTimeText == null })
    }
}
