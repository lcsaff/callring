package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Owner must prepare the formal number rule before construction; check never performs storage reads. */
class NumberLookupViewModel(
    private val check: (String) -> NumberCheck,
    private val savedState: SavedStateHandle,
    changes: ChangeFeed,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    val text = savedState.getStateFlow("number", "")
    private val resultMutable = MutableStateFlow(check(text.value))
    val result = resultMutable.asStateFlow()
    init {
        scope.launch { text.collect { resultMutable.value = check(it) } }
        ChangeObserver(changes, ChangeSubscription(setOf(DataDomain.NUMBER_RULES))).start(scope) {
            resultMutable.value = check(text.value)
        }
    }
    fun edit(value: String) { savedState["number"] = value; resultMutable.value = check(value) }
    fun checkedNumber(): String? = result.value.takeIf { it.validProfileNumber }?.normalized
}
