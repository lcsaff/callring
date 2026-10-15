package com.greatwall.callring.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.ui.app.UiApiOwner
import com.greatwall.callring.ui.app.uiViewModel
import com.greatwall.callring.ui.app.ThemeWindowSupport
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        if (!ownsWidget(widgetId)) { finish(); return }
        enableEdgeToEdge()
        val api = (application as UiApiOwner).uiApi
        setContent {
            val model: WidgetConfigurationModel = uiViewModel("widget-$widgetId") { WidgetConfigurationModel(widgetId, api, savedState = it) }
            val appearance by model.appearance.collectAsStateWithLifecycle()
            val saved by model.savedBinding.collectAsStateWithLifecycle()
            LaunchedEffect(saved) {
                val binding = saved ?: return@LaunchedEffect
                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, binding.widgetId))
                try {
                    withTimeout(7_000) { UiWidgetRefresh.refresh(applicationContext, setOf(widgetId)) }
                } catch (_: TimeoutCancellationException) { Toast.makeText(this@WidgetConfigurationActivity, "号码已保存，请在桌面重新刷新小组件", Toast.LENGTH_LONG).show() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(this@WidgetConfigurationActivity, "号码已保存，请在桌面重新刷新小组件", Toast.LENGTH_LONG).show() }
                finish()
            }
            CallringTheme(appearance.data ?: DefaultAppearance) {
                ThemeWindowSupport()
                WidgetConfigurationRoute(model, onCancel = ::finish, onSave = {
                    if (ownsWidget(widgetId)) model.save()
                    else { Toast.makeText(this, "此小组件已被移除，请重新添加", Toast.LENGTH_LONG).show(); finish() }
                })
            }
        }
    }

    private fun ownsWidget(widgetId: Int): Boolean = widgetId > 0 && AppWidgetManager.getInstance(this)
        .getAppWidgetInfo(widgetId)?.provider == ComponentName(this, CallringWidgetReceiver::class.java)

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = Intent(context, WidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("callring-widget://configure/$widgetId"))
    }
}

data class WidgetConfigurationState(
    val query: String = "",
    val selectedNumber: String? = null,
    val results: LoadState<Page<CallerProfile>> = LoadState(),
    val bindingError: ApiError? = null,
    val validation: String? = null,
    val mutation: MutationState = MutationState(),
)

@Composable
private fun WidgetConfigurationRoute(model: WidgetConfigurationModel, onCancel: () -> Unit, onSave: () -> Unit) {
    val query by model.query.collectAsStateWithLifecycle()
    val selected by model.selectedNumber.collectAsStateWithLifecycle()
    val results by model.results.collectAsStateWithLifecycle()
    val binding by model.binding.collectAsStateWithLifecycle()
    val validation by model.validation.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    BackHandler(enabled = mutation.busy || mutation.completed > 0) { /* Commit and host completion form one operation. */ }
    WidgetConfigurationScreen(
        WidgetConfigurationState(query, selected, results, binding.error, validation, mutation),
        onQueryChange = model::changeQuery,
        onUseDirectNumber = model::selectDirectNumber,
        onSelectNumber = model::select,
        onSearch = model::search,
        onCancel = onCancel,
        onSave = onSave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigurationScreen(
    state: WidgetConfigurationState,
    onQueryChange: (String) -> Unit,
    onUseDirectNumber: () -> Unit,
    onSelectNumber: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val query = state.query
    val selected = state.selectedNumber
    val results = state.results
    val validation = state.validation
    val mutation = state.mutation
    val blocked = mutation.busy || mutation.completed > 0
    Scaffold(topBar = { TopAppBar(title = { Text("选择小组件号码") }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !blocked, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = onSave, enabled = selected != null && !blocked, modifier = Modifier.weight(1f)) {
                    Text(if (mutation.busy) "正在保存…" else "保存")
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("搜索姓名、号码或部门，也可以直接输入号码。", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.bindingError != null) item {
                Text("原绑定读取失败，仍可重新选择号码：${state.bindingError!!.message}", color = MaterialTheme.colorScheme.error)
            }
            item {
                OutlinedTextField(value = query, onValueChange = onQueryChange, label = { Text("搜索号码或档案") },
                    singleLine = true, enabled = !blocked, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedButton(onClick = onUseDirectNumber, enabled = query.isNotBlank() && !blocked) { Text("使用输入的号码") }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (selected != null) item {
                Card(Modifier.fillMaxWidth()) { Text("已选择：$selected", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            }
            if (mutation.error != null) item { Text(mutation.error!!.message, color = MaterialTheme.colorScheme.error) }
            if (results.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (results.error != null) item {
                Text(results.error!!.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onSearch) { Text("重新搜索") }
            }
            items(results.data?.items.orEmpty(), key = { it.number }) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name.ifBlank { profile.number }) },
                    supportingContent = { Text(listOf(profile.number, profile.department, profile.position?.name.orEmpty()).filter(String::isNotBlank).joinToString(" · ")) },
                    trailingContent = { if (selected == profile.number) Text("已选", color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !blocked) { onSelectNumber(profile.number) },
                )
            }
            if (!results.loading && results.loaded && results.data?.items.isNullOrEmpty()) item {
                Text("没有匹配档案，可直接输入号码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
