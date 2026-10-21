package com.greatwall.callring.data.settings

import com.greatwall.callring.data.codec.WorkConfigCodec
import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.values

import com.greatwall.callring.application.ports.store.WorkScheduleWriter
import com.greatwall.callring.data.*
import com.greatwall.callring.domain.model.defaultWorkSchedule
import com.greatwall.callring.domain.model.WorkScheduleSettings

internal class SqliteWorkSchedule(private val sql: SqlAccess, private val checkActive: (Boolean) -> Unit) : WorkScheduleWriter {
    override fun latestWorkSchedule(): WorkScheduleSettings? {
        checkActive(false)
        return sql.rawQuery(QueryTag.WORK_ROWS, setOf(SqlTable.CONFIG_VERSIONS),
            "SELECT id,effective,snapshot FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1", null).use {
            if (it.moveToFirst()) WorkConfigCodec.decode(it.getString(2), it.getLong(0), it.getLong(1)) else null
        }
    }
    override fun appendWorkSchedule(value: WorkScheduleSettings): WorkScheduleSettings {
        checkActive(true)
        val id = sql.insertReturningId("config_versions", values("effective" to value.effectiveFromEpochMillis, "snapshot" to WorkConfigCodec.encode(value)))
        check(id != -1L) { "storage.insert.ignored" }
        return value.copy(configVersion = id)
    }
}
