package com.greatwall.callring.ui.feature.recording

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.ui.state.MutationState

internal fun archiveStatusLabel(status: ArchiveStatus): String = when (status) {
    ArchiveStatus.WAITING_RECORDING -> "等待录音"
    ArchiveStatus.READY -> "录音就绪"
    ArchiveStatus.QUEUED -> "等待分析"
    ArchiveStatus.TRANSCRIBING -> "正在转写"
    ArchiveStatus.ANALYZING -> "正在分析"
    ArchiveStatus.COMPLETE -> "已完成"
    ArchiveStatus.FAILED -> "处理失败"
}

@Composable internal fun RecordingFeedback(error: ApiError?, mutation: MutationState) {
    (mutation.error ?: error)?.let { Text(recordingMessage(it.message), color = MaterialTheme.colorScheme.error) }
    mutation.message?.let { Text(it) }
}

fun recordingMessage(key: String): String = when (key) {
    "recording.audio_not_restored" -> "原音频未随备份恢复；转写与分析已保留，可重新导入音频。"
    "recording.restore_retry_required" -> "恢复后已暂停未完成的处理，请检查连接后手动重试。"
    "ai.invalid_url" -> "服务地址须为 HTTPS，不能包含账户、查询参数或片段"
    "ai.invalid_key" -> "请填写有效 API 密钥"
    "ai.invalid_model" -> "请填写模型名称（最多 256 字）"
    "ai.transcription_requires_openai" -> "转写仅支持 OpenAI 兼容协议"
    "recording.unavailable" -> "当前设备暂不支持录音服务"
    "recording.unverified" -> "音频质量未经验证，可能缺少一方声音"
    "recording.permission_required" -> "需要文件或麦克风访问权限，请重新授权"
    "recording.invalid_configuration_or_audio" -> "配置或音频文件无效，请检查设置并重新选择音频"
    "recording.busy_or_changed" -> "正在处理或归档已变化，请刷新后重试"
    "recording.storage_or_network" -> "存储或网络操作失败，请检查设备空间与连接"
    "recording.insufficient_space" -> "设备可用空间不足，请手动清理后重试；原数据已保留"
    "recording.space_unknown" -> "无法确认设备可用空间，请检查存储状态后重试"
    "recording.missing_audio" -> "录音文件缺失，请重新导入"
    "recording.not_found" -> "尚无归档，请先导入录音"
    "recording.microphone_unverified", "recording.microphone_limited" -> "实验性麦克风录音，可能只有本机一方声音"
    "recording.waiting" -> "等待录音导入"
    else -> when {
        key.startsWith("ai.") -> "AI 请求失败，请检查服务地址、密钥、模型、网络或服务额度后重试"
        key.startsWith("recording.") -> "录音操作未完成，请检查文件访问授权和设备支持后重试"
        else -> "操作未完成，请稍后重试"
    }
}
