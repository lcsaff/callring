package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.ports.store.CallerTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.ports.store.CallerProfileReader
import com.greatwall.callring.application.recording.policy.validatedCallerCandidates
import com.greatwall.callring.domain.model.NumberIdentity
import java.security.MessageDigest
import com.greatwall.callring.domain.calling.CallerEnrichmentPolicy
import com.greatwall.callring.domain.model.IdentityFieldMetadata
import com.greatwall.callring.domain.model.ProfileRecord
import java.util.UUID

/** Caller identity writes for automatic enrichment and local confirmation. */
class CallerEnrichmentService(
    private val transactions: CallerTransactions,
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
) : com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment {
    private val fields = setOf("name", "department", "selfReportedPosition", "region")

    private val tokenOwner = UUID.randomUUID().toString()

    override fun hasConfirmation(archive: CallArchive): Boolean {
        val version = archive.manualAnalysisResultVersion ?: return false
        return transactions.readCaller { it.callerConfirmation(archive.callId, version) != null }
    }

    override fun suggestions(archive: CallArchive): CallerSuggestions? =
        transactions.readCaller { reader -> preview(reader, archive) }

    override fun confirm(
        archive: CallArchive, command: ConfirmCallerSuggestions, checkActive: () -> Unit,
    ): CallerSuggestionConfirmation = coordinator.guard {
        check(operationActive()) { "backend.closed" }
        val version = archive.manualAnalysisResultVersion ?: return@guard CallerSuggestionConfirmation.Conflict(null)
        val receipt = digest(listOf(command.token) + command.fields.sortedBy { it.ordinal }.map { it.name })
        val changed = linkedSetOf<String>()
        var changedNumber = ""
        val result = transactions.writeCaller { writer ->
            checkActive()
            val previous = writer.callerConfirmation(archive.callId, version)
            if (previous != null) return@writeCaller if (previous == receipt) CallerSuggestionConfirmation.Applied
                else CallerSuggestionConfirmation.Conflict(null)
            val latest = preview(writer, archive)
            if (latest == null || command.token != latest.token || command.fields.isEmpty() ||
                !latest.fields.map { it.field }.containsAll(command.fields))
                return@writeCaller CallerSuggestionConfirmation.Conflict(latest)

            val old = writer.caller(latest.number)
            var saved = old ?: ProfileRecord(latest.number)
            val metadata = saved.identityMetadata.toMutableMap()
            latest.fields.filter { it.field in command.fields }.forEach { suggestion ->
                val field = suggestion.field.storageKey()
                val revision = metadata[field]?.revision ?: 0
                metadata[field] = IdentityFieldMetadata(Math.addExact(revision, 1), false, archive.callId, version, suggestion.evidence)
                saved = saved.withIdentityField(suggestion.field, suggestion.suggestedValue)
                changed += field
            }
            if (old == null) saved = saved.copy(identityEpoch = UUID.randomUUID().toString())
            check(operationActive()) { "backend.closed" }
            checkActive()
            writer.putCaller(saved.copy(identityMetadata = metadata), old == null)
            writer.saveCallerConfirmation(archive.callId, version, receipt)
            changedNumber = latest.number
            CallerSuggestionConfirmation.Applied
        }
        if (changed.isNotEmpty()) publish(changedNumber, changed)
        result
    }

    private fun preview(reader: CallerProfileReader, archive: CallArchive): CallerSuggestions? {
        val version = archive.manualAnalysisResultVersion ?: return null
        if (archive.manualAnalysisState != ManualAnalysisState.PENDING_CONFIRMATION ||
            reader.callerConfirmation(archive.callId, version) != null) return null
        val identity = NumberIdentity.parse(archive.number)
        if (!identity.validProfileNumber) return null
        val number = identity.normalized
        val deletedAt = reader.callerDeletedAt(number)
        if (deletedAt != null && archive.startedAtEpochMillis <= deletedAt) return null
        val profile = reader.caller(number)
        val candidates = archive.validatedCallerCandidates()
        val proposed = CallerSuggestionField.entries.mapNotNull { field ->
            val (value, evidence) = candidates[field.storageKey()] ?: return@mapNotNull null
            CallerSuggestion(field, profile?.identityField(field).orEmpty(), value, evidence)
        }
        val snapshot = mutableListOf(tokenOwner, archive.callId.toString(), number, version.toString(),
            deletedAt.toString(), (profile != null).toString(), profile?.identityEpoch.orEmpty())
        proposed.forEach {
            snapshot += listOf(it.field.name, it.currentValue, it.suggestedValue, it.evidence,
                (profile?.identityMetadata?.get(it.field.storageKey())?.revision ?: 0).toString())
        }
        return CallerSuggestions(archive.callId, number, version, digest(snapshot), proposed)
    }

    private fun digest(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun CallerSuggestionField.storageKey() = when (this) {
        CallerSuggestionField.NAME -> "name"
        CallerSuggestionField.DEPARTMENT -> "department"
        CallerSuggestionField.POSITION -> "selfReportedPosition"
        CallerSuggestionField.REGION -> "region"
    }

    private fun ProfileRecord.identityField(field: CallerSuggestionField) = when (field) {
        CallerSuggestionField.NAME -> name
        CallerSuggestionField.DEPARTMENT -> department
        CallerSuggestionField.POSITION -> selfReportedPosition
        CallerSuggestionField.REGION -> region
    }

    private fun ProfileRecord.withIdentityField(field: CallerSuggestionField, value: String) = when (field) {
        CallerSuggestionField.NAME -> copy(name = value)
        CallerSuggestionField.DEPARTMENT -> copy(department = value)
        CallerSuggestionField.POSITION -> copy(selfReportedPosition = value)
        CallerSuggestionField.REGION -> copy(region = value)
    }

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

    private fun publish(number: String, changed: Set<String> = fields) = coordinator.publishCore(
        setOf("profiles", "profile:$number", "profile:$number:identityMetadata") + changed.map { "profile:$number:$it" },
        ChangeImpact(mapOf(DataDomain.CALLERS to setOf(number), DataDomain.REPORTS to setOf(number))))
}
