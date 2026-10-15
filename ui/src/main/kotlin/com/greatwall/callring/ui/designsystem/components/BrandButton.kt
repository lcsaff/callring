package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.greatwall.callring.ui.designsystem.colorContrast

/** Primary filled action: the user's exact color is independent of readable text accents. */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(onClick, modifier, enabled, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primaryFixed, contentColor = colors.onPrimaryFixed),
        border = if (enabled && colorContrast(colors.primaryFixed, colors.surfaceContainer) < 3.0)
            BorderStroke(1.dp, colors.outline) else null,
        content = content)
}
