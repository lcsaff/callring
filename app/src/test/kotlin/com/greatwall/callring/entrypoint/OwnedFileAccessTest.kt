package com.greatwall.callring.entrypoint

import com.greatwall.callring.platform.ownership.OwnedFileAccess
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OwnedFileAccessTest {
    @Test fun sealRejectsNewAccessAndCloseDrainsPreparedLeaseWithoutHoldingMonitor() {
        val access = OwnedFileAccess()
        val prepared = access.acquire()
        val drained = CountDownLatch(1)
        access.seal()
        val closer = Thread { access.close(); drained.countDown() }.apply { start() }
        try {
            assertThrows(IllegalStateException::class.java) { access.acquire() }
            assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
            prepared.close(); prepared.close()
            assertTrue(drained.await(2, TimeUnit.SECONDS))
        } finally { prepared.close(); closer.join(2000) }
        assertFalse(closer.isAlive)
    }
}
