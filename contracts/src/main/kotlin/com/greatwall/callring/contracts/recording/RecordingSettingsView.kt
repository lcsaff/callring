package com.greatwall.callring.contracts.recording

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*

data class RecordingFolderView(val displayName: String?, val access: AccessState)

data class RecordingSettingsView(val recordingEnabled: Boolean, val automaticAnalysis: Boolean, val captureMode: CaptureMode, val folderConfigured: Boolean, val revision: Long, val folderDisplayName: String? = null, val folderAccess: AccessState = if(folderConfigured) AccessState.UNKNOWN else AccessState.NOT_REQUIRED)

data class RecordingSettingsPatch(val recordingEnabled: PatchField<Boolean> = PatchField.Keep, val automaticAnalysis: PatchField<Boolean> = PatchField.Keep, val captureMode: PatchField<CaptureMode> = PatchField.Keep)
