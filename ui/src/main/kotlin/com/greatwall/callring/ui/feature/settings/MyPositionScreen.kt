package com.greatwall.callring.ui.feature.settings

import androidx.compose.material3.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.greatwall.callring.contracts.settings.PositionSettings
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable
fun MyPositionScreen(
    state: LoadState<PositionSettings>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onSelect: (Long?) -> Unit,
) {
    SettingsLayout("我的职位", state, mutation, onBack, onRetry) { data ->
        SettingsCard {
            Text("选择自己的职位，用于比较来电人的职位。职位列表在档案中管理。")
            FieldError(mutation, "myPositionId")
            ListItem(modifier = Modifier.selectable(data.myPositionId == null, enabled = !mutation.busy,
                role = Role.RadioButton, onClick = { onSelect(null) }), headlineContent = { Text("未设置") }, leadingContent = {
                RadioButton(data.myPositionId == null, null, enabled = !mutation.busy)
            })
            data.options.filter { it.active || it.id == data.myPositionId }.forEach { option ->
                ListItem(modifier = Modifier.selectable(data.myPositionId == option.id, enabled = option.active && !mutation.busy,
                    role = Role.RadioButton, onClick = { onSelect(option.id) }),
                    headlineContent = { Text(option.name + if (option.active) "" else "（已停用）") },
                    leadingContent = {
                        RadioButton(data.myPositionId == option.id, null, enabled = option.active && !mutation.busy)
                    })
            }
        }
    }
}
