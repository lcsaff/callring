package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WidgetSnapshot(
    val appearance: AppearanceSettings = DefaultAppearance,
    val quiet: QuietSettings? = null,
    val today: LocalDate,
    val leaveGranted: Boolean? = null,
    val error: ApiError? = null,
)

/** Global controls deliberately never read callers or per-widget number bindings. */
class WidgetModel(
    private val api: CallringUiApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun load(): WidgetSnapshot {
        val day = currentDay()
        val appearance = attempt { api.settings.getAppearance() }
        val quiet = attempt { api.settings.getQuietHours() }
        val leaves = attempt { api.settings.getLeaves() }
        return WidgetSnapshot(
            appearance = (appearance as? ApiResult.Success)?.value ?: DefaultAppearance,
            quiet = (quiet as? ApiResult.Success)?.value,
            today = day.date,
            leaveGranted = (leaves as? ApiResult.Success)?.value?.let { coversDay(it, day) },
            error = listOf(quiet, leaves, appearance).filterIsInstance<ApiResult.Failure>().firstOrNull()?.error,
        )
    }

    suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> = controlsMutex.withLock {
        attempt { api.settings.setQuietEnabled(enabled) }
    }

    /** Resolve the device date at the click, and serialize all widget instances against duplicates. */
    suspend fun requestTodayLeave(): ApiResult<Unit> {
        val day = currentDay()
        return controlsMutex.withLock {
            attempt {
                when (val existing = api.settings.getLeaves()) {
                    is ApiResult.Failure -> existing
                    is ApiResult.Success -> if (coversDay(existing.value, day)) ApiResult.Success(Unit)
                    else when (val saved = api.settings.saveLeave(SaveLeave(
                        period = LeavePeriod.AllDay(day.date, day.date, day.zone.id), note = "桌面快捷请假",
                    ))) {
                        is ApiResult.Failure -> saved
                        is ApiResult.Success -> ApiResult.Success(Unit)
                    }
                }
            }
        }
    }

    private fun currentDay(): DeviceDay {
        val deviceZone = zone()
        return DeviceDay(Instant.ofEpochMilli(now()).atZone(deviceZone).toLocalDate(), deviceZone)
    }

    private data class DeviceDay(val date: LocalDate, val zone: ZoneId) {
        val start: Long get() = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end: Long get() = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun coversDay(leaves: List<LeaveEntry>, day: DeviceDay): Boolean {
        var coveredUntil = day.start
        for (entry in leaves.sortedBy { it.startInclusiveEpochMillis }) {
            if (entry.startInclusiveEpochMillis > coveredUntil) break
            coveredUntil = maxOf(coveredUntil, entry.endExclusiveEpochMillis)
            if (coveredUntil >= day.end) return true
        }
        return false
    }

    private suspend fun <T> attempt(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "小组件操作未完成，请重试", retryable = true)) }

    private companion object { val controlsMutex = Mutex() }
}
