package com.greatwall.callring.application

import com.greatwall.callring.contracts.settings.*

internal fun changedScopeKeys(before: Map<String, Any?>, after: Map<String, Any?>) =
    (before.keys + after.keys).filterTo(linkedSetOf()) { before[it] != after[it] }

fun appearanceScopeValues(v: AppearanceSettings): Map<String, Any?> =
    mapOf(
        "appearance:selection" to v.selection,
        "appearance:presetId" to v.presetId,
        "appearance:customSeedRgb" to v.customSeedRgb,
        "appearance:colorMode" to v.colorMode,
    )
