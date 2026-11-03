package com.greatwall.callring.ui.feature.storage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.ui.state.LoadState
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class StorageManagementScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun deletionRequiresDialogAndUnknownIsDistinctFromZero() {
        var confirmations=0
        val preview=ArchiveCleanupPreview("token",listOf(CleanupTarget(-1,1)),ArchiveCleanupSelection(0,100,setOf(ArchiveStatus.COMPLETE)),0,1,1000)
        compose.setContent {
            StorageManagementScreen(LoadState(StorageInventory(null,0,0,0,null),loaded=true),StorageCleanupUiState(preview=preview),
                ZoneId.of("Asia/Shanghai"),{},{},{},{},{confirmations++},{})
        }
        compose.onNodeWithText("自有录音音频：未知").assertIsDisplayed()
        compose.onNodeWithText("数据库：0 字节").assertIsDisplayed()
        compose.onNodeWithTag("storage-content").performScrollToIndex(1)
        compose.onNodeWithText("删除这些归档").performScrollTo().performClick()
        assertEquals(0,confirmations)
        compose.onNodeWithText("确认删除录音归档").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0,confirmations)
        compose.onNodeWithText("删除这些归档").performClick()
        compose.onNodeWithText("确认删除").performClick()
        assertEquals(1,confirmations)
    }
}
