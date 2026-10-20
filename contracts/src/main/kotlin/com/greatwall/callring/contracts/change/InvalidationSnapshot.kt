package com.greatwall.callring.contracts.change

import java.util.Collections

/** Domain-local keys: a caller number never aliases a widget or archive identity. */
class DomainInvalidation(lastAnyRevision: Long = 0, wideRevision: Long = 0, entityRevisions: Map<String, Long> = emptyMap()) {
    val lastAnyRevision = lastAnyRevision
    val wideRevision = wideRevision
    val entityRevisions: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(entityRevisions))
    fun token(entity: String? = null): Long = if (entity == null) lastAnyRevision else maxOf(wideRevision, entityRevisions[entity] ?: 0)
}

class InvalidationSnapshot(val incarnation: String, val epoch: Long, val revision: Long, domains: Map<DataDomain, DomainInvalidation>) {
    val domains: Map<DataDomain, DomainInvalidation> = Collections.unmodifiableMap(LinkedHashMap(domains))
    fun token(subscription: ChangeSubscription) = InvalidationToken(incarnation, epoch,
        Collections.unmodifiableMap(subscription.domains.associateWith { domains[it]?.token(subscription.entities[it]) ?: 0 }))
}

class ChangeSubscription(domains: Set<DataDomain>, entities: Map<DataDomain, String> = emptyMap()) {
    val domains: Set<DataDomain> = Collections.unmodifiableSet(LinkedHashSet(domains))
    val entities: Map<DataDomain, String> = Collections.unmodifiableMap(LinkedHashMap(entities))
}

data class InvalidationToken(val incarnation: String, val epoch: Long, val revisions: Map<DataDomain, Long>)
