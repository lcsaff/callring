package com.greatwall.callring.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*

/** Owns only the system window and its Compose tree lifecycle. All calls are on main. */
internal class OverlayWindow(private val context: Context, controller: OverlayController, onReport: (String) -> Unit) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var availableHeightPx by mutableIntStateOf(600)
    private var attached = false
    private var editing = false
    private var positioned = false
    private val params = WindowManager.LayoutParams(1, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    private val view: ComposeView

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeViewModelStoreOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OverlayRoute(controller, (availableHeightPx / density).dp, ::drag, onReport)
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
        }
    }

    fun show(isEditing: Boolean) {
        val bounds = bounds()
        val margin = (8 * density).toInt()
        params.width = minOf((360 * density).toInt(), (bounds.right - bounds.left - 2 * margin).coerceAtLeast(1))
        availableHeightPx = (bounds.bottom - bounds.top - 2 * margin).coerceAtLeast(1)
        if (!positioned) {
            params.x = bounds.right - params.width - margin
            params.y = bounds.top + (48 * density).toInt()
            positioned = true
        }
        if (editing != isEditing) {
            editing = isEditing
            params.flags = if (editing) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (!editing) context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
        if (!attached) {
            manager.addView(view, params)
            attached = true
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        clamp()
    }

    fun hide() {
        if (!attached) return
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        manager.removeViewImmediate(view)
        view.disposeComposition()
        attached = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun close() {
        hide()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view.disposeComposition()
        viewModelStore.clear()
    }

    private fun drag(offset: Offset) {
        params.x += offset.x.toInt(); params.y += offset.y.toInt()
        clamp()
    }

    private fun clamp() {
        val point = clampWindow(params.x, params.y, params.width, view.height, bounds())
        params.x = point.x; params.y = point.y
        if (attached) manager.updateViewLayout(view, params)
    }

    private fun bounds(): WindowBounds {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val rect = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = if (editing) view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 else 0
            return WindowBounds(insets.left, insets.top, rect.width() - insets.right, rect.height() - maxOf(insets.bottom, keyboard))
        }
        @Suppress("DEPRECATION") val size = android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }
        val insets = view.rootWindowInsets
        val cutout = insets?.displayCutout
        fun dimension(name: String): Int = context.resources.getIdentifier(name, "dimen", "android")
            .takeIf { it != 0 }?.let(context.resources::getDimensionPixelSize) ?: 0
        @Suppress("DEPRECATION")
        return WindowBounds(maxOf(insets?.stableInsetLeft ?: 0, cutout?.safeInsetLeft ?: 0),
            maxOf(insets?.stableInsetTop ?: dimension("status_bar_height"), cutout?.safeInsetTop ?: 0),
            size.x - maxOf(insets?.stableInsetRight ?: 0, cutout?.safeInsetRight ?: 0),
            size.y - maxOf(insets?.stableInsetBottom ?: dimension("navigation_bar_height"),
                cutout?.safeInsetBottom ?: 0, if (editing) insets?.systemWindowInsetBottom ?: 0 else 0))
    }
}
