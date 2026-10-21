package com.greatwall.callring.data.preferences

import com.greatwall.callring.application.ports.store.PreferenceStore



import android.content.SharedPreferences
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*

import com.greatwall.callring.application.ports.*

internal fun SharedPreferences.Editor.commitOrThrow() {
    if (!commit())
        throw BackendFailure(
            ApiError(ErrorCode.STORAGE_FAILURE, "preferences.write_failed", retryable = true)
        )
}

/**
 * Android mutates the in-memory map before reporting a disk failure; restore touched keys on
 * failure.
 */
internal fun updatePreferences(preferences: SharedPreferences, updates: Map<String, Any?>) =
    synchronized(preferences) {
        val old = preferences.all.filterKeys { it in updates }
        fun edit(values: Map<String, Any?>): SharedPreferences.Editor =
            preferences.edit().apply {
                values.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        else -> error("preferences.unsupported_type")
                    }
                }
            }
        try {
            edit(updates).commitOrThrow()
        } catch (failure: Throwable) {
            try {
                edit(updates.keys.associateWith { old[it] }).commit()
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

class AndroidPreferences(
    private val appearance: SharedPreferences,
    private val widgets: SharedPreferences,
    private val defaultAppearance: AppearanceSettings,
) : PreferenceStore {
    fun scopedBindings():Sequence<WidgetBinding> = widgets.all.entries.asSequence().filter{it.key.startsWith("number_")}.map{entry->
        val id=entry.key.removePrefix("number_").toInt()
        WidgetBinding(id, entry.value as String)
    }
    @Synchronized
    override fun getAppearance(): AppearanceSettings {
        fun <E : Enum<E>> safeEnum(value: String?, entries: Array<E>, fallback: E): E =
            entries.firstOrNull { it.name == value } ?: fallback
        return AppearanceSettings(
            safeEnum(
                appearance.getString("selection", null),
                ThemeSelection.values(),
                defaultAppearance.selection,
            ),
            appearance.getString("preset_id", null) ?: defaultAppearance.presetId,
            if (appearance.contains("custom_seed")) appearance.getInt("custom_seed", 0)
            else defaultAppearance.customSeedRgb,
            safeEnum(
                appearance.getString("color_mode", null),
                ColorMode.values(),
                defaultAppearance.colorMode,
            ),
        )
    }

    @Synchronized
    override fun saveAppearance(settings: AppearanceSettings) {
        updatePreferences(
            appearance,
            mapOf(
                "selection" to settings.selection.name,
                "preset_id" to settings.presetId,
                "color_mode" to settings.colorMode.name,
                "custom_seed" to settings.customSeedRgb,
            ),
        )
    }

    @Synchronized
    override fun getBinding(widgetId: Int): WidgetBinding? =
        widgets.getString("number_$widgetId", null)?.let { WidgetBinding(widgetId, it) }

    @Synchronized override fun saveBinding(binding: WidgetBinding) {
        updatePreferences(widgets, mapOf("number_${binding.widgetId}" to binding.number))
    }

    @Synchronized override fun removeBinding(widgetId: Int) {
        updatePreferences(widgets, mapOf("number_$widgetId" to null))
    }

    @Synchronized override fun bindingsForNumber(number: String): List<WidgetBinding> = widgets.all.entries
        .asSequence().filter { it.key.startsWith("number_") }
        .filter { com.greatwall.callring.domain.model.sameRecordingNumber(it.value as? String ?: throw IllegalStateException("snapshot.binding_invalid"), number) }
        .map { WidgetBinding(it.key.removePrefix("number_").toInt(), it.value as String) }.sortedBy { it.widgetId }.toList()

    @Synchronized override fun listBindings(): List<WidgetBinding> = widgets.all.entries.filter { it.key.startsWith("number_") }.map {
        val id = it.key.removePrefix("number_").toIntOrNull() ?: throw IllegalStateException("snapshot.binding_invalid")
        WidgetBinding(id, it.value as? String ?: throw IllegalStateException("snapshot.binding_invalid"))
    }.sortedBy { it.widgetId }
}
