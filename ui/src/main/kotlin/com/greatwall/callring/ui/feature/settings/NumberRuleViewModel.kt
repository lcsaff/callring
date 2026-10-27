package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** IDs belong to the editable rows, independently of their current positions. */
data class NumberRuleDraft(val id: Long, val prefix: String, val length: String)

class NumberRuleViewModel(
    private val api: CallringUiApi,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private var nextId = savedState.get<Long>("rule.nextId") ?: 1L
    private var base: NumberRuleSettings? = restoreBase()
    private val draftMutable = MutableStateFlow(restoreDraft())
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
    private var autoSave: Job? = null
    private val loader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.NUMBER_RULES)),
        load = api.settings::getNumberRule, onSuccess = { latest ->
            if (mutation.value.busy) return@ObservedQuery
            val old = base
            val dirty = draft.value != null && !draft.value!!.represents(old)
            if (discardOnLoad || !dirty) {
                base = latest
                draftMutable.value = latest.asDraft(draft.value)
                conflictMutable.value = false
                discardOnLoad = false
                errorsMutable.value = validate(draft.value!!)
                persist()
            } else conflictMutable.value = latest != old
            updateTrial()
        })
    val state = loader.state
    init {
        loader.start(scope)
        scope.launch {
            state.first { it.loaded && it.error == null }
            scheduleSave()
        }
    }
    fun refresh() { loader.request(force = true) }
    fun reload() { if (!mutation.value.busy) { autoSave?.cancel(); discardOnLoad = true; loader.request(force = true) } }
    fun editPrefix(id: Long, value: String) = edit { rows -> rows.map { if (it.id == id) it.copy(prefix = value) else it } }
    fun editLength(id: Long, value: String) = edit { rows -> rows.map { if (it.id == id) it.copy(length = value) else it } }
    fun addRule() = edit { it + NumberRuleDraft(nextId++, "", "") }
    fun removeRule(id: Long) = edit { rows -> rows.filterNot { it.id == id } }
    private fun edit(change: (List<NumberRuleDraft>) -> List<NumberRuleDraft>) {
        draft.value?.let { draftMutable.value = change(it); errorsMutable.value = validate(draft.value!!); persist(); scheduleSave() }
    }
    private fun scheduleSave() {
        autoSave?.cancel()
        autoSave = scope.launch {
            delay(500)
            mutation.first { !it.busy }
            if (!conflict.value) save()
        }
    }
    fun editTrial(value: String) {
        trialMutable.value = value
        savedState["rule.trial"] = value
        if (state.value.loaded && state.value.error == null) updateTrial()
    }
    private fun updateTrial() { trialCheckMutable.value = api.checkNumber(trial.value) }
    fun save() {
        val snapshot = base ?: return
        val value = draft.value ?: return
        if (!state.value.loaded || state.value.error != null || mutation.value.busy) return
        errorsMutable.value = validate(value)
        if (errors.value.isNotEmpty() || value.represents(snapshot)) return
        val rules = value.map { NumberRuleEntry(it.prefix, it.parsedLength()!!) }
        writer.run({ api.settings.saveNumberRule(SaveNumberRule(snapshot, rules)) }, "规则已保存") { saved ->
            base = saved
            // Keep row IDs, blank length text, and edits made while this transaction ran.
            conflictMutable.value = false
            errorsMutable.value = validate(draft.value.orEmpty())
            persist()
            updateTrial()
            if (!draft.value.orEmpty().represents(saved)) scheduleSave()
        }
    }
    private fun validate(value: List<NumberRuleDraft>): Map<String, String> = buildMap {
        value.forEachIndexed { index, row ->
            val length = row.parsedLength()
            if (length == null || length !in 1..30) put("rules[$index].length", "号码长度须为 1–30 的整数")
            if (row.prefix.any { it !in '0'..'9' }) put("rules[$index].prefix", "前缀只能包含数字 0–9")
            else if (length != null && row.prefix.length > length) put("rules[$index].prefix", "前缀不能长于号码长度")
        }
    }
    private fun NumberRuleDraft.parsedLength(): Int? = if (length.isEmpty()) 5 else
        length.takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    private fun List<NumberRuleDraft>.represents(settings: NumberRuleSettings?) = settings != null &&
        map { it.prefix to it.parsedLength() } == settings.rules.map { it.prefix to it.length }
    private fun NumberRuleSettings.asDraft(previous: List<NumberRuleDraft>? = null): List<NumberRuleDraft> =
        if (previous?.represents(this) == true) previous else rules.map { NumberRuleDraft(nextId++, it.prefix, it.length.toString()) }
    private fun restoreBase(): NumberRuleSettings? {
        val revision = savedState.get<Long>("rule.base.revision") ?: return null
        val prefixes = savedState.get<ArrayList<String>>("rule.base.prefixes")
        val lengths = savedState.get<ArrayList<Int>>("rule.base.lengths")
        if (prefixes != null && lengths != null && prefixes.size == lengths.size) {
            return NumberRuleSettings(prefixes.indices.map { NumberRuleEntry(prefixes[it], lengths[it]) }, revision)
        }
        val prefix = savedState.get<String>("rule.base.prefix") ?: return null
        val length = savedState.get<Int>("rule.base.length") ?: return null
        return NumberRuleSettings(listOf(NumberRuleEntry(prefix, length)), revision)
    }
    private fun restoreDraft(): List<NumberRuleDraft>? {
        val prefixes = savedState.get<ArrayList<String>>("rule.prefixes")
        val lengths = savedState.get<ArrayList<String>>("rule.lengths")
        val ids = savedState.get<ArrayList<Long>>("rule.ids")
        if (prefixes != null && lengths != null && ids != null && prefixes.size == lengths.size && prefixes.size == ids.size) {
            nextId = maxOf(nextId, (ids.maxOrNull() ?: 0L) + 1)
            return prefixes.indices.map { NumberRuleDraft(ids[it], prefixes[it], lengths[it]) }
        }
        return savedState.get<String>("rule.prefix")?.let { listOf(NumberRuleDraft(nextId++, it, savedState["rule.length"] ?: "")) }
    }
    private fun persist() {
        draft.value?.let { rows ->
            savedState["rule.prefixes"] = ArrayList(rows.map { it.prefix })
            savedState["rule.lengths"] = ArrayList(rows.map { it.length })
            savedState["rule.ids"] = ArrayList(rows.map { it.id })
        }
        savedState["rule.nextId"] = nextId
        base?.let {
            savedState["rule.base.prefixes"] = ArrayList(it.rules.map { row -> row.prefix })
            savedState["rule.base.lengths"] = ArrayList(it.rules.map { row -> row.length })
            savedState["rule.base.revision"] = it.revision
        }
        listOf("rule.prefix", "rule.length", "rule.base.prefix", "rule.base.length").forEach { savedState.remove<Any>(it) }
    }
}
