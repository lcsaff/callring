package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.greatwall.callring.contracts.callers.NumberCheck

class NumberLookupViewModel(private val check: (String) -> NumberCheck, private val savedState: SavedStateHandle) : ViewModel() {
    val text = savedState.getStateFlow("number", "")
    fun edit(value: String) { savedState["number"] = value }
    fun result(): NumberCheck = check(text.value)
    fun checkedNumber(): String? = result().takeIf { it.validProfileNumber }?.normalized
}
