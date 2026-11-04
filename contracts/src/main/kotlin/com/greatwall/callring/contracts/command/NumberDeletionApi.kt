package com.greatwall.callring.contracts.command

import com.greatwall.callring.contracts.common.*

/** archiveCount/audioBytes describe recordings retained by this operation; busy is false. */
data class NumberDeletionImpact(
    val number: String,
    val profileExists: Boolean,
    val callCount: Int,
    val archiveCount: Int,
    val audioBytes: Long,
    val widgetBindingCount: Int,
    val busy: Boolean,
)

/** 通话及组件期望计数用于冲突检查；expectedArchiveCount 保留字段兼容，不授权删除录音。 */

data class DeleteNumberCommand(
    val number: String,
    val expectedCallCount: Int,
    val expectedArchiveCount: Int,
    val expectedWidgetBindingCount: Int,
)

/** 删除号码档案、应用内通话记录和小组件绑定；保留全部录音、音频、转写和分析。 */

interface NumberDeletionApi {
    suspend fun impact(number: String): ApiResult<NumberDeletionImpact>
    suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit>
}

data class NumberCoreImpact(val number: String, val profileExists: Boolean, val callCount: Int)
