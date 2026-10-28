package com.greatwall.callring.platform.storage

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class StorageSpaceGuardTest {
    @Test fun unknownAndLowSpaceFailBeforeWriting() {
        for (bytes in listOf<Long?>(null,0,1024)) {
            assertTrue(runCatching { StorageSpaceGuard.requireAvailable(bytes, 4096) }.exceptionOrNull() is IOException)
        }
        StorageSpaceGuard.requireAvailable(4096,4096)
    }
}
