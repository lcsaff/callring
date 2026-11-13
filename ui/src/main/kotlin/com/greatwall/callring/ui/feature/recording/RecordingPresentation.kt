package com.greatwall.callring.ui.feature.recording

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.ui.state.MutationState

internal fun ArchiveStatus.processing() = this in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING,
    ArchiveStatus.ANALYZING, ArchiveStatus.ENRICHING)

internal fun archiveStatusLabel(status: ArchiveStatus): String = when (status) {
    ArchiveStatus.WAITING_RECORDING -> "等待录音"
    ArchiveStatus.READY -> "录音就绪"
    ArchiveStatus.QUEUED -> "等待分析"
    ArchiveStatus.TRANSCRIBING -> "正在转写"
    ArchiveStatus.ANALYZING -> "正在分析"
    ArchiveStatus.ENRICHING -> "分析已完成，正在补全档案"
    ArchiveStatus.ENRICHMENT_FAILED -> "分析已完成，档案补全失败"
    ArchiveStatus.COMPLETE -> "已完成"
    ArchiveStatus.FAILED -> "处理失败"
}

@Composable internal fun RecordingFeedback(error: ApiError?, mutation: MutationState) {
    (mutation.error ?: error)?.let { Text(recordingMessage(it.message), color = MaterialTheme.colorScheme.error) }
    mutation.message?.takeIf(String::isNotBlank)?.let { Text(it) }
}

fun recordingMessage(key: String): String = when (key) {
    "recording.enrichment_failed" -> "分析已保存，档案补全未完成；重试只处理本地保存，不重复调用模型"
    "recording.audio_not_restored" -> "原音频未随备份恢复；转写与分析已保留，可重新导入音频。"
    "recording.restore_retry_required" -> "恢复后已暂停未完成的处理，请检查连接后手动重试。"
    "recording.reset_retry_required" -> "清空号码档案后已暂停旧录音处理；录音、转写和分析已保留，可手动重试继续处理。"
    "ai.invalid_url" -> "服务地址须为 HTTPS，不能包含账户、查询参数或片段"
    "ai.invalid_key" -> "请填写有效 API 密钥"
    "ai.invalid_model" -> "请填写模型名称（最多 256 字）"
    "recording.unavailable" -> "当前设备暂不支持录音服务"
    "recording.unverified" -> "音频质量未经验证，可能缺少一方声音"
    "recording.permission_required" -> "需要文件或麦克风访问权限，请重新授权"
    "recording.invalid_configuration_or_audio" -> "配置或音频文件无效，请检查设置并重新选择音频"
    "recording.busy_or_changed" -> "正在处理或归档已变化，请刷新后重试"
    "recording.storage_or_network" -> "存储或网络操作失败，请检查设备空间与连接"
    "recording.insufficient_space" -> "设备可用空间不足，请手动清理后重试；原数据已保留"
    "recording.space_unknown" -> "无法确认设备可用空间，请检查存储状态后重试"
    "recording.missing_audio" -> "录音文件缺失，请重新导入"
    "recording.offline_busy" -> "归档已有处理任务，请等任务结束后重试"
    "recording.offline_model_unavailable" -> "当前设备无法加载内置离线模型"
    "recording.silent_audio" -> "录音中没有识别到语音"
    "recording.offline_audio_too_long" -> "离线转写目前支持最长 10 分钟的录音"
    "ai.transcript_too_large" -> "转写结果超出保存上限"
    "recording.transcript_required" -> "请先将录音转为文本，再进行 AI 分析"
    "recording.not_found" -> "尚无归档，请先导入录音"
    "recording.microphone_unverified", "recording.microphone_limited" -> "实验性麦克风录音，可能只有本机一方声音"
    "recording.waiting" -> "等待录音导入"
    else -> when {
        key.startsWith("ai.") -> "AI 请求失败，请检查服务地址、密钥、模型、网络或服务额度后重试"
        key.startsWith("recording.") -> "录音操作未完成，请检查文件访问授权和设备支持后重试"
        else -> "操作未完成，请稍后重试"
    }
}
