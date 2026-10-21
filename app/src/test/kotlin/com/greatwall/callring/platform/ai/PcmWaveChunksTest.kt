package com.greatwall.callring.platform.ai

import com.greatwall.callring.platform.recording.PcmWaveChunks

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PcmWaveChunksTest {
    @Test fun chunkBoundaryPreservesEverySampleAndWritesCorrectWaveLengths() {
        val directory = Files.createTempDirectory("wave-chunks").toFile()
        try {
            val writer = PcmWaveChunks(directory, 3)
            (1..8).forEach { writer.write(it.toShort()) }
            val chunks = writer.finish()
            assertEquals(listOf(50L, 50L, 48L), chunks.map { it.length() })
            val samples = chunks.flatMap { file ->
                val bytes = file.readBytes()
                assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(bytes.size - 8, buffer.getInt(4))
                assertEquals(bytes.size - 44, buffer.getInt(40))
                (44 until bytes.size step 2).map { buffer.getShort(it).toInt() }
            }
            assertEquals((1..8).toList(), samples)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun abortRemovesPartialChunks() {
        val directory = Files.createTempDirectory("wave-abort").toFile()
        try {
            val writer = PcmWaveChunks(directory, 2)
            repeat(5) { writer.write(100) }
            writer.abort()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.delete() }
    }
}
