package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.ai.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class ForegroundActionService(private val clock: Clock = Clock.systemUTC(), private val checkCommand: (ForegroundTicket, ForegroundHostResult?) -> Boolean) : ForegroundActionApi {
    private val lock = Any()
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    @Volatile private var host: String? = null
    private var completion: CompletableDeferred<ForegroundHostResult>? = null
    private var valid: () -> Boolean = { false }
    // Publication may probe the lease while beginLaunch validates under the foreground lock.
    // This read must never acquire that lock in the opposite order to the publication fence.
    val hasHost: Boolean get() = host != null
    override fun attachHost(): String = synchronized(lock) { UUID.randomUUID().toString().also { host = it } }
    override fun detachHost(token: String) = synchronized(lock) { if (host == token) host = null }
    override fun beginLaunch(requestId: String, token: String): Boolean = synchronized(lock) {
        val ticket = pending.value ?: return false
        if (host != token || ticket.requestId != requestId || ticket.launched) return false
        if(!valid() || !checkCommand(ticket, null)) { finish(ForegroundHostResult(ForegroundResultStatus.STALE)); return false }
        if(clock.millis() >= ticket.expiresAt) { finish(ForegroundHostResult(ForegroundResultStatus.EXPIRED)); return false }
        pending.value = ticket.copy(launched = true)
        true
    }
    override fun deliver(requestId: String, result: ForegroundHostResult): Boolean = synchronized(lock) {
        val ticket = pending.value ?: return false
        if (ticket.requestId != requestId || !ticket.launched) return false
        if(!valid() || !checkCommand(ticket, result)) { finish(ForegroundHostResult(ForegroundResultStatus.STALE)); return false }
        if(clock.millis() >= ticket.expiresAt) { finish(ForegroundHostResult(ForegroundResultStatus.EXPIRED)); return false }
        finish(result)
    }
    private fun finish(result: ForegroundHostResult): Boolean { pending.value = null; val target = completion; completion = null; return target?.complete(result) == true }
    fun invalidate() = synchronized(lock) {
        pending.value = null
        completion?.complete(ForegroundHostResult(ForegroundResultStatus.CANCELLED))
        completion = null
        valid = { false }
    }
    suspend fun request(session: String, turn: String, tool: String, generation: Long, kind: ForegroundActionKind, parameters: AiJson.Obj, commandContext: CommandContext? = null, confirmation: ConfirmationTarget? = null, active: () -> Boolean): ForegroundHostResult {
        val deferred = CompletableDeferred<ForegroundHostResult>()
        val ticket = synchronized(lock) {
            check(completion == null) { "action.busy" }
            valid = active
            completion = deferred
            val now = clock.millis()
            ForegroundTicket(UUID.randomUUID().toString(), session, turn, tool, generation, kind, parameters, now, now + 600000, commandContext = commandContext, confirmation = confirmation).also { pending.value = it }
        }
        try { return withTimeoutOrNull(600000) { deferred.await() } ?: ForegroundHostResult(ForegroundResultStatus.EXPIRED) }
        finally { synchronized(lock) { if (pending.value?.requestId == ticket.requestId) { pending.value = null; completion = null } } }
    }
}
