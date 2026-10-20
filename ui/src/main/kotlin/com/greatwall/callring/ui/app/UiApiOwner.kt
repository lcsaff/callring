package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.CallringUiApi

/** The Application composition root supplies the single production API instance. */
interface UiApiOwner { val uiApi: CallringUiApi }
