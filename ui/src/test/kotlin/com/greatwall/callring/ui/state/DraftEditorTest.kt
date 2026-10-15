package com.greatwall.callring.ui.state

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DraftEditorTest {
    @Test fun backgroundRefreshNeverOverwritesUnsavedAppearance() = runTest {
        val changes = MutableSharedFlow<DataChange>()
        var snapshot = "red"
        val model = DraftEditor(
            changes = object : ChangeFeed { override fun observe() = changes },
            domains = setOf(DataDomain.APPEARANCE),
            savedState = SavedStateHandle(),
            codec = StringDraftCodec,
            load = { ApiResult.Success(snapshot) },
            save = { snapshot = it; ApiResult.Success(it) },
            testScope = backgroundScope
        )
        model.refresh(); runCurrent()
        model.edit("custom")
        snapshot = "blue"
        changes.emit(DataChange(1, setOf(DataDomain.APPEARANCE), null, null))
        runCurrent()
        assertEquals("blue", model.state.value.data)
        assertEquals("custom", model.draft.value)
        model.save(); runCurrent()
        assertEquals("custom", snapshot)
    }

    @Test fun failedSaveRetainsDraftAcrossRecreation() = runTest {
        val handle = SavedStateHandle()
        val changes = object : ChangeFeed { override fun observe() = MutableSharedFlow<DataChange>() }
        fun create() = DraftEditor(changes, setOf(DataDomain.APPEARANCE), handle, StringDraftCodec,
            load = { ApiResult.Success("red") },
            save = { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "保存未完成")) }, testScope = backgroundScope)
        val model = create()
        model.refresh(); runCurrent()
        model.edit("custom"); model.save(); runCurrent()
        assertEquals(ErrorCode.STORAGE_FAILURE, model.mutation.value.error?.code)
        val restored = create()
        restored.refresh(); runCurrent()
        assertEquals("custom", restored.draft.value)
    }

    private object StringDraftCodec : DraftCodec<String> {
        override fun encode(value: String) = value
        override fun decode(value: String) = value
    }
}
