package com.greatwall.callring.ui.state

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImmediateEditorTest {
    private class Editor(
        testScope: CoroutineScope,
        load: suspend () -> ApiResult<Int>,
        write: suspend (Int, Int) -> ApiResult<Int>,
    ) : ImmediateEditor<Int>(
        object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() },
        setOf(DataDomain.SCORE_SETTINGS), load, write, testScope
    ) {
        fun coalesce(value: Int, millis: Long) = editCoalesced(value, millis)
        fun resetWith(operation: suspend () -> ApiResult<Int>) = submitReset(operation)
    }

    @Test fun coalescedEditsCommitOnceWithTheLatestValue() = runTest {
        val writes = mutableListOf<Pair<Int, Int>>()
        val editor = Editor(backgroundScope, { ApiResult.Success(1) },
            { base, value -> writes += base to value; ApiResult.Success(value) })
        editor.refresh(); runCurrent()
        editor.coalesce(2, 500)
        editor.coalesce(3, 500)
        editor.coalesce(4, 500)
        advanceTimeBy(499); runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1 to 4), writes)
        assertEquals(4, editor.draft.value)
        assertFalse(editor.mutation.value.busy)
    }

    @Test fun conflictRefreshesTheBaseAndKeepsTheLocalValue() = runTest {
        var server = 1
        var conflict = true
        val bases = mutableListOf<Int>()
        val editor = Editor(backgroundScope, { ApiResult.Success(server) }, { base, value ->
            bases += base
            if (conflict) {
                conflict = false
                server = 5
                ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "conflict"))
            } else {
                server = value
                ApiResult.Success(value)
            }
        })
        editor.refresh(); runCurrent()
        editor.edit(2); runCurrent()
        assertEquals(listOf(1), bases)
        assertEquals(5, editor.state.value.data)
        assertEquals(2, editor.draft.value)
        assertEquals(ErrorCode.CONFLICT, editor.mutation.value.error?.code)
        assertTrue(editor.mutation.value.error!!.message.contains("已更新，请重新修改"))
        editor.edit(6); runCurrent()
        assertEquals(listOf(1, 5), bases)
        assertEquals(6, server)
        assertNull(editor.mutation.value.error)
    }

    @Test fun failureKeepsTheDraftAndRetryCommitsAgainstTheLastConfirmedBase() = runTest {
        var server = 1
        var fail = true
        val bases = mutableListOf<Int>()
        val editor = Editor(backgroundScope, { ApiResult.Success(server) }, { base, value ->
            bases += base
            if (fail) ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "disk"))
            else {
                server = value
                ApiResult.Success(value)
            }
        })
        editor.refresh(); runCurrent()
        editor.edit(3); runCurrent()
        assertEquals(3, editor.draft.value)
        assertEquals(ErrorCode.STORAGE_FAILURE, editor.mutation.value.error?.code)
        fail = false
        editor.save(); runCurrent()
        assertEquals(listOf(1, 1), bases)
        assertEquals(3, server)
        assertNull(editor.mutation.value.error)
    }

    @Test fun resetReplacesTheDraftAndCountsAsOneCompletedOperation() = runTest {
        val editor = Editor(backgroundScope, { ApiResult.Success(1) }, { _, value -> ApiResult.Success(value) })
        editor.refresh(); runCurrent()
        editor.edit(9); runCurrent()
        editor.resetWith { ApiResult.Success(1) }; runCurrent()
        assertEquals(1, editor.draft.value)
        assertEquals(1L, editor.mutation.value.completed)
        assertFalse(editor.mutation.value.busy)
    }

    @Test fun externalRefreshDoesNotReplaceAnUncommittedEdit() = runTest {
        var server = 1
        var fail = true
        val editor = Editor(backgroundScope, { ApiResult.Success(server) }, { _, value ->
            if (fail) ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "disk"))
            else ApiResult.Success(value).also { server = value }
        })
        editor.refresh(); runCurrent()
        editor.edit(7); runCurrent()
        assertEquals(7, editor.draft.value)
        editor.refresh(); runCurrent()
        assertEquals(7, editor.draft.value)
    }
}
