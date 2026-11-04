package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.ports.store.CallerTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.recording.CallerIdentityBaseline
import com.greatwall.callring.domain.calling.CallerEnrichmentPolicy
import com.greatwall.callring.domain.model.IdentityFieldMetadata
import com.greatwall.callring.domain.model.ProfileRecord
import java.util.UUID

/** Local background enrichment only; no general AI command authority. */
class CallerEnrichmentService(
    private val transactions: CallerTransactions,
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
) : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    private val fields = setOf("name", "department", "selfReportedPosition", "region")

    override fun capture(number: String, recordedAt: Long): CallerIdentityBaseline = coordinator.guard(null) {
        check(operationActive()) { "backend.closed" }
        val identity = com.greatwall.callring.domain.model.NumberIdentity.parse(number)
        if (!identity.validProfileNumber) return@guard CallerIdentityBaseline("", emptyMap(), eligible = false)
        val normalized = identity.normalized
        var changed = false
        val baseline = transactions.writeCaller { writer ->
            // A retained historical recording must never recreate or enrich a deleted profile.
            // Persisted cutoff survives restart/backup; future calls may establish a fresh profile.
            val deletedAt = writer.callerDeletedAt(normalized)
            if (deletedAt != null && recordedAt <= deletedAt)
                return@writeCaller CallerIdentityBaseline(UUID.randomUUID().toString(),emptyMap())
            val old = writer.caller(normalized)
            val row = old ?: ProfileRecord(normalized)
            val saved = if (row.identityEpoch.isEmpty()) row.copy(identityEpoch = UUID.randomUUID().toString()) else row
            check(operationActive()) { "backend.closed" }
            if (old != saved) { writer.putCaller(saved, old == null); changed = true }
            CallerIdentityBaseline(saved.identityEpoch, fields.associateWith { saved.identityMetadata[it]?.revision ?: 0 })
        }
        if (changed) publish(normalized)
        baseline
    }

    override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long, generation: Long,
              candidates: Map<String, Pair<String, String>>) = coordinator.guard(null) {
        check(operationActive()) { "backend.closed" }
        if (!baseline.eligible) return@guard
        val normalized = callerNumber(number)
        val changed = transactions.writeCaller { writer ->
            val old = writer.caller(normalized) ?: return@writeCaller false
            if (old.identityEpoch != baseline.epoch) return@writeCaller false
            var saved = old
            val metadata = old.identityMetadata.toMutableMap()
            fields.forEach { field ->
                val candidate = candidates[field] ?: return@forEach
                if (candidate.first.length > 500 || candidate.second.length > 4000) return@forEach
                val m = metadata[field] ?: IdentityFieldMetadata()
                val text = when (field) { "name" -> old.name; "department" -> old.department; "region" -> old.region; else -> old.selfReportedPosition }
                val next = CallerEnrichmentPolicy.apply(text, m.disputed, m.revision,
                    baseline.revisions[field] ?: return@forEach, candidate.first, candidate.second) ?: return@forEach
                saved = when (field) { "name" -> saved.copy(name = next); "department" -> saved.copy(department = next); "region" -> saved.copy(region = next); else -> saved.copy(selfReportedPosition = next) }
                metadata[field] = IdentityFieldMetadata(Math.addExact(m.revision, 1), false, archiveId, generation, candidate.second)
            }
            saved = saved.copy(identityMetadata = metadata.toMap())
            check(operationActive()) { "backend.closed" }
            if (saved == old) false else { writer.putCaller(saved, false); true }
        }
        if (changed) publish(normalized)
    }

    private fun publish(number: String) = coordinator.publishCore(
        setOf("profiles", "profile:$number", "profile:$number:name", "profile:$number:department",
            "profile:$number:selfReportedPosition", "profile:$number:region", "profile:$number:identityMetadata"),
        ChangeImpact(mapOf(DataDomain.CALLERS to setOf(number), DataDomain.REPORTS to setOf(number))))
}
