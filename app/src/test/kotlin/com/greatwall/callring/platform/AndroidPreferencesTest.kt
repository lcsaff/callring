package com.greatwall.callring.platform

import com.greatwall.callring.data.preferences.AndroidPreferences

import android.content.SharedPreferences
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.application.ports.BackendFailure
import org.junit.Assert.*
import org.junit.Test
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.change.DataDomain

class AndroidPreferencesTest {
    private val default =
        AppearanceSettings(ThemeSelection.PRESET, "provided_by_ui", null, ColorMode.FOLLOW_SYSTEM)

    @Test fun bindingPersistenceDoesNotPublishBusinessChanges() {
        val coordinator = BusinessRevisionCoordinator()
        val adapter = AndroidPreferences(MemoryPreferences(), MemoryPreferences(), default)
        adapter.saveBinding(WidgetBinding(42, "+8610010"))
        adapter.saveBinding(WidgetBinding(42, "+8610010"))
        adapter.removeBinding(99)
        assertEquals(0L, coordinator.marker())
    }

    @Test fun failedBindingCommitRestoresVisibleValueAndPublishesNothing() = kotlinx.coroutines.runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val widgets = MemoryPreferences()
        val adapter = AndroidPreferences(MemoryPreferences(), widgets, default)
        val service = com.greatwall.callring.application.widget.WidgetBindingsService(adapter,
            { WidgetInstallState(emptyList(), com.greatwall.callring.contracts.runtime.AccessState.GRANTED) },
            coordinator, kotlinx.coroutines.Dispatchers.Unconfined, operationActive = { true })
        service.saveBinding(42, "30001")
        widgets.failNext = true
        assertTrue(service.saveBinding(42, "30002") is com.greatwall.callring.contracts.common.ApiResult.Failure)
        assertEquals(WidgetBinding(42, "30001"), adapter.getBinding(42))
        assertEquals(1L, coordinator.marker())
        assertEquals(setOf(DataDomain.WIDGET_BINDINGS), coordinator.invalidations.snapshots.value.domains.keys)
    }

    @Test
    fun appearanceAndWidgetKeysAreIndependentAndPreserveLegacyBinding() {
        val theme = MemoryPreferences()
        val widgets = MemoryPreferences().apply { values["number_42"] = "+8610010" }
        val adapter = AndroidPreferences(theme, widgets, default)
        assertEquals(default, adapter.getAppearance())
        assertEquals(WidgetBinding(42, "+8610010"), adapter.getBinding(42))
        adapter.saveAppearance(default.copy(colorMode = ColorMode.DARK))
        assertEquals(WidgetBinding(42, "+8610010"), adapter.getBinding(42))
        assertFalse(widgets.values.containsKey("color_mode"))
    }

    @Test
    fun failedPreferenceCommitThrowsAndRestoresVisibleValue() {
        val theme = MemoryPreferences()
        val adapter = AndroidPreferences(theme, MemoryPreferences(), default)
        adapter.saveAppearance(default)
        theme.failNext = true
        assertThrows(BackendFailure::class.java) {
            adapter.saveAppearance(default.copy(presetId = "changed"))
        }
        assertEquals(default, adapter.getAppearance())
    }
}

/** Models Android's memory mutation even when Editor.commit reports a disk failure. */
internal class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var failNext = false

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue

    override fun contains(key: String?) = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            var clear = false

            override fun putString(key: String, value: String?) = apply { updates[key] = value }

            override fun putStringSet(key: String, value: MutableSet<String>?) = apply {
                updates[key] = value
            }

            override fun putInt(key: String, value: Int) = apply { updates[key] = value }

            override fun putLong(key: String, value: Long) = apply { updates[key] = value }

            override fun putFloat(key: String, value: Float) = apply { updates[key] = value }

            override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }

            override fun remove(key: String) = apply { updates[key] = null }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                val result = !failNext
                failNext = false
                return result
            }

            override fun apply() {
                commit()
            }
        }
}
