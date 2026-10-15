package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.SnapshotViewModel

class RingtoneViewModel(private val api: CallringUiApi) : SnapshotViewModel<RingtoneSettings>(
    api.changes, setOf(DataDomain.RINGTONE, DataDomain.RUNTIME), api.ringtone::getSettings
) {
    fun enabled(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) })
    fun import(source: DocumentRef) = write({ api.ringtone.importAudio(source) })
    fun restore() = write({ api.ringtone.restoreBuiltIn() })
    fun preview() = write({ api.ringtone.startPreview() })
    fun stop() = write({ api.ringtone.stopPlayback() })
}
