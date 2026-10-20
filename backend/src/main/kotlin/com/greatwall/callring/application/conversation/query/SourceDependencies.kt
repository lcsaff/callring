package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.ai.AiDomain

/**
 * Stored dependencies shared by scoped and complete projections; time/provider observations remain
 * live.
 */
fun sourceKeys(domain: AiDomain): Set<String> =
    when (domain) {
        AiDomain.D01 -> setOf("profiles", "calls", "positions", "myPosition")
        AiDomain.D02 -> setOf("positions", "myPosition")
        AiDomain.D03,
        AiDomain.D04 -> setOf("calls", "score", "schedule")
        AiDomain.D05 ->
            setOf(
                "statistics",
                "calls",
                "profiles",
                "positions",
                "myPosition",
                "score",
                "schedule",
                "runtime:capability:CALL_LOG",
            )
        AiDomain.D06 -> setOf("schedule", "calendar", "overrides", "leaves")
        AiDomain.D07 -> setOf("calendar")
        AiDomain.D08 -> setOf("overrides")
        AiDomain.D09 -> setOf("leaves")
        AiDomain.D10 -> setOf("quiet", "score", "statistics", "overlay", "leaves", "myPosition")
        AiDomain.D11 ->
            setOf(
                "appearance:selection",
                "appearance:presetId",
                "appearance:customSeedRgb",
                "appearance:colorMode",
            )
        AiDomain.D12 -> setOf("ringtone", "runtime:ringtonePlayback")
        AiDomain.D13 -> setOf("recordingConfig", "runtime:recordingFolder")
        AiDomain.D14 -> setOf("aiConfig")
        AiDomain.D15,
        AiDomain.D16 -> setOf("recording")
        AiDomain.D17 -> setOf("recordingConfig:systemFolder")
        AiDomain.D18 -> setOf("widgets")
        AiDomain.D19 -> setOf("runtime:status", "runtime:recordingPlayback", "runtime:ringtonePlayback")
        AiDomain.D20 -> setOf("diagnostics")
        AiDomain.D21 -> setOf("audit")
    }

fun BusinessRevisionCoordinator.sourceVersion(domain: AiDomain) = lastChange(sourceKeys(domain))

val coreQueryDomains =
    setOf(
        AiDomain.D01,
        AiDomain.D02,
        AiDomain.D03,
        AiDomain.D04,
        AiDomain.D05,
        AiDomain.D06,
        AiDomain.D07,
        AiDomain.D08,
        AiDomain.D09,
        AiDomain.D10,
        AiDomain.D21,
    )
