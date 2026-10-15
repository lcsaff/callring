package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The live choice and the last committed snapshot have separate lifetimes. */
class AppearanceViewModel(
    private val api: CallringUiApi,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private val mutableState = MutableStateFlow(LoadState<AppearanceSettings>())
    val state = mutableState.asStateFlow()
    private val restored = savedState.get<String>(PENDING_KEY)?.let(SettingsDraftCodecs.appearance::decode)
    private val mutableDraft = MutableStateFlow(restored)
    val draft = mutableDraft.asStateFlow()
    private val mutableMutation = MutableStateFlow(MutationState())
    val mutation = mutableMutation.asStateFlow()
    private var unpersisted = restored != null
    private var restoring = restored != null
    private var revision = 0L
    private var pending: AppearanceSettings? = null
    private var pendingReady = false
    private var writing = false
    private var debounce: Job? = null
    private var loadJob: Job? = null
    private var loadRevision = 0L
    private var refreshRequested = false

    init { observeChanges(api.changes, setOf(DataDomain.APPEARANCE), refresh = ::refresh) }

    fun refresh() {
        if (mutation.value.busy) { refreshRequested = true; return }
        val ticket = ++loadRevision
        loadJob?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        loadJob = scope.launch {
            val result = attempt { api.settings.getAppearance() }
            if (ticket != loadRevision) return@launch
            when (result) {
                is ApiResult.Failure -> mutableState.value = state.value.copy(loading = false, error = result.error)
                is ApiResult.Success -> {
                    mutableState.value = LoadState(result.value, loaded = true)
                    if (!unpersisted) mutableDraft.value = result.value
                    if (restoring) {
                        restoring = false
                        if (draft.value == result.value) {
                            unpersisted = false
                            savedState.remove<String>(PENDING_KEY)
                        } else draft.value?.let(::edit)
                    }
                }
            }
        }
    }

    fun edit(value: AppearanceSettings) = enqueue(value, debounceMillis = 0)

    /** Sliders update the application theme on every tick and coalesce storage writes. */
    fun editColor(value: AppearanceSettings) = enqueue(value, debounceMillis = 250)

    /** Explicit retry also flushes an outstanding slider debounce. */
    fun save() { draft.value?.let { enqueue(it, debounceMillis = 0) } }

    private fun enqueue(value: AppearanceSettings, debounceMillis: Long) {
        mutableDraft.value = value
        savedState[PENDING_KEY] = SettingsDraftCodecs.appearance.encode(value)
        unpersisted = true
        revision++
        pending = value
        pendingReady = debounceMillis == 0L
        mutableMutation.value = mutation.value.copy(busy = true, error = null, message = null)
        debounce?.cancel()
        if (pendingReady) startWriter() else debounce = scope.launch {
            delay(debounceMillis)
            pendingReady = true
            startWriter()
        }
    }

    private fun startWriter() {
        if (writing || !pendingReady || pending == null) return
        writing = true
        scope.launch {
            try {
                while (pendingReady) {
                    val value = pending ?: break
                    val ticket = revision
                    pending = null
                    pendingReady = false
                    when (val result = attempt { api.settings.saveAppearance(value) }) {
                        is ApiResult.Success -> {
                            ++loadRevision // A read started before this commit cannot replace it.
                            loadJob?.cancel()
                            mutableState.value = LoadState(result.value, loaded = true)
                            mutableMutation.value = mutation.value.copy(completed = mutation.value.completed + 1, error = null)
                            if (ticket == revision) {
                                mutableDraft.value = result.value
                                unpersisted = false
                                savedState.remove<String>(PENDING_KEY)
                            }
                        }
                        is ApiResult.Failure -> if (ticket == revision) {
                            mutableMutation.value = mutation.value.copy(error = result.error)
                        }
                    }
                }
            } finally {
                writing = false
                mutableMutation.value = mutation.value.copy(busy = pending != null)
                if (pending == null && refreshRequested) {
                    refreshRequested = false
                    refresh()
                }
            }
        }
    }

    private suspend fun <T> attempt(request: suspend () -> ApiResult<T>): ApiResult<T> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "外观保存未完成，请重试", retryable = true))
    }

    private companion object { const val PENDING_KEY = "appearance-pending" }
}
