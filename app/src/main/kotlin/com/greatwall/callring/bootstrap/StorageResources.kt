package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Every IO lease includes the callback, cursor consumption and transaction completion. */
internal class StorageResources(private val factory: () -> CoreStorageResource) :
    CoreUnitOfWork, FullSnapshotReader, Closeable {
    private var instance: CoreStorageResource? = null
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    val completeCaptures = AtomicLong()
    val coreOpens = AtomicLong()

    private fun ensureOpen() {
        if (closing.get())
            throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
    }

    private fun <T> lease(block: (CoreStorageResource) -> T): T {
        ensureOpen()
        return synchronized(this) {
            ensureOpen()
            val resource =
                instance
                    ?: factory().also {
                        instance = it
                        coreOpens.incrementAndGet()
                    }
            block(resource)
        }
    }

    @Synchronized
    fun narrowAccessCounts(): Map<String, Long> {
        ensureOpen()
        return instance?.narrowAccessCounts() ?: emptyMap()
    }

    override fun capture(): FullSnapshotFacts = lease {
        completeCaptures.incrementAndGet()
        it.fullSnapshot.capture()
    }

    override fun <T> readQuiet(block: (QuietReader) -> T): T = lease {
        it.sessions.readQuiet(block)
    }

    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = lease {
        it.sessions.writeQuiet(block)
    }

    override fun <T> readSession(block: (CoreReadSession) -> T): T = lease {
        it.sessions.readSession(block)
    }

    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = lease {
        it.sessions.writeSession(block)
    }

    fun scopedReads(coordinator: BusinessRevisionCoordinator): ScopedReadPort =
        object : ScopedReadPort {
            override val domains: Set<AiDomain>
                get() {
                    ensureOpen()
                    return coreQueryDomains
                }

            override fun version(domain: AiDomain): Long {
                ensureOpen()
                return coordinator.sourceVersion(domain)
            }

            override fun consistency(domain: AiDomain) =
                if (domain in setOf(AiDomain.D05, AiDomain.D06, AiDomain.D09)) "live_probe"
                else "source_snapshot"

            private fun <T> consistent(domain: AiDomain, block: (ScopedReadPort) -> T): T {
                repeat(3) {
                    val before = version(domain)
                    val value = lease { block(it.scoped) }
                    if (before == coordinator.sourceVersion(domain)) return value
                }
                throw BackendFailure(
                    ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true),
                )
            }

            override fun query(spec: QuerySpec, after: String?) =
                consistent(spec.domain) { it.query(spec, after) }

            override fun get(domain: AiDomain, key: String, projection: String) =
                consistent(domain) { it.get(domain, key, projection) }

            override fun text(
                domain: AiDomain,
                key: String,
                field: String,
                start: Long,
                maxChars: Int,
            ) = consistent(domain) { it.text(domain, key, field, start, maxChars) }

            override fun aggregate(spec: AggregateSpec) =
                consistent(spec.domain) { it.aggregate(spec) }
        }

    override fun close() {
        closing.set(true)
        synchronized(this) {
            try {
                instance?.close()
            } finally {
                instance = null
            }
        }
    }
}
