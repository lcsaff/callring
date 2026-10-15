package com.greatwall.callring.ui.app

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.greatwall.callring.ui.designsystem.LocalCallringDarkMode

@Composable internal fun ThemeWindowSupport() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val dark = LocalCallringDarkMode.current
    SideEffect {
        WindowInsetsControllerCompat(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
