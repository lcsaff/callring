package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.RecordingApi
import com.greatwall.callring.ui.state.MutationRunner
import com.greatwall.callring.ui.state.ObservedQuery
import kotlinx.coroutines.*
import java.util.UUID

/** Observes the existing runtime playback slot separately from archive and call data. */
class RecordingPlaybackState(
    private val api: RecordingApi,
    changes: ChangeFeed,
    private val scope: CoroutineScope,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val observation = ObservedQuery(scope, changes,
        ChangeSubscription(setOf(DataDomain.RUNTIME), mapOf(DataDomain.RUNTIME to setOf("recordingPlayback"))),
        load = api::getPlaybackSession)
    val state = observation.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private var activeToken: String? = null
    private var requestedToken: String? = null
    private var pendingPlay: Job? = null

    init { observation.start(scope) }

    fun toggle(callId: Long, alias: Long = callId) {
        if (mutation.value.busy) return
        val playing = state.value.data
        if (playing != null && (playing.callId == callId || playing.callId == alias)) {
            writer.run({ api.stopPlaybackSession(playing.token) }, "") {
                if (activeToken == playing.token) activeToken = null
                observation.request(force = true)
            }
        } else {
            val token = UUID.randomUUID().toString()
            requestedToken = token
            writer.run({
                coroutineScope {
                    if (requestedToken != token) throw CancellationException()
                    pendingPlay = coroutineContext.job
                    try {
                        api.playOwned(callId, token).also { result ->
                            if (requestedToken == token) {
                                if (result is ApiResult.Success) activeToken = token
                                requestedToken = null
                            }
                        }
                    } finally { pendingPlay = null }
                }
            }, "") { observation.request(force = true) }
        }
    }

    fun stopForExit(): Job? {
        val tokens = listOfNotNull(requestedToken, activeToken)
        if (tokens.isEmpty()) return null
        requestedToken = null
        activeToken = null
        pendingPlay?.cancel()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + cleanupDispatcher) {
                tokens.forEach { token ->
                    try { api.stopPlaybackSession(token) } catch (_: Exception) { /* The playback page has exited. */ }
                }
            }
        }
    }
}
