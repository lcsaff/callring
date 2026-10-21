package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class ScoreSettingsViewModel(private val api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle) : ImmediateEditor<ScoreSettings>(
    api.changes, setOf(DataDomain.SCORE_SETTINGS), api.settings::getScoreSettings, api.edits::score,
    editDebounceMillis = 500L
) {
    fun reset() = submitReset { api.settings.resetScoreSettings() }
}
