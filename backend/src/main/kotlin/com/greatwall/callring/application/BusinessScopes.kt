package com.greatwall.callring.application

import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.contracts.ai.*
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

fun recordingSettingsScopeValues(v: RecordingSettings): Map<String, Any?> = buildMap {
    put("recordingConfig:recordingEnabled", v.recordingEnabled)
    put("recordingConfig:automaticAnalysis", v.automaticAnalysis)
    put("recordingConfig:captureMode", v.captureMode)
    put("recordingConfig:systemFolder", v.systemFolder)
    listOf(AiSlot.ANALYSIS to v.analysis, AiSlot.TRANSCRIPTION to v.transcription).forEach {
        (slot, c) ->
        put("ai:$slot:protocol", c.protocol)
        put("ai:$slot:url", c.url)
        put("ai:$slot:model", c.model)
        put("ai:$slot:key", c.apiKey)
        put("ai:$slot:thinkingMode", c.thinkingMode)
    }
    put("ai:compatibility", v.conversationCompatibility)
    put("ai:budget", v.conversationBudget)
}
