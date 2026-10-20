package com.greatwall.callring.data.callers

import android.database.Cursor
import android.database.CursorWrapper
import com.greatwall.callring.data.*
import com.greatwall.callring.domain.RawCallRecord

internal object CallerRowCodec {
    private fun fact(c: Cursor, p: String): com.greatwall.callring.domain.model.ClassificationFact {
        val reason = c.text("${p}reason")
        return com.greatwall.callring.domain.model.ClassificationFact(
            c.flag("${p}working"),
            LegacyValueCodec.reason(reason),
            LegacyValueCodec.holidayName(reason),
            c.flag("${p}verified"),
            c.text("${p}zone"),
            c.long("${p}config_version"),
            LegacyValueCodec.calendarVersion(c.text("${p}calendar_version")),
            c.long("${p}leave_version"),
        )
    }

    /**
     * Read projection only. A writer must preserve screeningKey/account and raw legacy tokens
     * separately.
     */
    fun raw(c: Cursor, prefix: String = ""): RawCallRecord {
        val c = if (prefix.isEmpty()) c else PrefixedCursor(c, prefix)
        val decision = c.text("decision")
        return RawCallRecord(
            c.long("id"),
            c.text("number"),
            c.long("time"),
            c.optionalLong("duration"),
            LegacyValueCodec.result(c.text("result")),
            LegacyValueCodec.decision(decision),
            LegacyValueCodec.source(c.text("source")),
            c.optionalText("log_id"),
            null,
            c.flag("counted"),
            fact(c, ""),
            fact(c, "original_"),
            null,
            c.flag("hidden"),
            c.optionalLong("linked_screen_id"),
            decision.takeIf {
                LegacyValueCodec.decision(it) in
                    setOf(
                        com.greatwall.callring.domain.model.CallDecision.SYSTEM_RINGTONE_FALLBACK,
                        com.greatwall.callring.domain.model.CallDecision.UNKNOWN,
                    )
            },
        )
    }

    val callColumns =
        "id,number,time,duration,result,decision,source,log_id,counted,hidden,linked_screen_id,working,reason,verified,zone,config_version,calendar_version,leave_version,original_working,original_reason,original_verified,original_zone,original_config_version,original_calendar_version,original_leave_version"

    private class PrefixedCursor(cursor: Cursor, private val prefix: String) :
        CursorWrapper(cursor) {
        override fun getColumnIndex(name: String): Int = super.getColumnIndex(prefix + name)

        override fun getColumnIndexOrThrow(name: String): Int =
            super.getColumnIndexOrThrow(prefix + name)
    }
}
