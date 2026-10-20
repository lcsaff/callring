package com.greatwall.callring.ui.feature.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** One screen for all update phases; each phase exposes at most one primary action. */
@Composable
fun AppUpdateScreen(
    state: LoadState<UpdateStatus>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit,
    onCheck: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit,
) {
    val status = state.data
    ScreenFrame("应用更新", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本：${status?.current?.let { "${it.versionName}（${it.versionCode}）" } ?: "读取中…"}")
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            (mutation.error ?: state.error)?.let { error ->
                Text(updateMessage(error.message), color = MaterialTheme.colorScheme.error)
            }
            if (status == null) {
                if (!state.loading) PrimaryUpdateAction("重试", onRetry)
            } else {
                UpdateStatusBody(status, onCheck, onDownload, onCancel, onInstall)
            }
        }
    }
}

@Composable
private fun UpdateStatusBody(
    status: UpdateStatus,
    onCheck: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit,
) {
    status.latest?.let { ReleaseInfo(it) }
    status.checkedAtEpochMillis?.let {
        Text("上次检查：" + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            .toLocalDateTime().withNano(0), style = MaterialTheme.typography.bodySmall)
    }
    when (status.phase) {
        UpdatePhase.IDLE -> {
            Text("尚未检查更新。检查只会读取发布信息，不会在后台自动进行。")
            PrimaryUpdateAction("检查更新", onCheck)
        }
        UpdatePhase.CHECKING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在检查更新…")
        }
        UpdatePhase.UP_TO_DATE -> {
            Text("当前已是最新版本，无需更新。")
            PrimaryUpdateAction("重新检查", onCheck)
        }
        UpdatePhase.UPDATE_AVAILABLE -> PrimaryUpdateAction("下载更新", onDownload)
        UpdatePhase.UNSUPPORTED_DEVICE -> Text(updateMessage(status.failureReason ?: "update.unsupported_device"))
        UpdatePhase.NO_INSTALLABLE -> {
            Text("该版本尚未提供可安装更新。")
            PrimaryUpdateAction("重新检查", onCheck)
            ReleasePageAction(status.releasePageUrl)
        }
        UpdatePhase.CHECK_FAILED -> {
            Text(updateMessage(status.failureDetail ?: status.failureReason ?: "update.check_failed"), color = MaterialTheme.colorScheme.error)
            PrimaryUpdateAction("重试", onCheck)
        }
        UpdatePhase.DOWNLOADING -> {
            DownloadProgress(status)
            PrimaryUpdateAction("取消下载", onCancel)
        }
        UpdatePhase.VERIFYING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在校验安装包（大小、摘要、包名、版本与签名）…")
        }
        UpdatePhase.VERIFIED -> {
            Text("安装包校验通过，可以安装。")
            PrimaryUpdateAction("安装", onInstall)
        }
        UpdatePhase.VERIFY_FAILED -> {
            Text(updateMessage(status.failureReason ?: "update.verify_failed"), color = MaterialTheme.colorScheme.error)
            PrimaryUpdateAction("重新下载", onDownload)
        }
        UpdatePhase.INSTALL_PERMISSION_REQUIRED -> {
            Text("需要允许“安装未知应用”后继续。")
            PrimaryUpdateAction("去授权", onInstall)
        }
        UpdatePhase.INSTALL_CANCELLED -> {
            Text("安装已取消，未修改当前安装。")
            PrimaryUpdateAction("重新安装", onInstall)
        }
        UpdatePhase.INSTALL_PENDING_VERIFY -> {
            Text("等待系统确认安装结果；确认前不会显示为已安装。")
            PrimaryUpdateAction("重新检查", onCheck)
        }
    }
}

@Composable
private fun ReleaseInfo(release: AppReleaseInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("可用版本：${release.versionName}（${release.versionCode}）", style = MaterialTheme.typography.titleMedium)
            release.publishedAtEpochMillis?.let {
                Text("发布日期：" + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate())
            }
            Text("安装包：${release.assetName} · ${formatSize(release.sizeBytes)}")
            if (release.notes.isNotBlank()) Text("更新说明：${release.notes}")
        }
    }
}

@Composable
private fun DownloadProgress(status: UpdateStatus) {
    val total = status.totalBytes
    if (total != null && total > 0L) {
        LinearProgressIndicator(progress = { (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
        Text("已下载 ${formatSize(status.downloadedBytes)} / ${formatSize(total)}")
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("已下载 ${formatSize(status.downloadedBytes)}")
    }
}

@Composable
private fun PrimaryUpdateAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("update-primary-action")) { Text(label) }
}

@Composable
private fun ReleasePageAction(url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("打开 Release 网页手动下载") }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Maps platform failure keys to readable text; unknown platform reasons stay as returned. */
internal fun updateMessage(key: String): String = when (key) {
    "update.unavailable" -> "当前环境未提供应用更新服务。"
    "update.check_failed" -> "检查更新失败，请检查网络后重试。"
    "update.download_failed" -> "下载未完成，请重试。"
    "update.verify_failed" -> "安装包校验未通过，请重新下载。"
    "update.permission_required" -> "需要允许安装未知应用后才能继续。"
    "update.install_failed" -> "安装未完成，请重试。"
    "update.unsupported_device" -> "系统版本不满足更新要求。"
    "update.unsupported_build" -> "当前是调试或内部测试构建，不支持应用内更新；请安装正式构建。"
    "update.no_installable" -> "该版本尚未提供可安装更新。"
    "rate_limited" -> "更新服务触发 GitHub 限流（403/429），请稍后重试。"
    "forbidden" -> "更新服务拒绝访问（403），请检查网络环境。"
    "not_found" -> "未找到最新 Release，请稍后重试。"
    "timeout", "manifest_timeout" -> "连接更新服务超时，请检查网络。"
    "network", "manifest_network" -> "无法连接更新服务，请确认网络能访问 github.com。"
    "interrupted" -> "下载被中断，请重新下载。"
    "install_verification" -> "安装前校验未通过。"
    else -> when {
        key.startsWith("manifest_http_") -> "更新信息下载失败（HTTP ${key.removePrefix("manifest_http_")}）。"
        key.startsWith("http_") -> "更新服务返回 HTTP ${key.removePrefix("http_")}。"
        key.startsWith("update.") -> "更新操作未完成，请稍后重试。"
        else -> key
    }
}
