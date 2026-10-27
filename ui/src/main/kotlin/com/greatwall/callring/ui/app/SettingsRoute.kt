package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.settings.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.feature.update.AppUpdateScreen
import com.greatwall.callring.ui.feature.update.AppUpdateViewModel

@Composable internal fun SettingsRoute(
    page: SettingsPage, api: CallringUiApi, summaryModel: SettingsSummaryViewModel, appearanceModel: AppearanceViewModel,
    signal: Long, onBack: () -> Unit, onCapability: (Capability) -> Unit, onAppSettings: () -> Unit,
    onTestOverlay: () -> Unit = {}, startupUpdateFinished: Boolean = false,
) {
    when (page) {
        SettingsPage.NUMBER_RULE -> {
            val model: NumberRuleViewModel = uiViewModel { NumberRuleViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val errors by model.errors.collectAsStateWithLifecycle()
            val conflict by model.conflict.collectAsStateWithLifecycle()
            val trial by model.trial.collectAsStateWithLifecycle()
            val check by model.trialCheck.collectAsStateWithLifecycle()
            NumberRuleScreen(state, draft, mutation, errors, conflict, trial, check,
                onBack, model::refresh, model::editPrefix, model::editLength, model::editTrial,
                { model.editTrial(trial) }, model::reload, model::addRule, model::removeRule)
        }
        SettingsPage.RECORDING -> RecordingSettingsRoute(api, onBack)
        SettingsPage.AI -> AiSettingsRoute(api, signal, onBack)
        SettingsPage.QUIET -> {
            val model: QuietViewModel = uiViewModel { QuietViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            QuietScreen(state, draft, mutation, onBack, model::refresh, model::edit)
        }
        SettingsPage.WORK -> {
            val model: WorkScheduleViewModel = uiViewModel { WorkScheduleViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            WorkScheduleScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::reset)
        }
        SettingsPage.SCORE -> {
            val model: ScoreSettingsViewModel = uiViewModel { ScoreSettingsViewModel(api, it) }
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            ScoreSettingsScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::reset)
        }
        SettingsPage.APPEARANCE -> {
            val model = appearanceModel
            val state by model.state.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            AppearanceScreen(state, draft, mutation, onBack, model::refresh, model::edit, model::save,
                onColorChange = model::editColor)
        }
        SettingsPage.POSITIONS -> {
            val model: PositionsViewModel = uiViewModel { PositionsViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            PositionsScreen(state, mutation, onBack, model::refresh, model::save, model::delete, model::mine, onSaveDraft = model::saveDraft)
        }
        SettingsPage.MY_POSITION -> {
            val model: PositionsViewModel = uiViewModel { PositionsViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            MyPositionScreen(state, mutation, onBack, model::refresh, model::mine)
        }
        SettingsPage.CALENDAR -> {
            val model: CalendarViewModel = uiViewModel { CalendarViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val summary by summaryModel.state.collectAsStateWithLifecycle()
            val zone = summary.data?.workZoneId?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
            val year = java.time.LocalDate.now(zone).year
            CalendarScreen(state, mutation, onBack, model::refresh, { model.synchronize(year) }, year)
        }
        SettingsPage.LEAVES -> {
            val model: LeavesViewModel = uiViewModel { LeavesViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val summary by summaryModel.state.collectAsStateWithLifecycle()
            TimeRefresh(model.timeRefresh, signal, intervalMillis = 60_000, refresh = model::refresh)
            val zone = summary.data?.workZoneId
            if (zone == null) ScreenFrame("请假管理", onBack) { LoadFeedback(summary, summaryModel::refresh) }
            else LeavesScreen(state, mutation, zone, onBack, model::refresh, model::save, model::delete, onSaveDraft = model::saveDraft)
        }
        SettingsPage.RINGTONE -> {
            val model: RingtoneViewModel = uiViewModel { RingtoneViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            val import = rememberImportPicker(arrayOf("audio/*"), model::import)
            RingtonePreviewLifecycle(model)
            RingtoneScreen(state, mutation, onBack, model::refresh, model::enabled, import, model::select, model::preview, model::stop)
        }
        SettingsPage.RUNTIME -> {
            val model: RuntimeViewModel = uiViewModel { RuntimeViewModel(api) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            RuntimeScreen(state, mutation, onBack, model::refresh, onCapability, model::synchronize, onAppSettings, onTestOverlay)
        }
        SettingsPage.UPDATE -> {
            val model: AppUpdateViewModel = uiViewModel { AppUpdateViewModel(api.update) }
            val state by model.state.collectAsStateWithLifecycle()
            val mutation by model.mutation.collectAsStateWithLifecycle()
            LaunchedEffect(model, startupUpdateFinished) { model.refresh() }
            AppUpdateScreen(state, mutation, onBack, model::refresh,
                model::check, model::download, model::cancel, model::install)
        }
    }
}
