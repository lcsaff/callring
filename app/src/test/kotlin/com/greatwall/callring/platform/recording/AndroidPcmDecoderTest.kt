package com.greatwall.callring.platform.recording

import com.greatwall.callring.application.ports.recording.AiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPcmDecoderTest {
    @Test fun unsupportedAudioReportsLocalRecordingFailure() {
        val audio = Files.createTempFile("unsupported-audio", ".bin").toFile()
        try {
            audio.writeText("not an audio track")
            val failure = assertThrows(AiFailure::class.java) { AndroidPcmDecoder().decode(audio, { false }, 16_000) {} }
            assertEquals("recording.unsupported_audio", failure.reason)
        } finally { audio.delete() }
    }
}
