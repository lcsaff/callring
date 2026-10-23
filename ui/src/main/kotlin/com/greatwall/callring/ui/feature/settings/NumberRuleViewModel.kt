package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Text drafts are saved separately from their exact loaded revision; refresh never rebases an edit. */
data class NumberRuleDraft(val prefix: String, val length: String)

class NumberRuleViewModel(
    private val api: CallringUiApi,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private var base: NumberRuleSettings? = savedState.get<Long>("rule.base.revision")?.let {
        NumberRuleSettings(savedState["rule.base.prefix"] ?: "", savedState["rule.base.length"] ?: 5, it)
    }
    private val draftMutable = MutableStateFlow(savedState.get<String>("rule.prefix")?.let { NumberRuleDraft(it, savedState["rule.length"] ?: "") })
    val draft = draftMutable.asStateFlow()
    private val errorsMutable = MutableStateFlow(emptyMap<String, String>())
    val errors = errorsMutable.asStateFlow()
    private val conflictMutable = MutableStateFlow(false)
    val conflict = conflictMutable.asStateFlow()
    private val trialMutable = MutableStateFlow(savedState["rule.trial"] ?: "")
    val trial = trialMutable.asStateFlow()
    private val trialCheckMutable = MutableStateFlow<NumberCheck?>(null)
    val trialCheck = trialCheckMutable.asStateFlow()
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private var discardOnLoad = false
    private val loader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.NUMBER_RULES)),
        load = api.settings::getNumberRule, onSuccess = { latest ->
            val old = base
            val dirty = draft.value != null && draft.value != old?.asDraft()
            if (discardOnLoad || !dirty) {
                base = latest; draftMutable.value = latest.asDraft(); conflictMutable.value = false
                discardOnLoad = false; errorsMutable.value = emptyMap(); persist()
            } else conflictMutable.value = latest != old
            updateTrial()
        })
    val state = loader.state
    init { loader.start(scope) }
    fun refresh() { loader.request(force = true) }
    fun reload() { if (!mutation.value.busy) { discardOnLoad = true; loader.request(force = true) } }
    fun editPrefix(value: String) = edit { it.copy(prefix = value) }
    fun editLength(value: String) = edit { it.copy(length = value) }
    private fun edit(change: (NumberRuleDraft) -> NumberRuleDraft) {
        if (mutation.value.busy) return
        draft.value?.let { draftMutable.value = change(it); errorsMutable.value = validate(draft.value!!); persist() }
    }
    fun editTrial(value: String) { trialMutable.value = value; savedState["rule.trial"] = value; if (state.value.loaded) updateTrial() }
    private fun updateTrial() { trialCheckMutable.value = api.checkNumber(trial.value) }
    fun save() {
        val snapshot = base ?: return
        val value = draft.value ?: return
        if (!state.value.loaded || state.value.error != null || mutation.value.busy) return
        errorsMutable.value = validate(value)
        if (errors.value.isNotEmpty()) return
        writer.run({ api.settings.saveNumberRule(SaveNumberRule(snapshot, value.prefix, value.length.toInt())) }, "规则已保存") { saved ->
            base = saved; draftMutable.value = saved.asDraft(); conflictMutable.value = false; persist(); updateTrial()
        }
    }
    private fun validate(value: NumberRuleDraft): Map<String, String> = buildMap {
        val length = value.length.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
        if (length == null || length !in 1..30) put("length", "号码长度须为 1–30 的整数")
        if (value.prefix.any { it !in '0'..'9' }) put("prefix", "前缀只能包含数字 0–9")
        else if (length != null && value.prefix.length > length) put("prefix", "前缀不能长于号码长度")
    }
    private fun NumberRuleSettings.asDraft() = NumberRuleDraft(prefix, length.toString())
    private fun persist() {
        savedState["rule.prefix"] = draft.value?.prefix; savedState["rule.length"] = draft.value?.length
        base?.let { savedState["rule.base.prefix"] = it.prefix; savedState["rule.base.length"] = it.length; savedState["rule.base.revision"] = it.revision }
    }
}
