package com.greatwall.callring.ui.overlay

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.callers.IncomingCallUiRequest
import com.greatwall.callring.contracts.runtime.PhoneActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlaySessionState(
    val request: IncomingCallUiRequest,
    val editing: Boolean = false,
    val collapsed: Boolean = false,
    val callEnded: Boolean = false,
    val hasQueuedCall: Boolean = false,
    val preview: Boolean = false,
    val testPreview: Boolean = false
)

/** Presentation lifetime only. Original call decisions and work facts stay with the API. */
class OverlaySessions(
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val mutable = MutableStateFlow<OverlaySessionState?>(null)
    val state = mutable.asStateFlow()
    private val closed = linkedSetOf<String>()
    private var latestStart = Long.MIN_VALUE
    private var shownAt = 0L
    private var seenRinging = false
    private var phone = PhoneActivity.UNKNOWN
    private var queued: IncomingCallUiRequest? = null
    private var queuedAt = 0L
    private var queuedRinging = false
    private var windowOwner = 0L
    fun attachOwner(): Long = ++windowOwner
    fun dismissOwned(owner: Long) { if (windowOwner == owner) dismiss() }

    /** Explicit user preview keeps historical facts without claiming a live incoming call. */
    fun preview(request: IncomingCallUiRequest, testPreview: Boolean = false): Boolean {
        if (mutable.value != null) return false
        display(request, preview = true, testPreview = testPreview)
        return true
    }

    /** True only when a new session becomes the displayed session. */
    fun accept(request: IncomingCallUiRequest): Boolean {
        // A wall-clock correction must not invalidate genuinely new session identities.
        val now = epochMillis()
        if (now < latestStart && request.startedAtEpochMillis <= now) latestStart = Long.MIN_VALUE
        if (request.sessionId.isBlank() || expired(request) || request.sessionId in closed ||
            request.startedAtEpochMillis < latestStart || request.sessionId == mutable.value?.request?.sessionId ||
            request.sessionId == queued?.sessionId) return false
        latestStart = request.startedAtEpochMillis
        val current = mutable.value
        if (current?.editing == true) {
            queued?.let { rememberClosed(it.sessionId) }
            queued = request
            queuedAt = monotonicMillis()
            queuedRinging = false
            mutable.value = current.copy(callEnded = true, hasQueuedCall = true)
            return false
        }
        current?.let { rememberClosed(it.request.sessionId) }
        display(request)
        return true
    }

    fun beginEditing() {
        mutable.value = mutable.value?.copy(editing = true, collapsed = false)
    }

    fun finishEditing() {
        val current = mutable.value ?: return
        val pending = queued
        if (pending != null && phone == PhoneActivity.RINGING && !expired(pending)) {
            rememberClosed(current.request.sessionId)
            queued = null
            display(pending)
            seenRinging = true
        } else if (current.callEnded) dismiss()
        else mutable.value = current.copy(editing = false)
    }

    fun toggleCollapsed() {
        val current = mutable.value ?: return
        if (!current.editing) mutable.value = current.copy(collapsed = !current.collapsed)
    }

    fun phone(activity: PhoneActivity) {
        phone = activity
        val current = mutable.value ?: return
        if (current.preview && queued == null) return
        if (activity == PhoneActivity.RINGING) {
            seenRinging = true
            if (queued != null) queuedRinging = true
            return
        }
        val ended = activity == PhoneActivity.ACTIVE ||
            (activity == PhoneActivity.IDLE && seenRinging) ||
            ((activity == PhoneActivity.IDLE || activity == PhoneActivity.UNKNOWN) &&
                monotonicMillis() - shownAt >= 5_000)
        if (!ended) return
        if (queued != null && (activity == PhoneActivity.ACTIVE || queuedRinging || monotonicMillis() - queuedAt >= 5_000)) {
            rememberClosed(queued!!.sessionId)
            queued = null
        }
        if (current.editing) mutable.value = current.copy(callEnded = true, hasQueuedCall = queued != null)
        else dismiss()
    }

    fun dismiss() {
        mutable.value?.let { rememberClosed(it.request.sessionId) }
        queued?.let { rememberClosed(it.sessionId) }
        queued = null
        mutable.value = null
    }

    private fun display(request: IncomingCallUiRequest, preview: Boolean = false, testPreview: Boolean = false) {
        shownAt = monotonicMillis()
        seenRinging = false
        mutable.value = OverlaySessionState(request, preview = preview, testPreview = testPreview)
    }

    private fun expired(request: IncomingCallUiRequest) = epochMillis() - request.startedAtEpochMillis > 120_000

    private fun rememberClosed(id: String) {
        closed += id
        if (closed.size > 128) closed.iterator().apply { next(); remove() }
    }
}
