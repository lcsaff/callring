package com.greatwall.callring.contracts

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*

interface CallringUiApi {
    val storage: com.greatwall.callring.contracts.storage.StorageManagementApi
    val backup: com.greatwall.callring.contracts.backup.BackupApi
    val edits: UiEditsApi
    val conversation: ConversationApi
    val aiSettings: AiSettingsApi
    val numberDeletion: NumberDeletionApi
    val update: AppUpdateApi
    val snapshots: AiSnapshotApi
    fun commandApi(context: CommandContext): CallringUiApi
    suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile>
    val recordings: RecordingApi
    val home: HomeApi
    val callers: CallersApi
    val reports: ReportsApi
    val settings: SettingsApi
    val ringtone: RingtoneApi
    val runtime: RuntimeApi
    val widgets: WidgetBindingsApi
    val changes: ChangeFeed

    // 纯计算；调用前须成功 await settings.getNumberRule()。未准备或已关闭时明确失败。
    // 不拨号、不记录事件、不访问数据库或系统通话记录。
    fun checkNumber(raw: String): NumberCheck
}
