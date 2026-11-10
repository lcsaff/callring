package com.greatwall.callring.ui.feature.callers

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallerEditorViewModelTest {
    private class CallersFake : CallersApi {
        override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> = error("unused")
        override suspend fun getCallHistoryDetail(callId: Long): ApiResult<CallHistoryEntry> = error("unused")
    override suspend fun getCallDetail(callId: Long): ApiResult<CallDetail> = throw AssertionError("Unexpected fixture operation: getCallDetail")
    override suspend fun numberDataImpact(number: String): ApiResult<NumberCoreImpact> = throw AssertionError("Unexpected fixture operation: numberDataImpact")
    override suspend fun deleteNumberCore(number: String, expectedCallCount: Int): ApiResult<Unit> = throw AssertionError("Unexpected fixture operation: deleteNumberCore")

        var editor =
            CallerEditorData(CallerDraft("100", "已保存", "研发", null, false), emptyList(), null)
        var gate: kotlinx.coroutines.CompletableDeferred<CallerProfile>? = null
        var saved: SaveCallerProfile? = null
        var error: ApiError? = null

        override suspend fun getEditor(number: String?) = ApiResult.Success(editor)

        override suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile> =
            error("unexpected direct save")

        suspend fun edit(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> {
            val command =
                SaveCallerProfile(
                    value.number,
                    value.name,
                    value.department,
                    value.positionId,
                    value.whitelisted,
                    base, selfReportedPosition = value.selfReportedPosition, region = value.region,
                )
            saved = command
            gate?.let {
                return ApiResult.Success(it.await())
            }
            return error?.let { ApiResult.Failure(it) }
                ?: ApiResult.Success(
                    CallerProfile(
                        command.number,
                        true,
                        command.name,
                        command.department,
                        null,
                        PositionRelation.UNKNOWN,
                        command.whitelisted ?: false, value.selfReportedPosition, value.region, value.identityMetadata,
                    ),
                )
        }

        override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> =
            error("unused")

        override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> =
            error("unused")

        override suspend fun getLatestCall(): ApiResult<CallDetail?> = error("unused")

        override suspend fun setWhitelisted(
            number: String,
            enabled: Boolean,
        ): ApiResult<CallerProfile> = error("unused")
    }

    private class ChangesFake : ChangeFeed {
        val events = TestChangeFeed()

        override fun snapshots() = events.snapshots()
    }

    @Test fun fieldModeSubmitsOnlyTheSelectedFieldAgainstTheOriginalDraft() = runTest {
        val api = CallersFake()
        val source = CallerFieldMetadata(revision = 7, disputed = true, archiveId = 8, evidence = "部门依据")
        api.editor = api.editor.copy(draft = api.editor.draft.copy(identityMetadata = mapOf("department" to source)))
        val base = api.editor.draft
        var saved: Pair<CallerDraft, CallerDraft>? = null
        val model = CallerEditorViewModel("100", api, ChangesFake(), SavedStateHandle(), backgroundScope,
            saveWithBase = { before, after ->
                saved = before to after
                api.edit(before, after)
            }, field = CallerProfileField.NAME)
        runCurrent()
        model.edit { copy(number = "200", name = "新姓名", department = "新部门", identityMetadata = emptyMap()) }
        model.save()
        runCurrent()

        assertEquals(base to base.copy(name = "新姓名"), saved)
    }

    @Test fun revertingFieldToBaselineReconcilesAnAlreadyObservedExternalEdit() = runTest {
        val api = CallersFake()
        val changes = ChangesFake()
        val model = CallerEditorViewModel("100", api, changes, SavedStateHandle(), backgroundScope,
            api::edit, field = CallerProfileField.NAME)
        runCurrent()
        val initial = requireNotNull(model.draft.value).name
        model.edit { copy(name = "本地草稿") }
        api.editor = api.editor.copy(draft = api.editor.draft.copy(name = "外部已保存"))
        changes.events.entity(DataDomain.CALLERS, "100")
        runCurrent()
        assertEquals("本地草稿", model.draft.value?.name)
        assertTrue(model.dirty.value)

        model.edit { copy(name = initial) }
        runCurrent()
        assertEquals("外部已保存", model.draft.value?.name)
        assertFalse(model.dirty.value)
        model.save()
        runCurrent()
        assertNull(api.saved)
    }

    @Test fun fieldConflictReloadsTheCurrentBaselineOnlyAfterExplicitDiscard() = runTest {
        val api = CallersFake()
        val changes = ChangesFake()
        val model = CallerEditorViewModel("100", api, changes, SavedStateHandle(), backgroundScope,
            api::edit, field = CallerProfileField.REGION)
        runCurrent()
        model.edit { copy(region = "苏州") }
        api.editor = api.editor.copy(draft = api.editor.draft.copy(region = "杭州"))
        changes.events.entity(DataDomain.CALLERS, "100")
        runCurrent()
        api.error = ApiError(ErrorCode.CONFLICT, "draft.field_changed", mapOf("region" to "draft.field_changed"))
        model.save()
        runCurrent()
        assertEquals("苏州", model.draft.value?.region)
        assertEquals(ErrorCode.CONFLICT, model.mutation.value.error?.code)

        model.reload()
        runCurrent()
        assertEquals("杭州", model.draft.value?.region)
        assertNull(model.mutation.value.error)
        api.error = null
        model.edit { copy(region = "宁波") }
        model.save()
        runCurrent()
        assertEquals("杭州", api.saved?.baseline?.region)
        assertEquals("宁波", api.saved?.region)
    }

    @Test fun enrichmentFieldsAndDisputeDraftSurviveHostRecreation() = runTest {
        val api = CallersFake(); val changes = ChangesFake(); val saved = SavedStateHandle()
        val model = CallerEditorViewModel("100", api, changes, saved, backgroundScope, api::edit)
        runCurrent()
        model.edit { copy(selfReportedPosition = "经理", region = "杭州", identityMetadata = mapOf("name" to CallerFieldMetadata(3, true, -1, 12, "我是旧姓名"))) }
        val restored = CallerEditorViewModel("100", api, changes, saved, backgroundScope, api::edit)
        runCurrent()
        assertEquals("杭州", restored.draft.value!!.region)
        assertEquals("经理", restored.draft.value!!.selfReportedPosition)
        assertTrue(restored.draft.value!!.identityMetadata.getValue("name").disputed)
        assertEquals(3L, restored.draft.value!!.identityMetadata.getValue("name").revision)
        restored.save(); runCurrent()
        assertEquals("杭州", api.saved!!.region)
        assertEquals("经理", api.saved!!.selfReportedPosition)
    }

    @Test
    fun invalidationRefreshesOptionsWithoutOverwritingDraft() = runTest {
        val api = CallersFake()
        val changes = ChangesFake()
        val model =
            CallerEditorViewModel(
                "100",
                api,
                changes,
                SavedStateHandle(),
                backgroundScope,
                api::edit,
            )
        model.refresh()
        runCurrent()
        model.edit { copy(name = "正在编辑") }
        api.editor = api.editor.copy(draft = api.editor.draft.copy(name = "另一入口保存"))
        changes.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("100") })
        runCurrent()
        assertEquals("正在编辑", model.draft.value?.name)
        model.save()
        runCurrent()
        assertEquals("正在编辑", api.saved?.name)
        assertEquals(false, api.saved?.whitelisted)
        assertEquals("已保存", api.saved?.baseline?.name)
    }

    @Test
    fun explicitWhitelistEditSendsTargetValue() = runTest {
        val api = CallersFake()
        val model =
            CallerEditorViewModel(
                "100",
                api,
                ChangesFake(),
                SavedStateHandle(),
                backgroundScope,
                api::edit,
            )
        model.refresh()
        runCurrent()
        model.edit { copy(whitelisted = true) }
        model.save()
        runCurrent()
        assertEquals(true, api.saved?.whitelisted)
    }

    @Test
    fun saveFailureRetainsDraftAndFieldError() = runTest {
        val api =
            CallersFake().apply {
                error = ApiError(ErrorCode.VALIDATION, "输入有误", mapOf("name" to "名称过长"))
            }
        val model =
            CallerEditorViewModel(
                "100",
                api,
                ChangesFake(),
                SavedStateHandle(),
                backgroundScope,
                api::edit,
            )
        model.refresh()
        runCurrent()
        model.edit { copy(name = "草稿") }
        model.save()
        runCurrent()
        assertEquals("草稿", model.draft.value?.name)
        assertEquals("名称过长", model.mutation.value.error?.fieldErrors?.get("name"))
    }

    @Test
    fun processRecreationRestoresDraftAndWhitelistIntent() = runTest {
        val api = CallersFake()
        val handle = SavedStateHandle()
        val first =
            CallerEditorViewModel("100", api, ChangesFake(), handle, backgroundScope, api::edit)
        first.refresh()
        runCurrent()
        first.edit { copy(name = "恢复草稿", whitelisted = true) }
        val second =
            CallerEditorViewModel("100", api, ChangesFake(), handle, backgroundScope, api::edit)
        second.refresh()
        runCurrent()
        assertEquals("恢复草稿", second.draft.value?.name)
        second.save()
        runCurrent()
        assertEquals(true, api.saved?.whitelisted)
    }

    @Test
    fun externalChangeDuringSaveCannotBeOverwrittenByTheDelayedSaveResult() = runTest {
        val api = CallersFake()
        val changes = ChangesFake()
        val model =
            CallerEditorViewModel(
                "100",
                api,
                changes,
                SavedStateHandle(),
                backgroundScope,
                api::edit,
            )
        model.refresh()
        runCurrent()
        model.edit { copy(name = "local") }
        val gate = kotlinx.coroutines.CompletableDeferred<CallerProfile>()
        api.gate = gate
        model.save()
        runCurrent()
        api.editor =
            api.editor.copy(
                draft = api.editor.draft.copy(name = "newer", department = "after-save"),
            )
        changes.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("100") })
        runCurrent()
        gate.complete(
            CallerProfile("100", true, "local", "old", null, PositionRelation.UNKNOWN, false),
        )
        runCurrent()
        assertEquals("newer", model.draft.value?.name)
        assertEquals("after-save", model.draft.value?.department)
    }
}
