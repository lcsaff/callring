package com.greatwall.callring.bootstrap

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.BackendState
import java.io.Closeable

/**
 * Constructor is side-effect free; first application IO operation opens SQLite on its IO
 * dispatcher.
 */
internal class LazyBackendStore(private val factory: () -> BackendStore) : BackendStore, Closeable {
    private var instance: BackendStore? = null
    private var closed = false
    val completeCaptures=java.util.concurrent.atomic.AtomicLong()
    @Synchronized fun stateLoadCount()=(instance as? com.greatwall.callring.data.SqliteBackendStore)?.stateLoads?.get()?:0L
    fun scopedReads(coordinator:com.greatwall.callring.application.BusinessRevisionCoordinator):ScopedReadPort = object:ScopedReadPort {
        override val domains=(AiDomain.entries.take(10)+AiDomain.D21).toSet()
        override fun version(domain:AiDomain)=coordinator.capture().values.getValue("core")
        private fun source()=(get() as com.greatwall.callring.data.SqliteBackendStore).scopedReads(coordinator)
        override fun query(spec:QuerySpec,after:String?)=source().query(spec,after)
        override fun get(domain:AiDomain,key:String,projection:String)=source().get(domain,key,projection)
        override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int)=source().text(domain,key,field,start,maxChars)
        override fun aggregate(spec:AggregateSpec)=source().aggregate(spec)
    }

    @Synchronized
    private fun get(): BackendStore {
        if (closed) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
        return instance ?: factory().also { instance = it }
    }

    @Synchronized override fun <T> read(block: (BackendState) -> T): T = get().read(block)
    @Synchronized override fun <T> captureFull(block: (BackendState, List<com.greatwall.callring.domain.AuditRecord>) -> T): T {completeCaptures.incrementAndGet();return get().captureFull(block)}

    @Synchronized
    override fun <T> transaction(block: (BackendState) -> T): T = get().transaction(block)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        (instance as? AutoCloseable)?.close()
        instance = null
    }
}
