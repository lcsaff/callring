package com.greatwall.callring.application.callers

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.settings.positionOption
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.PositionRelation
import com.greatwall.callring.domain.*

internal fun callerProfile(
    number: String,
    value: ProfileRecord?,
    position: PositionRecord?,
    mine: PositionRecord?,
): CallerProfile {
    val option = position?.let { positionOption(it, mine) }
    return CallerProfile(
        number,
        value != null,
        value?.name.orEmpty(),
        value?.department.orEmpty(),
        option,
        option?.relationToMe ?: PositionRelation.UNKNOWN,
        value?.whitelisted ?: false,
    )
}

internal fun callDetail(raw: RawCallRecord, screen: RawCallRecord?): CallDetail {
    val original = screen?.originalClassification ?: raw.originalClassification
    return CallDetail(
        raw.id,
        raw.number,
        raw.time,
        screen?.time ?: raw.time.takeIf { raw.logId == null },
        raw.duration,
        raw.result.toContract(),
        (screen?.decision ?: raw.decision).toContract(),
        if (screen != null) CallSource.MERGED else raw.source.toContract(),
        raw.counted,
        raw.classification.toContract(),
        original.toContract(),
        raw.classification != original,
        screen?.decisionNote ?: raw.decisionNote,
    )
}
