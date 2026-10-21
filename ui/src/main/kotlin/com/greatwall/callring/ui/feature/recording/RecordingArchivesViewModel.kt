package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RecordingArchivesViewModel(private val api: RecordingApi, testScope: CoroutineScope? = null, changes: ChangeFeed) : UiViewModel(testScope) {
    private val loader = ObservedQuery(scope, changes, ChangeSubscription(setOf(DataDomain.RECORDING_ARCHIVES)),
        load = {
            when (val result = api.listArchives()) {
                is ApiResult.Success -> ApiResult.Success(result.value.sortedWith(
                    compareByDescending<CallArchive> { it.startedAtEpochMillis }.thenBy { it.callId }))
                is ApiResult.Failure -> result
            }
        })
    val state = loader.state
    init { loader.start(scope) }
    fun refresh() = loader.request(force = true)
}

/** Secrets deliberately stay in memory: no SavedStateHandle or saved instance bundle. */
