package com.greatwall.callring.data.backup

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyInterruptedSwitchRecoversEntireOldGeneration() {
        // Simulate process death: never rely on catch/finally in the interrupted invocation.
        for (stopAt in 0..5) {
            val root = temporary.newFolder("case$stopAt")
            val live = File(root, "live").apply { mkdir() }
            val stage = File(root, "stage").apply { mkdir() }
            listOf("core.db", "recordings.db", "preferences").forEach {
                File(live, it).writeText("old-$it")
                File(stage, it).writeText("new-$it")
            }
            val work = File(root, "journal")
            var steps = 0
            val journal = RestoreJournal(work, live, checkpoint = { if (steps++ == stopAt) throw SimulatedDeath() }, syncDirectory = {})
            try { journal.replace(stage, listOf("core.db", "recordings.db", "preferences")) } catch (_: SimulatedDeath) {}
            RestoreJournal(work, live, syncDirectory = {}).recover()
            val values = live.listFiles()!!.filter { !it.name.startsWith(".") }.associate { it.name to it.readText() }
            assertEquals(3, values.size)
            assertTrue("Mixed resources at interruption $stopAt: $values", values.all { (name, data) -> data == "old-$name" } || values.all { (name, data) -> data == "new-$name" })
        }
    }

    @Test fun completedCommitSurvivesRestartAndPreservesAbsentResourceSemantics() {
        val live = temporary.newFolder("live")
        val stage = temporary.newFolder("stage")
        File(live, "core.db").writeText("old")
        File(stage, "core.db").writeText("new")
        File(stage, "audio").apply { mkdir() }.resolve("recording.wav").writeText("audio")
        val work = File(temporary.root, "journal")
        RestoreJournal(work, live, syncDirectory = {}).replace(stage, listOf("core.db", "audio"))
        RestoreJournal(work, live, syncDirectory = {}).recover()
        assertEquals("new", File(live, "core.db").readText())
        assertEquals("audio", File(live, "audio/recording.wav").readText())
        assertFalse(work.exists())
    }

    @Test fun traversalAndDuplicateTargetsAreRejectedBeforeChangingLiveData() {
        val live = temporary.newFolder("live")
        val stage = temporary.newFolder("stage")
        File(live, "core.db").writeText("old")
        val journal = RestoreJournal(File(temporary.root, "journal"), live, syncDirectory = {})
        for (names in listOf(listOf("../outside"), listOf("core.db", "core.db"), listOf("/absolute"))) {
            assertThrows(BackupFormatException::class.java) { journal.replace(stage, names) }
        }
        assertEquals("old", File(live, "core.db").readText())
    }

    @Test fun recoveryAndCleanupCanBeInterruptedRepeatedlyWithoutLosingOriginals() {
        for (stopAt in 0..16) {
            val root = temporary.newFolder("recovery$stopAt")
            val live = File(root, "live").apply { mkdir() }
            val stage = File(root, "stage").apply { mkdir() }
            for (name in listOf("core.db", "recordings.db", "preferences")) {
                File(live, name).writeText("old-$name"); File(stage, name).writeText("new-$name")
            }
            val work = File(root, "journal")
            var switches = 0
            try { RestoreJournal(work, live, checkpoint = { if (switches++ == 2) throw SimulatedDeath() }, syncDirectory = {}).replace(stage, listOf("core.db", "recordings.db", "preferences")) } catch (_: SimulatedDeath) {}
            var steps = 0
            try { RestoreJournal(work, live, checkpoint = { if (steps++ == stopAt) throw SimulatedDeath() }, syncDirectory = {}).recover() } catch (_: SimulatedDeath) {}
            RestoreJournal(work, live, syncDirectory = {}).recover()
            for (name in listOf("core.db", "recordings.db", "preferences")) assertEquals("old-$name", File(live, name).readText())
        }
    }

    @Test fun failingCacheApplicationBeforeCommitRollsBackBothDatabasesAndPreferences() {
        val live = temporary.newFolder("cache-live")
        val stage = temporary.newFolder("cache-stage")
        val names = listOf("core.db", "recordings.db", "preferences")
        names.forEach { File(live, it).writeText("old"); File(stage, it).writeText("new") }
        val work = File(temporary.root, "cache-journal")
        assertThrows(IllegalStateException::class.java) {
            RestoreJournal(work, live, syncDirectory = {}).replace(stage, names) { error("cache.commit_failed") }
        }
        RestoreJournal(work, live, syncDirectory = {}).recover()
        names.forEach { assertEquals("old", File(live, it).readText()) }
    }
    @Test fun cleanupFailureAfterDurableCommitReturnsSuccessAndRestartKeepsNewGeneration() {
        val live = temporary.newFolder("committed-live")
        val stage = temporary.newFolder("committed-stage")
        File(live, "core.db").writeText("old"); File(stage, "core.db").writeText("new")
        val work = File(temporary.root, "committed-journal")
        var steps = 0
        RestoreJournal(work, live, checkpoint = { if (steps++ == 3) throw java.io.IOException("cleanup.unavailable") }, syncDirectory = {}).replace(stage, listOf("core.db"))
        assertEquals("new", File(live, "core.db").readText())
        RestoreJournal(work, live, syncDirectory = {}).recover()
        assertEquals("new", File(live, "core.db").readText())
        assertFalse(work.exists())
    }
    private class SimulatedDeath : Error()
}
