package com.greatwall.callring.contracts.runtime

import com.greatwall.callring.contracts.common.*

data class AppVersionInfo(val versionName: String, val versionCode: Long)

/** 更新页状态机的全部状态；同一时刻只有一个主操作。 */

enum class UpdatePhase {
    IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, UNSUPPORTED_DEVICE,
    NO_INSTALLABLE, CHECK_FAILED, DOWNLOADING, VERIFYING, VERIFIED,
    VERIFY_FAILED, INSTALL_PERMISSION_REQUIRED, INSTALL_CANCELLED,
    INSTALL_PENDING_VERIFY,
}

/** 从 Release 元数据解析出的可用版本。 */

data class AppReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val publishedAtEpochMillis: Long?,
    val notes: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
)

/** 一次读取的不可变状态。 */

data class UpdateStatus(
    val current: AppVersionInfo,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val latest: AppReleaseInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failureReason: String? = null,
    val failureDetail: String? = null,
    val checkedAtEpochMillis: Long? = null,
    val releasePageUrl: String = CALLRING_RELEASES_URL,
)

/**
 * 应用内更新契约。UI 只消费状态与操作，不直接访问网络或系统安装器；
 * 版本比较只按 versionCode，versionName 仅用于展示。
 */

interface AppUpdateApi {
    suspend fun status(): ApiResult<UpdateStatus>
    suspend fun check(): ApiResult<UpdateStatus>
    suspend fun download(): ApiResult<UpdateStatus>
    suspend fun cancel(): ApiResult<UpdateStatus>
    suspend fun install(): ApiResult<UpdateStatus>
}

const val CALLRING_RELEASES_URL = "https://github.com/lcsaff/callring/releases"
