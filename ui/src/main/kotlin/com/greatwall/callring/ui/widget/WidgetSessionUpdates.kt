package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.common.ApiError
import kotlinx.coroutines.flow.*

internal data class WidgetSessionUpdate(val error: ApiError?, val snapshot: WidgetSnapshot?)

/** Active Glance sessions load from the application bridge; presentation-only changes do not query. */
internal fun widgetSessionUpdates(widgetId: Int, initial: WidgetRefreshRequest?, model: WidgetModel): Flow<WidgetSessionUpdate> {
    var loadedRevision = initial?.revision ?: 0
    return WidgetRefreshSignals.requests.map { it[widgetId] }.distinctUntilChanged().dropWhile { it == initial }
        .map { request ->
            val revision = request?.revision ?: 0
            val snapshot = if (revision != loadedRevision) model.load().also { loadedRevision = revision } else null
            WidgetSessionUpdate(request?.error, snapshot)
        }
}
