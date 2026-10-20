package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Every IO lease includes the callback, cursor consumption and transaction completion. */
internal class StorageResources(private val factory: () -> CoreStorageResource) : BackendStore, CoreUnitOfWork, Closeable {
    private var instance: CoreStorageResource? = null
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    val completeCaptures = AtomicLong()
    private fun ensureOpen() { if (closing.get()) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed")) }
    private fun <T> lease(block: (CoreStorageResource) -> T): T {
        ensureOpen()
        return synchronized(this) {
            ensureOpen()
            val resource = instance ?: factory().also { instance = it }
            block(resource)
        }
    }
    @Synchronized fun stateLoadCount(): Long { ensureOpen(); return instance?.stateLoadCount() ?: 0 }
    @Synchronized fun narrowAccessCounts(): Map<String, Long> { ensureOpen(); return instance?.narrowAccessCounts() ?: emptyMap() }
    override fun <T> read(block: (BackendState) -> T): T = lease { it.legacy.read(block) }
    override fun <T> transaction(block: (BackendState) -> T): T = lease { it.legacy.transaction(block) }
    override fun <T> captureFull(block: (BackendState, List<AuditRecord>) -> T): T = lease {
        completeCaptures.incrementAndGet(); it.fullSnapshot.captureFull(block)
    }
    override fun <T> readQuiet(block: (QuietReader) -> T): T = lease { it.sessions.readQuiet(block) }
    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = lease { it.sessions.writeQuiet(block) }
    override fun <T> readSession(block: (CoreReadSession) -> T): T = lease { it.sessions.readSession(block) }
    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = lease { it.sessions.writeSession(block) }
    fun scopedReads(coordinator: BusinessRevisionCoordinator): ScopedReadPort = object : ScopedReadPort {
        override val domains: Set<AiDomain> get() { ensureOpen(); return (AiDomain.entries.take(10) + AiDomain.D21).toSet() }
        override fun version(domain: AiDomain): Long {
            ensureOpen()
            return coordinator.capture().values.getValue("core")
        }
        private fun <T> consistent(domain: AiDomain, block: (ScopedReadPort) -> T): T {
            repeat(3) {
                val before = version(domain)
                val value = lease { block(it.scoped) }
                if (before == coordinator.capture().values.getValue("core")) return value
            }
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
        }
        override fun query(spec: QuerySpec, after: String?) = consistent(spec.domain) { it.query(spec, after) }
        override fun get(domain: AiDomain, key: String, projection: String) = consistent(domain) { it.get(domain, key, projection) }
        override fun text(domain: AiDomain, key: String, field: String, start: Long, maxChars: Int) = consistent(domain) { it.text(domain, key, field, start, maxChars) }
        override fun aggregate(spec: AggregateSpec) = consistent(spec.domain) { it.aggregate(spec) }
    }
    override fun close() {
        closing.set(true)
        synchronized(this) {
            try { instance?.close() } finally { instance = null }
        }
    }
}
