package com.greatwall.callring.ui.feature.callers

import com.greatwall.callring.ui.testing.TestChangeFeed
import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallerListViewModelTest {
    @Test fun callerChangesReturnToFirstPageAndKeepSearchText() = runTest {
        val changes = TestChangeFeed()
        val queries = mutableListOf<CallerSearch>()
        val model = CallerListViewModel(
            search = { q -> queries += q; ApiResult.Success(Page(emptyList(), q.page.pageIndex, 50, 1000, SnapshotMeta(1, 0))) },
            checkNumber = { NumberCheck(it, true, false) },
            changes = object : ChangeFeed { override fun snapshots() = changes.snapshots() },
            savedState = SavedStateHandle(), testScope = backgroundScope)
        model.search("张"); runCurrent()
        model.page(3); runCurrent()
        changes.publish(setOf(DataDomain.CALLERS)); runCurrent()
        assertEquals(CallerSearch("张"), queries.last())
    }

    @Test fun shrinkingTotalCorrectsPageInsteadOfShowingFalseEmptyResult() = runTest {
        val queries = mutableListOf<Int>()
        val model = CallerListViewModel(
            search = { q -> queries += q.page.pageIndex; ApiResult.Success(Page(emptyList(), q.page.pageIndex, 50, 51, SnapshotMeta(1, 0))) },
            checkNumber = { NumberCheck(it, true, false) },
            changes = object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() },
            savedState = SavedStateHandle(), testScope = backgroundScope)
        model.page(7); runCurrent()
        assertEquals(listOf(7, 1), queries)
        assertEquals(1, model.query.value.page.pageIndex)
    }

    @Test fun numberCheckIsDerivedSynchronouslyFromTheSearchText() = runTest {
        val model = CallerListViewModel(
            search = { q -> ApiResult.Success(Page(emptyList(), 0, 50, 0, SnapshotMeta(1, 0))) },
            checkNumber = { raw -> NumberCheck(raw, raw == "31234", raw.startsWith("31")) },
            changes = object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() },
            savedState = SavedStateHandle(), testScope = backgroundScope)
        assertNull("Blank query has no number check", model.check.value)
        model.search("31234"); runCurrent()
        assertEquals(NumberCheck("31234", true, true), model.check.value)
        model.search("张"); runCurrent()
        assertEquals(NumberCheck("张", false, false), model.check.value)
        model.search(""); runCurrent()
        assertNull(model.check.value)
    }

    @Test fun restoredSearchTextExposesItsNumberCheckWithoutLoading() = runTest {
        val model = CallerListViewModel(
            search = { ApiResult.Success(Page(emptyList(), 0, 50, 0, SnapshotMeta(1, 0))) },
            checkNumber = { raw -> NumberCheck(raw, raw == "31234", false) },
            changes = object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() },
            savedState = SavedStateHandle(mapOf("text" to "31234")), testScope = backgroundScope)
        assertEquals(NumberCheck("31234", true, false), model.check.value)
    }
    @Test fun numberRuleInvalidationOnlyRecomputesHintWithoutSearch() = runTest {
        val feed = TestChangeFeed(); var searches = 0; var matches = false
        val model = CallerListViewModel(
            search = { searches++; ApiResult.Success(Page(emptyList(), 0, 50, 0, SnapshotMeta(1, 0))) },
            checkNumber = { NumberCheck(it, true, matches) }, changes = feed,
            savedState = SavedStateHandle(mapOf("text" to "812345")), testScope = backgroundScope)
        runCurrent(); val before = searches
        matches = true; feed.publish(setOf(DataDomain.NUMBER_RULES)); runCurrent()
        assertTrue(model.check.value!!.matchesRule)
        assertEquals(before, searches)
        assertEquals("812345", model.query.value.text)
    }
}
