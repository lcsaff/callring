package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.designsystem.components.rememberFormBack
import java.util.Locale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.greatwall.callring.ui.designsystem.components.formKeyboardActions

@Composable
fun AppearanceScreen(
    state: LoadState<AppearanceSettings>, draft: AppearanceSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (AppearanceSettings) -> Unit,
    onSave: () -> Unit, registry: ThemeRegistry = ThemeRegistry.Default,
) {
    var invalidCustomInput by remember { mutableStateOf(false) }
    val back = rememberFormBack(onBack, (draft != null && state.data != null && draft != state.data)
        || invalidCustomInput, mutation, onSave, canSave = !invalidCustomInput)
    SettingsLayout("外观", state, mutation, back, onRetry) { saved ->
        val value = draft ?: saved
        val seed = value.customSeedRgb?.takeIf { it in 0x000000..0xFFFFFF } ?: registry.resolve(value).seedRgb
        var hex by rememberSaveable(value.customSeedRgb) { mutableStateOf("#%06X".format(Locale.ROOT, seed)) }
        val rgb = hex.removePrefix("#").takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toIntOrNull(16)
        val customHexInvalid = value.selection == ThemeSelection.CUSTOM && rgb == null
        LaunchedEffect(customHexInvalid) { invalidCustomInput = customHexInvalid }
        SettingsCard("预设主题") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            registry.presets.forEach { preset ->
                FilterChip(selected = value.selection == ThemeSelection.PRESET && value.presetId == preset.id,
                    onClick = { onChange(value.copy(selection = ThemeSelection.PRESET, presetId = preset.id)) },
                    enabled = !mutation.busy,
                    label = { Text(preset.name) },
                    leadingIcon = { Box(Modifier.size(18.dp).background(Color(preset.seedRgb or 0xFF000000.toInt()), CircleShape)) },
                    modifier = Modifier.testTag("preset-${preset.id}"))
            }
            }
            FieldError(mutation, "presetId", "selection")
        }
        SettingsCard("自定义主题色") {
            FilterChip(selected = value.selection == ThemeSelection.CUSTOM, onClick = {
                onChange(value.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = seed))
            }, enabled = !mutation.busy, label = { Text("使用自定义颜色") })
            OutlinedTextField(hex, { raw ->
                hex = raw
                raw.removePrefix("#").takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toIntOrNull(16)?.let {
                    onChange(value.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = it))
                }
            }, label = { Text("十六进制颜色") }, supportingText = { Text("六位 RGB，例如 #E60012") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = formKeyboardActions(),
                isError = customHexInvalid, enabled = !mutation.busy, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("appearance-hex"))
            InputError(if (customHexInvalid) "请输入六位十六进制颜色" else null)
            FieldError(mutation, "customSeedRgb")
            listOf("红色 R" to 16, "绿色 G" to 8, "蓝色 B" to 0).forEach { (label, shift) ->
                val component = (seed shr shift) and 255
                Text("$label：$component")
                Slider(component.toFloat(), { raw ->
                    val next = (seed and (255 shl shift).inv()) or (raw.toInt() shl shift)
                    hex = "#%06X".format(Locale.ROOT, next)
                    onChange(value.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = next))
                }, valueRange = 0f..255f, steps = 254, enabled = !mutation.busy,
                    modifier = Modifier.semantics { contentDescription = label })
            }
        }
        SettingsCard("显示模式") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorMode.entries.forEach { mode ->
                FilterChip(value.colorMode == mode, { onChange(value.copy(colorMode = mode)) },
                    enabled = !mutation.busy, label = { Text(UiLabels.colorMode(mode)) })
            }
            }
            FieldError(mutation, "colorMode")
        }
        CallringTheme(appearance = value, registry = registry) {
            SettingsCard("效果预览") {
                Text("来电管理", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Text("在此查看主题效果，保存后应用。")
                LinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.fillMaxWidth())
                Surface(color = MaterialTheme.colorScheme.primaryFixed, shape = MaterialTheme.shapes.medium) {
                    Text("主题色预览", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryFixed)
                }
            }
        }
        SaveAction(mutation, enabled = !customHexInvalid, onSave = onSave)
    }
}
