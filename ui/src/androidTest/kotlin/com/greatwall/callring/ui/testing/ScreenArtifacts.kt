package com.greatwall.callring.ui.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

fun ComposeContentTestRule.captureArtifact(name: String) {
    val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-screenshots").apply { mkdirs() }
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
