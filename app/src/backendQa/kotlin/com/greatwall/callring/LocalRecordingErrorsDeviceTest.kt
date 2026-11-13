package com.greatwall.callring

import android.test.InstrumentationTestCase
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.platform.recording.AndroidPcmDecoder
import java.io.File

/** Exercises the real device extractor without models, network, or production files. */
class LocalRecordingErrorsDeviceTest : InstrumentationTestCase() {
    fun testUnreadableAudioHasLocalErrorCode() {
        val context = instrumentation.targetContext
        assertEquals("com.greatwall.callring.backendqa", context.packageName)
        val audio = File.createTempFile("invalid-local-audio-", ".bin", context.noBackupFilesDir)
        try {
            audio.writeText("not an audio track")
            val failure = try {
                AndroidPcmDecoder().decode(audio, { false }, 16_000) {}
                null
            } catch (failure: AiFailure) { failure }
            assertNotNull("Malformed audio must fail locally", failure)
            assertTrue(failure!!.reason in setOf("recording.unsupported_audio", "recording.audio_decode_failed"))
        } finally { audio.delete() }
    }
}
