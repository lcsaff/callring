package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope

/** Appearance choices apply as they change; sliders coalesce storage writes. */
class AppearanceViewModel(
    api: CallringUiApi,
    @Suppress("UNUSED_PARAMETER") savedState: SavedStateHandle,
    testScope: CoroutineScope? = null,
) : ImmediateEditor<AppearanceSettings>(
    api.changes, setOf(DataDomain.APPEARANCE), api.settings::getAppearance, api.edits::appearance, testScope
) {
    /** Sliders update the application theme on every tick and coalesce storage writes. */
    fun editColor(value: AppearanceSettings) = editCoalesced(value, debounceMillis = 250)
}
