package com.greatwall.callring.application.recording

import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.ai.AiSlot

internal fun recordingSettingsScopeValues(v: RecordingSettings): Map<String, Any?> = buildMap {
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
