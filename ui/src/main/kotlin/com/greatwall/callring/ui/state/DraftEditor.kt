package com.greatwall.callring.ui.state

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Only lightweight editor state is encoded here; backend snapshots are reloaded. */
interface DraftCodec<T> {
    fun encode(value: T): String
    fun decode(value: String): T?
}

open class DraftEditor<T : Any>(
    changes: ChangeFeed,
    domains: Set<DataDomain>,
    private val savedState: SavedStateHandle,
    private val codec: DraftCodec<T>,
    private val load: suspend () -> ApiResult<T>,
    private val save: suspend (T) -> ApiResult<T>,
    testScope: CoroutineScope? = null,
    private val saveWithBase: (suspend (T, T) -> ApiResult<T>)? = null,
) : UiViewModel(testScope) {
    private val loader = RequestLoader<T>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableDraft = MutableStateFlow(savedState.get<String>("draft")?.let(codec::decode))
    val draft = mutableDraft.asStateFlow()
    private var baseline = savedState.get<String>("draft-base")?.let(codec::decode)

    init { observeChanges(changes, domains, refresh = ::refresh) }

    fun refresh() = loader.load(onSuccess = {
        if (mutableDraft.value == null || mutableDraft.value == baseline) replaceDraft(it)
        else if (baseline == null) setBaseline(it)
    }) { load() }

    fun edit(value: T) { if (!mutation.value.busy) storeDraft(value) }

    fun save() {
        val value = mutableDraft.value ?: return
        val base = baseline ?: return
        writer.run({ saveWithBase?.invoke(base, value) ?: save(value) }, "设置已保存") { replaceDraft(it); refresh() }
    }

    fun <R> execute(operation: suspend () -> ApiResult<R>, onSuccess: (R) -> Unit = { refresh() }) =
        writer.run(operation, "设置已恢复", onSuccess)

    fun replaceDraft(value: T) {
        setBaseline(value)
        storeDraft(value)
    }

    private fun setBaseline(value: T) {
        baseline = value
        savedState["draft-base"] = codec.encode(value)
    }

    private fun storeDraft(value: T) {
        mutableDraft.value = value
        savedState["draft"] = codec.encode(value)
    }
}
