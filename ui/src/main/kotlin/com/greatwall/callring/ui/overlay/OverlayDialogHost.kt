package com.greatwall.callring.ui.overlay

import android.content.DialogInterface
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.greatwall.callring.ui.designsystem.LocalCallringDarkMode

/** A public Material dialog with its overlay window type configured before native attachment. */
@Composable
internal fun OverlayConfirmationDialog(
    title: String, message: String, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, busy: Boolean,
) {
    val context = LocalContext.current
    val dark = LocalCallringDarkMode.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentBusy by rememberUpdatedState(busy)
    val dialog = remember(context, dark, title, message, confirmLabel) {
        val theme = if (dark) com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert
            else com.google.android.material.R.style.Theme_Material3_Light_Dialog_Alert
        MaterialAlertDialogBuilder(ContextThemeWrapper(context, theme))
            .setTitle(title).setMessage(message)
            .setPositiveButton(confirmLabel, null).setNegativeButton("取消", null)
            .create().apply { window!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
    }
    fun update() {
        dialog.setCancelable(!busy)
        dialog.setCanceledOnTouchOutside(!busy)
        listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE).forEach { which ->
            dialog.getButton(which)?.apply { isEnabled = !busy; setTextColor(accent) }
        }
    }
    DisposableEffect(dialog) {
        dialog.setOnCancelListener { if (!currentBusy) currentDismiss() }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { if (!currentBusy) currentConfirm() }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { if (!currentBusy) currentDismiss() }
        update()
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
    }
    SideEffect { update() }
}
