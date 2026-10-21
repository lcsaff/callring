package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.application.recording.model.RecordingSettings

/** Atomic configuration persistence only. Business validation and publication belong to the command. */
interface RecordingSettingsStore {
    fun settings(): RecordingSettings
    fun persistSettings(value: RecordingSettings)
}
