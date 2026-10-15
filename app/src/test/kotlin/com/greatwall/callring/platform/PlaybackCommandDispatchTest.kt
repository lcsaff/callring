package com.greatwall.callring.platform

import com.greatwall.callring.application.api.ApiError
import com.greatwall.callring.application.api.ErrorCode
import com.greatwall.callring.application.ports.BackendFailure
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlaybackCommandDispatchTest {
    @Test
    fun deniedServicePermissionHasStructuredPermissionFailureWithoutPlatformText() {
        val failure =
            assertThrows(BackendFailure::class.java) {
                dispatchPlaybackCommand<Unit> { throw SecurityException("sensitive platform text") }
            }
        assertEquals(ErrorCode.PERMISSION_REQUIRED, failure.error.code)
        assertEquals("audio.service_permission_required", failure.error.message)
        assertTrue(failure.error.requiredCapabilities.isEmpty())
        assertFalse(failure.error.retryable)
    }

    @Test
    fun backgroundServiceStartRestrictionIsNotReportedAsStorageFailure() {
        val failure =
            assertThrows(BackendFailure::class.java) {
                dispatchPlaybackCommand<Unit> {
                    throw IllegalStateException("background start denied")
                }
            }
        assertEquals(ErrorCode.SYSTEM_RESTRICTED, failure.error.code)
        assertEquals("audio.service_start_restricted", failure.error.message)
        assertTrue(failure.error.retryable)
    }

    @Test
    fun unavailableServiceAndUnexpectedPlatformFailuresAreSystemUnavailable() {
        val missing =
            assertThrows(BackendFailure::class.java) { dispatchPlaybackCommand<String> { null } }
        val platform =
            assertThrows(BackendFailure::class.java) {
                dispatchPlaybackCommand<Unit> {
                    throw IllegalArgumentException("invalid platform state")
                }
            }
        listOf(missing, platform).forEach {
            assertEquals(ErrorCode.SYSTEM_UNAVAILABLE, it.error.code)
            assertEquals("audio.service_unavailable", it.error.message)
            assertTrue(it.error.retryable)
        }
    }

    @Test
    fun successfulDispatchReturnsItsReceipt() {
        val receipt = Any()
        assertSame(receipt, dispatchPlaybackCommand { receipt })
    }

    @Test
    fun cancellationAndExistingStructuredFailuresArePreserved() {
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                dispatchPlaybackCommand<Unit> { throw cancelled }
            },
        )
        val structured = BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
        assertSame(
            structured,
            assertThrows(BackendFailure::class.java) {
                dispatchPlaybackCommand<Unit> { throw structured }
            },
        )
    }
}
