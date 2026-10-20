package com.greatwall.callring.application.settings

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.frozenList
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.SettingsRules
import java.time.*
import kotlinx.coroutines.CoroutineDispatcher

internal class LeaveService(private val transactions: LeaveTransactions, coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher, private val clock: Clock, private val afterCommit: (WorkHistory) -> Unit,
    private val command: CommandContext? = null) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator)
    suspend fun get() = runner.io { transactions.readLeaves { frozenList(it.leaves().map(::entry)) } }
    suspend fun save(value: SaveLeave) = saveMerged(null, value, false)
    suspend fun edit(base: SaveLeave?, value: SaveLeave) = saveMerged(base, value, true)
    private suspend fun saveMerged(base: SaveLeave?, value: SaveLeave, draft: Boolean) = runner.write {
        // Direct validation precedes storage, as it did at the original public boundary.
        if (!draft) bounds(value.period)
        var committed: WorkHistory? = null
        committer.commit(command, {
            transactions.writeLeaves { writer ->
                if (draft && base == null && value.id != null) settingFailure(ErrorCode.VALIDATION, "draft.baseline_required", "id")
                if (draft && base != null && base.id != value.id) settingFailure(ErrorCode.VALIDATION, "draft.identity_changed", "id")
                val old = value.id?.let(writer::leave)
                if (draft && base != null && old == null) settingFailure(ErrorCode.CONFLICT, "draft.deleted", "id")
                val desired = if (base != null && old != null) value.copy(
                    period = mergeSetting("period", base.period, value.period, LeavePeriod.Timed(old.startMillis, old.endMillis)),
                    note = mergeSetting("note", base.note, value.note, old.note)) else value
                val (start,end) = bounds(desired.period)
                if (value.id != null && old == null) settingFailure(ErrorCode.NOT_FOUND, "leave.not_found")
                val record = LeaveRecord(value.id ?: 0, start, end, desired.note.trim())
                if (old == record) MutationDelta(entry(record))
                else {
                    val saved = writer.putLeave(record, old == null)
                    writer.incrementLeaveVersion()
                    writer.appendAudit(AuditRecord("leave", saved.id.toString(), "${saved.startMillis}:${saved.endMillis}:${saved.note}", clock.millis()))
                    correctedDelta(writer, entry(saved), setOf("leaves", "leave:${saved.id}", "audit")) { committed = it }
                }
            }
        }) { committed?.let(afterCommit) }
    }
    suspend fun delete(id: Long) = runner.write {
        var committed: WorkHistory? = null
        committer.commit(command, {
            transactions.writeLeaves { writer ->
                if (writer.leave(id) == null) settingFailure(ErrorCode.NOT_FOUND, "leave.not_found")
                writer.removeLeave(id)
                writer.incrementLeaveVersion()
                writer.appendAudit(AuditRecord("leave-delete", id.toString(), "", clock.millis()))
                correctedDelta(writer, Unit, setOf("leaves", "leave:$id", "audit")) { committed = it }
            }
        }) { committed?.let(afterCommit) }
    }
    private fun bounds(period: LeavePeriod): Pair<Long,Long> {
        val bounds = when(period) {
            is LeavePeriod.Timed -> period.startInclusiveEpochMillis to period.endExclusiveEpochMillis
            is LeavePeriod.AllDay -> {
                requireIssues(SettingsRules.zone(period.zoneId, "period.zoneId"))
                requireIssues(SettingsRules.dateOrder(period.startDate, period.endDateInclusive, "period.endDateInclusive"))
                val zone = ZoneId.of(period.zoneId)
                period.startDate.atStartOfDay(zone).toInstant().toEpochMilli() to period.endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        requireIssues(SettingsRules.leaveBounds(bounds.first,bounds.second))
        return bounds
    }
    private fun requireIssues(issues: List<com.greatwall.callring.domain.model.SettingsIssue>) { issues.firstOrNull()?.let { settingFailure(ErrorCode.VALIDATION,it.message,it.path) } }
    private fun entry(row: LeaveRecord): LeaveEntry {
        val now = clock.millis()
        return LeaveEntry(row.id,row.startMillis,row.endMillis,row.note,when {
        now < row.startMillis -> LeaveState.UPCOMING
        now >= row.endMillis -> LeaveState.FINISHED
        else -> LeaveState.ACTIVE
    })
    }
}
