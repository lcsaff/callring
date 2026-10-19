package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.BackendState

/** Ephemeral comparison inside shared writes. Neither values nor entity keys are diagnostic data. */
internal fun coreScopeValues(s:BackendState):Map<String,Any?> = buildMap {
    s.profiles.forEach{(n,p)->put("profile:$n",p);put("profile:$n:exists",true);put("profile:$n:name",p.name);put("profile:$n:department",p.department);put("profile:$n:positionId",p.positionId);put("profile:$n:whitelisted",p.whitelisted)}
    s.positions.forEach{(id,p)->put("position:$id",p);put("position:$id:valid",p.active to p.level)}
    put("positions",s.positions.toMap())
    s.profiles.values.groupBy{it.positionId}.forEach{(id,profiles)->if(id!=null)put("positionRefs:$id",profiles.map{it.number}.toSet())}
    put("myPosition",s.myPositionId)
    s.calls.forEach{(id,row)->put("call:$id",row)}
    put("calls",s.calls.toMap());s.calls.values.groupBy{it.number}.forEach{(n,rows)->put("calls:$n",rows)}
    put("schedule",s.schedules.toList());put("calendar",listOf(s.calendarYears.toMap(),s.holidays.toMap(),s.makeupDates.toSet()))
    put("overrides",s.overrides.toMap());s.overrides.forEach{(d,row)->put("override:$d",row)}
    put("leaves",s.leaves.toMap());s.leaves.forEach{(id,row)->put("leave:$id",row)}
    put("statistics",s.statisticsIntervals.toList());put("score",s.score);put("quiet",s.quiet to s.lastQuietMode);put("overlay",s.overlayEnabled);put("audit",s.pendingAudits.toList())
}
internal fun changedScopeKeys(before:Map<String,Any?>,after:Map<String,Any?>)=(before.keys+after.keys).filterTo(linkedSetOf()){before[it]!=after[it]}
fun appearanceScopeValues(v:AppearanceSettings):Map<String,Any?> = mapOf("appearance:selection" to v.selection,"appearance:presetId" to v.presetId,"appearance:customSeedRgb" to v.customSeedRgb,"appearance:colorMode" to v.colorMode)
fun recordingSettingsScopeValues(v:RecordingSettings):Map<String,Any?> = buildMap {
    put("recordingConfig:recordingEnabled",v.recordingEnabled);put("recordingConfig:automaticAnalysis",v.automaticAnalysis);put("recordingConfig:captureMode",v.captureMode);put("recordingConfig:systemFolder",v.systemFolder)
    listOf(AiSlot.ANALYSIS to v.analysis,AiSlot.TRANSCRIPTION to v.transcription).forEach{(slot,c)->put("ai:$slot:protocol",c.protocol);put("ai:$slot:url",c.url);put("ai:$slot:model",c.model);put("ai:$slot:key",c.apiKey)}
    put("ai:compatibility",v.conversationCompatibility);put("ai:budget",v.conversationBudget)
}
