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
    @Test fun typingDuringSaveRetainsLatestDraftAndUsesCommittedRevision() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : UiFakeApi() {
            override suspend fun saveNumberRule(command: SaveNumberRule): ApiResult<NumberRuleSettings> {
                release.await()
                return super.saveNumberRule(command)
            }
        }
        val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent(); val id = model.draft.value!!.single().id
        model.editPrefix(id, "01"); advanceTimeBy(501); runCurrent()
        assertTrue(model.mutation.value.busy)
        model.editPrefix(id, "010"); advanceTimeBy(501); runCurrent()
        assertEquals("010", model.draft.value!!.single().prefix)
        release.complete(Unit); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(listOf(NumberRuleEntry("010", 5)), api.numberRule.rules)
        assertEquals(2, api.numberRuleWrites)
        assertFalse(model.conflict.value)
    }
    @Test fun pausedEditsAutoSaveAndBlankLengthDefaultsToFive() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent(); val id = model.draft.value!!.single().id
        model.editLength(id, ""); model.editPrefix(id, "01"); model.editPrefix(id, "010")
        runCurrent(); assertEquals(0, api.numberRuleWrites)
        advanceTimeBy(501); runCurrent()
        assertEquals(listOf(NumberRuleEntry("010", 5)), api.numberRule.rules)
        assertEquals(1, api.numberRuleWrites)
        assertEquals(id, model.draft.value!!.single().id)
        model.removeRule(id); advanceTimeBy(501); runCurrent()
        assertTrue(api.numberRule.rules.isEmpty())
    }
    @Test fun explicitSaveUsesLoadedBaseAndCommitsBothFields() = runTest {
        val api = UiFakeApi()
        val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        model.editPrefix(model.draft.value!!.first().id, ""); model.editLength(model.draft.value!!.first().id, "6")
        assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0), api.numberRule)
        model.save(); runCurrent()
        assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("", 6)), 1), api.numberRule)
        assertEquals(("" to "6"), model.draft.value!!.single().let { it.prefix to it.length })
        assertFalse(model.conflict.value)
    }

    @Test fun invalidFieldsNeverWriteAndEmptyPrefixIsValid() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        listOf("０", "a", "1 ").forEach { prefix -> model.editPrefix(model.draft.value!!.first().id, prefix); model.save(); runCurrent(); assertNotNull(model.errors.value["rules[0].prefix"]) }
        model.editPrefix(model.draft.value!!.first().id, "")
        listOf("0", "31", "-1", "２", "1.5").forEach { length -> model.editLength(model.draft.value!!.first().id, length); model.save(); runCurrent(); assertNotNull(model.errors.value["rules[0].length"]) }
        model.editPrefix(model.draft.value!!.first().id, "123"); model.editLength(model.draft.value!!.first().id, "2"); model.save(); runCurrent()
        assertNotNull(model.errors.value["rules[0].prefix"])
        assertEquals(0, api.numberRuleWrites)
        model.editPrefix(model.draft.value!!.first().id, ""); model.editLength(model.draft.value!!.first().id, "1"); model.save(); runCurrent()
        assertEquals(1, api.numberRuleWrites)
    }

    @Test fun cleanExternalUpdateRefreshesButDirtyUpdateKeepsOriginalBaseAndInput() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        api.saveNumberRule(SaveNumberRule(api.numberRule, listOf(NumberRuleEntry("8", 6)))); runCurrent()
        assertEquals(("8" to "6"), model.draft.value!!.single().let { it.prefix to it.length })
        model.editPrefix(model.draft.value!!.first().id, "9")
        api.saveNumberRule(SaveNumberRule(api.numberRule, listOf(NumberRuleEntry("7", 7)))); runCurrent()
        assertTrue(model.conflict.value)
        assertEquals(("9" to "6"), model.draft.value!!.single().let { it.prefix to it.length })
        model.save(); runCurrent()
        assertEquals(ErrorCode.CONFLICT, model.mutation.value.error?.code)
        assertEquals(("9" to "6"), model.draft.value!!.single().let { it.prefix to it.length })
        model.reload(); runCurrent()
        assertEquals(("7" to "7"), model.draft.value!!.single().let { it.prefix to it.length })
        assertFalse(model.conflict.value)
        model.editPrefix(model.draft.value!!.single().id, "010")
        advanceTimeBy(501); runCurrent()
        assertEquals(listOf(NumberRuleEntry("010", 7)), api.numberRule.rules)
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
        val first = NumberRuleViewModel(api, handle, backgroundScope); runCurrent(); first.editPrefix(first.draft.value!!.first().id, "9")
        api.saveNumberRule(SaveNumberRule(api.numberRule, listOf(NumberRuleEntry("8", 6)))); runCurrent()
        val restored = NumberRuleViewModel(api, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), backgroundScope)
        runCurrent(); assertTrue(restored.conflict.value)
        restored.save(); runCurrent(); assertEquals(ErrorCode.CONFLICT, restored.mutation.value.error?.code)
        assertEquals("9", restored.draft.value!!.single().prefix)
    }
    @Test fun listEditsPreserveRowIdentityAndAllowSavingEmptyList() = runTest {
        val api = UiFakeApi(); val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope)
        runCurrent()
        val old = model.draft.value!!.single().id
        model.addRule()
        val row = model.draft.value!!.last()
        assertEquals("", row.prefix); assertEquals("", row.length)
        assertTrue(model.errors.value.isEmpty())
        model.editPrefix(row.id, "010"); model.editLength(row.id, "11")
        model.removeRule(old)
        assertEquals(row.id, model.draft.value!!.single().id)
        assertEquals("010", model.draft.value!!.single().prefix)
        model.save(); runCurrent(); assertEquals(listOf(NumberRuleEntry("010", 11)), api.numberRule.rules)
        model.removeRule(row.id); model.save(); runCurrent()
        assertTrue(api.numberRule.rules.isEmpty()); assertEquals(2, api.numberRuleWrites)
    }

    @Test fun restoredMultiRowDraftAndBaseAreSavedAsPlatformValues() = runTest {
        val api = UiFakeApi(); val handle = SavedStateHandle()
        val first = NumberRuleViewModel(api, handle, backgroundScope); runCurrent()
        first.addRule(); val id = first.draft.value!!.last().id
        first.editPrefix(id, "138"); first.editLength(id, "11")
        api.saveNumberRule(SaveNumberRule(api.numberRule, emptyList())); runCurrent()
        val restored = NumberRuleViewModel(api, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), backgroundScope)
        runCurrent(); assertEquals(first.draft.value, restored.draft.value); assertTrue(restored.conflict.value)
        restored.save(); runCurrent(); assertEquals(ErrorCode.CONFLICT, restored.mutation.value.error?.code)
        restored.reload(); runCurrent(); assertTrue(restored.draft.value!!.isEmpty())
    }

    @Test fun trialUsesSavedRulesAndReactsToEmptyGroup() = runTest {
        val api = object : UiFakeApi() {
            override fun checkNumber(raw: String) = NumberCheck(raw, true, numberRule.rules.any { raw.startsWith(it.prefix) && raw.length == it.length })
        }
        val model = NumberRuleViewModel(api, SavedStateHandle(), backgroundScope); runCurrent()
        model.editTrial("31234"); assertTrue(model.trialCheck.value!!.matchesRule)
        model.editPrefix(model.draft.value!!.single().id, "8")
        assertTrue(model.trialCheck.value!!.matchesRule)
        api.saveNumberRule(SaveNumberRule(api.numberRule, emptyList())); runCurrent()
        assertFalse(model.trialCheck.value!!.matchesRule); assertTrue(model.conflict.value)
    }

    @Test fun oldSingleRowSavedDraftMigratesWithoutLosingEditsOrBase() = runTest {
        val api = UiFakeApi()
        val handle = SavedStateHandle(mapOf("rule.prefix" to "010", "rule.length" to "11",
            "rule.base.prefix" to "3", "rule.base.length" to 5, "rule.base.revision" to 0L))
        val model = NumberRuleViewModel(api, handle, backgroundScope); runCurrent()
        assertEquals("010", model.draft.value!!.single().prefix)
        model.save(); runCurrent(); assertEquals(listOf(NumberRuleEntry("010", 11)), api.numberRule.rules)
    }

}
