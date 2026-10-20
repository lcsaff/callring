package com.greatwall.callring.ui.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.feature.callers.NumberDeletionDialogs
import com.greatwall.callring.ui.feature.ai.*
import com.greatwall.callring.ui.overlay.CallringOverlayService
import com.greatwall.callring.ui.widget.CallringWidgetReceiver
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Activity owns launchers. The application service retains only immutable tickets and results. */
class ForegroundActionHost(private val activity: ComponentActivity, private val api: CallringUiApi, private val notice: (String) -> Unit) : DefaultLifecycleObserver {
    private val foreground = api.conversation.foreground
    private var token: String? = null
    private var registeredId: String? = null
    private var launcher: ActivityResultLauncher<*>? = null
    private var launch: (() -> Unit)? = null
    private val localPermission = activity.activityResultRegistry.register("callring-local-permission", ActivityResultContracts.RequestPermission()) { if(!it) notice("未获得系统授权") }
    private val localScreen = activity.activityResultRegistry.register("callring-local-system", ActivityResultContracts.StartActivityForResult()) { }
    init {
        foreground.pending.value?.let(::prepare)
        activity.lifecycle.addObserver(this)
        activity.lifecycleScope.launch { foreground.pending.collect { ticket ->
            if(ticket != null) { prepare(ticket); launchPending() }
        } }
    }
    override fun onResume(owner: LifecycleOwner) { token = foreground.attachHost(); launchPending() }
    override fun onPause(owner: LifecycleOwner) { token?.let(foreground::detachHost); token = null }
    override fun onDestroy(owner: LifecycleOwner) { token?.let(foreground::detachHost); launcher?.unregister(); localPermission.unregister(); localScreen.unregister(); activity.lifecycle.removeObserver(this) }
    private fun launchPending() {
        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val ticket = foreground.pending.value ?: return
        val host = token ?: return
        if(!foreground.beginLaunch(ticket.requestId,host)) return
        try { launch?.invoke() }
        catch(_: ActivityNotFoundException) { deliver(ticket, ForegroundResultStatus.UNAVAILABLE) }
        catch(_: SecurityException) { deliver(ticket, ForegroundResultStatus.DENIED) }
        catch(_: RuntimeException) { deliver(ticket, ForegroundResultStatus.UNAVAILABLE) }
    }
    private fun <I,O> register(ticket: ForegroundTicket, contract: ActivityResultContract<I,O>, input: I, result: (O) -> Unit) {
        val next = activity.activityResultRegistry.register("callring-ai-${ticket.requestId}",contract,result)
        launcher = next
        launch = { next.launch(input) }
    }
    private fun prepare(ticket: ForegroundTicket) {
        if(registeredId == ticket.requestId) return
        launcher?.unregister(); launcher = null; launch = null; registeredId = ticket.requestId
        when(ticket.kind) {
            ForegroundActionKind.CAPABILITY -> {
                val capability = Capability.valueOf(ticket.parameters["capability"].string())
                val permission = permissionFor(capability)
                if(permission != null) register(ticket,ActivityResultContracts.RequestPermission(),permission) { granted ->
                    if(!granted) deliver(ticket,ForegroundResultStatus.DENIED) else probe(ticket,capability,false)
                } else {
                    val intent = capabilityIntent(capability)
                    if(intent == null) launch = { deliver(ticket,ForegroundResultStatus.UNAVAILABLE) }
                    else register(ticket,ActivityResultContracts.StartActivityForResult(),intent) { probe(ticket,capability,it.resultCode == Activity.RESULT_CANCELED) }
                }
            }
            ForegroundActionKind.APP_SETTINGS -> register(ticket,ActivityResultContracts.StartActivityForResult(),applicationSettingsIntent()) { deliver(ticket,ForegroundResultStatus.VERIFIED) }
            ForegroundActionKind.IMPORT_AUDIO, ForegroundActionKind.IMPORT_RECORDING, ForegroundActionKind.IMPORT_CALENDAR -> register(ticket,ActivityResultContracts.OpenDocument(),if(ticket.kind == ForegroundActionKind.IMPORT_CALENDAR) arrayOf("application/json", "text/*", "application/octet-stream") else arrayOf("audio/*")) { document(ticket,it,false) }
            ForegroundActionKind.SELECT_FOLDER -> register(ticket,ActivityResultContracts.OpenDocumentTree(),null) { document(ticket,it,true) }
            ForegroundActionKind.EXPORT_CSV -> register(ticket,ActivityResultContracts.CreateDocument("text/csv"),"callring-report.csv") { document(ticket,it,false) }
            ForegroundActionKind.PIN_WIDGET -> launch = { deliver(ticket,pinWidget()) }
            ForegroundActionKind.TEST_OVERLAY -> launch = { deliver(ticket,testOverlay()) }
            ForegroundActionKind.PREVIEW_CALL -> launch = {
                activity.lifecycleScope.launch {
                    when(val result = api.callers.getCallDetail(ticket.parameters["callId"].long())) {
                        is ApiResult.Success -> deliver(ticket,previewCall(result.value))
                        is ApiResult.Failure -> foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.UNAVAILABLE,error = result.error))
                    }
                }
            }
            ForegroundActionKind.CONFIRM, ForegroundActionKind.CREDENTIAL, ForegroundActionKind.COPY_CONNECTION, ForegroundActionKind.TEST_CONNECTION -> launch = { }
        }
    }
    private fun document(ticket: ForegroundTicket, uri: Uri?, tree: Boolean) {
        if(uri == null) { deliver(ticket,ForegroundResultStatus.CANCELLED); return }
        if(uri.scheme != "content" || (tree && !DocumentsContract.isTreeUri(uri))) { deliver(ticket,ForegroundResultStatus.DENIED); return }
        foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.VERIFIED,DocumentRef(uri.toString())))
    }
    private fun probe(ticket: ForegroundTicket, capability: Capability, cancelled: Boolean) { activity.lifecycleScope.launch {
        when(val status = api.runtime.getStatus()) {
            is ApiResult.Failure -> foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.UNKNOWN,error = status.error))
            is ApiResult.Success -> deliver(ticket, when(status.value.capabilities[capability]) {
                AccessState.GRANTED, AccessState.NOT_REQUIRED -> ForegroundResultStatus.VERIFIED
                AccessState.UNAVAILABLE -> ForegroundResultStatus.UNAVAILABLE
                AccessState.DENIED -> if(cancelled) ForegroundResultStatus.CANCELLED else ForegroundResultStatus.DENIED
                else -> ForegroundResultStatus.UNKNOWN
            })
        }
    } }
    private fun deliver(ticket: ForegroundTicket, status: ForegroundResultStatus) { foreground.deliver(ticket.requestId,ForegroundHostResult(status)) }
    fun requestCapability(capability: Capability) {
        try { val permission = permissionFor(capability); if(permission != null) localPermission.launch(permission) else capabilityIntent(capability)?.let(localScreen::launch) ?: notice("系统没有提供该授权入口") }
        catch(_: RuntimeException) { notice("系统未允许此授权入口，请在系统设置中检查") }
    }
    fun openApplicationSettings() { try { localScreen.launch(applicationSettingsIntent()) } catch(_: RuntimeException) { notice("系统没有提供该设置入口") } }
    fun pinWidget(): ForegroundResultStatus = try {
        val manager = AppWidgetManager.getInstance(activity)
        if(manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(activity,CallringWidgetReceiver::class.java),null,null)) ForegroundResultStatus.LAUNCHED else ForegroundResultStatus.UNAVAILABLE
    } catch(_: RuntimeException) { ForegroundResultStatus.UNAVAILABLE }
    fun testOverlay(): ForegroundResultStatus = overlay { activity.startForegroundService(CallringOverlayService.testPreviewIntent(activity)) }
    fun previewCall(call: CallDetail): ForegroundResultStatus = overlay { activity.startForegroundService(CallringOverlayService.previewIntent(activity,call)) }
    private fun overlay(launch: () -> Unit): ForegroundResultStatus {
        if(!Settings.canDrawOverlays(activity)) return ForegroundResultStatus.DENIED
        return try { launch(); ForegroundResultStatus.LAUNCHED } catch(_: RuntimeException) { ForegroundResultStatus.UNAVAILABLE }
    }
    private fun applicationSettingsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${activity.packageName}"))
    private fun permissionFor(capability: Capability): String? = when(capability) {
        Capability.PHONE_STATE -> Manifest.permission.READ_PHONE_STATE; Capability.CALL_LOG -> Manifest.permission.READ_CALL_LOG; Capability.CONTACTS -> Manifest.permission.READ_CONTACTS; Capability.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        Capability.NOTIFICATIONS -> if(Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        else -> null
    }
    private fun capabilityIntent(capability: Capability): Intent? = when(capability) {
        Capability.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${activity.packageName}"))
        Capability.BATTERY_OPTIMIZATION_EXEMPTION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Capability.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)
        Capability.CALL_SCREENING_ROLE -> activity.getSystemService(RoleManager::class.java)?.let { if(it.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) it.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING) else null }
        else -> null
    }
}

