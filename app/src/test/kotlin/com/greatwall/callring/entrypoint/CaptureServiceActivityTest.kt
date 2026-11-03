package com.greatwall.callring.entrypoint

import com.greatwall.callring.entrypoint.recording.CaptureServiceActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureServiceActivityTest {
    @Test fun busyCoversDeferredFinalAttachmentUntilWorkerDrain() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activity = CaptureServiceActivity()
        try {
            activity.started(); activity.started()
            worker.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            activity.shutdown(worker)
            assertTrue(CaptureServiceActivity.busy())
            release.countDown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            assertFalse(CaptureServiceActivity.busy())
            activity.shutdown(worker)
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
