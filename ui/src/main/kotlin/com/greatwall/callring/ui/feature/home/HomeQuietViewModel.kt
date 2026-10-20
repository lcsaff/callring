package com.greatwall.callring.ui.feature.home

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.feature.settings.SettingsDraftCodecs
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Home reads and edits the same persisted quiet settings as every other entry point. */
class HomeQuietViewModel(
    private val api: SettingsApi,
    changes: ChangeFeed,
    private val savedState: SavedStateHandle? = null,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private val loader = RequestLoader<QuietSettings>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableOpen = MutableStateFlow(savedState?.get<Boolean>("quiet.open") ?: false)
    val dialogOpen = mutableOpen.asStateFlow()
    private val mutableDraft = MutableStateFlow(savedState?.get<String>("quiet.draft")?.let(SettingsDraftCodecs.quiet::decode))
    val draft = mutableDraft.asStateFlow()

    init { observeChanges(changes, setOf(DataDomain.QUIET_HOURS), refresh = ::refresh) }

    fun refresh() = loader.load { api.getQuietHours() }

    fun setEnabled(enabled: Boolean) {
        if (state.value.data == null || mutableOpen.value) return
        writer.run({ api.setQuietEnabled(enabled) }, "免打扰已更新") { saved ->
            loader.load { ApiResult.Success(saved) }
        }
    }

    fun openEditor() {
        if (mutation.value.busy || mutableOpen.value) return
        val saved = state.value.data ?: return
        replaceDraft(saved.copy(mode = QuietMode.CUSTOM))
        mutableOpen.value = true
        savedState?.set("quiet.open", true)
    }

    fun edit(value: QuietSettings) {
        if (mutableOpen.value && !mutation.value.busy) replaceDraft(value)
    }

    fun closeEditor() {
        if (!mutation.value.busy) dismiss()
    }

    fun save() {
        val value = mutableDraft.value ?: return
        if (!mutableOpen.value || !validQuietDraft(value)) return
        writer.run({ api.saveQuietHours(value) }, "免打扰已保存") { saved ->
            loader.load { ApiResult.Success(saved) }
            dismiss()
        }
    }

    private fun replaceDraft(value: QuietSettings) {
        mutableDraft.value = value
        savedState?.set("quiet.draft", SettingsDraftCodecs.quiet.encode(value))
    }

    private fun dismiss() {
        mutableOpen.value = false
        mutableDraft.value = null
        savedState?.set("quiet.open", false)
        savedState?.remove<String>("quiet.draft")
    }
}

internal fun validQuietDraft(value: QuietSettings): Boolean = value.mode != QuietMode.OFF &&
    (value.mode != QuietMode.CUSTOM || value.weeklyWindows.values.any { it.isNotEmpty() }) &&
    value.weeklyWindows.all { (day, windows) -> day in 1..7 && windows.all {
            it.startMinute in 0..1439 && it.endMinute in 0..1439 && it.startMinute != it.endMinute
    } }
