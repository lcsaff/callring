package com.greatwall.callring.ui.testing

import android.app.Application
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.overlay.*
import com.greatwall.callring.ui.widget.observeWidgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Synthetic data for the independently installed UI test APK only. */
class UiTestApplication : Application(), UiOverlayOwner {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val overlaySessions = OverlaySessions()
    override val uiApi = UiDeviceFixture()
    override val availableUiApi = kotlinx.coroutines.flow.MutableStateFlow<com.greatwall.callring.contracts.CallringUiApi?>(uiApi)
    override suspend fun reobservePlatform() { uiApi.runtime.getStatus() }
    override fun onCreate() {
        super.onCreate()
        lifetime.observeWidgetUpdates(this, uiApi)
    }
}

class UiDeviceFixture : UiFakeApi(true) {
    private val bindings = ConcurrentHashMap<Int, String>()
    val whitelist = ConcurrentHashMap<String, Boolean>()
    @Volatile private var quiet = QuietSettings(QuietMode.OFF, false, emptyMap())
    private val leaves = CopyOnWriteArrayList<LeaveEntry>()
    val quietModeWrites = CopyOnWriteArrayList<QuietMode>()
    val leaveWrites = CopyOnWriteArrayList<SaveLeave>()
    private val nextLeaveId = AtomicLong()
    private val widgetRevision = AtomicLong()
    override fun checkNumber(raw: String) = NumberCheck(raw, raw in setOf("100", "200"), false)
    suspend fun resetWidgetState() {
        bindings.clear()
        whitelist.clear()
        quiet = QuietSettings(QuietMode.OFF, false, emptyMap())
        leaves.clear()
        quietModeWrites.clear()
        leaveWrites.clear()
        nextLeaveId.set(0)
        super.saveAppearance(DefaultAppearance)
        changed(DataDomain.QUIET_HOURS, DataDomain.WORK_CALENDAR)
    }

    override suspend fun getQuietHours() = ApiResult.Success(quiet)
    override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> =
        setQuietMode(if (enabled) quiet.mode.takeUnless { it == QuietMode.OFF } ?: QuietMode.NON_WORK else QuietMode.OFF)

    override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> {
        quietModeWrites += mode
        quiet = quiet.copy(mode = mode)
        changed(DataDomain.QUIET_HOURS)
        return ApiResult.Success(quiet)
    }

    override suspend fun getLeaves() = ApiResult.Success(leaves.toList())
    override suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry> {
        leaveWrites += command
        val (start, end) = when (val period = command.period) {
            is LeavePeriod.AllDay -> {
                val zone = ZoneId.of(period.zoneId)
                period.startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
                    period.endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            is LeavePeriod.Timed -> period.startInclusiveEpochMillis to period.endExclusiveEpochMillis
        }
        val now = System.currentTimeMillis()
        val entry = LeaveEntry(command.id ?: nextLeaveId.incrementAndGet(), start, end, command.note,
            when { now < start -> LeaveState.UPCOMING; now >= end -> LeaveState.FINISHED; else -> LeaveState.ACTIVE })
        leaves.removeAll { it.id == entry.id }
        leaves += entry
        changed(DataDomain.WORK_CALENDAR)
        return ApiResult.Success(entry)
    }

    private suspend fun changed(vararg domains: DataDomain) {
        feed.publish(domains.toSet())
    }

    override suspend fun getBinding(widgetId: Int) = ApiResult.Success(bindings[widgetId]?.let { WidgetBinding(widgetId, it) })
    override suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding> {
        bindings[widgetId] = number
        feed.publish(setOf(DataDomain.WIDGET_BINDINGS), mapOf(DataDomain.WIDGET_BINDINGS to setOf(widgetId).map { it.toString() }.toSet()))
        return ApiResult.Success(WidgetBinding(widgetId, number))
    }
    override suspend fun removeBinding(widgetId: Int): ApiResult<Unit> {
        bindings.remove(widgetId)
        return ApiResult.Success(Unit)
    }
    override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> {
        val data = (super.getOverview(query) as ApiResult.Success).value
        return ApiResult.Success(data.copy(profile = data.profile.copy(number = query.number,
            name = "测试号码${query.number}", whitelisted = whitelist[query.number] ?: false),
            rollingScore = data.rollingScore.copy(points = if (query.number == "100") 80 else 20)))
    }
    override suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile> {
        whitelist[number] = enabled
        return super.setWhitelisted(number, enabled)
    }
    override suspend fun getStatus() = ApiResult.Success(super.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
}
