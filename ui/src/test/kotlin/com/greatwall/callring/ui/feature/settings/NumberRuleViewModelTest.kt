package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NumberRuleViewModelTest {
    @Test fun explicitSaveUsesLoadedBaseAndCommitsBothFields() = runTest {
        val api = UiFakeApi()
        val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        model.editPrefix(""); model.editLength("6")
        assertEquals(NumberRuleSettings("3", 5, 0), api.numberRule)
        model.save(); runCurrent()
        assertEquals(NumberRuleSettings("", 6, 1), api.numberRule)
        assertEquals(NumberRuleDraft("", "6"), model.draft.value)
        assertFalse(model.conflict.value)
    }

    @Test fun invalidFieldsNeverWriteAndEmptyPrefixIsValid() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        listOf("０", "a", "1 ").forEach { prefix -> model.editPrefix(prefix); model.save(); runCurrent(); assertNotNull(model.errors.value["prefix"]) }
        model.editPrefix("")
        listOf("0", "31", "-1", "", "２", "1.5").forEach { length -> model.editLength(length); model.save(); runCurrent(); assertNotNull(model.errors.value["length"]) }
        model.editPrefix("123"); model.editLength("2"); model.save(); runCurrent()
        assertNotNull(model.errors.value["prefix"])
        assertEquals(0, api.numberRuleWrites)
        model.editPrefix(""); model.editLength("1"); model.save(); runCurrent()
        assertEquals(1, api.numberRuleWrites)
    }

    @Test fun cleanExternalUpdateRefreshesButDirtyUpdateKeepsOriginalBaseAndInput() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        api.saveNumberRule(SaveNumberRule(api.numberRule, "8", 6)); runCurrent()
        assertEquals(NumberRuleDraft("8", "6"), model.draft.value)
        model.editPrefix("9")
        api.saveNumberRule(SaveNumberRule(api.numberRule, "7", 7)); runCurrent()
        assertTrue(model.conflict.value)
        assertEquals(NumberRuleDraft("9", "6"), model.draft.value)
        model.save(); runCurrent()
        assertEquals(ErrorCode.CONFLICT, model.mutation.value.error?.code)
        assertEquals(NumberRuleDraft("9", "6"), model.draft.value)
        model.reload(); runCurrent()
        assertEquals(NumberRuleDraft("7", "7"), model.draft.value)
        assertFalse(model.conflict.value)
    }

    @Test fun failedLoadRetainsTrialAndRetryPreparesBeforeChecking() = runTest {
        val release = CompletableDeferred<Unit>(); var fail = true; var checks = 0
        val api = object : UiFakeApi() {
            override suspend fun getNumberRule(): ApiResult<NumberRuleSettings> {
                release.await()
                return if (fail) ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "规则读取失败")) else super.getNumberRule()
            }
            override fun checkNumber(raw: String): NumberCheck { checks++; return NumberCheck(raw, true, true) }
        }
        val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        model.editTrial("812345"); runCurrent(); assertEquals(0, checks)
        release.complete(Unit); runCurrent(); assertNotNull(model.state.value.error)
        assertEquals("812345", model.trial.value); assertEquals(0, checks)
        fail = false; model.refresh(); runCurrent()
        assertTrue(model.trialCheck.value!!.matchesRule); assertTrue(checks > 0)
    }

    @Test fun restoredDraftRetainsStaleBaseUntilExplicitReload() = runTest {
        val api = UiFakeApi(); val handle = SavedStateHandle()
        val first = NumberRuleViewModel(api, handle, backgroundScope); runCurrent(); first.editPrefix("9")
        api.saveNumberRule(SaveNumberRule(api.numberRule, "8", 6)); runCurrent()
        val restored = NumberRuleViewModel(api, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), backgroundScope)
        runCurrent(); assertTrue(restored.conflict.value)
        restored.save(); runCurrent(); assertEquals(ErrorCode.CONFLICT, restored.mutation.value.error?.code)
        assertEquals("9", restored.draft.value!!.prefix)
    }
}
