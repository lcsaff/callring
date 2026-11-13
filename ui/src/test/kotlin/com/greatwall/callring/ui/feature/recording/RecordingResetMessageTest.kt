package com.greatwall.callring.ui.feature.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingResetMessageTest {
    @Test fun resetPauseExplainsRetainedContentAndExplicitResume() {
        assertEquals("清空号码档案后已暂停旧录音处理；录音、转写和分析已保留，可手动重试继续处理。",
            recordingMessage("recording.reset_retry_required"))
    }
}
