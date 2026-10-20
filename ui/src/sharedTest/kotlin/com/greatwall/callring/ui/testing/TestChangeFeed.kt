package com.greatwall.callring.ui.testing

import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Cumulative contract fixture; every publication retains earlier domain/entity markers. */
open class TestChangeFeed : ChangeFeed {
    val state = MutableStateFlow(InvalidationSnapshot("test", 0, 0, emptyMap()))
    override fun snapshots() = state
    fun publish(domains: Set<DataDomain>, entities: Map<DataDomain, Set<String>> = emptyMap()) {
        val old = state.value
        val revision = old.revision + 1
        val next = old.domains.toMutableMap()
        domains.forEach { domain ->
            val previous = next[domain] ?: DomainInvalidation()
            val keys = entities[domain]
            if (keys == null) next[domain] = DomainInvalidation(revision, revision)
            else if (keys.isNotEmpty()) {
                val revisions = previous.entityRevisions + keys.associateWith { revision }
                next[domain] = if (revisions.size > 1024) DomainInvalidation(revision, revision)
                    else DomainInvalidation(revision, previous.wideRevision, revisions)
            }
        }
        state.value = InvalidationSnapshot(old.incarnation, old.epoch, revision, next)
    }
    fun entity(domain: DataDomain, vararg keys: String) = publish(setOf(domain), mapOf(domain to keys.toSet()))
    fun reset() { val old = state.value; state.value = InvalidationSnapshot(old.incarnation, old.epoch + 1, old.revision, emptyMap()) }
}
