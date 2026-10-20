package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotViewModelTest {
    @Test fun repeatedRuntimeRevisionStillReloadsButUnrelatedDomainDoesNot() = runTest {
        val events = MutableSharedFlow<DataChange>()
        var loads = 0
        val model = SnapshotViewModel(object : ChangeFeed { override fun observe() = events },
            setOf(DataDomain.RUNTIME), load = { ApiResult.Success(++loads) }, testScope = backgroundScope)
        runCurrent()
        repeat(2) { events.emit(DataChange(1, setOf(DataDomain.RUNTIME), null, null)); runCurrent() }
        events.emit(DataChange(2, setOf(DataDomain.CALLERS), null, null)); runCurrent()
        assertEquals(2, loads)
        assertEquals(2, model.state.value.data)
    }

    @Test fun successfulWriteRefreshesSnapshotWithoutRequiringNotificationDelivery() = runTest {
        var value = "before"
        val model = SnapshotViewModel(object : ChangeFeed { override fun observe() = MutableSharedFlow<DataChange>() },
            setOf(DataDomain.RUNTIME), load = { ApiResult.Success(value) }, testScope = backgroundScope)
        model.refresh(); runCurrent()
        model.execute({ value = "after"; ApiResult.Success(Unit) }); runCurrent()
        assertEquals("after", model.state.value.data)
    }
}
