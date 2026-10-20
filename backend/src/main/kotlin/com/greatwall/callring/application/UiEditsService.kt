package com.greatwall.callring.application

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.UiEditsApi
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.application.settings.*

/** Merge only dirty fields, then use the original validator and guarded publication path. */
internal fun createUiEditsApi(
    callerCommands: com.greatwall.callring.application.callers.CallerCommandService,
    quietService: QuietSettingsService,
    workScheduleService: WorkScheduleService,
    scoreService: ScoreSettingsService,
    appearanceService: AppearanceService,
    positionService: PositionService,
    leaveService: LeaveService,
): UiEditsApi =
    object : UiEditsApi {
        override suspend fun profile(base: CallerDraft, value: CallerDraft) =
            callerCommands.edit(base, value)

        override suspend fun quiet(base: QuietSettings, value: QuietSettings) =
            quietService.edit(base, value)

        override suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings) =
            workScheduleService.edit(base, value)

        override suspend fun score(base: ScoreSettings, value: ScoreSettings) =
            scoreService.edit(base, value)

        override suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings) =
            appearanceService.edit(base, value)

        override suspend fun position(base: SavePosition?, value: SavePosition) =
            positionService.edit(base, value)

        override suspend fun leave(base: SaveLeave?, value: SaveLeave) =
            leaveService.edit(base, value)
    }
