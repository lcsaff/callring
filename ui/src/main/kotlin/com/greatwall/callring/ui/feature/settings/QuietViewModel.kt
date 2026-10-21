package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class QuietViewModel(api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle, testScope: kotlinx.coroutines.CoroutineScope? = null) : ImmediateEditor<QuietSettings>(
    api.changes, setOf(DataDomain.QUIET_HOURS), api.settings::getQuietHours, api.edits::quiet, testScope
)
