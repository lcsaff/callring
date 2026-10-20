package com.greatwall.callring.platform

import com.greatwall.callring.domain.model.CallResult
import android.content.ContentResolver
import android.provider.CallLog
import com.greatwall.callring.domain.SystemCallRecord

/**
 * Returns provider facts. Null/negative duration remains unknown; it is never inferred from
 * timestamps.
 */
class AndroidCallLogSource(private val resolver: ContentResolver) {
    @JvmOverloads
    fun read(
        startInclusiveMillis: Long,
        endExclusiveMillis: Long,
        isStopped: () -> Boolean = { false },
    ): List<SystemCallRecord> {
        require(startInclusiveMillis >= 0 && endExclusiveMillis > startInclusiveMillis)
        if (isStopped() || Thread.currentThread().isInterrupted)
            throw java.io.InterruptedIOException("call_log.cancelled")
        val fields =
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                CallLog.Calls.PHONE_ACCOUNT_ID,
            )
        val selection =
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ? AND ${CallLog.Calls.TYPE} IN (1,3,5,6,7)"
        val cursor =
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                fields,
                selection,
                arrayOf(startInclusiveMillis.toString(), endExclusiveMillis.toString()),
                "${CallLog.Calls.DATE} ASC,${CallLog.Calls._ID} ASC",
            ) ?: throw java.io.IOException("call_log.provider_unavailable")
        return cursor.use { rows ->
            val results = mutableListOf<SystemCallRecord>()
            while (rows.moveToNext()) {
                if (isStopped() || Thread.currentThread().isInterrupted)
                    throw java.io.InterruptedIOException("call_log.cancelled")
                val result =
                    when (rows.getInt(4)) {
                        1 -> CallResult.ANSWERED
                        3 -> CallResult.MISSED
                        5 -> CallResult.REJECTED
                        6 -> CallResult.BLOCKED
                        7 -> CallResult.ANSWERED_ELSEWHERE
                        else -> continue
                    }
                val duration = if (rows.isNull(3)) null else rows.getLong(3).takeIf { it >= 0 }
                val account =
                    if (rows.isNull(5) || rows.isNull(6)) null
                    else rows.getString(5) + "|" + rows.getString(6)
                results +=
                    SystemCallRecord(
                        rows.getString(0),
                        rows.getString(1).orEmpty(),
                        rows.getLong(2),
                        duration,
                        result,
                        account,
                    )
            }
            results
        }
    }
}
