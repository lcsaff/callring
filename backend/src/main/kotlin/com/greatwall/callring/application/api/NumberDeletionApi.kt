package com.greatwall.callring.application.api

/** 删除号码前的影响预览：数量用于二次确认与冲突校验。 */
data class NumberDeletionImpact(
    val number: String,
    val profileExists: Boolean,
    val callCount: Int,
    val archiveCount: Int,
    val audioBytes: Long,
    val widgetBindingCount: Int,
    val busy: Boolean,
)

/** 删除号码时的期望计数；与实时计数不一致表示状态已变化，必须重新预览。 */
data class DeleteNumberCommand(
    val number: String,
    val expectedCallCount: Int,
    val expectedArchiveCount: Int,
    val expectedWidgetBindingCount: Int,
)

/** 删除号码及其全部关联数据（档案、通话记录、录音归档与音频、小组件绑定）。 */
interface NumberDeletionApi {
    suspend fun impact(number: String): ApiResult<NumberDeletionImpact>
    suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit>
}

object UnavailableNumberDeletionApi : NumberDeletionApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "number.delete_unavailable"))
    override suspend fun impact(number: String) = unavailable()
    override suspend fun delete(command: DeleteNumberCommand) = unavailable()
}

/** 核心数据（档案 + 通话记录）的删除影响，由 backend 计算。 */
data class NumberCoreImpact(val number: String, val profileExists: Boolean, val callCount: Int)
