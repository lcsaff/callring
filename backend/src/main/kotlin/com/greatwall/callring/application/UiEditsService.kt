package com.greatwall.callring.application

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.command.*
import java.util.UUID
import com.greatwall.callring.application.mapping.*

/** Merge only dirty fields, then use the original validator and guarded publication path. */
internal fun createUiEditsApi(context: BackendContext): UiEditsApi = with(context) {
    object : UiEditsApi {
        override suspend fun profile(base: CallerDraft, value: CallerDraft) = edit({
            requireField(base.number.isEmpty() || base.number == value.number, "number", "draft.number_changed")
            val n = number(value.number)
            read { s ->
                val current = s.profiles[n]
                SaveCallerProfile(n, merge("name", base.name, value.name, current?.name.orEmpty()),
                    merge("department", base.department, value.department, current?.department.orEmpty()),
                    merge("positionId", base.positionId, value.positionId, current?.positionId),
                    merge("whitelisted", base.whitelisted, value.whitelisted, current?.whitelisted ?: false),base,profileDirty(base,value))
            }
        },profileDirty(base,value).mapTo(linkedSetOf("profile:${checkNumber(value.number).normalized}:exists")){"profile:${checkNumber(value.number).normalized}:$it"}) { c, v -> createCallersApi(c).saveProfile(v) }

        override suspend fun quiet(base: QuietSettings, value: QuietSettings) = quietService.edit(base, value)

        override suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings) = edit({
            read { s -> currentSchedule(s).toContract().let { current -> SaveWorkSchedule(
                merge("zoneId", base.zoneId, value.zoneId, current.zoneId),
                merge("weekly", base.weekly, value.weekly, current.weekly),
                merge("makeup", base.makeup, value.makeup, current.makeup)) } }
        }) { c, v -> createSettingsApi(c).saveWorkSchedule(v) }

        override suspend fun score(base: ScoreSettings, value: ScoreSettings) = scoreService.edit(base, value)

        override suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings) = appearanceService.edit(base, value)

        override suspend fun position(base: SavePosition?, value: SavePosition) = positionService.edit(base, value)

        override suspend fun leave(base: SaveLeave?, value: SaveLeave) = edit({
            if (base == null) { requireField(value.id == null, "id", "draft.baseline_required"); value }
            else read { s ->
                requireField(base.id == value.id, "id", "draft.identity_changed")
                val current = s.leaves[value.id] ?: fail(ErrorCode.CONFLICT, "draft.deleted", "id")
                value.copy(period = merge("period", base.period, value.period, LeavePeriod.Timed(current.startMillis, current.endMillis)),
                    note = merge("note", base.note, value.note, current.note))
            }
        }) { c, v -> createSettingsApi(c).saveLeave(v) }
    }
}

private fun <T> BackendContext.merge(field: String, base: T, draft: T, current: T): T {
    if (base == draft) return current
    if (base != current) fail(ErrorCode.CONFLICT, "draft.field_changed", field)
    return draft
}

private fun profileDirty(a:CallerDraft,b:CallerDraft)=setOfNotNull(if(a.name!=b.name)"name"else null,if(a.department!=b.department)"department"else null,if(a.positionId!=b.positionId)"positionId"else null,if(a.whitelisted!=b.whitelisted)"whitelisted"else null)
private suspend fun <T, R> BackendContext.edit(prepare: () -> T,keys:Set<String> = setOf("core"), save: suspend (BackendContext, T) -> ApiResult<R>): ApiResult<R> {
    val prepared = io {
        coordinator.guard(commandContext) {
            val value = prepare()
            val command = commandContext ?: CommandContext(UUID.randomUUID().toString(), coordinator.capture(), 0,expectedScopes=coordinator.expect(keys)) { true }
            bound(command) to value
        }
    }
    return when (prepared) {
        is ApiResult.Failure -> prepared
        is ApiResult.Success -> save(prepared.value.first, prepared.value.second)
    }
}
