package com.greatwall.callring.application.api

data class RevisionVector(val incarnation: String, val values: Map<String, Long>)
data class CommandContext(val operationId: String, val expectedVersions: RevisionVector, val sessionGeneration: Long, val mayCommit: () -> Boolean = { true }, val expectedScopes:Map<String,Long>?=null, val active: () -> Boolean)
enum class SnapshotDomainStatus { COMPLETE, EMPTY, NOT_CONFIGURED, UNAVAILABLE }
data class SnapshotDomain(val id: String, val status: SnapshotDomainStatus, val itemCount: Long, val sourceVersion: Long, val collectedAt: Long, val sourceScope: String, val digest: String, val data: AiJson)
data class AiBusinessSnapshot(val snapshotId: String, val capturedAt: Long, val zoneId: String, val revision: RevisionVector, val domains: List<SnapshotDomain>, val redactedCount: Int = 0) {
    val schemaVersion: Int = 1
    init { require(domains.size == 21 && domains.all { it.itemCount >= 0 } && domains.map { it.id }.toSet() == (1..21).map { "D%02d".format(it) }.toSet()) { "ai.snapshot_domain_failed" } }
}
interface AiSnapshotApi { suspend fun captureComplete(): ApiResult<AiBusinessSnapshot> }
object UnavailableAiSnapshotApi : AiSnapshotApi { override suspend fun captureComplete() = aiUnavailable() }
