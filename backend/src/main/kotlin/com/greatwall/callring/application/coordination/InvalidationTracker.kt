package com.greatwall.callring.application.coordination

import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.flow.*

/** Owned by the coordinator fence; snapshots contain no mutable aliases. */
class InvalidationTracker(private val incarnation: String, private val capacity: Int = 1024) {
    private var epoch = 0L
    private val mutable = MutableStateFlow(InvalidationSnapshot(incarnation, epoch, 0, emptyMap()))
    val snapshots: StateFlow<InvalidationSnapshot> = mutable.asStateFlow()
    @Synchronized fun publish(revision: Long, impact: ChangeImpact) {
        if (impact.domains.isEmpty()) return
        require(revision > mutable.value.revision)
        val domains = mutable.value.domains.toMutableMap()
        impact.domains.forEach { (domain, keys) ->
            val previous = domains[domain] ?: DomainInvalidation()
            var wide = previous.wideRevision
            val entities = previous.entityRevisions.toMutableMap()
            if (keys == null) { wide = revision; entities.clear() }
            else {
                keys.forEach { entities[it] = revision }
                if (entities.size > capacity) { wide = revision; entities.clear() }
            }
            domains[domain] = DomainInvalidation(revision, wide, entities)
        }
        mutable.value = InvalidationSnapshot(incarnation, epoch, revision, domains)
    }
    @Synchronized fun reset(revision: Long) {
        epoch = Math.addExact(epoch, 1)
        mutable.value = InvalidationSnapshot(incarnation, epoch, revision, emptyMap())
    }
}

class ChangeImpact(domains: Map<DataDomain, Set<String>?>) {
    val domains = java.util.Collections.unmodifiableMap(domains.mapValues { (_, keys) -> keys?.let { java.util.Collections.unmodifiableSet(LinkedHashSet(it)) } })
    companion object {
        fun wide(domains: Set<DataDomain>) = ChangeImpact(domains.associateWith { null })
    }
}
