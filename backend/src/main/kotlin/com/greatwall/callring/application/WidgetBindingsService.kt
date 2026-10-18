package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createWidgetBindingsApi(context: BackendContext): WidgetBindingsApi =
    with(context) {
        object : WidgetBindingsApi {
            override suspend fun listBindings(): ApiResult<WidgetBindingsListing> = io { WidgetBindingsListing(preferences.listBindings(), runtimeProbe.getInstalledWidgets()) }
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = io {
                widgetId(widgetId)
                preferences.getBinding(widgetId)
            }

            override suspend fun saveBinding(
                widgetId: Int,
                number: String,
            ): ApiResult<WidgetBinding> = write {
                widgetId(widgetId)
                val binding = WidgetBinding(widgetId, number(number))
                coordinator.guard(commandContext) { preferences.saveBinding(binding) }
                notifyChanged()
                binding
            }

            override suspend fun removeBinding(widgetId: Int): ApiResult<Unit> = write {
                widgetId(widgetId)
                coordinator.guard(commandContext) { preferences.removeBinding(widgetId) }
                notifyChanged()
            }
        }
    }
