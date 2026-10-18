package com.greatwall.callring.application.api

/** Baselines are the values shown when editing began, never a refreshed snapshot. */
interface UiEditsApi {
    suspend fun profile(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> = aiUnavailable()
    suspend fun quiet(base: QuietSettings, value: QuietSettings): ApiResult<QuietSettings> = aiUnavailable()
    suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings): ApiResult<WorkScheduleSettings> = aiUnavailable()
    suspend fun score(base: ScoreSettings, value: ScoreSettings): ApiResult<ScoreSettings> = aiUnavailable()
    suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings): ApiResult<AppearanceSettings> = aiUnavailable()
    suspend fun position(base: SavePosition?, value: SavePosition): ApiResult<PositionOption> = aiUnavailable()
    suspend fun leave(base: SaveLeave?, value: SaveLeave): ApiResult<LeaveEntry> = aiUnavailable()
}
object UnavailableUiEditsApi : UiEditsApi
