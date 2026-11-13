package com.greatwall.callring.ui.feature.recording

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class ArchiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun transcriptionFailureAppearsInsideTranscriptSection() {
        compose.setContent {
            ArchiveScreen(LoadState(CallArchive(1, "31234", 0, status = ArchiveStatus.FAILED,
                audioPath = "owned", error = "recording.silent_audio"), loaded = true), LoadState(), MutationState(),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNode(hasText(recordingMessage("recording.silent_audio")) and
            hasAnyAncestor(hasTestTag("archive-section-转写文本"))).assertExists()
    }

    @Test fun waitingCaptureFailureExplainsMissingAudioAndOffersImport() {
        var imports = 0
        compose.setContent {
            ArchiveScreen(LoadState(CallArchive(1, "31234", 0, status = ArchiveStatus.WAITING_RECORDING,
                error = "recording.permission_required"), loaded = true), LoadState(), MutationState(),
                {}, {}, { imports++ }, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText(recordingMessage("recording.permission_required")).assertExists()
        compose.onNodeWithText("导入录音").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
    }

    @Test fun managementLivesInMenuAndDeletionStillRequiresConfirmation() {
        var deletes = 0
        show(CallArchive(1, "31234", 0, status = ArchiveStatus.READY, audioPath = "owned"), onDelete = { deletes++ })
        compose.onNodeWithText("导入音频文件").assertDoesNotExist()
        compose.onNodeWithText("删除本次归档").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("导入音频文件").assertExists()
        compose.onNodeWithText("导入用于离线转写").assertDoesNotExist()
        compose.onNodeWithText("删除本次归档").performClick()
        compose.onNodeWithText("删除本次归档？").assertExists()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun transcriptPreviewExpandsAndAnalysisEvidenceStartsCollapsed() {
        val transcript = "待确认的通话内容。".repeat(50) + "全文结尾"
        show(CallArchive(1, "31234", 0, status = ArchiveStatus.COMPLETE, audioPath = "owned", transcript = transcript,
            analysis = CallAnalysis(summary = "确认明日交付时间", evidence = "我是项目组的陈明")))
        compose.onNodeWithText(transcript).assertDoesNotExist()
        compose.onNodeWithText("展开全文").performScrollTo().performClick()
        compose.onNodeWithText(transcript).assertExists()
        compose.onNodeWithText("收起全文").performScrollTo().performClick()
        compose.onNodeWithText("确认明日交付时间").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("自述依据：我是项目组的陈明").assertDoesNotExist()
        compose.onNodeWithText("分析依据与其他信息").performScrollTo().performClick()
        compose.onNodeWithText("自述依据：我是项目组的陈明").performScrollTo().assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun narrowLayoutKeepsActionsAvailableWithLargeFonts() {
        val archive = CallArchive(1, "31234", 0, status = ArchiveStatus.COMPLETE, audioPath = "owned",
            transcript = "您好，我是项目组的陈明。方案里的交付时间需要再确认一下。明天上午核对排期，再给您回复。",
            analysis = CallAnalysis(summary = "确认方案交付时间，并核对预算附件。", actionItems = listOf("明天上午核对排期并回复")))
        show(archive, fontScale = 1.6f)
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        val destination = File("build/qa/cleanup-call-detail-large.png")
        destination.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            destination.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("复制全文").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("分析依据与其他信息").performScrollTo().performClick()
        compose.onNodeWithText("AI 结果可能不准确，不能作为已经核实的身份。").performScrollTo().assertIsDisplayed()
    }

    private fun show(archive: CallArchive, onDelete: () -> Unit = {}, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
                    ArchiveScreen(LoadState(archive, loaded = true), LoadState(), MutationState(),
                        {}, {}, {}, {}, {}, {}, onDelete, {}, {}, {},
                        zone = LoadState(java.time.ZoneId.of("Asia/Shanghai"), loaded = true))
                }
            }
        }
    }
}
