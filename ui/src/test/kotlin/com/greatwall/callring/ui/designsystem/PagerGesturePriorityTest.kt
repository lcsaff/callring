package com.greatwall.callring.ui.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.ui.designsystem.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PagerGesturePriorityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slowHorizontalControlDragKeepsItsOwnGestureThroughSmallInitialMoves() {
        lateinit var scroll: ScrollState
        lateinit var gate: PagerGestureGate
        compose.setContent {
            scroll = rememberScrollState()
            gate = remember { PagerGestureGate() }
            CompositionLocalProvider(LocalPagerGestureGate provides gate) {
                Box(Modifier.size(200.dp).testTag("control").pagerGesturePriority().horizontalScroll(scroll)) {
                    Box(Modifier.width(800.dp).height(100.dp))
                }
            }
        }
        compose.onNodeWithTag("control").performTouchInput {
            down(Offset(180f, 100f))
            (1..40).forEach { moveTo(Offset(180f - it * 2f, 100f), delayMillis = 16) }
            up()
        }
        compose.runOnIdle { assertTrue("child still scrolls after sub-slop motion", scroll.value > 0); assertEquals(0, gate.held) }
    }
}
