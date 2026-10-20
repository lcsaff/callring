package com.greatwall.callring

import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UseCaseRunnerTest {
    @Test fun errorsMapAtThePublicBoundary() = runBlocking {
        val runner = UseCaseRunner(Dispatchers.Unconfined)
        val expected = ApiError(ErrorCode.VALIDATION, "specific", mapOf("weekly.2.0.endMinute" to "specific"))
        assertEquals(ApiResult.Failure(expected), runner.io<Unit> { throw BackendFailure(expected) })
        assertEquals(ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "io.failure", retryable = true)), runner.io<Unit> { throw IOException("private") })
        assertEquals(ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "storage.failure", retryable = true)), runner.io<Unit> { error("private") })
        assertEquals(ApiResult.Success(17), runner.io { 17 })
    }
    @Test fun cancellationIsRethrownUnchanged() = runBlocking {
        val expected = CancellationException("cancelled")
        try { UseCaseRunner(Dispatchers.Unconfined).io<Unit> { throw expected }; fail("must cancel") }
        catch (actual: CancellationException) { assertSame(expected, actual) }
    }
    @Test fun cancelledCallerCannotStartWrite() = runBlocking {
        var wrote = false
        val child = launch(Dispatchers.Unconfined) {
            currentCoroutineContext().cancel()
            UseCaseRunner(Dispatchers.Unconfined).write { wrote = true }
        }
        child.join()
        assertFalse(wrote)
        assertTrue(child.isCancelled)
    }
    @Test fun cancellationAfterWriteStartsDoesNotLoseCommit() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = AtomicBoolean()
        val child = launch(Dispatchers.Default) {
            UseCaseRunner(Dispatchers.IO).write {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                committed.set(true)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        child.cancel()
        release.countDown()
        child.join()
        assertTrue(committed.get())
    }
}
