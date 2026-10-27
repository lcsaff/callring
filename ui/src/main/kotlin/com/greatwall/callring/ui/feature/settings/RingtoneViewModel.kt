package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.SnapshotViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RingtoneViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<RingtoneSettings>(
    api.changes, setOf(DataDomain.RINGTONE), api.ringtone::getSettings, testScope
) {
    private val playback = Mutex()
    private var visible = false
    private var generation = 0L

    fun enabled(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) }, if (enabled) "规则铃声已开启" else "规则铃声已关闭")
    fun import(source: DocumentRef) = write({ api.ringtone.importAudio(source) }, "铃声已导入")
    fun select(ringtone: BuiltInRingtone) = write({ api.ringtone.restoreBuiltIn(ringtone) }, "已选择内置铃声")
    fun enter() { visible = true }
    fun leave() { visible = false; stop() }
    fun preview() {
        if (!visible) return
        val ticket = generation
        write({ playback.withLock {
            if (visible && ticket == generation) api.ringtone.startPreview() else ApiResult.Success(Unit)
        } }, "正在试听铃声")
    }
    fun stop() {
        generation++
        // Cleanup must outlive the page/ViewModel and follow any in-flight start command.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                playback.withLock { api.ringtone.stopPreview() }
            }
        }
    }
}
