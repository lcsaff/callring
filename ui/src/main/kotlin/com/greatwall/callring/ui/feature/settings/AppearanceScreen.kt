package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import com.greatwall.callring.ui.designsystem.components.LoadingContent
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.designsystem.components.formKeyboardActions
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*
import java.util.Locale

@Composable
fun AppearanceScreen(
    state: LoadState<AppearanceSettings>, draft: AppearanceSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (AppearanceSettings) -> Unit,
    onRetrySave: () -> Unit, registry: ThemeRegistry = ThemeRegistry.Default,
    onColorChange: (AppearanceSettings) -> Unit = onChange,
) {
    val value = draft ?: state.data
    CallringTheme(appearance = value ?: DefaultAppearance, registry = registry) {
        ScreenFrame("外观", onBack) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.loaded && state.data == null && !state.loading && state.error == null) LoadingContent()
                LoadFeedback(state, onRetry)
                mutation.error?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetrySave) { Text("重试") }
                }
                if (value != null) AppearanceControls(value, mutation, onChange, onColorChange, registry)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AppearanceControls(
    value: AppearanceSettings, mutation: MutationState, onChange: (AppearanceSettings) -> Unit,
    onColorChange: (AppearanceSettings) -> Unit, registry: ThemeRegistry,
) {
    val seed = remember(registry, value.selection, value.presetId, value.customSeedRgb) { registry.resolve(value).seedRgb }
    var hex by rememberSaveable(seed) { mutableStateOf(rgbHex(seed)) }
    val rgb = parseRgb(hex)
    val customHexInvalid = rgb == null
    SettingsCard("预设主题") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("appearance-presets"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            registry.presets.forEach { preset ->
                FilterChip(selected = seed == preset.seedRgb,
                    onClick = {
                        hex = rgbHex(preset.seedRgb)
                        onChange(value.copy(selection = ThemeSelection.CUSTOM, presetId = preset.id, customSeedRgb = preset.seedRgb))
                    },
                    label = { Text(preset.name) },
                    leadingIcon = { Box(Modifier.size(18.dp).background(Color(preset.seedRgb or 0xFF000000.toInt()), CircleShape)) },
                    modifier = Modifier.testTag("preset-${preset.id}"))
            }
        }
        FieldError(mutation, "presetId", "selection")
    }
    SettingsCard("显示模式") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorMode.entries.forEach { mode ->
                FilterChip(value.colorMode == mode, { onChange(value.copy(colorMode = mode)) },
                    label = { Text(UiLabels.colorMode(mode)) })
            }
        }
        FieldError(mutation, "colorMode")
    }
    Card(Modifier.fillMaxWidth().testTag("appearance-custom"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("自定义主题色", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Color(seed or 0xFF000000.toInt()),
                    contentColor = MaterialTheme.colorScheme.onPrimaryFixed,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(64.dp, 48.dp).testTag("appearance-custom-preview")) {
                    Box(contentAlignment = Alignment.Center) { Text("Aa", style = MaterialTheme.typography.titleLarge) }
                }
                Text("当前主题色", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(hex, { raw ->
                hex = raw
                parseRgb(raw)?.let { onChange(value.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = it)) }
            }, label = { Text("十六进制颜色") }, supportingText = { Text("六位 RGB，例如 #E60012") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = formKeyboardActions(),
                isError = customHexInvalid, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("appearance-hex"))
            InputError(if (customHexInvalid) "请输入六位十六进制颜色" else null)
            FieldError(mutation, "customSeedRgb")
            listOf("红色 R" to 16, "绿色 G" to 8, "蓝色 B" to 0).forEach { (label, shift) ->
                val component = (seed shr shift) and 255
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$label\n$component", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(52.dp))
                    Slider(component.toFloat(), { raw ->
                        val next = (seed and (255 shl shift).inv()) or (raw.toInt() shl shift)
                        hex = rgbHex(next)
                        onColorChange(value.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = next))
                    }, valueRange = 0f..255f, steps = 254,
                        modifier = Modifier.weight(1f).semantics { contentDescription = label })
                }
            }
        }
    }
}

private fun rgbHex(seed: Int) = "#%06X".format(Locale.ROOT, seed)
private fun parseRgb(raw: String): Int? = raw.removePrefix("#")
    .takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toIntOrNull(16)
