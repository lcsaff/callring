package com.greatwall.callring.entrypoint.recording

import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.recording.RecordingService.CapturePlan
import com.greatwall.callring.domain.calling.RecordingCall
import java.util.concurrent.Executor

class CaptureServiceController(
    private val plan: (Boolean) -> CapturePlan?,
    private val results: () -> Results,
    private val newTarget: () -> Target,
    private val newMedia: () -> Media,
    private val foreground: () -> Unit,
    private val stopSelf: () -> Unit,
    private val worker: Executor,
    private val shutdown: () -> Unit,
) {
    interface Target { val reference: AudioReference; fun delete() }
    interface Media {
        fun configure(plan: CapturePlan, target: Target)
        fun prepare()
        fun start()
        fun stop()
        fun release()
    }
    interface Results {
        fun attach(call: RecordingCall, audio: AudioReference)
        fun failure(call: RecordingCall, reason: String = "recording.capture_restricted")
    }
    private val lock = Any()
    private var recorder: Media? = null
    private var file: Target? = null
    private var call: RecordingCall? = null
    private var destroyed = false

    fun start(stop: Boolean) {
        if (stop) { stopSelf(); return }
        if (recorder != null || call != null) return
        // Notification construction precedes the baseline foreground exception boundary.
        try { foreground() } catch (_: ForegroundFailure) { stopSelf(); return }
        worker.execute {
            try { synchronized(lock) {
                if (destroyed || recorder != null) return@execute
                val sink = results()
                val selected = plan(false)
                if (selected == null) { stopSelf(); return@execute }
                val candidate = selected.call
                call = candidate
                try {
                    val target = newTarget(); file = target
                    val next = newMedia(); recorder = next
                    next.configure(selected, target)
                    next.prepare(); next.start()
                } catch (failure: Exception) {
                    recorder?.release(); recorder = null; file?.delete(); file = null
                    val reason=when(failure.message) {
                        "storage.insufficient_space" -> "recording.insufficient_space"
                        "storage.space_unknown" -> "recording.space_unknown"
                        else -> "recording.capture_restricted"
                    }
                    sink.failure(candidate, reason); stopSelf()
                }
            } } catch (_: Exception) { stopSelf() }
        }
    }

    fun destroy() {
        synchronized(lock) { destroyed = true }
        worker.execute {
            synchronized(lock) {
                val current = recorder; recorder = null
                var valid = false
                try { current?.stop(); valid = current != null } catch (_: Exception) {}
                finally { current?.release() }
                val audio = file; val candidate = call
                if (audio != null && candidate != null) {
                    val sink = results()
                    try {
                        if (valid) sink.attach(candidate, audio.reference)
                        else { audio.delete(); sink.failure(candidate) }
                    } catch (_: Exception) { audio.delete(); sink.failure(candidate) }
                }
            }
        }
        shutdown()
    }

    class ForegroundFailure(cause: Exception) : RuntimeException(cause)

    companion object {
        fun phoneState(active: Boolean, plan: () -> CapturePlan?, start: () -> Unit,
                       stop: () -> Unit, failure: (RecordingCall) -> Unit) {
            if (!active) { stop(); return }
            val selected = plan() ?: return
            try { start() } catch (_: Exception) { failure(selected.call) }
        }
    }
}
