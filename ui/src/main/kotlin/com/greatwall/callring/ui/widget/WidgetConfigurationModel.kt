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
    private val bindingLoader = RequestLoader<WidgetBinding?>(scope)
    val binding = bindingLoader.state
    private val resultsLoader = RequestLoader<Page<CallerProfile>>(scope)
    val results = resultsLoader.state
    private val appearanceLoader = RequestLoader<AppearanceSettings>(scope)
    val appearance = appearanceLoader.state
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

    init {
        bindingLoader.load(onSuccess = { if (!edited) selectionMutable.value = it?.number }) {
            api.widgets.getBinding(widgetId)
        }
        appearanceLoader.load { api.settings.getAppearance() }
        search()
    }

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
        if (mutation.value.busy) return
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
        val text = query.value
        resultsLoader.load(preserveData = false) { api.callers.search(CallerSearch(text)) }
    }

    fun save() {
        val number = selectedNumber.value ?: return
        if (widgetId <= 0 || !api.checkNumber(number).validProfileNumber) return
        writer.run({ api.widgets.saveBinding(widgetId, number) }) { savedMutable.value = it }
    }
}
