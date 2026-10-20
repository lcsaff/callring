package com.greatwall.callring.coordination

import com.greatwall.callring.application.*
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.WidgetBinding
import com.greatwall.callring.domain.StatisticsInterval
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SimpleSettingsServiceTest {
    private class Scores : ScoreTransactions {
        var value = ScoreSettings(5, 2, 10, 30).toDomain()
        var reads = 0; var writes = 0; var fail = false
        var afterSave: () -> Unit = {}
        override fun <T> readScore(block: (ScoreReader) -> T) = block(object : ScoreReader { override fun score() = value.also { reads++ } })
        override fun <T> writeScore(block: (ScoreWriter) -> T): T {
            var pending = value
            val result = block(object : ScoreWriter {
                override fun score() = pending.also { reads++ }
                override fun saveScore(value: com.greatwall.callring.domain.model.ScoreSettings): Boolean {
                    writes++; val changed = pending != value; pending = value; afterSave(); return changed
                }
            })
            if (fail) error("commit.failed")
            value = pending
            return result
        }
    }
    private class Preferences : AppearanceStore {
        var value = AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
        var fail = false; var saves = 0
        override fun getAppearance() = value
        override fun saveAppearance(settings: AppearanceSettings) { if (fail) error("disk.failed"); value = settings; saves++ }

    }
    @Test fun cancelledInvalidSavesStillThrowBeforeAnyStorageAccess() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val tx = Scores()
        val quiet = object : QuietTransactions {
            override fun <T> readQuiet(block: (QuietReader) -> T): T = error("unexpected.storage")
            override fun <T> writeQuiet(block: (QuietWriter) -> T): T = error("unexpected.storage")
        }
        val positions = object : PositionTransactions {
            override fun <T> readPositions(block: (PositionReader) -> T): T = error("unexpected.storage")
            override fun <T> writePositions(block: (PositionWriter) -> T): T = error("unexpected.storage")
        }
        val saves: List<suspend () -> Any> = listOf(
            { ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined).save(ScoreSettings(-1, 2, 10, 30)) },
            { QuietSettingsService(quiet, coordinator, Dispatchers.Unconfined, {}).save(QuietSettings(QuietMode.CUSTOM, false, mapOf(1 to listOf(TimeWindow(-1, 1))))) },
            { PositionService(positions, coordinator, Dispatchers.Unconfined).save(SavePosition(null, "", 0, true)) }
        )
        val failures = mutableListOf<Int>()
        saves.forEachIndexed { index, save ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val job = scope.launch {
                currentCoroutineContext().cancel()
                try { save(); failures += index } catch (_: CancellationException) { }
            }
            job.join(); scope.cancel()
        }
        assertEquals("cancelled invalid saves must throw for score/quiet/position", emptyList<Int>(), failures)
        assertEquals(0, tx.reads); assertEquals(0, tx.writes)
    }
    @Test fun invalidScoreRejectsFirstFieldBeforeOpeningStorage() = runBlocking {
        val tx = Scores(); val service = ScoreSettingsService(tx, BusinessRevisionCoordinator(), Dispatchers.Unconfined)
        val error = (service.save(ScoreSettings(-1, -2, 0, 0)) as ApiResult.Failure).error
        assertEquals(ErrorCode.VALIDATION, error.code)
        assertEquals(setOf("offHoursWeight"), error.fieldErrors.keys)
        assertEquals(0, tx.reads); assertEquals(0, tx.writes)
    }
    @Test fun scoreDirtyMergeNoopRollbackAndCommandContinuationUseFinalTruth() = runBlocking {
        val tx = Scores(); val coordinator = BusinessRevisionCoordinator()
        val command = CommandContext("score", coordinator.capture(), 0, continueOwnCommits = true, expectedScopes = coordinator.expect(setOf("score"))) { true }
        val service = ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined, command)
        val base = tx.value.toContract()
        assertTrue(service.save(base.copy(repeatWeight = 3)) is ApiResult.Success)
        val merged = (service.edit(base, base.copy(offHoursWeight = 6)) as ApiResult.Success).value
        assertEquals(ScoreSettings(6, 3, 10, 30), merged)
        assertEquals(2L, coordinator.marker())
        val snapshot = coordinator.invalidations.snapshots.value
        assertEquals(setOf(DataDomain.SCORE_SETTINGS), snapshot.domains.keys)
        assertTrue(service.save(merged) is ApiResult.Success)
        assertSame(snapshot, coordinator.invalidations.snapshots.value)
        assertNotNull(coordinator.committed(command.operationId))
        assertEquals("draft.field_changed", (service.edit(base, base.copy(repeatWeight = 4)) as ApiResult.Failure).error.message)
        tx.fail = true
        assertTrue(service.reset() is ApiResult.Failure)
        assertEquals(merged, tx.value.toContract()); assertEquals(2L, coordinator.marker())
    }
    @Test fun scoreRejectsStaleInactiveAndForegroundCommandsAndPublishesAfterCancellation() = runBlocking {
        val tx = Scores(); val coordinator = BusinessRevisionCoordinator()
        val stale = CommandContext("stale", coordinator.capture(), 0, expectedScopes = coordinator.expect(setOf("core"))) { true }
        val service = ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined)
        service.save(tx.value.toContract().copy(repeatWeight = 3))
        assertEquals("business.changed", (ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined, stale).reset() as ApiResult.Failure).error.message)
        val inactive = CommandContext("inactive", coordinator.capture(), 0) { false }
        assertEquals("ai.cancelled", (ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined, inactive).reset() as ApiResult.Failure).error.message)
        val denied = CommandContext("foreground", coordinator.capture(), 0, mayCommit = { false }) { true }
        assertEquals("action.foreground_required", (ScoreSettingsService(tx, coordinator, Dispatchers.Unconfined, denied).reset() as ApiResult.Failure).error.message)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) { service.reset() }
        tx.afterSave = { job.cancel() }; job.start(); job.join()
        assertEquals(2L, coordinator.marker()); assertEquals(ScoreSettings(5, 2, 10, 30), tx.value.toContract())
        scope.cancel()
    }
    @Test fun appearanceDirectNullPreservesSeedAndDraftNullClearsItWithOnePreferencesPublication() = runBlocking {
        val prefs = Preferences(); val coordinator = BusinessRevisionCoordinator()
        val service = AppearanceService(prefs, coordinator, Dispatchers.Unconfined)
        val custom = prefs.value.copy(customSeedRgb = 0x123456)
        service.save(custom)
        assertEquals(custom, (service.save(custom.copy(customSeedRgb = null)) as ApiResult.Success).value)
        assertEquals(1L, coordinator.marker()); assertEquals(0L, coordinator.capture().values["core"])
        assertEquals(1L, coordinator.capture().values["preferences"])
        val cleared = custom.copy(customSeedRgb = null)
        assertEquals(cleared, (service.edit(custom, cleared) as ApiResult.Success).value)
        assertEquals(2L, coordinator.marker()); assertEquals(setOf(DataDomain.APPEARANCE), coordinator.changes.value.domains)
        prefs.fail = true
        assertTrue(service.save(custom) is ApiResult.Failure)
        assertEquals(cleared, prefs.value); assertEquals(2L, coordinator.marker())
    }
    @Test fun appearanceDirtyConflictRetainsExistingDraftConflictCode() = runBlocking {
        val prefs = Preferences(); val coordinator = BusinessRevisionCoordinator()
        val service = AppearanceService(prefs, coordinator, Dispatchers.Unconfined)
        val base = prefs.value
        service.save(base.copy(colorMode = ColorMode.DARK))
        val error = (service.edit(base, base.copy(colorMode = ColorMode.LIGHT)) as ApiResult.Failure).error
        assertEquals(ErrorCode.CONFLICT, error.code)
        assertEquals(mapOf("colorMode" to "draft.field_changed"), error.fieldErrors)
    }
    @Test fun summaryRechecksRelevantScopesAndNeverLabelsOldDataWithLaterRevision() = runBlocking {
        val prefs = Preferences(); val coordinator = BusinessRevisionCoordinator(); var reads = 0
        val tx = object : SummaryTransactions {
            override fun <T> readSummary(block: (SummaryReader) -> T): T {
                reads++
                val result = block(object : SummaryReader { override fun settingsSummary() = CoreSettingsSummary(false, false,
                    com.greatwall.callring.domain.model.QuietMode.OFF, null, "UTC", ScoreSettings(5, 2, 10, 30).toDomain()) })
                coordinator.publishCore(setOf("score"), ChangeImpact.wide(setOf(DataDomain.SCORE_SETTINGS)))
                return result
            }
        }
        val service = SettingsSummaryService(tx, prefs, coordinator, Dispatchers.Unconfined, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC))
        val error = (service.get() as ApiResult.Failure).error
        assertEquals(3, reads); assertEquals("business.changed", error.message); assertTrue(error.retryable)
    }
    @Test fun summaryUnrelatedRecordingCommitDoesNotRetryAndUsesObservedMarker() = runBlocking {
        val prefs = Preferences(); val coordinator = BusinessRevisionCoordinator(); var reads = 0
        val tx = object : SummaryTransactions {
            override fun <T> readSummary(block: (SummaryReader) -> T): T {
                reads++
                val result = block(object : SummaryReader { override fun settingsSummary() = CoreSettingsSummary(false, false,
                    com.greatwall.callring.domain.model.QuietMode.OFF, null, "UTC", ScoreSettings(5, 2, 10, 30).toDomain()) })
                coordinator.changed("recording")
                return result
            }
        }
        val value = (SettingsSummaryService(tx, prefs, coordinator, Dispatchers.Unconfined,
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)).get() as ApiResult.Success).value
        assertEquals(1, reads); assertEquals(0L, value.meta.dataRevision)
    }
}
