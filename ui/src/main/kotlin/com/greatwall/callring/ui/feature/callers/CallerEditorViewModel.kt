package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class CallerEditorViewModel(
    private val number: String?,
    private val api: CallersApi,
    changes: ChangeFeed,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null,
    private val saveWithBase: (suspend (CallerDraft, CallerDraft) -> ApiResult<CallerProfile>)? = null,
) : UiViewModel(testScope) {
    private val loader = RequestLoader<CallerEditorData>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableDraft = MutableStateFlow<CallerDraft?>(null)
    val draft = mutableDraft.asStateFlow()
    private var baseline: CallerDraft? = if (savedState.get<String>("baseNumber") != null) restoreBase() else null

    init {
        observeChanges(changes, setOf(DataDomain.CALLERS, DataDomain.POSITIONS), number, ::refresh)
    }

    fun refresh() = loader.load(onSuccess = { snapshot ->
        if (mutableDraft.value == null) {
            mutableDraft.value = if (savedState.get<Boolean>("hasDraft") == true) restore(snapshot.draft) else snapshot.draft
        }
        if (baseline == null || mutableDraft.value == baseline) {
            if (baseline != null) { mutableDraft.value = snapshot.draft; persist(snapshot.draft) }
            setBaseline(snapshot.draft)
        }
    }) { api.getEditor(number) }

    fun edit(change: CallerDraft.() -> CallerDraft) {
        if (mutation.value.busy) return
        val previous = mutableDraft.value ?: return
        val next = previous.change()
        if (next.whitelisted != previous.whitelisted) savedState["whitelistTouched"] = true
        mutableDraft.value = next
        persist(next)
    }

    fun save() {
        val draft = mutableDraft.value ?: return
        val base = baseline ?: return
        val command = SaveCallerProfile(draft.number, draft.name, draft.department, draft.positionId,
            if (savedState.get<Boolean>("whitelistTouched") == true) draft.whitelisted else null)
        writer.run({ saveWithBase?.invoke(base, draft) ?: api.saveProfile(command) }, "档案已保存") { profile ->
            savedState["whitelistTouched"] = false
            mutableDraft.value = CallerDraft(profile.number, profile.name, profile.department, profile.position?.id, profile.whitelisted)
            setBaseline(mutableDraft.value!!)
            persist(mutableDraft.value!!)
            refresh()
        }
    }

    private fun setBaseline(value: CallerDraft) {
        baseline = value
        savedState["baseNumber"] = value.number
        savedState["baseName"] = value.name
        savedState["baseDepartment"] = value.department
        savedState["basePosition"] = value.positionId
        savedState["baseWhitelist"] = value.whitelisted
    }
    private fun restoreBase() = CallerDraft(savedState["baseNumber"] ?: "", savedState["baseName"] ?: "",
        savedState["baseDepartment"] ?: "", savedState["basePosition"], savedState["baseWhitelist"] ?: false)

    private fun persist(draft: CallerDraft) {
        savedState["hasDraft"] = true
        savedState["draftNumber"] = draft.number
        savedState["draftName"] = draft.name
        savedState["draftDepartment"] = draft.department
        savedState["draftPosition"] = draft.positionId
        savedState["draftWhitelist"] = draft.whitelisted
    }

    private fun restore(fallback: CallerDraft) = CallerDraft(
        savedState["draftNumber"] ?: fallback.number,
        savedState["draftName"] ?: fallback.name,
        savedState["draftDepartment"] ?: fallback.department,
        savedState["draftPosition"],
        savedState["draftWhitelist"] ?: fallback.whitelisted
    )
}
