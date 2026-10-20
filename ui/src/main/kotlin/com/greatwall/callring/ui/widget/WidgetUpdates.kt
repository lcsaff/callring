package com.greatwall.callring.ui.widget

import android.content.*
import android.util.Log
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application owns this observer, so widgets update while no screen is open. */
fun CoroutineScope.observeWidgetUpdates(context: Context, api: CallringUiApi): Job = observeWidgetChanges(api.changes) {
    UiWidgetRefresh.refresh(context, it)
}

internal fun CoroutineScope.observeWidgetChanges(changes: ChangeFeed, refresh: suspend (Set<Int>?) -> Unit): Job = launch {
    changes.snapshots().map { it.token(widgetSubscription) }.distinctUntilChanged().collect {
        try { refresh(null) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Log.w("CallringWidget", "Widget refresh deferred to next update") }
    }
}

internal val widgetSubscription = ChangeSubscription(
    setOf(DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS, DataDomain.APPEARANCE),
    mapOf(DataDomain.WORK_CALENDAR to setOf("leaves")))

/** System time changes invalidate the device-day leave acknowledgement. */
class WidgetTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { withTimeout(7_000) { UiWidgetRefresh.refresh(context.applicationContext) } }
            catch (_: CancellationException) { /* Broadcast completion still runs below. */ }
            catch (_: Exception) { Log.w("CallringWidget", "Time-change refresh deferred") }
            finally { pending.finish(); cancel() }
        }
    }
    companion object {
        private val actions = setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)
    }
}
