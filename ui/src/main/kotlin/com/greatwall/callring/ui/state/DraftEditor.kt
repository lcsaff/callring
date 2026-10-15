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
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val loader = RequestLoader<T>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableDraft = MutableStateFlow(savedState.get<String>("draft")?.let(codec::decode))
    val draft = mutableDraft.asStateFlow()

    init { observeChanges(changes, domains, refresh = ::refresh) }

    fun refresh() = loader.load(onSuccess = { if (mutableDraft.value == null) replaceDraft(it) }) { load() }

    fun edit(value: T) { if (!mutation.value.busy) replaceDraft(value) }

    fun save() {
        val value = mutableDraft.value ?: return
        writer.run({ save(value) }, "设置已保存") { replaceDraft(it); refresh() }
    }

    fun <R> execute(operation: suspend () -> ApiResult<R>, onSuccess: (R) -> Unit = { refresh() }) =
        writer.run(operation, "设置已恢复", onSuccess)

    fun replaceDraft(value: T) {
        mutableDraft.value = value
        savedState["draft"] = codec.encode(value)
    }
}
