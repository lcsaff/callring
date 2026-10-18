package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import java.util.UUID

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
                    merge("whitelisted", base.whitelisted, value.whitelisted, current?.whitelisted ?: false))
            }
        }) { c, v -> createCallersApi(c).saveProfile(v) }

        override suspend fun quiet(base: QuietSettings, value: QuietSettings) = edit({
            read { s -> s.quiet.let { current -> QuietSettings(
                merge("mode", base.mode, value.mode, current.mode),
                merge("allNumbers", base.allNumbers, value.allNumbers, current.allNumbers),
                merge("weeklyWindows", base.weeklyWindows, value.weeklyWindows, current.weeklyWindows)) } }
        }) { c, v -> createSettingsApi(c).saveQuietHours(v) }

        override suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings) = edit({
            read { s -> currentSchedule(s).let { current -> SaveWorkSchedule(
                merge("zoneId", base.zoneId, value.zoneId, current.zoneId),
                merge("weekly", base.weekly, value.weekly, current.weekly),
                merge("makeup", base.makeup, value.makeup, current.makeup)) } }
        }) { c, v -> createSettingsApi(c).saveWorkSchedule(v) }

        override suspend fun score(base: ScoreSettings, value: ScoreSettings) = edit({
            read { s -> s.score.let { current -> ScoreSettings(
                merge("offHoursWeight", base.offHoursWeight, value.offHoursWeight, current.offHoursWeight),
                merge("repeatWeight", base.repeatWeight, value.repeatWeight, current.repeatWeight),
                merge("repeatMinutes", base.repeatMinutes, value.repeatMinutes, current.repeatMinutes),
                merge("observationDays", base.observationDays, value.observationDays, current.observationDays)) } }
        }) { c, v -> createSettingsApi(c).saveScoreSettings(v) }

        override suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings) = edit({
            preferences.getAppearance().let { current -> AppearanceSettings(
                merge("selection", base.selection, value.selection, current.selection),
                merge("presetId", base.presetId, value.presetId, current.presetId),
                merge("customSeedRgb", base.customSeedRgb, value.customSeedRgb, current.customSeedRgb),
                merge("colorMode", base.colorMode, value.colorMode, current.colorMode)) }
        }) { c, v -> c.io { c.saveAppearanceExact(v, preserveNull = false) } }

        override suspend fun position(base: SavePosition?, value: SavePosition) = edit({
            if (base == null) { requireField(value.id == null, "id", "draft.baseline_required"); value }
            else read { s ->
                requireField(base.id == value.id, "id", "draft.identity_changed")
                val current = s.positions[value.id] ?: fail(ErrorCode.CONFLICT, "draft.deleted", "id")
                value.copy(name = merge("name", base.name, value.name, current.name),
                    level = merge("level", base.level, value.level, current.level),
                    active = merge("active", base.active, value.active, current.active))
            }
        }) { c, v -> createSettingsApi(c).savePosition(v) }

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

private suspend fun <T, R> BackendContext.edit(prepare: () -> T, save: suspend (BackendContext, T) -> ApiResult<R>): ApiResult<R> {
    val prepared = io {
        coordinator.guard(commandContext) {
            val value = prepare()
            val command = commandContext ?: CommandContext(UUID.randomUUID().toString(), coordinator.capture(), 0) { true }
            bound(command) to value
        }
    }
    return when (prepared) {
        is ApiResult.Failure -> prepared
        is ApiResult.Success -> save(prepared.value.first, prepared.value.second)
    }
}
