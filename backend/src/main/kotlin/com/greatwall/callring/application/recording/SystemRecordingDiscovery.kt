package com.greatwall.callring.application.recording

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.recording.CallArchive
import com.greatwall.callring.contracts.recording.RecordingSource
import com.greatwall.callring.domain.calling.NumberMatcher
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.application.mapping.checkNumber
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.model.sameRecordingNumber
import com.greatwall.callring.domain.recording.*
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Metadata discovery and identity registration; audio/AI remain in the existing workflow. */
class SystemRecordingDiscovery(
    private val store: SystemRecordingImportStore,
    private val reads: RecordingIdentityReads,
    private val settings: () -> RecordingSettings,
    private val settingsRevision: () -> Long,
    private val scanner: SystemRecordingScanPort,
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
    private val activeCallId: () -> Long?,
    private val findCalls: (String, Long) -> List<RecordingCall>,
    private val numberRules: () -> NumberRuleSnapshot,
    private val numberMatcher: NumberMatcher,
    private val outgoingCallCloser: (number: String, recordingStartedAt: Long, incomingStartedAt: Long, cancelled: () -> Boolean) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val importAudio: (RecordingCall, SystemRecordingCandidate, () -> Unit, () -> Boolean, () -> com.greatwall.callring.contracts.recording.RecordingFile?) -> Unit,
) : SystemRecordingDiscoveryRunner, AutoCloseable {
    private val lifetime = Object()
    @Volatile private var sealed = false
    private var running = false

    fun cancel() = scanner.cancelScan()
    fun seal() { synchronized(lifetime) { sealed = true }; cancel() }
    override fun close() {
        seal()
        var interrupted = false
        synchronized(lifetime) { while(running) try { lifetime.wait() } catch(_: InterruptedException) { interrupted = true } }
        if(interrupted) Thread.currentThread().interrupt()
    }
    override fun discover(cancelled: () -> Boolean): Long? {
        synchronized(lifetime) {
            if(sealed || !operationActive()) throw CancellationException("recording.closed")
            if(running) return now() + 1000
            running = true
        }
        try { return run(cancelled) }
        finally { synchronized(lifetime) { running = false; lifetime.notifyAll() } }
    }

    private fun run(cancelled: () -> Boolean): Long? {
        val ruleKeys = setOf("numberRule")
        val (frozen, ruleRevision) = coordinator.guard {
            Triple(settings(), settingsRevision(), numberRules()) to coordinator.lastChange(ruleKeys)
        }
        val (config, revision, rules) = frozen
        fun rulesChanged() = coordinator.lastChange(ruleKeys) != ruleRevision
        val folder = config.systemFolder
        if(!config.recordingEnabled || folder == null) return null
        fun active() {
            if(sealed || cancelled() || !operationActive() || Thread.currentThread().isInterrupted)
                throw CancellationException("recording.cancelled")
            // All production settings commits publish this revision under the same fence;
            // restore seals/replaces the owner. Never decrypt settings for every directory row.
            if(settingsRevision() != revision || rulesChanged())
                throw CancellationException("recording.configuration_changed")
        }
        fun <T> commit(block: () -> T): T = coordinator.guard { active(); block() }
        // A legacy projection still being built cannot establish the absence of an archive.
        // Reuse its bounded index preparation instead of allocating a duplicate identity.
        // The store owns its monitor while indexing; never acquire the coordinator from its
        // cancellation callback (writers acquire coordinator -> store). This derived work is
        // configuration-independent; commit() rechecks the revision immediately afterwards.
        if(!store.prepareSystemMatches { sealed || cancelled() || !operationActive() }) return now() + 1000
        fun complete(key: String) = commit {
            if(!store.sourceCompleted(key)) {
                store.completeSystemSource(key)
                coordinator.publishResource("recording",setOf("recordingSources"),ChangeImpact(emptyMap()))
            }
        }
        val zoneId = zone()
        val signature = folder.length.toString() + ":" + folder + ":" + zoneId.id + ":" + revision + ":rules:" + rules.revision + ":" + ruleRevision + ":history:3"
        val scan = commit { store.beginSystemScan(signature, now(), zoneId.id) }
        fun matchingCalls(parsed: ParsedSystemRecording) =
            findCalls(parsed.number,parsed.startedAtEpochMillis).map { reads.linkedRecording(it.id) ?: it }.distinctBy { it.id }
        fun targets(parsed: ParsedSystemRecording): List<RecordingCall> {
            val all = (store.matchingRecordingEvents(parsed) + findCalls(parsed.number,parsed.startedAtEpochMillis))
                .map { reads.linkedRecording(it.id) ?: it }.distinctBy { it.id }
            // Apply close-match priority across both stores together. A broader match to
            // an earlier archive must not obscure this call's exact system-log identity.
            return all.filter { kotlin.math.abs(it.startedAt-parsed.startedAtEpochMillis) <= SystemRecordingAssociation.CLOCK_SKEW_MILLIS }.ifEmpty { all }
        }
        fun outgoingConflict(number: String, recordedAt: Long, incomingAt: Long) =
            outgoingCallCloser(number,recordedAt,incomingAt) { sealed || cancelled() || !operationActive() || settingsRevision() != revision || rulesChanged() }
        fun blockedByOutgoing(parsed: ParsedSystemRecording, target: RecordingCall): Boolean {
            // Keep the original core timestamp for the comparison: an independent archive's
            // canonical start may be the recording start itself and would erase the delay.
            if (target.direction == com.greatwall.callring.domain.model.CallDirection.OUTGOING) return false
            val original = findCalls(parsed.number,parsed.startedAtEpochMillis).singleOrNull() ?: return false
            val canonical = reads.linkedRecording(original.id) ?: original
            return canonical.id == target.id && outgoingConflict(original.number,parsed.startedAtEpochMillis,original.startedAt)
        }
        fun releaseReservation(key: String, callId: Long) = commit {
            if(store.releaseUnimportedSystemSource(key,callId))
                coordinator.publishResource("recording",setOf("recordingSources"),ChangeImpact(emptyMap()))
        }
        var next: Long? = null
        fun due(at: Long) { next = next?.let { minOf(it, at) } ?: at }

        // Preserve canonical associations for legacy independent archives.
        var linkedAfter: String? = null
        while(true) {
            active()
            val page = store.systemSourcePage(linkedAfter,64)
            if(page.isEmpty()) break
            for((key, call) in page) {
                active(); linkedAfter = key
                if(!checkNumber(call.number,rules,numberMatcher).matchesRule) continue
                val match = findCalls(call.number,call.startedAt).singleOrNull() ?: continue
                if (call.direction != com.greatwall.callring.domain.model.CallDirection.UNKNOWN && call.direction != match.direction &&
                    (reads.linkedRecording(match.id)?.id ?: match.id) != call.id) continue
                if(store.hasConflictingSystemLink(key,call,match) || (match.direction == com.greatwall.callring.domain.model.CallDirection.INCOMING && outgoingConflict(match.number,call.startedAt,match.startedAt))) continue
                commit {
                    if(findCalls(call.number,call.startedAt).singleOrNull() != match) return@commit
                    if(store.hasConflictingSystemLink(key,call,match) || (match.direction == com.greatwall.callring.domain.model.CallDirection.INCOMING && outgoingConflict(match.number,call.startedAt,match.startedAt))) return@commit
                    active()
                    if(store.linkSystemStatistics(key,match)) coordinator.publishResource("recording",setOf("recordingLinks","recordingLink:${match.id}","archive:${call.id}"),
                        ChangeImpact(mapOf(DataDomain.RECORDING_ARCHIVES to setOf(call.id.toString(),"statistics:${match.id}"))))
                }
            }
        }
        try {
            scanner.scan(folder, { sealed || cancelled() || !operationActive() || settingsRevision() != revision || rulesChanged() }) { entry ->
                active()
                val parsed = SystemRecordingNameParser.parse(entry.file.name, ZoneId.of(scan.zone))
                // Observe only a unique currently visible call, across all retained history.
                if(parsed != null && checkNumber(parsed.number,rules,numberMatcher).matchesRule && matchingCalls(parsed).singleOrNull() != null)
                    commit { store.observeSystemFile(scan.id,entry,parsed,now()) }
            }
            commit { store.finishSystemScan(scan.id) }
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: SecurityException) { return null }
        catch(_: java.io.IOException) { return commit { store.systemRetry(now() + 60_000) } }

        var after: String? = null
        while(true) {
            active()
            val candidates = store.systemCandidates(scan.id,after,64)
            if(candidates.isEmpty()) break
            for(candidate in candidates) {
                active()
                val key = candidate.entry.sourceKey
                after = key
                if(store.sourceCompleted(key) || !checkNumber(candidate.parsed.number,rules,numberMatcher).matchesRule) continue
                val historyTarget = matchingCalls(candidate.parsed).singleOrNull() ?: continue
                fun historyStillMatches() = matchingCalls(candidate.parsed).singleOrNull() == historyTarget
                val file = candidate.entry.file
                if((file.size ?: 0) <= 0 || !file.modifiedKnown || file.modifiedAt <= 0 || file.modifiedAt > now()) continue
                val existing = store.sourceBinding(key)
                val possibilities = if(existing != null) listOf(existing) else targets(candidate.parsed)
                if(possibilities.size > 1) continue
                val target = possibilities.singleOrNull() ?: continue
                if(target.id != historyTarget.id) continue
                if(blockedByOutgoing(candidate.parsed,target)) {
                    releaseReservation(key,target.id)
                    continue
                }
                if(store.hasConflictingSystemSource(scan.id,key,candidate.parsed,target)) continue
                if(store.isDeleted(target.id)) continue
                if(candidate.observedAt < candidate.stableSince || candidate.observedAt - candidate.stableSince < STABILITY_MILLIS) {
                    due(maxOf(now() + 1000,candidate.stableSince + STABILITY_MILLIS)); continue
                }
                if(activeCallId() != null) { due(now() + STABILITY_MILLIS); continue }
                val call = commit {
                    // A removed/replaced call must not turn an accepted history-only file
                    // into an independent recording while enumeration or copying is in flight.
                    if(!historyStillMatches()) return@commit null
                    if(blockedByOutgoing(candidate.parsed,target)) return@commit null
                    active()
                    val boundNow = store.sourceBinding(key)
                    val currentTargets = if(boundNow != null) listOf(boundNow) else targets(candidate.parsed)
                    if(currentTargets.size > 1 || currentTargets.singleOrNull() != target || store.hasConflictingSystemSource(scan.id,key,candidate.parsed,target) ||
                        (store.isDeleted(target.id)) || activeCallId() != null) return@commit null
                    val registered = store.registerSystemSource(key,candidate.parsed,target) { value ->
                        CallArchive(value.id,value.number,value.startedAt,generation = now() * 1000, direction = com.greatwall.callring.contracts.callers.CallDirection.valueOf(value.direction.name))
                    }
                    if(existing == null) coordinator.publishResource("recording",
                        buildSet { add("recordingSources"); if(registered.eventInserted) add("recordingEvents"); if(registered.archiveInserted) add("archive:${registered.call.id}") },
                        ChangeImpact(if(registered.eventInserted || registered.archiveInserted) mapOf(DataDomain.RECORDING_ARCHIVES to setOf(registered.call.id.toString())) else emptyMap()))
                    registered.call
                } ?: continue
                if(!sameRecordingNumber(call.number,candidate.parsed.number) || !SystemRecordingAssociation.matches(call.startedAt,candidate.parsed.startedAtEpochMillis)) continue
                val metadata = reads.metadata(call.id) { sealed || cancelled() || !operationActive() }
                if(metadata?.resetPaused==true) continue
                // Restored/missing audio requires explicit same-identity file selection; a new
                // folder grant is not permission to resume the restored processing queue.
                if(metadata != null && !metadata.hasAudio && metadata.nextAttemptAt == Long.MAX_VALUE) continue
                if(metadata?.hasAudio == true && (metadata.source != RecordingSource.MICROPHONE || metadata.status == com.greatwall.callring.contracts.recording.ArchiveStatus.COMPLETE)) {
                    complete(key); continue
                }
                try {
                    fun importActive() {
                        active()
                        if(!historyStillMatches()) throw CancellationException("recording.call_changed")
                        if(blockedByOutgoing(candidate.parsed,call)) throw OutgoingRecordingConflict()
                        active()
                        if(activeCallId() != null) throw CancellationException("recording.call_active")
                    }
                    fun copyActive(): Boolean = try { active(); activeCallId() == null } catch(_: CancellationException) { false }
                    importAudio(call,candidate,::importActive,::copyActive) { scanner.inspect(folder,com.greatwall.callring.contracts.common.DocumentRef(file.uri)) { sealed || cancelled() || !operationActive() || settingsRevision() != revision || rulesChanged() } }
                    complete(key)
                } catch(cancel: CancellationException) {
                    if(cancel is OutgoingRecordingConflict) releaseReservation(key,call.id)
                    throw cancel
                }
                catch(_: SecurityException) { return null }
                catch(_: Exception) { due(now() + 60_000) }
            }
        }
        return commit { store.systemRetry(next) }
    }
    private class OutgoingRecordingConflict : CancellationException("recording.outgoing_conflict")
    companion object { const val STABILITY_MILLIS = 10_000L }
}
