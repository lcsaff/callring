package com.greatwall.callring.contracts.widget

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*

interface WidgetBindingsApi {
    suspend fun listBindings(): ApiResult<WidgetBindingsListing> = aiUnavailable()
    suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?>
    suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding>
    suspend fun removeBinding(widgetId: Int): ApiResult<Unit>
}

data class WidgetBinding(val widgetId: Int, val number: String)

data class WidgetInstallState(val ids: List<Int>, val access: AccessState)

data class WidgetBindingsListing(val stored: List<WidgetBinding>, val installed: WidgetInstallState)
