package com.greatwall.callring.application.coordination

/** Built from transaction-old values and successful writes, never a whole-state comparison. */
data class MutationDelta<T>(val value: T, val conflictKeys: Set<String> = emptySet(),
    val impact: ChangeImpact = ChangeImpact(emptyMap()), val decisionInputsChanged: Boolean = false) {
    val changed: Boolean get() = conflictKeys.isNotEmpty()
}
