package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class CallerListViewModel(
    search: suspend (CallerSearch) -> ApiResult<Page<CallerProfile>>,
    checkNumber: (String) -> NumberCheck,
    changes: ChangeFeed,
    private val savedState: SavedStateHandle,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val searchRequest = search
    private val checkNumberValue = checkNumber
    private val mutableQuery = MutableStateFlow(CallerSearch(savedState["text"] ?: "", PageRequest(savedState["page"] ?: 0)))
    val query = mutableQuery.asStateFlow()

    /** Synchronous pure derivation: no extra request, no duplicated rule state in the screen. */
    val check: StateFlow<NumberCheck?> = mutableQuery
        .map { search -> search.text.takeIf { it.isNotBlank() }?.let(checkNumberValue) }
        .stateIn(scope, SharingStarted.Eagerly, checkFor(mutableQuery.value))

    private val loader = ObservedQuery(scope, changes,
        ChangeSubscription(setOf(DataDomain.CALLERS, DataDomain.POSITIONS)), { query.value },
        { searchRequest(query.value) }, { result ->
            val lastPage = ((result.totalItems - 1).coerceAtLeast(0) / result.pageSize).toInt()
            if (query.value.page.pageIndex > lastPage) page(lastPage)
        })
    val state = loader.state
    init { loader.start(scope) { mutableQuery.value = query.value.copy(page = PageRequest()); savedState["page"] = 0 } }

    fun search(text: String) = update(CallerSearch(text))
    fun page(index: Int) = update(query.value.copy(page = PageRequest(index.coerceAtLeast(0))))
    fun refresh() = loader.request(force = true)

    private fun checkFor(search: CallerSearch): NumberCheck? = search.text.takeIf { it.isNotBlank() }?.let(checkNumberValue)

    private fun update(value: CallerSearch) {
        mutableQuery.value = value
        savedState["text"] = value.text
        savedState["page"] = value.page.pageIndex
        loader.request()
    }
}