@Composable fun ForegroundActionDialogs(api: CallringUiApi) {
    val ticket by api.conversation.foreground.pending.collectAsStateWithLifecycle()
    val current = ticket?.takeIf { it.launched } ?: return
    key(current.requestId) {
        val bound = remember(current.requestId) { current.commandContext?.let(api::commandApi) ?: api }
        fun finish(saved: Boolean) { api.conversation.foreground.deliver(current.requestId,ForegroundHostResult(if(saved) ForegroundResultStatus.VERIFIED else ForegroundResultStatus.CANCELLED)) }
        when(current.kind) {
            ForegroundActionKind.CREDENTIAL -> CredentialDialog(bound.aiSettings,AiSlot.valueOf(current.parameters["slot"].string()),onFinished = ::finish)
            ForegroundActionKind.COPY_CONNECTION -> CopyConnectionDialog(bound.aiSettings,AiSlot.valueOf(current.parameters["from"].string()),AiSlot.valueOf(current.parameters["to"].string()),::finish)
            ForegroundActionKind.CONFIRM, ForegroundActionKind.TEST_CONNECTION -> {
                val target = current.confirmation
                if (target is ConfirmationTarget.NumberDeletion) {
                    NumberDeletionDialogs(
                        impact = NumberDeletionImpact(
                            target.number, target.profileExists, target.callCount,
                            target.archiveCount, target.audioBytes, target.widgetBindingCount, false,
                        ),
                        state = null, error = null,
                        onDismiss = { finish(false) },
                        onConfirm = { finish(true) },
                    )
                } else {
                    val operation = (current.parameters["operation"] as? AiJson.Str)?.value.orEmpty()
                    val message = when(operation) {
                        "recordings_testConnection" -> "将向已保存的服务发送固定测试文字或音频，可能产生费用；不会发送通话内容。"
                        "recordings_adoptIdentity" -> "仅采纳本次分析中的非空自述姓名和单位，保留职位与白名单；模型推测不会采纳。"
                        "recordings_delete" -> "将删除本次录音归档及其本地音频，此操作保留原删除规则。"
                        "settings_deletePosition" -> "将删除所选职位，正在使用的职位会拒绝删除。"
                        "settings_deleteLeave" -> "将删除所选请假记录并重新计算受影响的业务数据。"
                        else -> "请核对当前操作，确认后执行；取消时不会执行依赖操作。"
                    }
                    AlertDialog(onDismissRequest = { finish(false) },title = { Text("确认操作？") },text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(message)
                            current.confirmation?.let { ConfirmationSummary(it) } ?: Text("无法核对实际目标，请取消并重新发起。")
                        }
                    },confirmButton = { TextButton(enabled = current.confirmation != null,onClick = { finish(true) }) { Text("确认") } },dismissButton = { TextButton(onClick = { finish(false) }) { Text("取消") } })
                }
            }
            else -> Unit
        }
    }
}

