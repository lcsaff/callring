package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Owner must prepare the formal number rule before construction; checks are memory-only. */
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

    private val checkMutable = MutableStateFlow(checkFor(mutableQuery.value))
    val check = checkMutable.asStateFlow()
    private val loader = ObservedQuery(scope, changes,
        ChangeSubscription(setOf(DataDomain.CALLERS, DataDomain.POSITIONS)), { query.value },
        { searchRequest(query.value) }, { result ->
            val lastPage = ((result.totalItems - 1).coerceAtLeast(0) / result.pageSize).toInt()
            if (query.value.page.pageIndex > lastPage) page(lastPage)
        })
    val state = loader.state
    init {
        loader.start(scope) { mutableQuery.value = query.value.copy(page = PageRequest()); savedState["page"] = 0 }
        ChangeObserver(changes, ChangeSubscription(setOf(DataDomain.NUMBER_RULES))).start(scope) {
            checkMutable.value = checkFor(query.value)
        }
    }

    fun search(text: String) = update(CallerSearch(text))
    fun page(index: Int) = update(query.value.copy(page = PageRequest(index.coerceAtLeast(0))))
    fun refresh() = loader.request(force = true)

    private fun checkFor(search: CallerSearch): NumberCheck? = search.text.takeIf { it.isNotBlank() }?.let(checkNumberValue)

    private fun update(value: CallerSearch) {
        mutableQuery.value = value
        checkMutable.value = checkFor(value)
        savedState["text"] = value.text
        savedState["page"] = value.page.pageIndex
        loader.request()
    }
}
