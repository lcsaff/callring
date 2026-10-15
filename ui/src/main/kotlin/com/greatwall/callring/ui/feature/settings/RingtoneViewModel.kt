package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.SnapshotViewModel

class RingtoneViewModel(private val api: CallringUiApi) : SnapshotViewModel<RingtoneSettings>(
    api.changes, setOf(DataDomain.RINGTONE, DataDomain.RUNTIME), api.ringtone::getSettings
) {
    fun enabled(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) }, if (enabled) "规则铃声已开启" else "规则铃声已关闭")
    fun import(source: DocumentRef) = write({ api.ringtone.importAudio(source) }, "铃声已导入")
    fun restore() = write({ api.ringtone.restoreBuiltIn() }, "已恢复内置铃声")
    fun preview() = write({ api.ringtone.startPreview() }, "正在试听铃声")
    fun stop() = write({ api.ringtone.stopPlayback() }, "已停止播放")
}
