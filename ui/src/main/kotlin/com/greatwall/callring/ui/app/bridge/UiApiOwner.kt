package com.greatwall.callring.ui.app.bridge

import com.greatwall.callring.contracts.CallringUiApi

/** The Application composition root supplies the single production API instance. */
interface UiApiOwner {
    val uiApi: CallringUiApi
    /** null seals UI entry while persistent restore is switching owners. */
    val availableUiApi: kotlinx.coroutines.flow.StateFlow<CallringUiApi?>
    suspend fun reobservePlatform()
}
