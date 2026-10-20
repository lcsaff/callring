package com.greatwall.callring.application

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun BackendContext.relation(s: BackendState, positionId: Long?): PositionRelation {
    val level = s.positions[positionId]?.level ?: return PositionRelation.UNKNOWN
    val mine = s.positions[s.myPositionId]?.level ?: return PositionRelation.UNKNOWN
    return when {
        level < mine -> PositionRelation.HIGHER
        level > mine -> PositionRelation.LOWER
        else -> PositionRelation.SAME
    }
}

internal fun BackendContext.option(s: BackendState, p: PositionRecord) =
    PositionOption(p.id, p.name, p.level, p.active, relation(s, p.id))

internal fun BackendContext.options(s: BackendState) =
    frozenList(
        s.positions.values
            .sortedWith(compareBy<PositionRecord> { it.level }.thenBy { it.name }.thenBy { it.id })
            .map { option(s, it) }
    )

internal fun BackendContext.profile(s: BackendState, number: String): CallerProfile {
    val p = s.profiles[number]
    return CallerProfile(
        number,
        p != null,
        p?.name.orEmpty(),
        p?.department.orEmpty(),
        s.positions[p?.positionId]?.let { option(s, it) },
        relation(s, p?.positionId),
        p?.whitelisted ?: false,
    )
}

internal fun BackendContext.detail(s: BackendState, raw: RawCallRecord): CallDetail {
    val screen = raw.linkedScreenId?.let { s.calls[it] }
    val original = screen?.originalClassification ?: raw.originalClassification
    return CallDetail(
        raw.id,
        raw.number,
        raw.time,
        screen?.time ?: raw.time.takeIf { raw.logId == null },
        raw.duration,
        raw.result,
        screen?.decision ?: raw.decision,
        if (screen != null) CallSource.MERGED else raw.source,
        raw.counted,
        raw.classification,
        original,
        raw.classification != original,
        screen?.decisionNote ?: raw.decisionNote,
    )
}

internal fun BackendContext.visible(s: BackendState, number: String? = null) =
    s.calls.values
        .asSequence()
        .filter { !it.hidden && (number == null || it.number == number) }
        .sortedWith(compareByDescending<RawCallRecord> { it.time }.thenByDescending { it.id })
        .map { detail(s, it) }
        .toList()

internal fun BackendContext.latest(s: BackendState, number: String? = null) =
    visible(s, number).firstOrNull()

internal fun BackendContext.validatePosition(s: BackendState, id: Long?, retained: Long? = null) {
    if (id == null) return
    val p = s.positions[id] ?: fail(ErrorCode.VALIDATION, "position.not_found", "positionId")
    requireField(p.active || id == retained, "positionId", "position.inactive")
}
