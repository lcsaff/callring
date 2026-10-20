package com.greatwall.callring

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.AuditRecord
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.CalendarImport
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.defaultWorkSchedule
import java.io.*
import java.lang.management.ManagementFactory
import java.time.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CallringBackendTest {
    @Test fun quickQuietEnableDefaultsToNonWorkAndRestoresCustomWithoutLosingWindows() = runBlocking {
        assertEquals(QuietMode.NON_WORK, api.settings.setQuietEnabled(true).value().mode)
        val custom = QuietSettings(QuietMode.CUSTOM, true, mapOf(1 to listOf(TimeWindow(1380, 60))))
        api.settings.saveQuietHours(custom).value()
        assertEquals(custom.copy(mode = QuietMode.OFF), api.settings.setQuietEnabled(false).value())
        assertEquals(custom, api.settings.setQuietEnabled(true).value())
    }
    @Test fun allQuietWritePathsUpdateDurableResumeMode() = runBlocking {
        api.settings.saveQuietHours(QuietSettings(QuietMode.CUSTOM, true, mapOf(2 to listOf(TimeWindow(60, 120))))).value()
        api.settings.setQuietMode(QuietMode.NON_WORK).value()
        api.settings.setQuietEnabled(false).value()
        assertEquals(QuietMode.NON_WORK, api.settings.setQuietEnabled(true).value().mode)
        api.settings.setQuietMode(QuietMode.CUSTOM).value()
        api.settings.setQuietMode(QuietMode.OFF).value()
        assertEquals(QuietMode.CUSTOM, api.settings.setQuietEnabled(true).value().mode)
    }
    @Test fun emptyHistoricalCustomResumeFallsBackWithoutEnablingInvalidMode() = runBlocking {
        store.state.lastQuietMode = QuietMode.CUSTOM.toDomain()
        assertEquals(QuietMode.NON_WORK, api.settings.setQuietEnabled(true).value().mode)
    }
    @Test fun failedQuickQuietSaveKeepsActualModeAndRememberedMode() = runBlocking {
        val custom = QuietSettings(QuietMode.CUSTOM, false, mapOf(1 to listOf(TimeWindow(60, 120))))
        api.settings.saveQuietHours(custom).value()
        store.failCommit = true
        assertTrue(api.settings.setQuietEnabled(false) is ApiResult.Failure)
        assertEquals(custom, api.settings.getQuietHours().value())
        store.failCommit = false
        api.settings.setQuietEnabled(false).value()
        assertEquals(custom, api.settings.setQuietEnabled(true).value())
    }
    private val now = Instant.parse("2026-10-12T10:00:00Z").toEpochMilli()

    private class MemoryStore : BackendStore {
        var state =
            BackendState().apply {
                schedules += defaultWorkSchedule("UTC")
                nextConfigVersion = 2
            }
        var failCommit = false

        override fun <T> read(block: (BackendState) -> T) = synchronized(this) { block(state) }

        override fun <T> transaction(block: (BackendState) -> T): T =
            synchronized(this) {
                val candidate =
                    BackendState().apply {
                        profiles.putAll(state.profiles)
                        positions.putAll(state.positions)
                        calls.putAll(state.calls)
                        schedules.addAll(state.schedules)
                        calendarYears.putAll(state.calendarYears)
                        holidays.putAll(state.holidays)
                        makeupDates.addAll(state.makeupDates)
                        overrides.putAll(state.overrides)
                        leaves.putAll(state.leaves)
                        statisticsIntervals.addAll(state.statisticsIntervals)
                        pendingAudits.addAll(state.pendingAudits)
                        myPositionId = state.myPositionId
                        quiet = state.quiet
                        lastQuietMode = state.lastQuietMode
                        score = state.score
                        overlayEnabled = state.overlayEnabled
                        leaveVersion = state.leaveVersion
                        nextPositionId = state.nextPositionId
                        nextLeaveId = state.nextLeaveId
                        nextCallId = state.nextCallId
                        nextConfigVersion = state.nextConfigVersion
                    }
                val result = block(candidate)
                if (failCommit) throw IllegalStateException("test.commit_failure")
                state = candidate
                result
            }
    }

    private class Prefs : PreferenceStore {
        override fun bindingsForNumber(number: String) = listBindings().filter { com.greatwall.callring.application.sameRecordingNumber(it.number, number) }
        override fun listBindings() = bindings.values.toList()
        @Volatile
        var appearanceValue =
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
        var afterAppearanceRead: () -> Unit = {}
        val bindings = mutableMapOf<Int, WidgetBinding>()

        override fun getAppearance(): AppearanceSettings {
            val captured = appearanceValue
            afterAppearanceRead()
            return captured
        }

        override fun saveAppearance(settings: AppearanceSettings) {
            appearanceValue = settings
        }

        override fun getBinding(widgetId: Int) = bindings[widgetId]

        override fun saveBinding(binding: WidgetBinding) {
            bindings[binding.widgetId] = binding
        }

        override fun removeBinding(widgetId: Int) {
            bindings.remove(widgetId)
        }
    }

    private class Documents : DocumentStore {
        val bytes = ByteArrayOutputStream()
        lateinit var calendar: CalendarImport
        var beforeWrite: () -> Unit = {}
        var failOutput = false

        override fun <T> read(source: DocumentRef, block: (InputStream) -> T): T =
            ByteArrayInputStream(byteArrayOf()).use(block)

        override fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T {
            beforeWrite()
            if (failOutput) throw IOException("test.output_failure")
            return block(bytes)
        }

        override fun readCalendar(source: DocumentRef) = calendar
    }

    private class Probe : RuntimeProbe {
            override fun getInstalledWidgets() = com.greatwall.callring.contracts.widget.WidgetInstallState(emptyList(), AccessState.GRANTED)
        var capabilities = Capability.values().associateWith { AccessState.GRANTED }

        override fun getStatus() =
            RuntimeStatus(
                capabilities,
                Audibility.AUDIBLE,
                PhoneActivity.IDLE,
                PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE),
                true,
                0,
            )

        override fun getDiagnostics() = emptyList<DiagnosticEntry>()
    }

    private class Audio : AudioController {
        var current = RingtoneSettings(false, AudioSource.BUILT_IN, "audio.built_in", 8)
        var stops = 0
        var stopRestricted = false
        var beforeCommit: () -> Unit = {}
        var afterCommit: () -> Unit = {}
        var imports = 0

        override fun getSettings() = current

        override fun setEnabled(enabled: Boolean): RingtoneSettings {
            current = current.copy(enabled = enabled)
            return current
        }

        override fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport {
            beforeCommit()
            checkCancellation()
            return object : PreparedRingtoneImport {
                override fun commit(checkCancellation: () -> Unit): RingtoneSettings {
                    checkCancellation()
                    current = current.copy(source = AudioSource.IMPORTED)
                    imports++
                    afterCommit()
                    return current
                }
                override fun close() {}
            }
        }

        override fun restoreBuiltIn() = current

        override fun startPreview() {}

        override fun stopPlayback() {
            stops++
            if (stopRestricted)
                throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.stop_restricted"))
        }
    }

    private val store = MemoryStore()
    private val prefs = Prefs()
    private val docs = Documents()
    private val probe = Probe()
    private val audio = Audio()
    private var syncs = 0
    private var annualCalendar: () -> CalendarImport = { throw IOException("offline") }
    private val api =
        CallringBackend(
            prefs,
            docs,
            probe,
            audio,
            object : SyncScheduler {
                override fun requestSync(atEpochMillis: Long): SyncRequestReceipt {
                    syncs++
                    return SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis)
                }
            },
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            Dispatchers.Unconfined,
            CalendarSource { annualCalendar() },
            coreTransactions = MemoryQuietTransactions(store), numberRecordings = com.greatwall.callring.EmptyNumberRecordings, identityArchive = { com.greatwall.callring.contracts.common.ApiResult.Success(null) }, operationActive = { true }, conversationProvider = { com.greatwall.callring.testing.FixtureConversationApi }, aiSettingsProvider = { com.greatwall.callring.testing.FixtureAiSettingsApi }, updateProvider = { com.greatwall.callring.testing.FixtureAppUpdateApi }, snapshotsProvider = { com.greatwall.callring.testing.FixtureAiSnapshotApi }, recordingsProvider = { com.greatwall.callring.testing.FixtureRecordingApi }, persistenceActive = { true })

    @Test fun unchangedRingtoneSettingDoesNotPublish() = runBlocking {
        api.ringtone.setEnabled(false).value()
        assertEquals(0L, api.coordinator.marker())
    }

    @Test
    fun annualSyncPreservesCachedCalendarWhenDownloadFails() = runBlocking {
        val day = LocalDate.of(2026, 10, 1)
        annualCalendar = {
            CalendarImport(CalendarYear(2026, "holiday-cn", "2025-11-04", "v1").toDomain(), mapOf(day to "国庆节"), emptySet(), "raw")
        }
        api.settings.synchronizeCalendar(2026).value()
        annualCalendar = { throw IOException("offline") }
        assertTrue(api.settings.synchronizeCalendar(2026) is ApiResult.Failure)
        assertEquals("国庆节", store.state.holidays[day])
        assertEquals("v1", store.state.calendarYears[2026]?.version)
    }

    @Test
    fun annualSyncRejectsWrongYearBeforeReplacingCache() = runBlocking {
        annualCalendar = { CalendarImport(CalendarYear(2027, "source", "2026-10-01", "v").toDomain(), emptyMap(), emptySet()) }
        assertTrue(api.settings.synchronizeCalendar(2026) is ApiResult.Failure)
        assertTrue(store.state.calendarYears.isEmpty())
    }

    @Test
    fun annualDownloadDoesNotHoldTheDatabaseCoordinatorLock() = runBlocking {
        annualCalendar = {
            val finished = CountDownLatch(1)
            Thread {
                runBlocking { api.settings.getCalendar().value() }
                finished.countDown()
            }.start()
            assertTrue("calendar read blocked behind download", finished.await(2, TimeUnit.SECONDS))
            CalendarImport(CalendarYear(2026, "source", "2026-10-01", "hash").toDomain(),
                mapOf(LocalDate.of(2026, 10, 1) to "国庆节"), emptySet())
        }
        api.settings.synchronizeCalendar(2026).value()
        Unit
    }

    @Test
    fun unchangedAnnualContentDoesNotRewriteAuditOrFetchDate() = runBlocking {
        val downloaded = CalendarImport(CalendarYear(2026, "source", "2026-10-01", "same-hash").toDomain(),
            mapOf(LocalDate.of(2026, 10, 1) to "国庆节"), emptySet())
        annualCalendar = { downloaded }
        api.settings.synchronizeCalendar(2026).value()
        val audits = store.state.pendingAudits.toList()
        annualCalendar = { downloaded.copy(metadata = downloaded.metadata.copy(published = "2026-10-02")) }
        api.settings.synchronizeCalendar(2026).value()
        assertEquals(audits, store.state.pendingAudits)
        assertEquals("2026-10-01", store.state.calendarYears[2026]?.published)
    }

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success<T>).value

    @Test
    fun nullableWhitelistKeepsLatestValueAndWhitespaceNormalizes() = runBlocking {
        api.callers.setWhitelisted("(30001)", true).value()
        val p =
            api.callers.saveProfile(SaveCallerProfile("30 001", " name ", " dept ", null)).value()
        assertTrue(p.whitelisted)
        assertEquals("30001", p.number)
        assertEquals("name", p.name)
        assertEquals(1L, api.callers.search(CallerSearch("dept")).value().totalItems)
    }

    @Test
    fun validationKeepsMachineCodesAndInactivePositionCanOnlyBeRetained() = runBlocking {
        val p =
            api.settings
                .savePosition(SavePosition(name = "manager", level = 1, active = true))
                .value()
        api.callers.saveProfile(SaveCallerProfile("30001", "", "", p.id)).value()
        api.settings.savePosition(SavePosition(p.id, "manager", 1, false)).value()
        api.callers.saveProfile(SaveCallerProfile("30001", "edited", "", p.id)).value()
        val failure =
            api.callers.saveProfile(SaveCallerProfile("30002", "", "", p.id)) as ApiResult.Failure
        assertEquals(ErrorCode.VALIDATION, failure.error.code)
        assertEquals("position.inactive", failure.error.fieldErrors["positionId"])
        assertEquals(
            ErrorCode.IN_USE,
            (api.settings.deletePosition(p.id) as ApiResult.Failure).error.code,
        )
    }

    @Test
    fun statisticsTransitionsAreIdempotentAndClosingPreservesHistory() = runBlocking {
        api.settings.setStatisticsEnabled(true).value()
        api.settings.setStatisticsEnabled(true).value()
        assertEquals(1, store.state.statisticsIntervals.size)
        assertEquals(1, syncs)
        api.settings.setStatisticsEnabled(false).value()
        assertEquals(now, store.state.statisticsIntervals.single().endMillis)
    }

    @Test
    fun reportFilterDoesNotChangeTotalsAndExportIncludesUnresolved() = runBlocking {
        val fact = com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
        store.state.calls[1] =
            RawCallRecord(
                1,
                "30001",
                now,
                0,
                CallResult.ANSWERED.toDomain(),
                CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                CallSource.SYSTEM_CALL_LOG.toDomain(),
                "1",
                null,
                true,
                fact,
                fact,
            )
        store.state.calls[2] =
            RawCallRecord(
                2,
                "30001",
                now + 1,
                null,
                CallResult.UNRESOLVED.toDomain(),
                CallDecision.QUIET_SILENCED.toDomain(),
                CallSource.SCREENING.toDomain(),
                null,
                "s",
                false,
                fact,
                fact,
            )
        val range = DateRange(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-12"), "UTC")
        val report = api.reports.load(ReportQuery("30001", range, CallFilter.HOLIDAY)).value()
        assertEquals(1L, report.totals.totalCalls)
        assertEquals(0L, report.totals.unknownDurationCount)
        assertEquals(1L, report.totals.pendingCount)
        assertEquals(0L, report.details.totalItems)
        val receipt =
            api.reports.exportCsv(ReportExportQuery("30001", range), DocumentRef("memory")).value()
        assertEquals(2L, receipt.recordCount)
        assertArrayEquals(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            docs.bytes.toByteArray().take(3).toByteArray(),
        )
        assertTrue(docs.bytes.toString("UTF-8").contains("UNRESOLVED"))
    }

    @Test
    fun roleAndPermissionFailureDoNotChangeRingtoneState() = runBlocking {
        probe.capabilities =
            probe.capabilities + (Capability.CALL_SCREENING_ROLE to AccessState.DENIED)
        val result = api.ringtone.setEnabled(true) as ApiResult.Failure
        assertEquals(ErrorCode.ROLE_REQUIRED, result.error.code)
        assertFalse(audio.current.enabled)
        assertEquals(Capability.values().toSet(), api.runtime.getStatus().value().capabilities.keys)
    }

    @Test
    fun disablingRingtonePersistsEvenWhenPlaybackStopIsRestricted() = runBlocking {
        audio.current = audio.current.copy(enabled = true)
        audio.stopRestricted = true
        probe.capabilities = probe.capabilities.mapValues { AccessState.DENIED }
        val disabled = api.ringtone.setEnabled(false).value()
        assertFalse(disabled.enabled)
        assertFalse(audio.current.enabled)
        assertEquals(1, audio.stops)
    }

    @Test
    fun initialChangeInvalidatesAllDomainsAndReturnedCollectionsCannotMutateStorage() =
        runBlocking {
            assertEquals(DataDomain.values().toSet(), api.changes.observe().first().domains)
            val windows = mutableListOf(TimeWindow(540, 720))
            api.settings
                .saveWorkSchedule(SaveWorkSchedule("UTC", mapOf(1 to windows), emptyList()))
                .value()
            windows.clear()
            val schedule = api.settings.getWorkSchedule().value()
            assertEquals(1, schedule.weekly.getValue(1).size)
            try {
                (schedule.weekly.getValue(1) as MutableList<TimeWindow>).clear()
                fail("mutable snapshot")
            } catch (_: UnsupportedOperationException) {}
        }

    @Test
    fun calendarChangesReclassifyHistoryButRegularScheduleDoesNot() = runBlocking {
        val fact = com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
        store.state.calls[1] =
            RawCallRecord(
                1,
                "30001",
                now,
                null,
                CallResult.PENDING.toDomain(),
                CallDecision.CUSTOM_RINGTONE.toDomain(),
                CallSource.SCREENING.toDomain(),
                null,
                "s",
                true,
                fact,
                fact,
            )
        api.settings.saveWorkSchedule(SaveWorkSchedule("UTC", emptyMap(), emptyList())).value()
        assertEquals(fact, store.state.calls.getValue(1).classification)
        api.settings
            .saveDateOverride(
                DateOverride(LocalDate.parse("2026-10-12"), DateOverrideKind.REST, emptyList())
            )
            .value()
        assertEquals(com.greatwall.callring.domain.model.WorkReason.REST_DAY, store.state.calls.getValue(1).classification.reason)
        assertEquals(fact, store.state.calls.getValue(1).originalClassification)
    }

    @Test
    fun capturedDecisionIsImmutableAndDelayedInsertRetainsOriginalFacts() = runBlocking {
        api.settings.saveQuietHours(QuietSettings(QuietMode.NON_WORK, true, emptyMap())).value()
        api.refreshDecisionSnapshot()
        val before = api.decisionSnapshot()
        val evaluated = before.evaluate("30001", now)
        api.settings
            .saveDateOverride(
                DateOverride(LocalDate.parse("2026-10-12"), DateOverrideKind.REST, emptyList())
            )
            .value()
        assertTrue(before.evaluate("30001", now).classification.working)
        assertTrue(api.decisionSnapshot().evaluate("30001", now).silence)
        api.settings.setStatisticsEnabled(true).value()
        val id =
            api.recordIncoming(
                "30001",
                now,
                CallDecision.CUSTOM_RINGTONE,
                null,
                evaluated.classification.toContract(),
            )
        assertEquals(
            evaluated.classification,
            store.state.calls.getValue(id).originalClassification,
        )
        assertEquals(com.greatwall.callring.domain.model.WorkReason.REST_DAY, store.state.calls.getValue(id).classification.reason)
    }

    @Test
    fun callLogReimportIsIdempotentAndUnknownAccountRetainsKnownAccount() = runBlocking {
        api.settings.setStatisticsEnabled(true).value()
        val record =
            com.greatwall.callring.domain.SystemCallRecord(
                "id",
                "30001",
                now,
                0,
                CallResult.MISSED.toDomain(),
                "account",
            )
        assertEquals(setOf("30001"), api.importSystemCalls(listOf(record)))
        assertTrue(api.importSystemCalls(listOf(record.copy(account = null))).isEmpty())
        assertEquals(1, store.state.calls.size)
        assertEquals("account", store.state.calls.values.single().account)
    }

    @Test
    fun mergedProjectionUsesScreeningDecisionAndOriginalFactsWithoutChangingProviderOriginal() =
        runBlocking {
            api.settings.setStatisticsEnabled(true).value()
            val original =
                com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
                    .copy(reason = WorkReason.UNKNOWN.toDomain())
            api.recordIncoming("30001", now, CallDecision.QUIET_SILENCED, "account", original.toContract())
            api.importSystemCalls(
                listOf(
                    com.greatwall.callring.domain.SystemCallRecord(
                        "log",
                        "30001",
                        now + 1000,
                        0,
                        CallResult.MISSED.toDomain(),
                        "account",
                    )
                )
            )
            val detail = api.callers.getLatestCall().value()!!
            assertEquals(CallSource.MERGED, detail.source)
            assertEquals(CallDecision.QUIET_SILENCED, detail.decision)
            assertEquals(now, detail.screeningAtEpochMillis)
            assertEquals(original.toContract(), detail.originalClassification)
            assertNotEquals(
                original,
                store.state.calls.values.single { it.logId != null }.originalClassification,
            )
            assertFalse(api.hasRecentPending(now + 1000))
        }

    @Test
    fun allDayLeaveUsesLocalDateBoundariesAcrossDaylightSavings() = runBlocking {
        val period =
            LeavePeriod.AllDay(
                LocalDate.parse("2026-03-08"),
                LocalDate.parse("2026-03-08"),
                "America/New_York",
            )
        val saved = api.settings.saveLeave(SaveLeave(period = period, note = "day")).value()
        assertEquals(23 * 3600000L, saved.endExclusiveEpochMillis - saved.startInclusiveEpochMillis)
        val updated =
            api.settings
                .saveLeave(
                    SaveLeave(
                        saved.id,
                        LeavePeriod.Timed(
                            saved.startInclusiveEpochMillis,
                            saved.endExclusiveEpochMillis,
                        ),
                        "edit",
                    )
                )
                .value()
        assertEquals(saved.id, updated.id)
        assertEquals("edit", updated.note)
        api.settings.deleteLeave(saved.id).value()
        assertTrue(api.settings.getLeaves().value().isEmpty())
        assertEquals(
            ErrorCode.NOT_FOUND,
            (api.settings.deleteLeave(saved.id) as ApiResult.Failure).error.code,
        )
    }

    @Test
    fun reportDateRangeUsesDstAndOutOfRangePageRemainsEmpty() = runBlocking {
        val range =
            DateRange(
                LocalDate.parse("2026-03-08"),
                LocalDate.parse("2026-03-08"),
                "America/New_York",
            )
        val report =
            api.reports
                .load(ReportQuery("30001", range, page = PageRequest(Int.MAX_VALUE, 200)))
                .value()
        assertEquals(
            23 * 3600000L,
            report.range.endExclusiveEpochMillis - report.range.startInclusiveEpochMillis,
        )
        assertEquals(Int.MAX_VALUE, report.details.pageIndex)
        assertTrue(report.details.items.isEmpty())
        assertNull(report.score.points)
        assertEquals(
            ErrorCode.VALIDATION,
            (api.reports.load(ReportQuery("30001", range.copy(zoneId = "bad")))
                    as ApiResult.Failure)
                .error
                .code,
        )
    }

    @Test
    fun scoreCountsOnlyVisibleCountedRowsAndRepeatBoundaryIsInclusive() = runBlocking {
        val fact = com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
        for ((id, offset) in listOf(1L to 0L, 2L to 600000L, 3L to 1200001L)) {
            store.state.calls[id] =
                RawCallRecord(
                    id,
                    "30001",
                    now + offset,
                    0,
                    CallResult.MISSED.toDomain(),
                    CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                    CallSource.SYSTEM_CALL_LOG.toDomain(),
                    "$id",
                    null,
                    true,
                    fact.copy(working = false),
                    fact,
                )
        }
        val range = DateRange(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-12"), "UTC")
        val report = api.reports.load(ReportQuery("30001", range)).value()
        assertEquals(3L, report.score.offHoursCalls)
        assertEquals(1L, report.score.repeatedCalls)
        assertEquals(83, report.score.points)
        api.settings.saveScoreSettings(ScoreSettings(Int.MAX_VALUE, Int.MAX_VALUE, 10, 30)).value()
        assertEquals(0, api.reports.load(ReportQuery("30001", range)).value().score.points)
    }

    @Test
    fun customQuietIsValidatedAndWhitelistAlwaysBypassesIt() = runBlocking {
        api.refreshDecisionSnapshot()
        assertEquals(
            ErrorCode.VALIDATION,
            (api.settings.setQuietMode(QuietMode.CUSTOM) as ApiResult.Failure).error.code,
        )
        api.settings
            .saveQuietHours(
                QuietSettings(QuietMode.CUSTOM, true, mapOf(1 to listOf(TimeWindow(0, 120))))
            )
            .value()
        val at = Instant.parse("2026-10-12T01:00:00Z").toEpochMilli()
        assertTrue(api.decisionSnapshot().evaluate("123", at).silence)
        api.callers.setWhitelisted("123", true).value()
        assertFalse(api.decisionSnapshot().evaluate("123", at).silence)
        api.settings.setQuietMode(QuietMode.OFF).value()
        assertEquals(1, api.settings.getQuietHours().value().weeklyWindows.size)
    }

    @Test
    fun appearanceAndIndependentWidgetBindingsRoundTripWithoutDiscardingCustomSeed() = runBlocking {
        api.settings
            .saveAppearance(
                AppearanceSettings(ThemeSelection.CUSTOM, "opaque", 0x123456, ColorMode.DARK)
            )
            .value()
        val restored =
            api.settings
                .saveAppearance(
                    AppearanceSettings(ThemeSelection.PRESET, "other", null, ColorMode.LIGHT)
                )
                .value()
        assertEquals(0x123456, restored.customSeedRgb)
        api.widgets.saveBinding(1, "(30001)").value()
        api.widgets.saveBinding(2, "30002").value()
        api.widgets.removeBinding(1).value()
        api.widgets.removeBinding(1).value()
        assertNull(api.widgets.getBinding(1).value())
        assertEquals("30002", api.widgets.getBinding(2).value()!!.number)
        assertEquals(
            ErrorCode.VALIDATION,
            (api.widgets.saveBinding(0, "30001") as ApiResult.Failure).error.code,
        )
    }

    @Test
    fun failedCommitRollsBackProfileAndDoesNotPublishCandidateSnapshotOrEvent() = runBlocking {
        api.callers.setWhitelisted("30001", false).value()
        val before = api.refreshDecisionSnapshot()
        val revision = api.changes.observe().first().dataRevision
        store.failCommit = true
        val failed = api.callers.setWhitelisted("30001", true) as ApiResult.Failure
        assertEquals(ErrorCode.STORAGE_FAILURE, failed.error.code)
        assertFalse(store.state.profiles.getValue("30001").whitelisted)
        assertSame(before, api.decisionSnapshot())
        assertEquals(revision, api.changes.observe().first().dataRevision)
    }

    @Test
    fun csvCapturesMyPositionBasisAndCompleteTotalsInTheReportSnapshot() = runBlocking {
        val mine =
            api.settings.savePosition(SavePosition(name = "mine", level = 2, active = true)).value()
        val caller =
            api.settings
                .savePosition(SavePosition(name = "caller", level = 1, active = true))
                .value()
        api.settings.setMyPosition(mine.id).value()
        api.callers
            .saveProfile(SaveCallerProfile("30001", "=2+3", "@department", caller.id))
            .value()
        val fact = com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
        store.state.calls[1] =
            RawCallRecord(
                1,
                "30001",
                now,
                0,
                CallResult.MISSED.toDomain(),
                CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                CallSource.SYSTEM_CALL_LOG.toDomain(),
                "1",
                null,
                true,
                fact,
                fact,
            )
        store.state.calls[2] =
            RawCallRecord(
                2,
                "30001",
                now + 1,
                0,
                CallResult.BLOCKED.toDomain(),
                CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                CallSource.SYSTEM_CALL_LOG.toDomain(),
                "2",
                null,
                true,
                fact,
                fact,
            )
        docs.beforeWrite = { store.state.myPositionId = caller.id }
        val range = DateRange(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-12"), "UTC")
        api.reports.exportCsv(ReportExportQuery("30001", range), DocumentRef("memory")).value()
        assertArrayEquals(javaClass.getResourceAsStream("/report/t09a-original.csv")!!.use { it.readBytes() }, docs.bytes.toByteArray())
        val lines = docs.bytes.toString("UTF-8").removePrefix("\uFEFF").split("\r\n")
        val headers = csvValues(lines[0])
        val values = csvValues(lines[1])
        val profileRow = headers.zip(values).toMap()
        assertEquals(mine.id.toString(), profileRow["my_position_id"])
        assertEquals("mine", profileRow["my_position_name"])
        assertEquals("2", profileRow["my_position_level"])
        assertEquals("HIGHER", profileRow["relation"])
        assertEquals("'=2+3", profileRow["name"])
        assertEquals("'@department", profileRow["department"])
        assertEquals("'30001", profileRow["number"])
        val summary = csvValues(lines[2]).zip(csvValues(lines[3])).toMap()
        assertEquals("1", summary["missed_count"])
        assertEquals("1", summary["rejected_count"])
    }

    @Test
    fun outputFailureReturnsIoFailureAndDoesNotPublishDataChanges() = runBlocking {
        val revision = api.changes.observe().first().dataRevision
        docs.failOutput = true
        val range = DateRange(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-12"), "UTC")
        val result =
            api.reports.exportCsv(ReportExportQuery("30001", range), DocumentRef("memory"))
                as ApiResult.Failure
        assertEquals(ErrorCode.IO_FAILURE, result.error.code)
        assertEquals(revision, api.changes.observe().first().dataRevision)
    }

    @Test
    fun cancellationDuringAudioPreparationDoesNotPublishSelectionOrChangeEvent() = runBlocking {
        val revision = api.changes.observe().first().dataRevision
        val importTask =
            launch(start = CoroutineStart.LAZY) { api.ringtone.importAudio(DocumentRef("memory")) }
        audio.beforeCommit = { importTask.cancel() }
        importTask.start()
        importTask.join()
        assertTrue(importTask.isCancelled)
        assertEquals(0, audio.imports)
        assertEquals(AudioSource.BUILT_IN, audio.current.source)
        assertEquals(revision, api.changes.observe().first().dataRevision)
    }

    @Test
    fun cancellationAfterAudioCommitStillPublishesTheCommittedChange() = runBlocking {
        val revision = api.changes.observe().first().dataRevision
        val importTask =
            launch(start = CoroutineStart.LAZY) { api.ringtone.importAudio(DocumentRef("memory")) }
        audio.afterCommit = { importTask.cancel() }
        importTask.start()
        importTask.join()
        assertTrue(importTask.isCancelled)
        assertEquals(1, audio.imports)
        assertEquals(AudioSource.IMPORTED, audio.current.source)
        assertTrue(api.changes.observe().first().dataRevision > revision)
    }

    @Test
    fun calendarImportCorrectsOnlyCurrentFactsAndPreservesTheExactSourceAudit() = runBlocking {
        api.settings.setStatisticsEnabled(true).value()
        val callId = api.recordIncoming("30001", now, CallDecision.CUSTOM_RINGTONE)
        val original = store.state.calls.getValue(callId)
        val adjustedDate = LocalDate.parse("2026-10-13")
        api.settings
            .saveDateOverride(DateOverride(adjustedDate, DateOverrideKind.REST, emptyList()))
            .value()
        val raw =
            """{"year":2026,"source":"source","published":"2025-01-01","version":"v1","holidays":[{"start":"2026-10-12","end":"2026-10-12","name":"holiday"}],"workdays":[]}"""
        docs.calendar =
            CalendarImport(
                CalendarYear(2026, "source", "2025-01-01", "v1").toDomain(),
                mapOf(LocalDate.parse("2026-10-12") to "holiday"),
                emptySet(),
                raw,
            )
        val info = api.settings.importCalendar(DocumentRef("memory")).value()
        assertEquals(listOf(2026), info.years.map { it.year })
        assertEquals(listOf(adjustedDate), info.overrides.map { it.date })
        val corrected = store.state.calls.getValue(callId)
        assertEquals(com.greatwall.callring.domain.model.WorkReason.HOLIDAY, corrected.classification.reason)
        assertEquals("holiday", corrected.classification.holidayName)
        assertEquals("v1", corrected.classification.calendarVersion)
        assertTrue(corrected.classification.calendarVerified)
        assertEquals(original.copy(classification = corrected.classification), corrected)
        val audit = store.state.pendingAudits.last()
        assertEquals(AuditRecord("calendar", "2026", raw, now), audit)
    }

    @Test
    fun replacingOneCalendarYearRemovesObsoleteDatesAndReplayDoesNotAppendAuditOrChangeFacts() =
        runBlocking {
            api.settings.setStatisticsEnabled(true).value()
            val callId = api.recordIncoming("30001", now, CallDecision.QUIET_SILENCED)
            val original = store.state.calls.getValue(callId).originalClassification
            val preservedDate = LocalDate.parse("2025-01-01")
            docs.calendar =
                CalendarImport(
                    CalendarYear(2025, "source", "2024-01-01", "2025.1").toDomain(),
                    mapOf(preservedDate to "previous"),
                    emptySet(),
                    "raw-2025",
                )
            api.settings.importCalendar(DocumentRef("memory")).value()
            val replacedDate = LocalDate.parse("2026-10-12")
            val obsoleteMakeup = LocalDate.parse("2026-10-10")
            docs.calendar =
                CalendarImport(
                    CalendarYear(2026, "source", "2025-01-01", "v1").toDomain(),
                    mapOf(replacedDate to "holiday"),
                    setOf(obsoleteMakeup),
                    "raw-v1",
                )
            api.settings.importCalendar(DocumentRef("memory")).value()
            val earlierAudits = store.state.pendingAudits.toList()
            docs.calendar =
                CalendarImport(
                    CalendarYear(2026, "replacement", "2025-02-01", "v2").toDomain(),
                    emptyMap(),
                    setOf(replacedDate),
                    "raw-v2",
                )
            val info = api.settings.importCalendar(DocumentRef("memory")).value()
            assertEquals(listOf(2025, 2026), info.years.map { it.year })
            assertEquals(mapOf(preservedDate to "previous"), store.state.holidays)
            assertEquals(setOf(replacedDate), store.state.makeupDates)
            assertEquals(
                com.greatwall.callring.domain.model.WorkReason.MAKEUP_WORKING,
                store.state.calls.getValue(callId).classification.reason,
            )
            assertEquals(original, store.state.calls.getValue(callId).originalClassification)
            assertEquals(earlierAudits, store.state.pendingAudits.dropLast(1))
            assertEquals(
                AuditRecord("calendar", "2026", "raw-v2", now),
                store.state.pendingAudits.last(),
            )
            val committedAudits = store.state.pendingAudits.toList()
            val committedCalls = store.state.calls.toMap()
            val committedCalendars = store.state.calendarYears.toMap()
            val committedSchedules = store.state.schedules.toList()
            api.settings.importCalendar(DocumentRef("memory")).value()
            assertEquals(committedAudits, store.state.pendingAudits)
            assertEquals(committedCalls, store.state.calls)
            assertEquals(committedCalendars, store.state.calendarYears)
            assertEquals(committedSchedules, store.state.schedules)
        }

    @Test
    fun invalidCalendarSemanticsLeaveExistingCalendarAuditCallsAndSnapshotUntouched() =
        runBlocking {
            val date = LocalDate.parse("2026-10-12")
            val valid =
                CalendarImport(
                    CalendarYear(2026, "source", "2025-01-01", "v1").toDomain(),
                    mapOf(date to "holiday"),
                    emptySet(),
                    "raw",
                )
            docs.calendar = valid
            api.settings.importCalendar(DocumentRef("memory")).value()
            val calendars = store.state.calendarYears.toMap()
            val holidays = store.state.holidays.toMap()
            val calls = store.state.calls.toMap()
            val audits = store.state.pendingAudits.toList()
            val snapshot = api.refreshDecisionSnapshot()
            val revision = api.changes.observe().first().dataRevision
            val invalid =
                listOf(
                    "conflicting date" to valid.copy(makeupDates = setOf(date)),
                    "date outside declared year" to
                        valid.copy(holidays = mapOf(date.plusYears(1) to "holiday")),
                    "missing holiday name" to valid.copy(holidays = mapOf(date to " ")),
                    "missing source" to valid.copy(metadata = valid.metadata.copy(source = " ")),
                    "missing version" to valid.copy(metadata = valid.metadata.copy(version = "")),
                    "invalid publication date" to
                        valid.copy(metadata = valid.metadata.copy(published = "2025-02-30")),
                    "invalid year" to valid.copy(metadata = valid.metadata.copy(year = 0)),
                )
            for ((label, calendar) in invalid) {
                docs.calendar = calendar
                val failed = api.settings.importCalendar(DocumentRef("memory")) as ApiResult.Failure
                assertEquals(label, ErrorCode.VALIDATION, failed.error.code)
                assertEquals(label, calendars, store.state.calendarYears)
                assertEquals(label, holidays, store.state.holidays)
                assertEquals(label, calls, store.state.calls)
                assertEquals(label, audits, store.state.pendingAudits)
                assertSame(label, snapshot, api.decisionSnapshot())
                assertEquals(label, revision, api.changes.observe().first().dataRevision)
            }
        }

    @Test
    fun movingAnExistingSystemIdUnlinksOldScreenAndLinksTheNewIdentity() = runBlocking {
        api.settings.setStatisticsEnabled(true).value()
        val oldScreen = api.recordIncoming("30001", now, CallDecision.CUSTOM_RINGTONE, "A")
        val newScreen = api.recordIncoming("30002", now + 30000, CallDecision.QUIET_SILENCED, "B")
        val originalRecord =
            com.greatwall.callring.domain.SystemCallRecord(
                "same-id",
                "30001",
                now + 1000,
                0,
                CallResult.MISSED.toDomain(),
                "A",
            )
        api.importSystemCalls(listOf(originalRecord))
        val provider = store.state.calls.values.single { it.logId == "same-id" }
        assertEquals(oldScreen, provider.linkedScreenId)
        assertTrue(store.state.calls.getValue(oldScreen).hidden)
        val changed =
            api.importSystemCalls(
                listOf(
                    originalRecord.copy(
                        number = "30002",
                        startedAtEpochMillis = now + 31000,
                        account = "B",
                        result = CallResult.REJECTED.toDomain(),
                    )
                )
            )
        assertEquals(setOf("30001", "30002"), changed)
        assertEquals(3, store.state.calls.size)
        val moved = store.state.calls.values.single { it.logId == "same-id" }
        assertEquals(provider.id, moved.id)
        assertEquals(newScreen, moved.linkedScreenId)
        assertEquals(provider.originalClassification, moved.originalClassification)
        val restored = store.state.calls.getValue(oldScreen)
        assertFalse(restored.hidden)
        assertTrue(restored.counted)
        assertEquals(com.greatwall.callring.domain.model.CallResult.PENDING, restored.result)
        assertTrue(store.state.calls.getValue(newScreen).hidden)
        assertEquals(CallDecision.QUIET_SILENCED, api.callers.getLatestCall().value()!!.decision)
    }

    @Test
    fun slowChangeCollectorEventuallyReceivesAllDomainsAfterConflatedCommits() = runBlocking {
        val paused = CompletableDeferred<DataChange>()
        val resume = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<DataChange>()
        var pausedRevision = -1L
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                api.changes.observe().collect { change ->
                    if (change.dataRevision > 0 && !paused.isCompleted) {
                        pausedRevision = change.dataRevision
                        paused.complete(change)
                        resume.await()
                    } else if (paused.isCompleted && change.dataRevision > pausedRevision) {
                        delivered.complete(change)
                    }
                }
            }
        try {
            api.callers.setWhitelisted("30001", true).value()
            withTimeout(5000) { paused.await() }
            api.settings.saveScoreSettings(ScoreSettings(7, 3, 5, 14)).value()
            api.widgets.saveBinding(1, "30001").value()
            val finalRevision = api.changes.observe().first().dataRevision
            resume.complete(Unit)
            val observed = withTimeout(5000) { delivered.await() }
            assertEquals(finalRevision, observed.dataRevision)
            assertTrue(api.changes.snapshots()!!.value.domains.getValue(DataDomain.CALLERS).lastAnyRevision > 0)
            assertTrue(
                observed.domains.containsAll(
                    setOf(
                        DataDomain.SCORE_SETTINGS,
                        DataDomain.WIDGET_BINDINGS,
                    )
                )
            )
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun commitBetweenInitialInvalidationAndFlowSubscriptionIsNotLost() = runBlocking {
        val initial = CompletableDeferred<DataChange>()
        val subscribe = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<DataChange>()
        var isInitial = true
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                api.changes.observe().collect { change ->
                    if (isInitial) {
                        isInitial = false
                        initial.complete(change)
                        subscribe.await()
                    } else delivered.complete(change)
                }
            }
        try {
            val first = withTimeout(5000) { initial.await() }
            assertEquals(DataDomain.values().toSet(), first.domains)
            api.callers.setWhitelisted("30001", true).value()
            api.settings.setOverlayEnabled(true).value()
            val finalRevision = api.changes.observe().first().dataRevision
            subscribe.complete(Unit)
            val observed = withTimeout(5000) { delivered.await() }
            assertEquals(finalRevision, observed.dataRevision)
            assertTrue(api.changes.snapshots()!!.value.domains.getValue(DataDomain.CALLERS).lastAnyRevision > 0)
            assertTrue(
                observed.domains.containsAll(
                    setOf(DataDomain.CALLERS, DataDomain.REPORTS, DataDomain.OVERLAY_SETTINGS)
                )
            )
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun concurrentPresetSelectionCannotRestoreAnOlderCustomSeed() = runBlocking {
        val red = 0xff0000
        val blue = 0x0000ff
        api.settings
            .saveAppearance(
                AppearanceSettings(ThemeSelection.CUSTOM, "initial", red, ColorMode.LIGHT)
            )
            .value()
        val readPaused = CountDownLatch(1)
        val continuePreset = CountDownLatch(1)
        val blueCommitted = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val presetThread =
            Thread(
                {
                    try {
                        runBlocking {
                            api.settings
                                .saveAppearance(
                                    AppearanceSettings(
                                        ThemeSelection.PRESET,
                                        "new-preset",
                                        null,
                                        ColorMode.LIGHT,
                                    )
                                )
                                .value()
                        }
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                },
                "appearance-preset-save",
            )
        val blueThread =
            Thread(
                {
                    try {
                        runBlocking {
                            api.settings
                                .saveAppearance(
                                    AppearanceSettings(
                                        ThemeSelection.CUSTOM,
                                        "new-preset",
                                        blue,
                                        ColorMode.DARK,
                                    )
                                )
                                .value()
                        }
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    } finally {
                        blueCommitted.countDown()
                    }
                },
                "appearance-custom-save",
            )
        prefs.afterAppearanceRead = {
            if (Thread.currentThread() === presetThread) {
                readPaused.countDown()
                check(continuePreset.await(5, TimeUnit.SECONDS)) { "preset test gate timed out" }
            }
        }
        try {
            presetThread.start()
            assertTrue(
                "preset save must capture the original seed",
                readPaused.await(5, TimeUnit.SECONDS),
            )
            blueThread.start()
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Without serialization blue commits before the stale preset resumes. With
            // serialization blue waits for the preset's monitor, then becomes the last commit.
            while (blueCommitted.count > 0) {
                val waiting = threads.getThreadInfo(blueThread.id)
                if (
                    waiting?.threadState == Thread.State.BLOCKED &&
                        waiting.lockOwnerId == presetThread.id
                )
                    break
                check(System.nanoTime() < deadline) {
                    "custom save neither committed nor waited for the preset"
                }
                Thread.yield()
            }
        } finally {
            continuePreset.countDown()
            presetThread.join(5000)
            if (blueThread.state != Thread.State.NEW) blueThread.join(5000)
            prefs.afterAppearanceRead = {}
        }
        assertFalse("preset save must terminate", presetThread.isAlive)
        assertFalse("custom save must terminate", blueThread.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(blue, api.settings.getAppearance().value().customSeedRgb)
    }

    private fun csvValues(row: String): List<String> {
        val values = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            val char = row[index++]
            when {
                char == '"' && quoted && index < row.length && row[index] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += cell.toString()
                    cell.setLength(0)
                }
                else -> cell.append(char)
            }
        }
        values += cell.toString()
        return values
    }

    @Test
    fun numberDeletionImpactAndCoreDeleteRemoveProfileAndAllCallsAtomically() = runBlocking {
        val fact = com.greatwall.callring.domain.WorkClassifier.classify(store.state, now)
        store.state.profiles["30001"] = com.greatwall.callring.domain.ProfileRecord("30001", "张三", "研发", null, false)
        store.state.profiles["40004"] = com.greatwall.callring.domain.ProfileRecord("40004", "李四", "市场", null, false)
        listOf(1L, 2L).forEachIndexed { index, id ->
            store.state.calls[id] = RawCallRecord(id, "30001", now + index, 0, CallResult.ANSWERED.toDomain(), CallDecision.NORMAL_RINGING.toDomain(), CallSource.SCREENING.toDomain(), null, null, true, fact, fact)
        }
        store.state.calls[3L] = RawCallRecord(3, "40004", now, 0, CallResult.ANSWERED.toDomain(), CallDecision.NORMAL_RINGING.toDomain(), CallSource.SCREENING.toDomain(), null, null, true, fact, fact)

        val impact = api.callers.numberDataImpact("30001").value()
        assertEquals("30001", impact.number)
        assertTrue(impact.profileExists)
        assertEquals(2, impact.callCount)

        assertTrue(api.callers.deleteNumberCore("30001", 1) is ApiResult.Failure)
        assertEquals(2, api.callers.numberDataImpact("30001").value().callCount)

        api.callers.deleteNumberCore("30001", 2).value()
        assertFalse(store.state.profiles.containsKey("30001"))
        assertEquals(0, api.callers.numberDataImpact("30001").value().callCount)
        assertTrue(store.state.profiles.containsKey("40004"))
        assertTrue(store.state.calls.containsKey(3L))
        assertTrue(api.callers.deleteNumberCore("30001", 0) is ApiResult.Failure)
    }
}
