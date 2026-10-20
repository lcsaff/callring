package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeQuietViewModelTest {
    private class Fixture : SettingsApi by UiFakeApi() {
        val changes = com.greatwall.callring.ui.testing.TestChangeFeed()
        var saved = QuietSettings(QuietMode.OFF, false, mapOf(1 to listOf(TimeWindow(1320, 420))))
        val toggles = mutableListOf<Boolean>()
        var writes = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getQuietHours() = ApiResult.Success(saved)
        override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> {
            toggles += enabled
            gate?.await()
            if (fail) return ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "保存失败"))
            saved = saved.copy(mode = if (enabled) QuietMode.NON_WORK else QuietMode.OFF)
            changes.publish(setOf(com.greatwall.callring.contracts.change.DataDomain.QUIET_HOURS))
            return ApiResult.Success(saved)
        }
        override suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings> {
            writes++
            gate?.await()
            if (fail) return ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "保存失败"))
            saved = settings
            changes.publish(setOf(com.greatwall.callring.contracts.change.DataDomain.QUIET_HOURS))
            return ApiResult.Success(saved)
        }
    }

    @Test fun toggleUsesSharedApiAndFailedWriteKeepsSavedTruth() = runTest {
        val api = Fixture()
        val model = HomeQuietViewModel(api, api.changes, testScope = backgroundScope)
        model.refresh(); runCurrent()
        api.fail = true
        model.setEnabled(true); runCurrent()
        assertEquals(listOf(true), api.toggles)
        assertEquals(QuietMode.OFF, model.state.value.data?.mode)
        assertNotNull(model.mutation.value.error)
        api.fail = false
        model.setEnabled(true); runCurrent()
        assertEquals(QuietMode.NON_WORK, model.state.value.data?.mode)
    }

    @Test fun openingOffStartsCustomPreservesWindowsAndCancelNeverWrites() = runTest {
        val api = Fixture()
        val model = HomeQuietViewModel(api, api.changes, testScope = backgroundScope)
        model.refresh(); runCurrent(); model.openEditor()
        assertEquals(QuietMode.CUSTOM, model.draft.value?.mode)
        assertEquals(api.saved.weeklyWindows, model.draft.value?.weeklyWindows)
        model.edit(model.draft.value!!.copy(allNumbers = true))
        model.closeEditor()
        assertFalse(model.dialogOpen.value)
        assertEquals(0, api.writes)
        model.openEditor()
        assertFalse(model.draft.value!!.allNumbers)
    }

    @Test fun failedSaveKeepsDraftAndSuccessActivatesAndCloses() = runTest {
        val api = Fixture()
        val model = HomeQuietViewModel(api, api.changes, testScope = backgroundScope)
        model.refresh(); runCurrent(); model.openEditor()
        val draft = model.draft.value!!.copy(allNumbers = true)
        model.edit(draft); api.fail = true
        model.save(); runCurrent()
        assertTrue(model.dialogOpen.value)
        assertEquals(draft, model.draft.value)
        assertEquals(QuietMode.OFF, model.state.value.data?.mode)
        api.fail = false
        model.save(); runCurrent()
        assertFalse(model.dialogOpen.value)
        assertEquals(draft, model.state.value.data)
    }

    @Test fun emptyOrEqualCustomWindowsCannotSaveAndBusyPreventsDuplicateWrites() = runTest {
        val api = Fixture()
        val model = HomeQuietViewModel(api, api.changes, testScope = backgroundScope)
        model.refresh(); runCurrent(); model.openEditor()
        model.edit(model.draft.value!!.copy(weeklyWindows = emptyMap()))
        model.save(); runCurrent()
        assertEquals(0, api.writes)
        model.edit(model.draft.value!!.copy(weeklyWindows = mapOf(1 to listOf(TimeWindow(60, 60)))))
        model.save(); runCurrent()
        assertEquals(0, api.writes)
        model.edit(model.draft.value!!.copy(mode = QuietMode.NON_WORK, weeklyWindows = emptyMap()))
        api.gate = CompletableDeferred()
        model.save(); model.save(); runCurrent()
        model.closeEditor()
        assertTrue(model.dialogOpen.value)
        assertEquals(1, api.writes)
        api.gate!!.complete(Unit); runCurrent()
        assertFalse(model.dialogOpen.value)
    }
}
