package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class ArchiveSuggestionsModel(
    private val api: RecordingApi,
    private val changes: ChangeFeed,
    scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(LoadState<CallerSuggestions?>())
    private val conflictCount = MutableStateFlow(0)
    private val writer = MutationRunner(scope)
    val state = combine(mutable, writer.state, conflictCount, ::ArchiveSuggestionsState)
        .stateIn(scope, SharingStarted.Eagerly, ArchiveSuggestionsState())
    private data class Target(val callId: Long, val number: String, val generation: Long)
    private var target: Target? = null
    private var requestedToken: InvalidationToken? = null
    private fun token(snapshot: InvalidationSnapshot = changes.snapshots().value): InvalidationToken? =
        target?.let {
            val numbers = setOfNotNull(it.number, mutable.value.data?.number)
            snapshot.token(ChangeSubscription(setOf(DataDomain.CALLERS), mapOf(DataDomain.CALLERS to numbers)))
        }
    private val loader = RefreshCoordinator(scope, { token() }, {
        try { target?.let { api.getCallerSuggestions(it.callId) } ?: ApiResult.Success(null) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "无法读取档案建议，请重试", retryable = true)) }
    }, { result ->
        mutable.value = when (result) {
            is ApiResult.Success -> LoadState(result.value, loaded = true)
            is ApiResult.Failure -> mutable.value.copy(error = result.error)
        }
        requestedToken = token()
    }, { busy -> mutable.value = mutable.value.copy(loading = busy) })

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            changes.snapshots().collect { snapshot ->
                val current = token(snapshot)
                if (current != requestedToken && current != null) {
                    requestedToken = current
                    loader.request(target)
                }
            }
        }
    }

    fun show(archive: CallArchive?) {
        val next = archive?.takeIf { it.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && !it.status.processing() }
            ?.let { Target(it.callId, it.number, it.generation) }
        if (target == next) return
        target = next
        conflictCount.value = 0
        loader.cancel()
        mutable.value = LoadState()
        requestedToken = token()
        if (next != null) loader.request(next)
    }

    fun refresh() {
        if (target != null) loader.request(target, force = true)
    }

    fun confirm(suggestions: CallerSuggestions, fields: Set<CallerSuggestionField>) {
        if (mutable.value.loading || suggestions != mutable.value.data || fields.isEmpty()) return
        val expected = target ?: return
        val before = token()
        writer.run({ api.confirmCallerSuggestions(ConfirmCallerSuggestions(suggestions.callId, suggestions.token, fields)) }, "") { receipt ->
            if (target == expected) {
                loader.cancel()
                mutable.value = LoadState((receipt as? CallerSuggestionConfirmation.Conflict)?.latest, loaded = true)
                requestedToken = token()
                if (receipt is CallerSuggestionConfirmation.Conflict) {
                    conflictCount.value++
                    if (before != requestedToken) loader.request(target)
                }
            }
        }
    }
}
