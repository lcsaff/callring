package com.greatwall.callring.ui.feature.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeReadinessJvmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownReadinessOffersAnExplicitCheckWithoutLaunchingPermissions() {
        val home = runBlocking { (UiFakeApi().load() as ApiResult.Success).value }
        compose.setContent { HomeScreen(LoadState(home, loaded = true), {}, {}) }
        compose.onNodeWithText("核心来电条件待确认").assertExists()
        compose.onNodeWithText("检查配置").assertExists()
    }
}
