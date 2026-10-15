package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallerListViewModelTest {
    @Test fun callerChangesReturnToFirstPageAndKeepSearchText() = runTest {
        val changes = MutableSharedFlow<DataChange>()
        val queries = mutableListOf<CallerSearch>()
        val model = CallerListViewModel(
            search = { q -> queries += q; ApiResult.Success(Page(emptyList(), q.page.pageIndex, 50, 1000, SnapshotMeta(1, 0))) },
            changes = object : ChangeFeed { override fun observe() = changes },
            savedState = SavedStateHandle(), testScope = backgroundScope)
        model.search("张"); runCurrent()
        model.page(3); runCurrent()
        changes.emit(DataChange(5, setOf(DataDomain.CALLERS), null, null)); runCurrent()
        assertEquals(CallerSearch("张"), queries.last())
    }

    @Test fun shrinkingTotalCorrectsPageInsteadOfShowingFalseEmptyResult() = runTest {
        val queries = mutableListOf<Int>()
        val model = CallerListViewModel(
            search = { q -> queries += q.page.pageIndex; ApiResult.Success(Page(emptyList(), q.page.pageIndex, 50, 51, SnapshotMeta(1, 0))) },
            changes = object : ChangeFeed { override fun observe() = MutableSharedFlow<DataChange>() },
            savedState = SavedStateHandle(), testScope = backgroundScope)
        model.page(7); runCurrent()
        assertEquals(listOf(7, 1), queries)
        assertEquals(1, model.query.value.page.pageIndex)
    }
}
