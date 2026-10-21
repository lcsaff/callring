package com.greatwall.callring.platform

import com.greatwall.callring.platform.audio.AudioFiles
import com.greatwall.callring.platform.audio.AudioGeneration
import com.greatwall.callring.platform.audio.prepareAudioBackup
import com.greatwall.callring.platform.audio.replaceAudioAtomically
import com.greatwall.callring.platform.document.DocumentLimitExceeded
import com.greatwall.callring.platform.document.copyBounded
import com.greatwall.callring.platform.document.validateDocumentUri

import com.greatwall.callring.platform.audio.sanitizeAudioName

import com.greatwall.callring.data.codec.StrictJson

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class PlatformSafetyTest {
    @Test
    fun calendarJsonRequiresStrictGrammarAndRejectsDuplicateKeys() {
        StrictJson.validate("{\"year\":2026,\"items\":[true,null,1.2e3,\"ok\"]}")
        listOf(
                "{'year':2026}",
                "{year:2026}",
                "{\"year\":2026,}",
                "{\"year\":01}",
                "{} trailing",
                "{\"year\":2026,\"year\":2027}",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { StrictJson.validate(it) }
            }
    }

    @Test
    fun rejectsFileAndMalformedDocumentReferences() {
        listOf(
                "file:///private/audio",
                "content:opaque",
                "https://host/x",
                "content:///missing",
                "content://host/x#fragment",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { validateDocumentUri(it) }
            }
        validateDocumentUri("content://documents/tree/audio%20file")
    }

    @Test
    fun boundedCopyAcceptsExactLimitAndRejectsOneMoreByte() {
        val target = ByteArrayOutputStream()
        copyBounded(ByteArrayInputStream(ByteArray(8)), target, 8)
        assertEquals(8, target.size())
        assertThrows(DocumentLimitExceeded::class.java) {
            copyBounded(ByteArrayInputStream(ByteArray(9)), ByteArrayOutputStream(), 8)
        }
    }

    @Test
    fun cancellationDuringDocumentReadPreventsCopyingOrPublishingItsBytes() {
        val cancelled = AtomicBoolean(false)
        val input =
            object : ByteArrayInputStream(ByteArray(8)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    cancelled.set(true)
                    return count
                }
            }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            copyBounded(input, output, 8) { if (cancelled.get()) throw CancellationException() }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun cancellationAfterPreparationPreservesPreviousFileAndMetadata() {
        val directory = Files.createTempDirectory("audio-cancelled").toFile()
        try {
            val selected = directory.resolve("selected_audio").apply { writeText("old") }
            val candidate = directory.resolve("candidate").apply { writeText("new") }
            val gate = AudioGeneration()
            val ticket = gate.begin()
            var metadata = "old"
            assertThrows(CancellationException::class.java) {
                gate.publish(ticket, { throw CancellationException() }) {
                    replaceAudioAtomically(candidate, selected, prepareAudioBackup(selected)) { metadata = "new" }
                }
            }
            assertEquals("old", selected.readText())
            assertEquals("old", metadata)
            assertEquals("new", candidate.readText())
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun cancellationBeforeAtomicRenamePreservesFileAndMetadata() {
        val directory = Files.createTempDirectory("audio-cancelled-backup").toFile()
        try {
            val selected = directory.resolve("selected_audio").apply { writeText("old") }
            val candidate = directory.resolve("candidate").apply { writeText("new") }
            var metadata = "old"
            assertThrows(CancellationException::class.java) {
                replaceAudioAtomically(candidate, selected, prepareAudioBackup(selected), { throw CancellationException() }) {
                    metadata = "new"
                }
            }
            assertEquals("old", selected.readText())
            assertEquals("old", metadata)
            assertEquals("new", candidate.readText())
            assertFalse(directory.listFiles()!!.any { it.name.startsWith("audio-backup-") })
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun cancellationAfterAtomicRenameCannotSplitFileAndMetadataCommit() {
        val directory = Files.createTempDirectory("audio-commit-boundary").toFile()
        try {
            val selected = directory.resolve("selected_audio").apply { writeText("old") }
            val candidate = directory.resolve("candidate").apply { writeText("new") }
            var cancelled = false
            var metadata = "old"
            replaceAudioAtomically(
                candidate,
                selected,
                prepareAudioBackup(selected),
                { if (cancelled) throw CancellationException() },
            ) {
                cancelled = true
                metadata = "new"
            }
            assertEquals("new", selected.readText())
            assertEquals("new", metadata)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun audioDirectoryResolvesOnlyWhenAnOperationUsesItsFiles() {
        val creatingThread = Thread.currentThread()
        var resolvedOn: Thread? = null
        val files = AudioFiles {
            resolvedOn = Thread.currentThread()
            java.io.File("fixture")
        }
        assertNull(resolvedOn)
        val operation = Thread { assertEquals("selected_audio", files.selected.name) }
        operation.start()
        operation.join(5000)
        assertFalse(operation.isAlive)
        assertSame(operation, resolvedOn)
        assertNotSame(creatingThread, resolvedOn)
    }

    @Test
    fun restoringOrNewerImportInvalidatesOldGeneration() {
        val gate = AudioGeneration()
        val older = gate.begin()
        val newer = gate.begin()
        var published = false
        assertFalse(gate.publish(older, {}) { published = true })
        assertFalse(published)
        assertTrue(gate.publish(newer, {}) { published = true })
        gate.invalidate { published = false }
        assertFalse(gate.publish(newer, {}) { published = true })
        assertFalse(published)
    }

    @Test
    fun restoreInvalidatesImportWhileItsCopyIsStillRunning() {
        val gate = AudioGeneration()
        val copying = CountDownLatch(1)
        val copied = CountDownLatch(1)
        var selected = "old"
        val worker = Thread {
            val ticket = gate.begin()
            copying.countDown()
            check(copied.await(5, TimeUnit.SECONDS))
            gate.publish(ticket, {}) { selected = "stale" }
        }
        worker.start()
        assertTrue(copying.await(5, TimeUnit.SECONDS))
        gate.invalidate { selected = "built_in" }
        copied.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals("built_in", selected)
    }

    @Test
    fun importedNamesCannotExposePathsOrControlCharacters() {
        assertEquals("ring.wav", sanitizeAudioName("/private/files/ring.wav"))
        assertEquals("tone.mp3", sanitizeAudioName("C:\\private\\tone.mp3\n"))
        assertEquals("audio.imported", sanitizeAudioName("\n"))
    }

    @Test
    fun failedMetadataCommitRestoresPreviousAudioFile() {
        val directory = Files.createTempDirectory("audio-transaction").toFile()
        try {
            val selected = directory.resolve("selected_audio").apply { writeText("old") }
            val candidate = directory.resolve("candidate").apply { writeText("new") }
            assertThrows(java.io.IOException::class.java) {
                replaceAudioAtomically(candidate, selected, prepareAudioBackup(selected)) {
                    throw java.io.IOException("prefs_failed")
                }
            }
            assertEquals("old", selected.readText())
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
