package com.greatwall.callring.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class UiViewModel(testScope: CoroutineScope? = null) : ViewModel() {
    protected val scope = testScope ?: viewModelScope

    protected fun observeChanges(changes: ChangeFeed, domains: Set<DataDomain>, number: String? = null, refresh: () -> Unit) {
        scope.launch { changes.observe().collect { if (it.affects(domains, number)) refresh() } }
    }
}
