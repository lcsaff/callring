package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.CallerProfile
import com.greatwall.callring.ui.R
import com.greatwall.callring.ui.designsystem.CallringSpacing
import com.greatwall.callring.ui.designsystem.LocalCallringDarkMode
import com.greatwall.callring.ui.designsystem.ScoreBand
import com.greatwall.callring.ui.designsystem.ScorePalette
import com.greatwall.callring.ui.format.UiFormat

/** A shared header and inset-aware content frame. The caller chooses its scrolling container. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)
                        .padding(horizontal = CallringSpacing.compact, vertical = CallringSpacing.compact),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CallringSpacing.compact),
                ) {
                    if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.callring_back)) }
                    Text(
                        title,
                        modifier = Modifier.weight(1f).padding(horizontal = CallringSpacing.compact).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    actions()
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                .padding(horizontal = CallringSpacing.screen, vertical = CallringSpacing.item),
            verticalArrangement = Arrangement.spacedBy(CallringSpacing.item),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(top = CallringSpacing.item).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun LoadingContent(message: String = stringResource(R.string.callring_loading), modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = CallringSpacing.section),
        horizontalArrangement = Arrangement.spacedBy(CallringSpacing.item, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ErrorContent(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = CallringSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CallringSpacing.item),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.callring_retry)) }
    }
}

@Composable
fun EmptyContent(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.fillMaxWidth().padding(vertical = CallringSpacing.section),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    BrandButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val actionModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Row(
        modifier = actionModifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = CallringSpacing.item),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CallringSpacing.item),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

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

@Composable
fun ScoreBadge(points: Int?, modifier: Modifier = Modifier) {
    val band = ScorePalette.band(points)
    val colors = ScorePalette.colors(points, LocalCallringDarkMode.current)
    val score = points?.takeIf { band != ScoreBand.UNAVAILABLE }
    val accessible = if (score != null) stringResource(R.string.callring_score_accessibility, score, band.label) else band.label
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = accessible },
        color = colors.background,
        contentColor = colors.foreground,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier.padding(horizontal = CallringSpacing.item, vertical = CallringSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (score != null) Text(stringResource(R.string.callring_score_points, score), style = MaterialTheme.typography.titleMedium)
            Text(band.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
