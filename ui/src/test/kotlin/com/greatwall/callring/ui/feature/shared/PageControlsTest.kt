package com.greatwall.callring.ui.feature.shared

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.ui.designsystem.CallringTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(pageIndex: Int, pageSize: Int, totalItems: Long, loading: Boolean = false) {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            Column { PageControls(pageIndex, pageSize, totalItems, loading) {} }
        } }
    }

    @Test fun zeroItemsHideEveryPageControl() {
        show(0, 50, 0)
        listOf("上一页", "下一页").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("第 1 页", substring = true).assertDoesNotExist()
        compose.onNodeWithText("共 0 条", substring = true).assertDoesNotExist()
    }

    @Test fun exactlyOnePageHidesEveryPageControl() {
        show(0, 50, 50)
        listOf("上一页", "下一页").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("第 1 页", substring = true).assertDoesNotExist()
    }

    @Test fun oneItemBeyondTheFirstPageShowsControlsAndDisablesPrevious() {
        show(0, 50, 51)
        compose.onNodeWithText("上一页").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertExists().assertIsEnabled()
        compose.onNodeWithText("第 1 页 · 共 51 条").assertExists()
    }

    @Test fun lastPageDisablesNextAndKeepsOneBasedPageNumber() {
        show(1, 50, 51)
        compose.onNodeWithText("上一页").assertIsEnabled()
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithText("第 2 页 · 共 51 条").assertExists()
    }

    @Test fun loadingDisablesBothDirectionButtons() {
        show(2, 50, 200, loading = true)
        compose.onNodeWithText("上一页").assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithText("第 3 页 · 共 200 条").assertExists()
    }
}
