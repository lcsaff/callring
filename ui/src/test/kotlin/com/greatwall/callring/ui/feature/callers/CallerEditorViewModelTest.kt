package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallerEditorViewModelTest {
    private class CallersFake : CallersApi {
        var editor = CallerEditorData(CallerDraft("100", "已保存", "研发", null, false), emptyList(), null)
        var saved: SaveCallerProfile? = null
        var error: ApiError? = null
        override suspend fun getEditor(number: String?) = ApiResult.Success(editor)
        override suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile> {
            saved = command
            return error?.let { ApiResult.Failure(it) } ?: ApiResult.Success(CallerProfile(command.number, true, command.name, command.department, null, PositionRelation.UNKNOWN, command.whitelisted ?: false))
        }
        override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> = error("unused")
        override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> = error("unused")
        override suspend fun getLatestCall(): ApiResult<CallDetail?> = error("unused")
        override suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile> = error("unused")
    }
    private class ChangesFake : ChangeFeed {
        val events = MutableSharedFlow<DataChange>()
        override fun observe() = events
    }

    @Test fun invalidationRefreshesOptionsWithoutOverwritingDraft() = runTest {
        val api = CallersFake()
        val changes = ChangesFake()
        val model = CallerEditorViewModel("100", api, changes, SavedStateHandle(), backgroundScope)
        model.refresh()
        runCurrent()
        model.edit { copy(name = "正在编辑") }
        api.editor = api.editor.copy(draft = api.editor.draft.copy(name = "另一入口保存"))
        changes.events.emit(DataChange(1, setOf(DataDomain.CALLERS), setOf("100"), null))
        runCurrent()
        assertEquals("正在编辑", model.draft.value?.name)
        model.save()
        runCurrent()
        assertEquals("正在编辑", api.saved?.name)
        assertNull(api.saved?.whitelisted)
    }

    @Test fun explicitWhitelistEditSendsTargetValue() = runTest {
        val api = CallersFake()
        val model = CallerEditorViewModel("100", api, ChangesFake(), SavedStateHandle(), backgroundScope)
        model.refresh(); runCurrent()
        model.edit { copy(whitelisted = true) }
        model.save(); runCurrent()
        assertEquals(true, api.saved?.whitelisted)
    }

    @Test fun saveFailureRetainsDraftAndFieldError() = runTest {
        val api = CallersFake().apply { error = ApiError(ErrorCode.VALIDATION, "输入有误", mapOf("name" to "名称过长")) }
        val model = CallerEditorViewModel("100", api, ChangesFake(), SavedStateHandle(), backgroundScope)
        model.refresh(); runCurrent()
        model.edit { copy(name = "草稿") }
        model.save(); runCurrent()
        assertEquals("草稿", model.draft.value?.name)
        assertEquals("名称过长", model.mutation.value.error?.fieldErrors?.get("name"))
    }

    @Test fun processRecreationRestoresDraftAndWhitelistIntent() = runTest {
        val api = CallersFake()
        val handle = SavedStateHandle()
        val first = CallerEditorViewModel("100", api, ChangesFake(), handle, backgroundScope)
        first.refresh(); runCurrent()
        first.edit { copy(name = "恢复草稿", whitelisted = true) }
        val second = CallerEditorViewModel("100", api, ChangesFake(), handle, backgroundScope)
        second.refresh(); runCurrent()
        assertEquals("恢复草稿", second.draft.value?.name)
        second.save(); runCurrent()
        assertEquals(true, api.saved?.whitelisted)
    }
}
