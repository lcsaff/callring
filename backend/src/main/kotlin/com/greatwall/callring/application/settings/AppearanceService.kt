package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.*
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.AppearanceStore
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.CoroutineDispatcher

/** Preferences have their own resource and publisher; no SQL capability is required. */
internal class AppearanceService(private val preferences: AppearanceStore,
    private val coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    suspend fun get() = runner.io { preferences.getAppearance() }
    suspend fun save(value: AppearanceSettings) = update { current ->
        if (value.customSeedRgb == null) value.copy(customSeedRgb = current.customSeedRgb) else value
    }
    suspend fun edit(base: AppearanceSettings, value: AppearanceSettings) = update { current ->
        // Preserve the draft entry conflict shape from its former premerge.
        fun <T> merge(field: String, before: T, desired: T, actual: T): T {
            if (before == desired) return actual
            if (before != actual) settingFailure(ErrorCode.CONFLICT, "draft.field_changed", field)
            return desired
        }
        AppearanceSettings(merge("selection", base.selection, value.selection, current.selection),
            merge("presetId", base.presetId, value.presetId, current.presetId),
            merge("customSeedRgb", base.customSeedRgb, value.customSeedRgb, current.customSeedRgb),
            merge("colorMode", base.colorMode, value.colorMode, current.colorMode))
    }
    private suspend fun update(desired: (AppearanceSettings) -> AppearanceSettings) = runner.write {
        coordinator.guard(command) {
            requireOperationActive(operationActive)
            val old = preferences.getAppearance()
            val saved = desired(old)
            if (saved.presetId.isBlank()) settingFailure(ErrorCode.VALIDATION, "preset.required", "presetId")
            if (saved.customSeedRgb != null && saved.customSeedRgb !in 0..0xffffff) settingFailure(ErrorCode.VALIDATION, "rgb.invalid", "customSeedRgb")
            if (saved.selection == ThemeSelection.CUSTOM && saved.customSeedRgb == null) settingFailure(ErrorCode.VALIDATION, "rgb.required", "customSeedRgb")
            if (old != saved) {
                preferences.saveAppearance(saved)
                coordinator.publishResource("preferences", changedScopeKeys(appearanceScopeValues(old), appearanceScopeValues(saved)), ChangeImpact.wide(setOf(DataDomain.APPEARANCE)))
            }
            coordinator.record(command)
            saved
        }
    }
}
