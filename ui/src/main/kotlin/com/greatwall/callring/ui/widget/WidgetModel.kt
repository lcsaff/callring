package com.greatwall.callring.ui.widget

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import kotlinx.coroutines.CancellationException

/** A rendering snapshot only; score, caller and work facts always come from the business API. */
data class WidgetSnapshot(
    val binding: WidgetBinding? = null,
    val overview: CallerOverview? = null,
    val appearance: AppearanceSettings = DefaultAppearance,
    val error: ApiError? = null,
) {
    val needsConfiguration: Boolean get() = binding == null || overview == null
}

class WidgetModel(
    private val api: CallringUiApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(widgetId: Int): WidgetSnapshot = try {
        val appearance = api.settings.getAppearance()
        val colors = (appearance as? ApiResult.Success)?.value ?: DefaultAppearance
        when (val binding = api.widgets.getBinding(widgetId)) {
            is ApiResult.Failure -> WidgetSnapshot(appearance = colors, error = binding.error)
            is ApiResult.Success -> {
                val bound = binding.value
                if (bound == null) WidgetSnapshot(appearance = colors)
                else when (val overview = api.callers.getOverview(OverviewQuery(bound.number, now()))) {
                    is ApiResult.Success -> WidgetSnapshot(bound, overview.value, colors, (appearance as? ApiResult.Failure)?.error)
                    is ApiResult.Failure -> WidgetSnapshot(bound, appearance = colors, error = overview.error)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WidgetSnapshot(error = ApiError(ErrorCode.INTERNAL, "小组件暂时无法读取，请重试或重新选择号码"))
    }

    /** A delayed click must never mutate a widget's newly selected number. */
    suspend fun setWhitelisted(widgetId: Int, expectedNumber: String, enabled: Boolean): ApiResult<CallerProfile> {
        return when (val binding = api.widgets.getBinding(widgetId)) {
            is ApiResult.Failure -> binding
            is ApiResult.Success -> if (binding.value?.number == expectedNumber) {
                api.callers.setWhitelisted(expectedNumber, enabled)
            } else ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "小组件号码已更改，请刷新后重试"))
        }
    }

    suspend fun remove(widgetId: Int): ApiResult<Unit> = api.widgets.removeBinding(widgetId)
}
