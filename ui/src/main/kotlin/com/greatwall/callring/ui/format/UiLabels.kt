package com.greatwall.callring.ui.format

import com.greatwall.callring.application.api.*

object UiLabels {
    fun quiet(value: QuietMode) = when (value) {
        QuietMode.OFF -> "关闭免打扰"
        QuietMode.NON_WORK -> "非工作时间静音"
        QuietMode.CUSTOM -> "自定义时段静音"
    }
    fun capability(value: Capability) = when (value) {
        Capability.PHONE_STATE -> "电话状态"
        Capability.NOTIFICATIONS -> "通知权限"
        Capability.CALL_LOG -> "通话记录"
        Capability.CONTACTS -> "通讯录"
        Capability.OVERLAY -> "悬浮窗权限"
        Capability.CALL_SCREENING_ROLE -> "来电筛选角色"
        Capability.BATTERY_OPTIMIZATION_EXEMPTION -> "电池优化设置"
    }
    fun access(value: AccessState) = when (value) {
        AccessState.GRANTED -> "已允许"
        AccessState.DENIED -> "未允许"
        AccessState.NOT_REQUIRED -> "无需授权"
        AccessState.UNAVAILABLE -> "系统不支持"
        AccessState.UNKNOWN -> "待确认"
    }
    fun audibility(value: Audibility) = when (value) {
        Audibility.AUDIBLE -> "可响铃"
        Audibility.SILENT -> "系统静音"
        Audibility.VIBRATE -> "振动模式"
        Audibility.SYSTEM_DND -> "系统免打扰"
        Audibility.ZERO_VOLUME -> "铃声音量为零"
        Audibility.UNKNOWN -> "声音状态未知"
    }
    fun phoneActivity(value: PhoneActivity) = when (value) {
        PhoneActivity.IDLE -> "空闲"
        PhoneActivity.RINGING -> "来电中"
        PhoneActivity.ACTIVE -> "通话中"
        PhoneActivity.UNKNOWN -> "电话状态未知"
    }
    fun playback(value: PlaybackStatus): String {
        val mode = when (value.mode) { PlaybackMode.NONE -> "无播放"; PlaybackMode.PREVIEW -> "铃声试听"; PlaybackMode.RINGTONE -> "来电铃声" }
        val state = when (value.state) { PlaybackState.IDLE -> "已停止"; PlaybackState.PREPARING -> "准备中"; PlaybackState.PLAYING -> "播放中"; PlaybackState.UNKNOWN -> "状态未知" }
        return "$mode · $state"
    }
    fun filter(value: CallFilter) = when (value) {
        CallFilter.ALL -> "全部明细"
        CallFilter.WORKING -> "工作时间"
        CallFilter.PERSONAL_LEAVE -> "请假期间"
        CallFilter.HOLIDAY -> "节假日"
        CallFilter.REST_DAY -> "休息日"
        CallFilter.OUTSIDE_WORK_HOURS -> "非工作时段"
    }
    fun colorMode(value: ColorMode) = when (value) {
        ColorMode.FOLLOW_SYSTEM -> "跟随系统"
        ColorMode.LIGHT -> "浅色"
        ColorMode.DARK -> "深色"
    }
}
