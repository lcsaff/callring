package com.greatwall.callring.ui.testing

import com.greatwall.callring.contracts.ai.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Host test driver: supplies a ticket and records its single launch and completion. */
class UiForegroundFixture : ForegroundActionApi {
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    private var host: String? = null
    private var nextHost = 0
    private val completion = CompletableDeferred<ForegroundHostResult>()
    suspend fun request(): ForegroundHostResult {
        pending.value = ForegroundTicket("fixture", "s", "t", "tool", 1, ForegroundActionKind.CONFIRM, aiObject(), 0, Long.MAX_VALUE)
        return completion.await()
    }
    override fun attachHost() = (++nextHost).toString().also { host = it }
    override fun detachHost(token: String) { if(host == token) host = null }
    override fun beginLaunch(requestId: String, token: String): Boolean {
        val ticket = pending.value ?: return false
        if(host != token || ticket.requestId != requestId || ticket.launched) return false
        pending.value = ticket.copy(launched = true)
        return true
    }
    override fun deliver(requestId: String, result: ForegroundHostResult): Boolean {
        val ticket = pending.value ?: return false
        if(ticket.requestId != requestId || !ticket.launched) return false
        pending.value = null
        return completion.complete(result)
    }
}
