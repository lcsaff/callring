package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/** Interaction ownership only; controls keep receiving their original pointer events. */
@Stable
internal class PagerGestureGate {
    var held by mutableIntStateOf(0)
        private set
    fun acquire() { held++ }
    fun release() { held-- }
}

internal val LocalPagerGestureGate = staticCompositionLocalOf<PagerGestureGate?> { null }

/** A gesture starting in a text editor/selection/horizontal control never pages at its edge. */
internal fun Modifier.pagerGesturePriority(): Modifier = composed {
    val gate = LocalPagerGestureGate.current
    if (gate == null) this else {
        val stopHorizontalPropagation = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
                override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
            }
        }
        nestedScroll(stopHorizontalPropagation).pointerInput(gate) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gate.acquire()
                try {
                    // Descendants see Main first. Consume only horizontal motion left over by
                    // the editor/control before the parent Pager sees this same event.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { change ->
                            val motion = change.position - down.position
                            if (change.id == down.id && abs(motion.x) > viewConfiguration.touchSlop &&
                                abs(motion.x) > abs(motion.y)) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                } finally { gate.release() }
            }
        }
    }
}
