package com.greatwall.callring.platform.ai

import com.greatwall.callring.platform.*
import java.time.Clock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingSnapshotSourceTest {
    @Test fun independentEventsAndFullArchiveTailSurviveStatisticsAbsence() {
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "full_recording", cipher)
        val call = store.registerIncoming("session", "999", 1, "account")
        store.linkStatistics(9, "session")
        val old = store.get(call.id)!!
        store.compareAndSet(call.id, old.generation, old.copy(generation = old.generation + 1, audioPath = "/private/audio", transcript = "x".repeat(210) + "FULL-TAIL"))
        val parts = store.captureRecordingText()
        assertTrue(parts.getValue("D15").encode().contains("session"))
        assertTrue(parts.getValue("D16").encode().contains("FULL-TAIL"))
        assertFalse(parts.getValue("D16").encode().contains("/private/audio"))
    }
    @Test fun diagnosticsExportIncludesAllStoredRowsAndFailsOnCorruption() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("full_diagnostics", 0)
        prefs.edit().putString("diagnostics_v2", (1..25).joinToString("\n") { "$it\taudio.playing" }).putString("events", "12:00:00  legacy-full-tail").commit()
        AndroidDiagnosticsStore(prefs, Clock.systemUTC()).use { source ->
            assertEquals(26, source.captureStoredText().size)
            assertEquals("legacy-full-tail", source.captureStoredText().last().message)
            prefs.edit().putString("diagnostics_v2", "bad-row").commit()
            assertTrue(runCatching { source.captureStoredText() }.isFailure)
        }
    }
}
