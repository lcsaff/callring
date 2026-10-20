package com.greatwall.callring.application.widget

import com.greatwall.callring.contracts.widget.WidgetBinding

interface WidgetBindingStore {
    fun bindingsForNumber(number: String): List<WidgetBinding>
    fun listBindings(): List<WidgetBinding>
    fun getBinding(widgetId: Int): WidgetBinding?
    fun saveBinding(binding: WidgetBinding)
    fun removeBinding(widgetId: Int)
}
