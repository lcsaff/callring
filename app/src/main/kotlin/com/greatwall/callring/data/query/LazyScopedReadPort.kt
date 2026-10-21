package com.greatwall.callring.data.query

import com.greatwall.callring.application.ports.query.*
import com.greatwall.callring.contracts.ai.*

/** Keeps adapter construction lazy while preserving every scoped resource/lifetime hook. */
class LazyScopedReadPort(
    override val domains: Set<AiDomain>,
    private val sourceVersion: (AiDomain) -> Long,
    private val factory: () -> ScopedReadPort,
) : ScopedReadPort {
    private val sourceDelegate = lazy(factory)
    private val source get() = sourceDelegate.value
    override fun version(domain: AiDomain) = sourceVersion(domain)
    override fun reset() { if (sourceDelegate.isInitialized()) sourceDelegate.value.reset() }
    override fun validateRead(domain: AiDomain, rows: List<SourceRow>) = source.validateRead(domain, rows)
    override fun delivered(rows: List<SourceRow>) = source.delivered(rows)
    override fun discard(rows: List<SourceRow>) = source.discard(rows)
    override fun closeCursor(key: String) = source.closeCursor(key)
    override fun scope(domain: AiDomain) = source.scope(domain)
    override fun consistency(domain: AiDomain) = source.consistency(domain)
    override fun query(spec: QuerySpec, after: String?) = source.query(spec, after)
    override fun get(domain: AiDomain, key: String, projection: String) = source.get(domain, key, projection)
    override fun text(domain: AiDomain, key: String, field: String, start: Long, maxChars: Int) = source.text(domain, key, field, start, maxChars)
    override fun aggregate(spec: AggregateSpec) = source.aggregate(spec)
}
