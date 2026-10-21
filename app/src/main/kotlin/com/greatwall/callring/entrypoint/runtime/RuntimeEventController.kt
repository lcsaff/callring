package com.greatwall.callring.entrypoint.runtime

import java.util.concurrent.Executor

class RuntimeEventController(
    private val ringing: () -> Unit,
    private val answered: () -> Unit,
    private val idle: (Long) -> Unit,
    private val capture: (Boolean) -> Unit,
    private val refresh: () -> Unit,
    private val sync: () -> Unit,
    private val recording: (Long) -> Unit,
    private val now: () -> Long,
    private val worker: Executor,
) {
    enum class Event { RINGING, OFFHOOK, IDLE, RESTORE, IGNORED }

    fun receive(event: Event, beginAsync: () -> (() -> Unit)) {
        if (event == Event.IGNORED) return
        if (event == Event.IDLE) sync()
        val finish = beginAsync()
        worker.execute {
            try {
                when (event) {
                    Event.RINGING -> ringing()
                    Event.OFFHOOK -> { answered(); capture(true) }
                    Event.IDLE -> { idle(now()); capture(false); recording(10_000) }
                    Event.RESTORE -> { refresh(); sync(); recording(0) }
                    Event.IGNORED -> Unit
                }
            } catch (_: Exception) {} finally { finish() }
        }
    }
}
