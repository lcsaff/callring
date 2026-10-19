package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.*
import java.time.LocalDate

/** Explicit value projection: credentials, document references and private audio paths have no encoder. */
fun safeValue(v: Any?): AiJson {
    fun obj(vararg fields: Pair<String, Any?>) = AiJson.Obj(fields.associate { it.first to safeValue(it.second) })
    return when (v) {
        null, Unit -> AiJson.Null
        is AiJson -> v
        is String -> AiJson.Str(v)
        is Boolean -> AiJson.Bool(v)
        is Number -> AiJson.Num(v.toString())
        is Enum<*> -> AiJson.Str(v.name)
        is LocalDate -> AiJson.Str(v.toString())
        is Map<*, *> -> AiJson.Obj(v.entries.associate { it.key.toString() to safeValue(it.value) })
        is Iterable<*> -> AiJson.Arr(v.map(::safeValue))
        is NumberCheck -> obj("normalized" to v.normalized, "validProfileNumber" to v.validProfileNumber, "matchesRule" to v.matchesRule)
        is PositionOption -> obj("id" to v.id, "name" to v.name, "level" to v.level, "active" to v.active, "relationToMe" to v.relationToMe)
        is CallerProfile -> obj("number" to v.number,"stored" to v.stored,"name" to v.name,"department" to v.department,"position" to v.position,"relation" to v.relation,"whitelisted" to v.whitelisted)
        is CallerDraft -> obj("number" to v.number,"name" to v.name,"department" to v.department,"positionId" to v.positionId,"whitelisted" to v.whitelisted)
        is CallerEditorData -> obj("draft" to v.draft,"selectablePositions" to v.selectablePositions,"myPosition" to v.myPosition)
        is CallerOverview -> obj("profile" to v.profile,"rollingScore" to v.rollingScore,"lifetimeCallCount" to v.lifetimeCallCount,"latestCall" to v.latestCall,"work" to v.work,"quietMode" to v.quietMode,"numberCheck" to v.numberCheck,"quality" to v.quality,"meta" to v.meta)
        is ClassificationFact -> obj("working" to v.working,"reason" to v.reason,"holidayName" to v.holidayName,"calendarVerified" to v.calendarVerified,"zoneId" to v.zoneId,"configVersion" to v.configVersion,"calendarVersion" to v.calendarVersion,"leaveVersion" to v.leaveVersion)
        is WorkStatus -> obj("evaluatedAtEpochMillis" to v.evaluatedAtEpochMillis,"classification" to v.classification,"nextBoundaryEpochMillis" to v.nextBoundaryEpochMillis,"dayKind" to v.dayKind,"holidayName" to v.holidayName)
        is CallDetail -> obj("id" to v.id,"number" to v.number,"startedAtEpochMillis" to v.startedAtEpochMillis,"screeningAtEpochMillis" to v.screeningAtEpochMillis,"durationSeconds" to v.durationSeconds,"result" to v.result,"decision" to v.decision,"source" to v.source,"counted" to v.counted,"classification" to v.classification,"originalClassification" to v.originalClassification,"corrected" to v.corrected,"decisionNote" to v.decisionNote)
        is TimeRange -> obj("startInclusiveEpochMillis" to v.startInclusiveEpochMillis,"endExclusiveEpochMillis" to v.endExclusiveEpochMillis,"zoneId" to v.zoneId)
        is ScoreSummary -> obj("points" to v.points,"totalCalls" to v.totalCalls,"offHoursCalls" to v.offHoursCalls,"repeatedCalls" to v.repeatedCalls,"offHoursWeight" to v.offHoursWeight,"repeatWeight" to v.repeatWeight,"repeatMinutes" to v.repeatMinutes,"range" to v.range)
        is ReportTotals -> obj("totalCalls" to v.totalCalls,"workingCalls" to v.workingCalls,"offHoursCalls" to v.offHoursCalls,"knownDurationSeconds" to v.knownDurationSeconds,"unknownDurationCount" to v.unknownDurationCount,"missedCount" to v.missedCount,"rejectedCount" to v.rejectedCount,"pendingCount" to v.pendingCount)
        is DataQuality -> obj("statisticsEnabled" to v.statisticsEnabled,"callLogAccess" to v.callLogAccess,"hasUnknownDuration" to v.hasUnknownDuration,"hasPendingResults" to v.hasPendingResults,"hasUnresolvedRecords" to v.hasUnresolvedRecords,"hasUnverifiedCalendar" to v.hasUnverifiedCalendar)
        is CallerReport -> obj("profile" to v.profile,"range" to v.range,"totals" to v.totals,"score" to v.score,"details" to v.details,"quality" to v.quality,"meta" to v.meta)
        is ExportReceipt -> obj("number" to v.number,"range" to v.range,"recordCount" to v.recordCount,"mimeType" to v.mimeType)
        is Page<*> -> obj("items" to v.items,"pageIndex" to v.pageIndex,"pageSize" to v.pageSize,"totalItems" to v.totalItems,"meta" to v.meta)
        is SnapshotMeta -> obj("dataRevision" to v.dataRevision,"loadedAtEpochMillis" to v.loadedAtEpochMillis)
        is QuietSettings -> obj("mode" to v.mode,"allNumbers" to v.allNumbers,"weeklyWindows" to v.weeklyWindows)
        is TimeWindow -> obj("startMinute" to v.startMinute,"endMinute" to v.endMinute)
        is PositionSettings -> obj("options" to v.options,"myPositionId" to v.myPositionId)
        is WorkScheduleSettings -> obj("zoneId" to v.zoneId,"weekly" to v.weekly,"makeup" to v.makeup,"configVersion" to v.configVersion,"effectiveFromEpochMillis" to v.effectiveFromEpochMillis)
        is CalendarYear -> obj("year" to v.year,"source" to v.source,"published" to v.published,"version" to v.version)
        is DateOverride -> obj("date" to v.date,"kind" to v.kind,"windows" to v.windows)
        is CalendarInfo -> obj("years" to v.years,"overrides" to v.overrides)
        is LeaveEntry -> obj("id" to v.id,"startInclusiveEpochMillis" to v.startInclusiveEpochMillis,"endExclusiveEpochMillis" to v.endExclusiveEpochMillis,"note" to v.note,"state" to v.state)
        is ScoreSettings -> obj("offHoursWeight" to v.offHoursWeight,"repeatWeight" to v.repeatWeight,"repeatMinutes" to v.repeatMinutes,"observationDays" to v.observationDays)
        is AppearanceSettings -> obj("selection" to v.selection,"presetId" to v.presetId,"customSeedRgb" to v.customSeedRgb,"colorMode" to v.colorMode)
        is SettingsSummary -> obj("statisticsEnabled" to v.statisticsEnabled,"overlayEnabled" to v.overlayEnabled,"quietMode" to v.quietMode,"myPosition" to v.myPosition,"workZoneId" to v.workZoneId,"score" to v.score,"appearance" to v.appearance,"meta" to v.meta)
        is RuntimeStatus -> obj("capabilities" to v.capabilities,"audibility" to v.audibility,"phoneActivity" to v.phoneActivity,"playback" to v.playback,"pinWidgetSupported" to v.pinWidgetSupported,"checkedAtEpochMillis" to v.checkedAtEpochMillis)
        is PlaybackStatus -> obj("mode" to v.mode,"state" to v.state)
        is RingtoneSettings -> obj("enabled" to v.enabled,"source" to v.source,"displayName" to v.displayName,"previewSeconds" to v.previewSeconds)
        is HomeData -> obj("ringtone" to v.ringtone,"runtime" to v.runtime,"currentWork" to v.currentWork,"latestCall" to v.latestCall,"statisticsEnabled" to v.statisticsEnabled,"overlayEnabled" to v.overlayEnabled,"quietMode" to v.quietMode,"meta" to v.meta)
        is DiagnosticEntry -> obj("occurredAtEpochMillis" to v.occurredAtEpochMillis,"legacyTimeText" to v.legacyTimeText,"message" to v.message)
        is SyncRequestReceipt -> obj("state" to v.state,"requestedAtEpochMillis" to v.requestedAtEpochMillis)
        is WidgetBinding -> obj("widgetId" to v.widgetId,"number" to v.number)
        is WidgetInstallState -> obj("ids" to v.ids, "access" to v.access)
        is WidgetBindingsListing -> obj("stored" to v.stored, "installed" to v.installed)
        is CallArchive -> obj("callId" to v.callId,"number" to v.number,"startedAtEpochMillis" to v.startedAtEpochMillis,"generation" to v.generation,"status" to v.status,"source" to v.source,"audioPresent" to (v.audioPath != null),"audioQuality" to v.audioQuality,"durationMillis" to v.durationMillis,"transcript" to v.transcript,"analysis" to v.analysis,"error" to v.error,"attempts" to v.attempts,"nextAttemptAt" to v.nextAttemptAt,"analyzedAt" to v.analyzedAt,"analysisModel" to v.analysisModel,"transcriptionModel" to v.transcriptionModel,"analysisProtocol" to v.analysisProtocol,"manualAnalysis" to v.manualAnalysis)
        is CallAnalysis -> obj("name" to v.name,"organization" to v.organization,"position" to v.position,"purpose" to v.purpose,"evidence" to v.evidence,"inferredIdentity" to v.inferredIdentity,"summary" to v.summary,"actionItems" to v.actionItems)
        is AiConnectionView -> obj("protocol" to v.protocol,"url" to v.url,"model" to v.model,"keyConfigured" to v.keyConfigured,"resolvedProfile" to v.resolvedProfile,"thinkingMode" to v.thinkingMode,"thinkingSupport" to v.thinkingSupport,"thinkingNotice" to v.thinkingNotice)
        is ConversationBudget -> obj("context" to v.context,"output" to v.output)
        is AiSettingsView -> obj("analysis" to v.analysis,"transcription" to v.transcription,"budget" to v.budget,"revision" to v.revision,"compatibility" to v.compatibility)
        is RecordingSettingsView -> obj("recordingEnabled" to v.recordingEnabled,"automaticAnalysis" to v.automaticAnalysis,"captureMode" to v.captureMode,"folderConfigured" to v.folderConfigured,"revision" to v.revision,"folderDisplayName" to v.folderDisplayName,"folderAccess" to v.folderAccess)
        is StatisticsInterval -> obj("startMillis" to v.startMillis,"endMillis" to v.endMillis)
        is LeaveRecord -> obj("id" to v.id,"startMillis" to v.startMillis,"endMillis" to v.endMillis,"note" to v.note)
        is AuditRecord -> obj("kind" to v.kind,"target" to v.target,"raw" to v.raw,"createdMillis" to v.createdMillis)
        is RawCallRecord -> obj("id" to v.id,"number" to v.number,"time" to v.time,"duration" to v.duration,"result" to v.result,"decision" to v.decision,"source" to v.source,"logId" to v.logId,"screeningKey" to v.screeningKey,"counted" to v.counted,"classification" to v.classification,"originalClassification" to v.originalClassification,"account" to v.account,"hidden" to v.hidden,"linkedScreenId" to v.linkedScreenId,"decisionNote" to v.decisionNote,"legacyResult" to v.legacyResult,"legacyDecision" to v.legacyDecision,"legacySource" to v.legacySource)
        else -> throw IllegalArgumentException("ai.projection_unsupported")
    }
}
