package com.greatwall.callring.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class WindowPlacementTest {
    @Test fun dragStaysInsideSafeDisplayBounds() {
        assertEquals(WindowPosition(10, 20), clampWindow(-90, -80, 300, 200, WindowBounds(10, 20, 1010, 2020)))
        assertEquals(WindowPosition(710, 1820), clampWindow(2000, 3000, 300, 200, WindowBounds(10, 20, 1010, 2020)))
    }
    @Test fun oversizedWindowAnchorsToVisibleTopLeftWithoutInvalidRange() {
        assertEquals(WindowPosition(10, 20), clampWindow(50, 80, 2000, 3000, WindowBounds(10, 20, 1010, 2020)))
    }
}
