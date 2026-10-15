package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.settings.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback

@Composable internal fun SettingsRoute(
    page: SettingsPage, api: CallringUiApi, summaryModel: SettingsSummaryViewModel,
    signal: Long, onBack: () -> Unit, onCapability: (Capability) -> Unit
) {
    when (page) {
        SettingsPage.QUIET -> {
            val model: QuietViewModel = uiViewModel { QuietViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, refresh = model::refresh)
            QuietScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::save)
        }
        SettingsPage.WORK -> {
            val model: WorkScheduleViewModel = uiViewModel { WorkScheduleViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, refresh = model::refresh)
            WorkScheduleScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::save, model::reset)
        }
        SettingsPage.SCORE -> {
            val model: ScoreSettingsViewModel = uiViewModel { ScoreSettingsViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, refresh = model::refresh)
            ScoreSettingsScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::save, model::reset)
        }
        SettingsPage.APPEARANCE -> {
            val model: AppearanceViewModel = uiViewModel { AppearanceViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, refresh = model::refresh)
            AppearanceScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::save)
        }
        SettingsPage.POSITIONS -> {
            val model: PositionsViewModel = uiViewModel { PositionsViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, refresh = model::refresh)
            PositionsScreen(state, mutation, onBack, model::refresh, model::save, model::delete, model::mine)
        }
        SettingsPage.CALENDAR -> {
            val model: CalendarViewModel = uiViewModel { CalendarViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val import = rememberImportPicker(arrayOf("application/json", "text/plain"), model::import)
            ActiveRefresh(signal, refresh = model::refresh)
            CalendarScreen(state, mutation, onBack, model::refresh, import, model::save, model::delete)
        }
        SettingsPage.LEAVES -> {
            val model: LeavesViewModel = uiViewModel { LeavesViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val summary by summaryModel.state.collectAsStateWithLifecycle()
            ActiveRefresh(signal, intervalMillis = 60_000, refresh = model::refresh)
            val zone = summary.data?.workZoneId
            if (zone == null) ScreenFrame("请假管理", onBack) { LoadFeedback(summary, summaryModel::refresh) }
            else LeavesScreen(state, mutation, zone, onBack, model::refresh, model::save, model::delete)
        }
        SettingsPage.RINGTONE -> {
            val model: RingtoneViewModel = uiViewModel { RingtoneViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val import = rememberImportPicker(arrayOf("audio/*"), model::import)
            ActiveRefresh(signal, refresh = model::refresh)
            RingtoneScreen(state, mutation, onBack, model::refresh, model::enabled, import, model::restore, model::preview, model::stop)
        }
        SettingsPage.RUNTIME -> {
            val model: RuntimeViewModel = uiViewModel { RuntimeViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ActiveRefresh(signal, intervalMillis = 10_000, refresh = model::refresh)
            RuntimeScreen(state, mutation, onBack, model::refresh, onCapability, model::synchronize)
        }
    }
}
