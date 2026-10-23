package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.SavedStateHandle

/** Queries never save. The selected number is committed only by an explicit successful save. */
class WidgetConfigurationModel(
    private val widgetId: Int,
    private val api: CallringUiApi,
    testScope: CoroutineScope? = null,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : UiViewModel(testScope) {
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val queryMutable = MutableStateFlow(savedState.get<String>("query") ?: "")
    val query = queryMutable.asStateFlow()
    private val selectionMutable = MutableStateFlow(savedState.get<String>("selection"))
    val selectedNumber = selectionMutable.asStateFlow()
    private val savedMutable = MutableStateFlow<WidgetBinding?>(null)
    val savedBinding = savedMutable.asStateFlow()
    private val validationMutable = MutableStateFlow<String?>(null)
    val validation = validationMutable.asStateFlow()
    private var edited = savedState.get<Boolean>("edited") ?: false

    private val ruleLoader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.NUMBER_RULES)),
        load = api.settings::getNumberRule)
    val rule = ruleLoader.state
    fun retryRule() = ruleLoader.request(force = true)
    private fun ruleReady() = rule.value.loaded && !rule.value.loading && rule.value.error == null

    private val bindingLoader = ObservedQuery(scope, api.changes,
        ChangeSubscription(setOf(DataDomain.WIDGET_BINDINGS), mapOf(DataDomain.WIDGET_BINDINGS to setOf(widgetId.toString()))),
        load = { api.widgets.getBinding(widgetId) }, onSuccess = { if (!edited) selectionMutable.value = it?.number })
    val binding = bindingLoader.state
    private val resultsLoader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.CALLERS, DataDomain.POSITIONS)),
        { query.value }, { api.callers.search(CallerSearch(query.value)) })
    val results = resultsLoader.state
    private val appearanceLoader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.APPEARANCE)),
        load = { api.settings.getAppearance() })
    val appearance = appearanceLoader.state
    init { ruleLoader.start(scope); bindingLoader.start(scope); resultsLoader.start(scope); appearanceLoader.start(scope) }

    fun changeQuery(text: String) {
        if (mutation.value.busy) return
        edited = true
        queryMutable.value = text
        selectionMutable.value = null
        persist()
        validationMutable.value = null
        search()
    }

    fun select(number: String) {
        if (mutation.value.busy || !ruleReady()) return
        edited = true
        val checked = api.checkNumber(number)
        selectionMutable.value = checked.normalized.takeIf { checked.validProfileNumber }
        persist()
        validationMutable.value = if (checked.validProfileNumber) null else "请输入有效号码"
    }

    fun selectDirectNumber() = select(query.value)
    private fun persist() {
        savedState["query"] = query.value
        savedState["selection"] = selectedNumber.value
        savedState["edited"] = edited
    }

    fun search() {
        resultsLoader.request()
    }

    fun save() {
        if (!ruleReady()) return
        val number = selectedNumber.value ?: return
        if (widgetId <= 0 || !api.checkNumber(number).validProfileNumber) return
        writer.run({ api.widgets.saveBinding(widgetId, number) }) { savedMutable.value = it }
    }
}
