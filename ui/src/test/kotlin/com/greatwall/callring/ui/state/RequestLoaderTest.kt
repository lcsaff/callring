package com.greatwall.callring.ui.state

import com.greatwall.callring.application.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestLoaderTest {
    @Test fun lateUncooperativeResponseCannotReplaceNewQuery() = runTest {
        val loader = RequestLoader<String>(backgroundScope)
        val first = CompletableDeferred<ApiResult<String>>()
        loader.load { withContext(NonCancellable) { first.await() } }
        runCurrent()
        loader.load { ApiResult.Success("new") }
        runCurrent()
        first.complete(ApiResult.Success("old"))
        runCurrent()
        assertEquals("new", loader.state.value.data)
        assertFalse(loader.state.value.loading)
    }

    @Test fun successfulNullIsLoadedRatherThanStillLoading() = runTest {
        val loader = RequestLoader<String?>(backgroundScope)
        loader.load { ApiResult.Success(null) }
        runCurrent()
        assertTrue(loader.state.value.loaded)
        assertNull(loader.state.value.data)
        assertNull(loader.state.value.error)
    }

    @Test fun refreshFailureKeepsSnapshotAndStructuredError() = runTest {
        val loader = RequestLoader<String>(backgroundScope)
        loader.load { ApiResult.Success("saved") }
        runCurrent()
        val error = ApiError(ErrorCode.PERMISSION_REQUIRED, "需要权限", requiredCapabilities = setOf(Capability.CALL_LOG))
        loader.load { ApiResult.Failure(error) }
        runCurrent()
        assertEquals("saved", loader.state.value.data)
        assertEquals(error, loader.state.value.error)
    }

    @Test fun cancellationNeverBecomesAnError() = runTest {
        val loader = RequestLoader<String>(backgroundScope)
        loader.load { throw CancellationException() }
        runCurrent()
        assertFalse(loader.state.value.loading)
        assertNull(loader.state.value.error)
    }

    @Test fun runtimeEventsWithSameRevisionAreBothRelevant() {
        val event = DataChange(3, setOf(DataDomain.RUNTIME), null, null)
        repeat(2) { assertTrue(event.affects(setOf(DataDomain.RUNTIME))) }
        assertFalse(event.affects(setOf(DataDomain.CALLERS)))
    }

    @Test fun objectFiltersRestrictOnlyTheirOwnObjectDomain() {
        val event = DataChange(3, setOf(DataDomain.CALLERS), setOf("100"), null)
        assertTrue(event.affects(setOf(DataDomain.CALLERS), number = "100"))
        assertFalse(event.affects(setOf(DataDomain.CALLERS), number = "200"))
        assertTrue(event.affects(setOf(DataDomain.CALLERS)))
        val broad = event.copy(numbers = null)
        assertTrue(broad.affects(setOf(DataDomain.CALLERS), number = "200"))
    }

    @Test fun duplicateMutationIsIgnoredAndValidationRetained() = runTest {
        val runner = MutationRunner(backgroundScope)
        val result = CompletableDeferred<ApiResult<Unit>>()
        var count = 0
        runner.run({ count++; result.await() }) {}
        runCurrent()
        runner.run({ count++; ApiResult.Success(Unit) }) {}
        result.complete(ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "检查输入", mapOf("name" to "必填"))))
        runCurrent()
        assertEquals(1, count)
        assertEquals("必填", runner.state.value.error?.fieldErrors?.get("name"))
        assertFalse(runner.state.value.busy)
    }
}
