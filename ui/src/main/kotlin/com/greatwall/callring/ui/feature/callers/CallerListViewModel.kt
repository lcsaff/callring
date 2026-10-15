package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class CallerListViewModel(
    search: suspend (CallerSearch) -> ApiResult<Page<CallerProfile>>,
    changes: ChangeFeed,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val searchRequest = search
    private val loader = RequestLoader<Page<CallerProfile>>(scope)
    val state = loader.state
    private val mutableQuery = MutableStateFlow(CallerSearch(savedState["text"] ?: "", PageRequest(savedState["page"] ?: 0)))
    val query = mutableQuery.asStateFlow()

    init { observeChanges(changes, setOf(DataDomain.CALLERS, DataDomain.POSITIONS)) { page(0) } }

    fun search(text: String) = update(CallerSearch(text))
    fun page(index: Int) = update(query.value.copy(page = PageRequest(index.coerceAtLeast(0))))

    fun refresh(preserveData: Boolean = true) {
        val captured = query.value
        loader.load(preserveData, onSuccess = { result ->
            val lastPage = ((result.totalItems - 1).coerceAtLeast(0) / result.pageSize).toInt()
            if (captured.page.pageIndex > lastPage) page(lastPage)
        }) { searchRequest(captured) }
    }

    private fun update(value: CallerSearch) {
        mutableQuery.value = value
        savedState["text"] = value.text
        savedState["page"] = value.page.pageIndex
        refresh(false)
    }
}
