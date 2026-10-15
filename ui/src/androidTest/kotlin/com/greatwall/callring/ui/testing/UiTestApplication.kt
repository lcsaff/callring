package com.greatwall.callring.ui.testing

import android.app.Application
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.overlay.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/** Synthetic data for the independently installed UI test APK only. */
class UiTestApplication : Application(), UiOverlayOwner {
    override val overlaySessions = OverlaySessions()
    override val uiApi = UiDeviceFixture()
}

class UiDeviceFixture : UiFakeApi(true) {
    private val bindings = ConcurrentHashMap<Int, String>()
    val whitelist = ConcurrentHashMap<String, Boolean>()
    private val widgetChanges = MutableSharedFlow<DataChange>(extraBufferCapacity = 16)
    override val changes: ChangeFeed = object : ChangeFeed {
        override fun observe() = merge(this@UiDeviceFixture.observe(), widgetChanges)
    }
    override fun checkNumber(raw: String) = NumberCheck(raw, raw in setOf("100", "200"), false)
    override suspend fun getBinding(widgetId: Int) = ApiResult.Success(bindings[widgetId]?.let { WidgetBinding(widgetId, it) })
    override suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding> {
        bindings[widgetId] = number
        widgetChanges.emit(DataChange(0, setOf(DataDomain.WIDGET_BINDINGS), null, setOf(widgetId)))
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
