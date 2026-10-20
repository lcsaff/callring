package com.greatwall.callring.contracts.command

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*

interface UiEditsApi {
    suspend fun profile(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile>
    suspend fun quiet(base: QuietSettings, value: QuietSettings): ApiResult<QuietSettings>
    suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings): ApiResult<WorkScheduleSettings>
    suspend fun score(base: ScoreSettings, value: ScoreSettings): ApiResult<ScoreSettings>
    suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings): ApiResult<AppearanceSettings>
    suspend fun position(base: SavePosition?, value: SavePosition): ApiResult<PositionOption>
    suspend fun leave(base: SaveLeave?, value: SaveLeave): ApiResult<LeaveEntry>
}
