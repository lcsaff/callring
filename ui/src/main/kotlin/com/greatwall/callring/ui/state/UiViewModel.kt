package com.greatwall.callring.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.CoroutineScope

abstract class UiViewModel(testScope: CoroutineScope? = null) : ViewModel() {
    protected val scope = testScope ?: viewModelScope

}
