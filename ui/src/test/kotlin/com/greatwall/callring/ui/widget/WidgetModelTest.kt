package com.greatwall.callring.ui.widget

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WidgetModelTest {
    @Test fun productionResumeDecisionOverridesStaleWidgetPreference() = runTest {
        val api = object : ControlsApi() {
            override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> {
                quiet = quiet.copy(mode = if (enabled) QuietMode.NON_WORK else QuietMode.OFF)
                return ApiResult.Success(quiet)
            }
            override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> = error("legacy restore must not override backend decision")
        }
        api.quiet = api.quiet.copy(mode = QuietMode.OFF)
        assertTrue(WidgetModel(api).setQuietEnabled(true, { QuietMode.CUSTOM }) is ApiResult.Success)
        assertEquals(QuietMode.NON_WORK, api.quiet.mode)
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 15)
    private val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private open class ControlsApi : UiFakeApi() {
        var quiet = QuietSettings(QuietMode.CUSTOM, true, mapOf(1 to listOf(TimeWindow(60, 120))))
        val leaves = mutableListOf<LeaveEntry>()
        val commands = mutableListOf<SaveLeave>()
        override suspend fun getQuietHours() = ApiResult.Success(quiet)
        override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> {
            quiet = quiet.copy(mode = mode)
            return ApiResult.Success(quiet)
        }
        override suspend fun getLeaves(): ApiResult<List<LeaveEntry>> = ApiResult.Success(leaves.toList())
        override suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry> {
            commands += command
            val period = command.period as LeavePeriod.AllDay
            val entry = LeaveEntry(commands.size.toLong(), period.startDate.atStartOfDay(ZoneId.of(period.zoneId)).toInstant().toEpochMilli(),
                period.endDateInclusive.plusDays(1).atStartOfDay(ZoneId.of(period.zoneId)).toInstant().toEpochMilli(), command.note, LeaveState.ACTIVE)
            leaves += entry
            return ApiResult.Success(entry)
        }
    }

    @Test fun globalControlsLoadWithoutReadingAnyNumberOrWidgetBinding() = runTest {
        val snapshot = WidgetModel(ControlsApi(), { noon }, { zone }).load()
        assertEquals(QuietMode.CUSTOM, snapshot.quiet?.mode)
        assertEquals(date, snapshot.today)
        assertEquals(false, snapshot.leaveGranted)
        assertNull(snapshot.error)
    }

    @Test fun quickLeaveUsesSystemDateAtClickAndRepeatedConcurrentClicksSaveOnlyOnce() = runTest {
        val api = ControlsApi()
        var time = noon
        val model = WidgetModel(api, { time }, { zone })
        model.load()
        time = date.plusDays(1).atTime(0, 1).atZone(zone).toInstant().toEpochMilli()
        listOf(async { model.requestTodayLeave() }, async { WidgetModel(api, { time }, { zone }).requestTodayLeave() }).awaitAll()
        assertEquals(1, api.commands.size)
        assertEquals(LeavePeriod.AllDay(date.plusDays(1), date.plusDays(1), zone.id), api.commands.single().period)
        assertEquals(true, model.load().leaveGranted)
    }

    @Test fun existingTimedCoveragePreventsDuplicateAllDayLeaveAndRespectsDst() = runTest {
        val api = ControlsApi()
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 11, 1)
        val start = dstDay.atStartOfDay(dstZone).toInstant().toEpochMilli()
        val end = dstDay.plusDays(1).atStartOfDay(dstZone).toInstant().toEpochMilli()
        assertEquals(25 * 60 * 60 * 1000L, end - start)
        api.leaves += LeaveEntry(7, start, end, "existing", LeaveState.UPCOMING)
        assertTrue(WidgetModel(api, { start + 1 }, { dstZone }).requestTodayLeave() is ApiResult.Success)
        assertTrue(api.commands.isEmpty())
    }

    @Test fun quietSwitchPreservesRulesAndRestoresPreviousCustomMode() = runTest {
        val api = ControlsApi()
        val model = WidgetModel(api, { noon }, { zone })
        val original = api.quiet
        model.setQuietEnabled(false, { QuietMode.CUSTOM })
        assertEquals(original.copy(mode = QuietMode.OFF), api.quiet)
        model.setQuietEnabled(true, { QuietMode.CUSTOM })
        assertEquals(original, api.quiet)
    }

    @Test fun failedLeaveReadCannotSubmitBlindDuplicateAndUnknownStateIsNotFalse() = runTest {
        val error = ApiError(ErrorCode.STORAGE_FAILURE, "read failed")
        val api = object : ControlsApi() {
            override suspend fun getLeaves(): ApiResult<List<LeaveEntry>> = ApiResult.Failure(error)
        }
        val model = WidgetModel(api, { noon }, { zone })
        assertNull(model.load().leaveGranted)
        assertEquals(error, model.load().error)
        assertTrue(model.requestTodayLeave() is ApiResult.Failure)
        assertTrue(api.commands.isEmpty())
    }

    @Test fun queuedOnActionReadsRememberedModeOnlyAfterOffActionFinishes() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        var remembered = QuietMode.NON_WORK
        val api = object : ControlsApi() {
            override suspend fun getQuietHours(): ApiResult.Success<QuietSettings> {
                if (first) { first = false; entered.complete(Unit); release.await() }
                return super.getQuietHours()
            }
        }
        val model = WidgetModel(api)
        val off = async { model.setQuietEnabled(false, { remembered }) { remembered = it } }
        entered.await()
        val on = async { model.setQuietEnabled(true, { remembered }) { remembered = it } }
        runCurrent()
        release.complete(Unit)
        off.await(); on.await()
        assertEquals(QuietMode.CUSTOM, api.quiet.mode)
    }

    @Test fun leaveQueuedAcrossMidnightKeepsCallbackDate() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var time = noon
        val api = object : ControlsApi() {
            override suspend fun getQuietHours(): ApiResult.Success<QuietSettings> {
                entered.complete(Unit); release.await()
                return super.getQuietHours()
            }
        }
        val model = WidgetModel(api, { time }, { zone })
        val quiet = async { model.setQuietEnabled(false, { QuietMode.CUSTOM }) }
        entered.await()
        val leave = async { model.requestTodayLeave() }
        runCurrent()
        time = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        release.complete(Unit)
        quiet.await(); leave.await()
        assertEquals(LeavePeriod.AllDay(date, date, zone.id), api.commands.single().period)
    }
}
