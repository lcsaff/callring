package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.frozenList
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.PositionRecord
import kotlinx.coroutines.*

internal class PositionService(private val transactions: PositionTransactions,
    private val coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun get() = runner.io { requireOperationActive(operationActive); transactions.readPositions { settings(it) } }
    suspend fun save(value: SavePosition): ApiResult<PositionOption> {
        currentCoroutineContext().ensureActive()
        if (value.name.trim().isEmpty()) return ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "name.required", mapOf("name" to "name.required")))
        if (value.level <= 0) return ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "level.invalid", mapOf("level" to "level.invalid")))
        return saveMerged(null, value, false)
    }
    suspend fun edit(base: SavePosition?, value: SavePosition) = saveMerged(base, value, true)
    private suspend fun saveMerged(base: SavePosition?, value: SavePosition, draft: Boolean) = runner.write { requireOperationActive(operationActive);
        committer.commit(command, {
            transactions.writePositions { writer ->
                if (draft && base == null && value.id != null) settingFailure(ErrorCode.VALIDATION, "draft.baseline_required", "id")
                if (draft && base != null && base.id != value.id) settingFailure(ErrorCode.VALIDATION, "draft.identity_changed", "id")
                val old = value.id?.let { writer.position(it) }
                if (draft && base != null && old == null) settingFailure(ErrorCode.CONFLICT, "draft.deleted", "id")
                val desired = if (base != null && old != null) value.copy(
                    name = mergeSetting("name", base.name, value.name, old.name),
                    level = mergeSetting("level", base.level, value.level, old.level),
                    active = mergeSetting("active", base.active, value.active, old.active)) else value
                val name = desired.name.trim()
                if (name.isEmpty()) settingFailure(ErrorCode.VALIDATION, "name.required", "name")
                if (desired.level <= 0) settingFailure(ErrorCode.VALIDATION, "level.invalid", "level")
                if (desired.id != null && requireNotNull(desired.id) <= 0) settingFailure(ErrorCode.VALIDATION, "id.invalid", "id")
                if (desired.id != null && old == null) settingFailure(ErrorCode.NOT_FOUND, "position.not_found")
                if (writer.positionNameExists(name, desired.id)) settingFailure(ErrorCode.VALIDATION, "position.name.duplicate", "name")
                val record = PositionRecord(desired.id ?: 0, name, desired.level, desired.active)
                val mine = writer.myPositionId()
                val saved = if (old == record) record else writer.putPosition(record, old == null)
                val myRecord = if (mine == saved.id) saved else mine?.let(writer::position)
                val keys = if (old == saved) emptySet() else buildSet {
                    add("positions"); add("position:${saved.id}")
                    if (old?.active != saved.active || old.level != saved.level) add("position:${saved.id}:valid")
                }
                val numbers = if (keys.isEmpty()) emptySet() else writer.positionNumbers(saved.id)
                MutationDelta(positionOption(saved, myRecord), keys, impact(numbers, mine == saved.id))
            }
        }) {}
    }
    suspend fun delete(id: Long) = runner.write { requireOperationActive(operationActive);
        committer.commit(command, {
            transactions.writePositions { writer ->
                if (writer.position(id) == null) settingFailure(ErrorCode.NOT_FOUND, "position.not_found")
                if (writer.myPositionId() == id || writer.positionReferenced(id)) settingFailure(ErrorCode.IN_USE, "position.in_use")
                writer.deletePosition(id)
                MutationDelta(Unit, setOf("positions", "position:$id", "position:$id:valid"), ChangeImpact.wide(setOf(DataDomain.POSITIONS)))
            }
        }) {}
    }
    suspend fun mine(id: Long?) = runner.write { requireOperationActive(operationActive);
        committer.commit(command, {
            transactions.writePositions { writer ->
                val old = writer.myPositionId()
                if (id != null) {
                    val target = writer.position(id) ?: settingFailure(ErrorCode.VALIDATION, "position.not_found", "positionId")
                    if (!target.active && old != id) settingFailure(ErrorCode.VALIDATION, "position.inactive", "positionId")
                }
                if (old != id) writer.saveMyPosition(id)
                MutationDelta(settings(writer), if (old == id) emptySet() else setOf("myPosition"), impact(emptySet(), true))
            }
        }) {}
    }
    private fun settings(reader: PositionReader): PositionSettings {
        val id = reader.myPositionId()
        val positions = reader.positions()
        val mine = positions.firstOrNull { it.id == id }
        return PositionSettings(frozenList(positions.sortedWith(compareBy<PositionRecord> { it.level }.thenBy { it.name }.thenBy { it.id }).map { positionOption(it, mine) }), id)
    }
    private fun impact(numbers: Set<String>, mine: Boolean) = ChangeImpact(buildMap {
        put(DataDomain.POSITIONS, null)
        if (mine || numbers.isNotEmpty()) {
            put(DataDomain.CALLERS, if (mine) null else numbers)
            put(DataDomain.REPORTS, if (mine) null else numbers)
        }
    })
}

internal fun positionOption(value: PositionRecord, mine: PositionRecord?): PositionOption = PositionOption(
    value.id, value.name, value.level, value.active, when {
        mine == null -> PositionRelation.UNKNOWN
        value.level < mine.level -> PositionRelation.HIGHER
        value.level > mine.level -> PositionRelation.LOWER
        else -> PositionRelation.SAME
    })
