package com.greatwall.callring.ui.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.callers.CallerSearch
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class HomePagerInterruptionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arrivingModalRollsBackHeldDragAndDoesNotVisitTentativePage() {
        val finish = CompletableDeferred<UpdateStatus>()
        var searches = 0
        val api = object : UiFakeApi() {
            override suspend fun search(query: CallerSearch) = super.search(query).also { searches++ }
            override val update = object : AppUpdateApi by FixtureAppUpdateApi {
                override suspend fun status() = ApiResult.Success(UpdateStatus(AppVersionInfo("0.1.0", 1), UpdatePhase.IDLE))
                override suspend fun check() = ApiResult.Success(finish.await())
            }
        }
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("home-pager").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), delayMillis = 300)
        }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.runOnIdle {
            finish.complete(UpdateStatus(AppVersionInfo("0.1.0", 1), UpdatePhase.UPDATE_AVAILABLE,
                AppReleaseInfo("0.2.0", 2, null, "测试", "test.apk", 1, "sha", "https://example.test/test.apk")))
        }
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithTag("tab-home").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected().assertIsEnabled()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, searches) }
    }

    @Test fun immediateBackDuringDetailTransitionPopsDetailAndKeepsSourcePage() {
        val api = HomePagerFixture()
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {})
        }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("档案0").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("档案0").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNodeWithTag("caller-list").assertIsDisplayed()
    }

    @Test fun externalPageRequestWaitsForQuietEditorToClose() {
        val api = UiFakeApi()
        val target = mutableStateOf<UiDestination>(UiDestination.Home)
        val sequence = mutableLongStateOf(0)
        compose.setContent { CallringApp(api, target.value, sequence.longValue,
            onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        compose.onNodeWithText("自定义免打扰").assertIsDisplayed()
        compose.runOnIdle { target.value = UiDestination.Callers; sequence.longValue = 1 }
        compose.onNodeWithText("自定义免打扰").assertIsDisplayed()
        compose.onNodeWithTag("tab-home").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("quiet-dialog-cancel").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("tab-callers").assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }
}
