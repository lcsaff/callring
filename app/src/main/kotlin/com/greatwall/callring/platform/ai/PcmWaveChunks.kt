package com.greatwall.callring.platform.ai

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Streams mono 16 kHz PCM into independently uploadable WAV files, without dropping the tail. */
internal class PcmWaveChunks(private val directory: File, private val samplesPerChunk: Int = 16_000 * 300) {
    private val files = mutableListOf<File>()
    private var output: BufferedOutputStream? = null
    private var sampleCount = 0
    var audibleSamples = 0L
        private set
    init { require(samplesPerChunk > 0); check(directory.isDirectory || directory.mkdirs()) }
    fun write(sample: Short) {
        if (output == null) {
            val file = File(directory, UUID.randomUUID().toString() + ".wav")
            files += file
            output = BufferedOutputStream(FileOutputStream(file), 64 * 1024).also { it.write(ByteArray(44)) }
            sampleCount = 0
        }
        val value = sample.toInt()
        output!!.write(value and 255); output!!.write((value shr 8) and 255)
        sampleCount++
        if (kotlin.math.abs(value) > 64) audibleSamples++
        if (sampleCount >= samplesPerChunk) closeChunk()
    }
    private fun closeChunk() {
        val current = output ?: return
        current.flush(); current.close(); output = null
        val length = sampleCount * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(length + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(length)
        RandomAccessFile(files.last(), "rw").use { it.write(header.array()) }
    }
    fun finish(): List<File> { closeChunk(); return files.toList() }
    fun abort() { try { output?.close() } finally { output = null; files.forEach { it.delete() } } }
}
