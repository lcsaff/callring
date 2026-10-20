package com.greatwall.callring.contracts.ai

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*

enum class SnapshotDomainStatus { COMPLETE, EMPTY, NOT_CONFIGURED, UNAVAILABLE }

data class SnapshotDomain(val id: String, val status: SnapshotDomainStatus, val itemCount: Long, val sourceVersion: Long, val collectedAt: Long, val sourceScope: String, val digest: String, val data: AiJson)

data class AiBusinessSnapshot(val snapshotId: String, val capturedAt: Long, val zoneId: String, val revision: RevisionVector, val domains: List<SnapshotDomain>, val redactedCount: Int = 0) {
    val schemaVersion: Int = 1
    init { require(domains.size == 21 && domains.all { it.itemCount >= 0 } && domains.map { it.id }.toSet() == (1..21).map { "D%02d".format(it) }.toSet()) { "ai.snapshot_domain_failed" } }
}

interface AiSnapshotApi { suspend fun captureComplete(): ApiResult<AiBusinessSnapshot> }
