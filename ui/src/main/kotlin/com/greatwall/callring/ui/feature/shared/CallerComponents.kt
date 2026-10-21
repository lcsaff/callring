package com.greatwall.callring.ui.feature.shared

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import com.greatwall.callring.contracts.callers.CallerProfile
import com.greatwall.callring.ui.R
import com.greatwall.callring.ui.designsystem.CallringSpacing
import com.greatwall.callring.ui.format.UiFormat

@Composable
fun CallerCard(profile: CallerProfile, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val actionModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Card(
        modifier = actionModifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(CallringSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(CallringSpacing.compact),
        ) {
            Text(profile.name.ifBlank { profile.number }, style = MaterialTheme.typography.titleMedium)
            if (profile.name.isNotBlank()) Text(profile.number, style = MaterialTheme.typography.bodyLarge)
            if (profile.department.isNotBlank()) Text(profile.department, style = MaterialTheme.typography.bodyMedium)
            profile.position?.let { position ->
                val status = if (position.active) "" else stringResource(R.string.callring_inactive_position)
                Text("${position.name}$status", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                UiFormat.position(profile.relation),
                modifier = Modifier.semantics { contentDescription = UiFormat.positionAccessibility(profile.relation) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.whitelisted) Text(
                stringResource(R.string.callring_whitelisted),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
