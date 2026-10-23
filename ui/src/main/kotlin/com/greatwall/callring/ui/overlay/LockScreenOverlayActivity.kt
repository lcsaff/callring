package com.greatwall.callring.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.greatwall.callring.ui.app.CallringActivity
import com.greatwall.callring.ui.app.UiDestination
import kotlinx.coroutines.launch

/** A floating application window that Android permits above keyguard; no new API observers. */
class LockScreenOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setFinishOnTouchOutside(false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { LockScreenOverlayHost.controller.value?.dismiss(); finish() }
        })
        if (LockScreenOverlayHost.controller.value == null) { finish(); return }
        lifecycleScope.launch {
            LockScreenOverlayHost.controller.collect { if (it == null) finish() }
        }
        setContent {
            val controller by LockScreenOverlayHost.controller.collectAsState()
            controller?.let { owner ->
                BoxWithConstraints {
                    OverlayRoute(owner, maxHeight, { offset ->
                        val attrs = window.attributes
                        val metrics = resources.displayMetrics
                        attrs.x = (attrs.x + offset.x.toInt()).coerceIn(
                            -(metrics.widthPixels - attrs.width).coerceAtLeast(0) / 2,
                            (metrics.widthPixels - attrs.width).coerceAtLeast(0) / 2)
                        attrs.y = (attrs.y + offset.y.toInt()).coerceIn(0,
                            (metrics.heightPixels - window.decorView.height).coerceAtLeast(0))
                        window.attributes = attrs
                    }, { number ->
                        startActivity(CallringActivity.intent(this@LockScreenOverlayActivity, UiDestination.Report(number)))
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        window.setLayout(minOf((360 * metrics.density).toInt(), metrics.widthPixels - (16 * metrics.density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { y = (48 * metrics.density).toInt() }
    }

    override fun onResume() {
        super.onResume()
        LockScreenOverlayHost.resumed(this)
    }

    override fun onPause() {
        LockScreenOverlayHost.paused(this)
        super.onPause()
    }

    override fun onDestroy() {
        LockScreenOverlayHost.paused(this)
        super.onDestroy()
    }

    companion object {
        internal fun intent(context: Context) = Intent(context, LockScreenOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
