package com.greatwall.callring.data.calling

import com.greatwall.callring.data.codec.LegacyValueCodec
import com.greatwall.callring.data.database.flag
import com.greatwall.callring.data.database.long
import com.greatwall.callring.data.database.optionalLong
import com.greatwall.callring.data.database.optionalText
import com.greatwall.callring.data.database.text
import com.greatwall.callring.data.database.values

import android.content.ContentValues
import android.database.Cursor
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.*

/** Full persistence facts; updates preserve every unchanged raw column. */
internal object CallingRowCodec {
    fun decode(cursor: Cursor): RawCallRecord {
        val result = cursor.text("result")
        val decision = cursor.text("decision")
        val source = cursor.text("source")
        val decoded = LegacyValueCodec.decision(decision)
        return RawCallRecord(
            cursor.long("id"),
            cursor.text("number"),
            cursor.long("time"),
            cursor.optionalLong("duration"),
            LegacyValueCodec.result(result),
            decoded,
            LegacyValueCodec.source(source),
            cursor.optionalText("log_id"),
            cursor.optionalText("screening_key"),
            cursor.flag("counted"),
            fact(cursor, ""),
            fact(cursor, "original_"),
            cursor.optionalText("account"),
            cursor.flag("hidden"),
            cursor.optionalLong("linked_screen_id"),
            decision.takeIf {
                decoded == CallDecision.SYSTEM_RINGTONE_FALLBACK || decoded == CallDecision.UNKNOWN
            },
            result,
            decision,
            source,
        )
    }

    fun encode(row: RawCallRecord, old: RawCallRecord? = null): ContentValues =
        ContentValues().apply {
            fun field(name: String, value: Any?, previous: Any?) {
                if (old == null || value != previous) putAll(values(name to value))
            }
            field("number", row.number, old?.number)
            field("time", row.time, old?.time)
            field("duration", row.duration, old?.duration)
            field("log_id", row.logId, old?.logId)
            field("screening_key", row.screeningKey, old?.screeningKey)
            field("counted", row.counted, old?.counted)
            field("account", row.account, old?.account)
            field("hidden", row.hidden, old?.hidden)
            field("linked_screen_id", row.linkedScreenId, old?.linkedScreenId)
            if (old == null || row.result != old.result || row.legacyResult != old.legacyResult)
                put(
                    "result",
                    LegacyValueCodec.encodeResult(
                        row.result,
                        row.legacyResult.takeIf { old == null || row.result == old.result },
                    ),
                )
            if (
                old == null ||
                    row.decision != old.decision ||
                    row.decisionNote != old.decisionNote ||
                    row.legacyDecision != old.legacyDecision
            )
                put(
                    "decision",
                    LegacyValueCodec.encodeDecision(
                        row.decision,
                        row.legacyDecision.takeIf {
                            old == null ||
                                row.legacyDecision != old.legacyDecision ||
                                (row.decision == old.decision &&
                                    row.decisionNote == old.decisionNote)
                        },
                        row.decisionNote,
                    ),
                )
            if (old == null || row.source != old.source || row.legacySource != old.legacySource)
                put(
                    "source",
                    LegacyValueCodec.encodeSource(
                        row.source,
                        row.legacySource.takeIf { old == null || row.source == old.source },
                    ),
                )
            fun classification(
                fact: ClassificationFact,
                prior: ClassificationFact?,
                prefix: String,
            ) {
                field("${prefix}working", fact.working, prior?.working)
                field("${prefix}verified", fact.calendarVerified, prior?.calendarVerified)
                if (
                    prior == null ||
                        fact.reason != prior.reason ||
                        fact.holidayName != prior.holidayName
                )
                    put("${prefix}reason", LegacyValueCodec.encodeReason(fact))
                field("${prefix}config_version", fact.configVersion, prior?.configVersion)
                if (old == null || fact.calendarVersion != prior?.calendarVersion)
                    put(
                        "${prefix}calendar_version",
                        LegacyValueCodec.encodeCalendarVersion(fact.calendarVersion),
                    )
                field("${prefix}leave_version", fact.leaveVersion, prior?.leaveVersion)
                field("${prefix}zone", fact.zoneId, prior?.zoneId)
            }
            classification(row.classification, old?.classification, "")
            classification(row.originalClassification, old?.originalClassification, "original_")
        }

    private fun fact(cursor: Cursor, prefix: String): ClassificationFact {
        val reason = cursor.text("${prefix}reason")
        return ClassificationFact(
            cursor.flag("${prefix}working"),
            LegacyValueCodec.reason(reason),
            LegacyValueCodec.holidayName(reason),
            cursor.flag("${prefix}verified"),
            cursor.text("${prefix}zone"),
            cursor.long("${prefix}config_version"),
            LegacyValueCodec.calendarVersion(cursor.text("${prefix}calendar_version")),
            cursor.long("${prefix}leave_version"),
        )
    }
}
