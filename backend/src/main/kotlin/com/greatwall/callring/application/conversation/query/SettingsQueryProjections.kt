package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.recording.RecordingSettingsView
import kotlinx.coroutines.runBlocking

internal fun currentQueryRow(spec: QuerySpec, value: () -> AiJson): Sequence<SourceRow> {
    require(spec.projection in setOf("default", "current"))
    return sequenceOf(SourceRow("current", value() as AiJson.Obj))
}

class AppearanceQueryProjection(private val read: () -> AppearanceSettings) {
    fun rows(spec: QuerySpec) = currentQueryRow(spec) { safeValue(read()) }
}

class RingtoneQueryProjection(
    private val settings: suspend () -> ApiResult<RingtoneSettings>,
    private val playback: () -> PlaybackStatus,
) {
    fun rows(spec: QuerySpec) = currentQueryRow(spec) {
        aiObject("settings" to runBlocking { safeValue(settings().requiredValue()) }, "playback" to safeValue(playback()))
    }
}

class RecordingSettingsQueryProjection(private val read: suspend () -> ApiResult<RecordingSettingsView>) {
    fun rows(spec: QuerySpec) = currentQueryRow(spec) { runBlocking { safeValue(read().requiredValue()) } }
}

class AiSettingsQueryProjection(private val read: suspend () -> ApiResult<AiSettingsView>) {
    fun rows(spec: QuerySpec) = currentQueryRow(spec) { runBlocking { safeValue(read().requiredValue()) } }
}

fun queryConsistency(domain: AiDomain) =
    if (domain in setOf(AiDomain.D12, AiDomain.D13, AiDomain.D18, AiDomain.D19)) "live_probe" else "source_snapshot"
