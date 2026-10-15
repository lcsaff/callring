package com.greatwall.callring.ui.overlay

data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class WindowPosition(val x: Int, val y: Int)

fun clampWindow(x: Int, y: Int, width: Int, height: Int, bounds: WindowBounds) = WindowPosition(
    x.coerceIn(bounds.left, (bounds.right - width.coerceAtLeast(0)).coerceAtLeast(bounds.left)),
    y.coerceIn(bounds.top, (bounds.bottom - height.coerceAtLeast(0)).coerceAtLeast(bounds.top))
)
