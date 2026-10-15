package com.greatwall.callring.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.RawCallRecord

/**
 * Stores independent screening/provider rows. Joining screening facts belongs to the application.
 */
internal class CallStore(private val db: SQLiteDatabase) {
    private val calls = TableSnapshot(db, "calls", "id")
    private val initial = linkedMapOf<Long, RawCallRecord>()

    fun load(state: BackendState) {
        db.query("calls", null, null, null, null, null, "id").use { cursor ->
            while (cursor.moveToNext()) {
                val rawResult = cursor.text("result")
                val rawDecision = cursor.text("decision")
                val rawSource = cursor.text("source")
                val row =
                    RawCallRecord(
                        cursor.long("id"),
                        cursor.text("number"),
                        cursor.long("time"),
                        cursor.optionalLong("duration"),
                        LegacyValueCodec.result(rawResult),
                        LegacyValueCodec.decision(rawDecision),
                        LegacyValueCodec.source(rawSource),
                        cursor.optionalText("log_id"),
                        cursor.optionalText("screening_key"),
                        cursor.flag("counted"),
                        fact(cursor, ""),
                        fact(cursor, "original_"),
                        cursor.optionalText("account"),
                        cursor.flag("hidden"),
                        cursor.optionalLong("linked_screen_id"),
                        if (
                            LegacyValueCodec.decision(rawDecision) ==
                                CallDecision.SYSTEM_RINGTONE_FALLBACK ||
                                LegacyValueCodec.decision(rawDecision) == CallDecision.UNKNOWN
                        )
                            rawDecision
                        else null,
                        rawResult,
                        rawDecision,
                        rawSource,
                    )
                state.calls[row.id] = row
                initial[row.id] = row
            }
        }
        state.nextCallId = db.nextId("calls")
    }

    fun save(state: BackendState) {
        // Clear all changed old links before any new association to preserve the UNIQUE reference.
        initial.forEach { (id, old) ->
            if (
                old.linkedScreenId != null && old.linkedScreenId != state.calls[id]?.linkedScreenId
            ) {
                check(db.update("calls", values("linked_screen_id" to null), "id=?", args(id)) == 1)
            }
        }
        // Inserts omit references until every new screening row exists.
        state.calls.values.forEach { row ->
            val old = initial[row.id]
            if (old != row) {
                val desired = encode(row, old)
                desired.remove("linked_screen_id")
                calls.save(row.id, desired)
            }
        }
        state.calls.values.forEach { row ->
            val old = initial[row.id]
            if (old?.linkedScreenId != row.linkedScreenId && row.linkedScreenId != null) {
                check(
                    db.update(
                        "calls",
                        values("linked_screen_id" to row.linkedScreenId),
                        "id=?",
                        args(row.id),
                    ) == 1
                )
            }
        }
        calls.deleteMissing(state.calls.keys)
    }

    private fun encode(row: RawCallRecord, old: RawCallRecord?): ContentValues {
        val previous = calls.previous(row.id)
        fun text(column: String, unchanged: Boolean, encoded: String): String =
            if (unchanged) previous?.getAsString(column) ?: encoded else encoded
        val values =
            values(
                "id" to row.id,
                "number" to row.number,
                "time" to row.time,
                "duration" to row.duration,
                "result" to
                    text(
                        "result",
                        old != null &&
                            old.result == row.result &&
                            old.legacyResult == row.legacyResult,
                        LegacyValueCodec.encodeResult(
                            row.result,
                            row.legacyResult.takeIf { old == null || row.result == old.result },
                        ),
                    ),
                "decision" to
                    text(
                        "decision",
                        old != null &&
                            old.decision == row.decision &&
                            old.decisionNote == row.decisionNote &&
                            old.legacyDecision == row.legacyDecision,
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
                    ),
                "source" to
                    text(
                        "source",
                        old != null &&
                            old.source == row.source &&
                            old.legacySource == row.legacySource,
                        LegacyValueCodec.encodeSource(
                            row.source,
                            row.legacySource.takeIf { old == null || row.source == old.source },
                        ),
                    ),
                "log_id" to row.logId,
                "screening_key" to row.screeningKey,
                "counted" to row.counted,
                "account" to row.account,
                "hidden" to row.hidden,
            )
        putFact(values, row.classification, old?.classification, previous, "")
        putFact(
            values,
            row.originalClassification,
            old?.originalClassification,
            previous,
            "original_",
        )
        return values
    }

    private fun putFact(
        target: ContentValues,
        fact: ClassificationFact,
        old: ClassificationFact?,
        previous: ContentValues?,
        prefix: String,
    ) {
        target.put("${prefix}working", if (fact.working) 1 else 0)
        target.put("${prefix}verified", if (fact.calendarVerified) 1 else 0)
        target.put(
            "${prefix}reason",
            if (old != null && old.reason == fact.reason && old.holidayName == fact.holidayName)
                previous?.getAsString("${prefix}reason") ?: LegacyValueCodec.encodeReason(fact)
            else LegacyValueCodec.encodeReason(fact),
        )
        target.put("${prefix}config_version", fact.configVersion)
        target.put(
            "${prefix}calendar_version",
            LegacyValueCodec.encodeCalendarVersion(fact.calendarVersion),
        )
        target.put("${prefix}leave_version", fact.leaveVersion)
        target.put("${prefix}zone", fact.zoneId)
    }

    private fun fact(cursor: Cursor, prefix: String): ClassificationFact {
        val rawReason = cursor.text("${prefix}reason")
        return ClassificationFact(
            cursor.flag("${prefix}working"),
            LegacyValueCodec.reason(rawReason),
            LegacyValueCodec.holidayName(rawReason),
            cursor.flag("${prefix}verified"),
            cursor.text("${prefix}zone"),
            cursor.long("${prefix}config_version"),
            LegacyValueCodec.calendarVersion(cursor.text("${prefix}calendar_version")),
            cursor.long("${prefix}leave_version"),
        )
    }
}
