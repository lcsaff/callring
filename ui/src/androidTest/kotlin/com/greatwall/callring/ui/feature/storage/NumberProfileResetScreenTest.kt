package com.greatwall.callring.ui.feature.storage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.ui.state.LoadState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/** The connected UI test package is isolated from application data and system providers. */
class NumberProfileResetScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainingRecordingsIsTheDefaultAndRequiresFinalConfirmation() {
        var confirmed: Boolean? = null
        compose.setContent {
            StorageManagementScreen(
                LoadState(StorageInventory(50, 0, 0, 0, 100), loaded = true), StorageCleanupUiState(),
                ZoneId.of("Asia/Shanghai"), {}, {}, {}, {}, {}, {},
                NumberProfileResetUiState(NumberProfileResetPreview("reset", 3, 12, 2, 4, 50, 1000)),
                {}, {}, { confirmed = it })
        }
        compose.onNodeWithTag("storage-content").performScrollToIndex(1)
        compose.onNodeWithText("继续清空全部档案").performScrollTo().performClick()
        assertNull(confirmed)
        compose.onNodeWithTag("reset-keep-recordings").assertIsOn()
        compose.onNodeWithText("确认清空").performClick()
        assertEquals(true, confirmed)
    }

    @Test fun finalConfirmationRestoresAnExplicitDiscardChoice() {
        var confirmed: Boolean? = null
        val restore = StateRestorationTester(compose)
        restore.setContent {
            StorageManagementScreen(
                LoadState(StorageInventory(50, 0, 0, 0, 100), loaded = true), StorageCleanupUiState(),
                ZoneId.of("Asia/Shanghai"), {}, {}, {}, {}, {}, {},
                NumberProfileResetUiState(NumberProfileResetPreview("reset", 3, 12, 2, 4, 50, 1000)),
                {}, {}, { confirmed = it })
        }
        compose.onNodeWithTag("storage-content").performScrollToIndex(1)
        compose.onNodeWithText("继续清空全部档案").performScrollTo().performClick()
        compose.onNodeWithTag("reset-keep-recordings").assertIsOn().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("再次确认清空全部号码档案").assertIsDisplayed()
        compose.onNodeWithTag("reset-keep-recordings").assertIsOff()
        assertNull(confirmed)
        compose.onNodeWithText("确认清空").performClick()
        assertEquals(false, confirmed)
    }
}