@Composable internal fun ConfirmationSummary(target: ConfirmationTarget) {
    fun time(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toString()
    when(target) {
        is ConfirmationTarget.Position -> { Text("职位 #${target.id}：${target.name}"); Text("级别 ${target.level} · ${if(target.active) "启用" else "停用"}") }
        is ConfirmationTarget.Leave -> { Text("请假 #${target.id}"); Text("${time(target.start)} 至 ${time(target.end)}"); Text("备注：${target.note}") }
        is ConfirmationTarget.Recording -> {
            Text("录音 #${target.id}：${target.number}"); Text("来电时间：${time(target.startedAt)}")
            target.adoption?.let {
                Text(if(it.name == null) "姓名保持：${it.previousName}" else "姓名：${it.previousName} → ${it.name}")
                Text(if(it.organization == null) "单位保持：${it.previousOrganization}" else "单位：${it.previousOrganization} → ${it.organization}")
            }
        }
        is ConfirmationTarget.Connection -> {
            Text(if(target.slot == AiSlot.ANALYSIS) "内容分析连接" else "录音转写连接")
            Text("${target.connection.protocol} · ${target.connection.url}"); Text("模型：${target.connection.model}")
            Text(if(target.connection.keyConfigured) "密钥已配置" else "密钥未配置")
        }
        is ConfirmationTarget.NumberDeletion -> {
            Text("号码：${target.number}")
            if (target.profileExists) Text("档案 1 条")
            Text("通话记录 ${target.callCount} 条")
            Text("录音归档 ${target.archiveCount} 条")
            if (target.widgetBindingCount > 0) Text("小组件绑定 ${target.widgetBindingCount} 个")
            Text("删除后不可恢复。", color = MaterialTheme.colorScheme.error)
        }
    }
}
