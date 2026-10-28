package com.greatwall.callring.ui.feature.backup

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.backup.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun validatedPreviewDoesNotRestoreUntilExplicitLocalConfirmation() {
        var restores = 0
        val preview = BackupPreview("bound", BackupSummary(1, 2, 3, 4, 0, 0, false, false))
        compose.setContent {
            BackupScreen(BackupUiState(preview = preview), BackupProgress(BackupPhase.READY), "", false,
                {}, {}, {}, {}, {}, { restores++ }, {})
        }
        assertEquals(0, restores)
        compose.onNodeWithText("恢复此备份").performScrollTo().performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("恢复此备份").performClick()
        compose.onNodeWithText("确认恢复", useUnmergedTree = true).performClick()
        assertEquals(1, restores)
    }
}
