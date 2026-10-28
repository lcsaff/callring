package com.greatwall.callring.platform.storage

import android.content.Context
import org.robolectric.RuntimeEnvironment
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class AndroidStorageInventoryTest {
    @Test fun observesOwnedFilesSeparatelyAndPreservesUnknownSpace() {
        val context = RuntimeEnvironment.getApplication()
        val audio = File(context.filesDir,"inventory-test-audio").apply { mkdirs() }
        val sound = File(audio,"owned.m4a").apply { writeBytes(ByteArray(321)) }
        val scratch = File(audio,"active.tmp").apply { writeBytes(ByteArray(17)) }
        val observed = AndroidStorageInventory(context,audio) { null }.sample()
        assertEquals(321L,observed.ownedAudioBytes)
        assertTrue(requireNotNull(observed.temporaryBytes) >= 17L)
        assertNull(observed.freeBytes)
        assertTrue(sound.exists()); assertTrue(scratch.exists())
        audio.deleteRecursively()
    }
}
