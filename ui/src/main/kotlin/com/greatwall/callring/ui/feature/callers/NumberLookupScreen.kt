package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.application.api.NumberCheck

@Composable fun NumberLookupScreen(text: String, check: NumberCheck, onBack: () -> Unit, onChange: (String) -> Unit, onOpen: () -> Unit) {
    val valid = check.validProfileNumber
    ScreenFrame("查询号码报表", onBack) {
        Text("输入号码，查看已记录的通话与评分。查询不会创建档案。")
        OutlinedTextField(text, onChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text("号码") },
            isError = text.isNotBlank() && !valid, supportingText = { if (text.isNotBlank() && !valid) Text("请输入有效号码") })
        PrimaryAction("查看报表", onOpen, valid)
        if (text.isNotBlank() && valid) {
            Text("规范号码：${check.normalized}")
            Text(if (check.matchesRule) "符合规则号码" else "不符合规则号码")
        }
    }
}
