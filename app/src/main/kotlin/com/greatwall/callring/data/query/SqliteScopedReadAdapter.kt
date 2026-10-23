package com.greatwall.callring.data.query

import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.application.ports.query.SourceText
import com.greatwall.callring.data.codec.LegacyValueCodec
import com.greatwall.callring.data.codec.WorkConfigCodec
import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.args
import com.greatwall.callring.data.database.long
import com.greatwall.callring.data.database.text
import com.greatwall.callring.data.database.values
import com.greatwall.callring.domain.model.LeaveRecord

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.query.*
import com.greatwall.callring.application.ports.store.WorkHistory
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.data.codec.parseAiJson
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.CalendarYear
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.DateOverride
import com.greatwall.callring.domain.model.DateOverrideKind
import com.greatwall.callring.domain.model.ScoreSettings
import java.time.*
import org.json.JSONArray

/**
 * SELECT-only projections; no coordinator access in a
 * transaction.
 */
internal class SqliteScopedReadAdapter(
    private val db: SQLiteDatabase,
    private val versionProvider: (AiDomain) -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sql: SqlAccess = SqlAccess(db),
) : CoreQueryFactsPort {
    override val domains = com.greatwall.callring.application.conversation.query.coreQueryDomains

    override fun version(domain: AiDomain) = versionProvider(domain)

    override fun consistency(domain: AiDomain) =
        if (domain in setOf(AiDomain.D05, AiDomain.D06, AiDomain.D09)) "live_probe"
        else "source_snapshot"

    private val numberRuleReader: com.greatwall.callring.application.ports.store.NumberRuleReader =
        com.greatwall.callring.data.settings.SqliteSimpleSettings(sql) { write ->
            check(!write && db.inTransaction()) { "storage.session.closed" }
        }

    private data class Table(
        val sql: String,
        val columns: List<String>,
        val texts: Set<String> = emptySet(),
        val structured: Set<String> = emptySet(),
        val searchable: Set<String> = texts,
        val tables: Set<SqlTable> = emptySet(),
    )

    private fun dependencies(domain: AiDomain, projection: String): Set<SqlTable> =
        when (domain) {
            AiDomain.D01 -> setOf(SqlTable.CALLERS, SqlTable.CALLS, SqlTable.POSITIONS)
            AiDomain.D02 ->
                if (projection == "current") setOf(SqlTable.SETTINGS)
                else setOf(SqlTable.POSITIONS, SqlTable.SETTINGS)
            AiDomain.D03,
            AiDomain.D04 -> setOf(SqlTable.CALLS)
            AiDomain.D05 -> setOf(SqlTable.STATISTICS_INTERVALS)
            AiDomain.D06 -> setOf(SqlTable.CONFIG_VERSIONS)
            AiDomain.D07 ->
                when (projection) {
                    "years" -> setOf(SqlTable.CALENDAR_YEARS)
                    "days" -> setOf(SqlTable.CALENDAR_DAYS)
                    else -> setOf(SqlTable.CALENDAR_YEARS, SqlTable.CALENDAR_DAYS)
                }
            AiDomain.D08 -> setOf(SqlTable.OVERRIDES)
            AiDomain.D09 -> setOf(SqlTable.LEAVES)
            AiDomain.D10 -> setOf(SqlTable.SETTINGS, SqlTable.STATISTICS_INTERVALS)
            AiDomain.D21 -> setOf(SqlTable.CALENDAR_AUDIT)
            else -> emptySet()
        }

    private fun table(domain: AiDomain, projection: String): Table {
        val projections =
            when (domain) {
                AiDomain.D02 -> setOf("default", "current")
                AiDomain.D04 -> setOf("default", "counted", "visible")
                AiDomain.D05 -> setOf("default", "intervals")
                AiDomain.D06 -> setOf("default", "history", "current", "work")
                AiDomain.D07 -> setOf("default", "years", "days")
                else -> setOf("default")
            }
        require(projection in projections)
        return when (domain) {
            AiDomain.D01 ->
                Table(
                    "SELECT n.number entity_key,n.number,CASE WHEN c.number IS NULL THEN 0 ELSE 1 END stored,coalesce(c.name,'') name,coalesce(c.department,'') department,c.position_id,coalesce(c.whitelist,0) whitelisted,p.name position_name,p.level position_level,p.active position_active FROM (SELECT number FROM callers UNION SELECT number FROM calls) n LEFT JOIN callers c ON n.number=c.number LEFT JOIN positions p ON p.id=c.position_id",
                    listOf(
                        "number",
                        "stored",
                        "name",
                        "department",
                        "position_id",
                        "whitelisted",
                        "position_name",
                        "position_level",
                        "position_active",
                    ),
                    setOf("name", "department", "position_name"),
                    searchable = setOf("number", "name", "department"),
                )
            AiDomain.D02 ->
                if (projection == "current")
                    Table(
                        "SELECT 'current' entity_key,(SELECT position_id FROM settings WHERE key='my_position') myPositionId",
                        listOf("myPositionId"),
                    )
                else
                    Table(
                        "SELECT printf('%020d',id) entity_key,id,name,level,active,(SELECT position_id FROM settings WHERE key='my_position') myPositionId FROM positions",
                        listOf("id", "name", "level", "active", "myPositionId"),
                        setOf("name"),
                    )
            AiDomain.D03,
            AiDomain.D04 -> {
                val columns =
                    listOf(
                        "id",
                        "number",
                        "time",
                        "duration",
                        "result",
                        "decision",
                        "source",
                        "log_id",
                        "screening_key",
                        "counted",
                        "working",
                        "verified",
                        "reason",
                        "original_working",
                        "original_verified",
                        "original_reason",
                        "config_version",
                        "original_config_version",
                        "calendar_version",
                        "original_calendar_version",
                        "leave_version",
                        "original_leave_version",
                        "zone",
                        "original_zone",
                        "account",
                        "hidden",
                        "linked_screen_id",
                    )
                Table(
                    "SELECT printf('%020d',id) entity_key,${columns.joinToString(",")} FROM calls" +
                        if (domain == AiDomain.D04)
                            " WHERE hidden=0" +
                                (if (projection == "counted") " AND counted=1" else "")
                        else "",
                    columns,
                    setOf(
                        "log_id",
                        "screening_key",
                        "reason",
                        "original_reason",
                        "calendar_version",
                        "original_calendar_version",
                        "account",
                    ),
                )
            }
            AiDomain.D05 ->
                Table(
                    "SELECT printf('%020d',id) entity_key,id,start,end FROM statistics_intervals",
                    listOf("id", "start", "end"),
                )
            AiDomain.D06 ->
                Table(
                    "SELECT printf('%020d',id) entity_key,id,effective,snapshot FROM config_versions" +
                        if (projection == "current")
                            " WHERE id=(SELECT id FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1)"
                        else "",
                    listOf("id", "effective", "snapshot"),
                    setOf("snapshot"),
                    setOf("snapshot"),
                )
            AiDomain.D07 -> {
                val year =
                    "SELECT 'year:'||year entity_key,year,source,published,version,NULL date,NULL working,NULL name,'year' kind FROM calendar_years"
                val days =
                    "SELECT 'day:'||date entity_key,year,NULL source,NULL published,NULL version,date,working,name,'day' kind FROM calendar_days"
                Table(
                    when (projection) {
                        "years" -> year
                        "days" -> days
                        else -> "$year UNION ALL $days"
                    },
                    listOf(
                        "year",
                        "source",
                        "published",
                        "version",
                        "date",
                        "working",
                        "name",
                        "kind",
                    ),
                    setOf("source", "published", "version", "name"),
                )
            }
            AiDomain.D08 ->
                Table(
                    "SELECT date entity_key,date,windows FROM overrides",
                    listOf("date", "windows"),
                    setOf("windows"),
                    setOf("windows"),
                )
            AiDomain.D09 ->
                clock().let { at ->
                    Table(
                        "SELECT printf('%020d',id) entity_key,id,start,end,note,CASE WHEN start>$at THEN 'UPCOMING' WHEN end>$at THEN 'ACTIVE' ELSE 'FINISHED' END status FROM leaves",
                        listOf("id", "start", "end", "note", "status"),
                        setOf("note"),
                    )
                }
            AiDomain.D10 -> {
                fun setting(key: String, default: String) =
                    "coalesce((SELECT value FROM settings WHERE key='$key'),'$default') $key"
                val values =
                    listOf(
                        setting("quiet_mode", "OFF"),
                        setting("all_numbers", "0"),
                        setting("quiet_windows", "{}"),
                        setting("last_quiet_mode", "NON_WORK"),
                        setting("off_weight", "5"),
                        setting("repeat_weight", "2"),
                        setting("repeat_minutes", "10"),
                        setting("score_days", "30"),
                        setting("overlay", "0"),
                        setting("leave_revision", "0"),
                        "EXISTS(SELECT 1 FROM statistics_intervals WHERE end IS NULL) statisticsEnabled",
                        "(SELECT position_id FROM settings WHERE key='my_position') myPositionId",
                    )
                Table(
                    "SELECT 'configuration' entity_key," + values.joinToString(","),
                    listOf(
                        "quiet_mode",
                        "all_numbers",
                        "quiet_windows",
                        "last_quiet_mode",
                        "off_weight",
                        "repeat_weight",
                        "repeat_minutes",
                        "score_days",
                        "overlay",
                        "leave_revision",
                        "statisticsEnabled",
                        "myPositionId",
                    ),
                    setOf("quiet_windows"),
                    setOf("quiet_windows"),
                )
            }
            AiDomain.D21 ->
                Table(
                    "SELECT printf('%020d',id) entity_key,id,kind,target,raw,created FROM calendar_audit",
                    listOf("id", "kind", "target", "raw", "created"),
                    setOf("target", "raw"),
                )
            else -> throw BackendFailure(ApiError(ErrorCode.INTERNAL, "ai.query_not_implemented"))
        }.copy(tables = dependencies(domain, projection))
    }

    private fun where(t: Table, f: QueryFilter): Pair<String, List<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        fun add(column: String, value: Any?, op: String = "=") {
            if (value != null) {
                require(column in t.columns)
                clauses += "$column $op ?"
                args += value.toString()
            }
        }
        add("number", f.number)
        add("id", f.id)
        add("year", f.year)
        add("date", f.date)
        if ("result" in t.columns) {
            add(
                "result",
                f.status?.let { raw ->
                    runCatching { CallResult.valueOf(raw) }
                        .getOrNull()
                        ?.let { LegacyValueCodec.encodeResult(it) } ?: raw
                },
            )
            f.kind?.let { kind ->
                when (kind) {
                    "WORKING" -> add("working", 1)
                    "HOLIDAY" -> {
                        clauses += "reason LIKE ?"
                        args += "节假日·%"
                    }
                    else ->
                        add(
                            "reason",
                            when (kind) {
                                "MAKEUP_WORKING" -> "调休上班"
                                "PERSONAL_LEAVE" -> "请假期间"
                                "REST_DAY" -> "休息日"
                                "OUTSIDE_WORK_HOURS" -> "工作时段外"
                                "UNKNOWN" -> "未知"
                                else -> kind
                            },
                        )
                }
            }
        } else {
            add("status", f.status)
            add("kind", f.kind)
        }
        val time =
            when {
                "time" in t.columns -> "time"
                "start" in t.columns -> "start"
                "created" in t.columns -> "created"
                "effective" in t.columns -> "effective"
                else -> null
            }
        if (f.start != null || f.end != null) {
            require(time != null)
            add(time, f.start, ">=")
            add(time, f.end, "<")
        }
        f.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { text ->
                val fields = t.searchable.toList()
                require(fields.isNotEmpty())
                val pattern = UnicodeSearchPattern.contains(text)
                clauses += "(" + fields.joinToString(" OR ") { "$it GLOB ?" } + ")"
                repeat(fields.size) { args += pattern }
            }
        require(f.zone == null || runCatching { ZoneId.of(f.zone) }.isSuccess)
        return (if (clauses.isEmpty()) "1" else clauses.joinToString(" AND ")) to args
    }

    private fun <T> read(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun selection(t: Table) =
        listOf("entity_key") +
            t.columns.flatMap { c ->
                if (c in t.texts)
                    listOf(
                        "CASE WHEN length($c)>4096 THEN NULL ELSE $c END AS $c",
                        "length($c) AS __len_$c",
                    )
                else listOf(c)
            }

    private fun row(c: Cursor, t: Table, domain: AiDomain): SourceRow {
        val refs = linkedMapOf<String, Long>()
        val values =
            t.columns.associateWith { name ->
                val i = c.getColumnIndexOrThrow(name)
                if (name in t.texts && c.getLong(c.getColumnIndexOrThrow("__len_$name")) > 4096) {
                    refs[name] = c.getLong(c.getColumnIndexOrThrow("__len_$name"))
                    AiJson.Null
                } else
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> AiJson.Null
                        Cursor.FIELD_TYPE_INTEGER -> aiNumber(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> AiJson.Num(c.getDouble(i).toString())
                        else ->
                            if (name in t.structured) parseAiJson(c.getString(i))
                            else aiString(c.getString(i))
                    }
            }
        return SourceRow(
            c.getString(c.getColumnIndexOrThrow("entity_key")),
            AiJson.Obj(
                if (domain == AiDomain.D10)
                    values + ("numberRule" to safeValue(numberRuleReader.numberRule()))
                else values,
            ),
            refs,
        )
    }

    override fun query(spec: QuerySpec, after: String?): SourcePage = read {
        val t = table(spec.domain, spec.projection)
        val (filter, args) = where(t, spec.filter)
        val total =
            sql.longForQuery(
                QueryTag.AGGREGATE,
                t.tables,
                "SELECT count(*) FROM (${t.sql}) WHERE $filter",
                args.toTypedArray(),
            )
        val calls = spec.domain in setOf(AiDomain.D03, AiDomain.D04)
        val tail =
            if (after == null) emptyList()
            else if (calls) {
                val parts = after.split('|')
                require(parts.size == 2)
                listOf(parts[0], parts[0], parts[1])
            } else listOf(after)
        val continuation =
            if (after == null) ""
            else if (calls) " AND (time<? OR (time=? AND id<?))" else " AND entity_key>?"
        val order = if (calls) "time DESC,id DESC" else "entity_key"
        val rows =
            sql.rawQuery(
                    QueryTag.SCOPED_ROWS,
                    t.tables,
                    "SELECT ${selection(t).joinToString(",")} FROM (${t.sql}) WHERE ($filter)$continuation ORDER BY $order LIMIT ?",
                    (args + tail + (spec.limit + 1).toString()).toTypedArray(),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val r = row(c, t, spec.domain)
                            add(
                                if (calls)
                                    r.copy(
                                        cursorKey =
                                            "${r.value["time"].long()}|${r.value["id"].long()}",
                                    )
                                else r,
                            )
                        }
                    }
                }
        val page = rows.take(spec.limit)
        SourcePage(page, total, if (rows.size > spec.limit) page.last().cursorKey else null)
    }

    override fun get(domain: AiDomain, key: String, projection: String): SourceRow? = read {
        val t = table(domain, projection)
        val normalized =
            if (domain == AiDomain.D10 && key == "current") "configuration"
            else if ("id" in t.columns) key.toLongOrNull()?.let { "%020d".format(it) } ?: key
            else key
        sql.rawQuery(
                QueryTag.SCOPED_ROWS,
                t.tables,
                "SELECT ${selection(t).joinToString(",")} FROM (${t.sql}) WHERE entity_key=?",
                arrayOf(normalized),
            )
            .use { c -> if (c.moveToFirst()) row(c, t, domain) else null }
    }

    override fun text(
        domain: AiDomain,
        key: String,
        field: String,
        start: Long,
        maxChars: Int,
    ): SourceText = read {
        val t = table(domain, "default")
        require(field in t.texts && start >= 0)
        sql.rawQuery(
                QueryTag.SCOPED_TEXT,
                t.tables,
                "SELECT substr($field,?,?),length($field) FROM (${t.sql}) WHERE entity_key=?",
                arrayOf((start + 1).toString(), maxChars.toString(), key),
            )
            .use { c ->
                if (!c.moveToFirst())
                    throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "ai.not_found"))
                val text = c.getString(0).orEmpty()
                val end = start + text.codePointCount(0, text.length)
                SourceText(text, start, end, c.getLong(1))
            }
    }

    override fun aggregate(spec: AggregateSpec): AiJson = read {
        val domain = if (spec.domain == AiDomain.D05) AiDomain.D04 else spec.domain
        val t = table(domain, "default")
        val (filter, args) = where(t, spec.filter)
        require(spec.metric == "count")
        val effectiveFilter = if (spec.domain == AiDomain.D05) "($filter) AND counted=1" else filter
        if (spec.groupBy == null)
            aiObject(
                "count" to
                    aiNumber(
                        sql.longForQuery(
                            QueryTag.AGGREGATE,
                            t.tables,
                            "SELECT count(*) FROM (${t.sql}) WHERE $effectiveFilter",
                            args.toTypedArray(),
                        ),
                    ),
            )
        else {
            require(spec.groupBy in t.columns)
            AiJson.Arr(
                sql.rawQuery(
                        QueryTag.AGGREGATE,
                        t.tables,
                        "SELECT ${spec.groupBy},count(*) FROM (${t.sql}) WHERE $effectiveFilter GROUP BY ${spec.groupBy} ORDER BY ${spec.groupBy} LIMIT 201",
                        args.toTypedArray(),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                if (size == 200)
                                    throw BackendFailure(
                                        ApiError(
                                            ErrorCode.SYSTEM_RESTRICTED,
                                            "ai.aggregate_incomplete",
                                        ),
                                    )
                                add(
                                    aiObject(
                                        "group" to aiString(c.getString(0).orEmpty()),
                                        "count" to aiNumber(c.getLong(1)),
                                    ),
                                )
                            }
                        }
                    },
            )
        }
    }

    override fun <T> readStatistics(spec: AggregateSpec, block: (StatisticsQueryFacts) -> T): T =
        read {
            val domain = if (spec.domain == AiDomain.D05) AiDomain.D04 else spec.domain
            require(domain in setOf(AiDomain.D03, AiDomain.D04))
            val t = table(domain, "default")
            val (filter, args) = where(t, spec.filter)
            var active = true
            fun checkActive() {
                check(active) { "storage.session.closed" }
            }
            try {
                block(
                    object : StatisticsQueryFacts {
                        override fun scoreSettings(): ScoreSettings {
                            checkActive()
                            fun setting(key: String, default: String) =
                                sql.stringForQuery(
                                    QueryTag.SCOPED_SETTING,
                                    setOf(SqlTable.SETTINGS),
                                    "SELECT coalesce((SELECT value FROM settings WHERE key=?),?)",
                                    arrayOf(key, default),
                                )
                            return ScoreSettings(
                                setting("off_weight", "5").toInt(),
                                setting("repeat_weight", "2").toInt(),
                                setting("repeat_minutes", "10").toInt(),
                                setting("score_days", "30").toInt(),
                            )
                        }

                        override fun latestScheduleZone(): String {
                            checkActive()
                            return sql.rawQuery(
                                    QueryTag.WORK_ROWS,
                                    setOf(SqlTable.CONFIG_VERSIONS),
                                    "SELECT snapshot FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1",
                                    null,
                                )
                                .use { c ->
                                    if (c.moveToFirst())
                                        org.json.JSONObject(c.getString(0)).getString("zone")
                                    else "UTC"
                                }
                        }

                        override fun visit(accept: (StatisticsQueryFact) -> Unit) {
                            checkActive()
                            sql.rawQuery(
                                    QueryTag.SCOPED_ROWS,
                                    t.tables,
                                    "SELECT number,time,counted,working,duration,result,verified FROM (${t.sql}) WHERE $filter ORDER BY number,time,id",
                                    args.toTypedArray(),
                                )
                                .use { c ->
                                    while (c.moveToNext()) accept(
                                        StatisticsQueryFact(
                                            c.getString(0),
                                            c.getLong(1),
                                            c.getInt(2) != 0,
                                            c.getInt(3) != 0,
                                            if (c.isNull(4)) null else c.getLong(4),
                                            LegacyValueCodec.result(c.getString(5)),
                                            c.getInt(6) != 0,
                                        ),
                                    )
                                }
                        }
                    },
                )
            } finally {
                active = false
            }
        }

    override fun <T> readWork(block: (WorkQueryFacts) -> T): T = read {
        var active = true
        fun checkActive() {
            check(active) { "storage.session.closed" }
        }
        fun schedules(where: String, args: Array<String>?, order: String) =
            sql.rawQuery(
                    QueryTag.WORK_ROWS,
                    setOf(SqlTable.CONFIG_VERSIONS),
                    "SELECT id,effective,snapshot FROM config_versions $where ORDER BY $order",
                    args,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            WorkConfigCodec.decode(c.getString(2), c.getLong(0), c.getLong(1)),
                        )
                    }
                }
        try {
            block(
                object : WorkQueryFacts {
                    override fun scheduleCandidates(
                        at: Long,
                    ): List<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                        checkActive()
                        return schedules(
                                "WHERE effective<=?",
                                arrayOf(at.toString()),
                                "effective DESC,id DESC LIMIT 1",
                            )
                            .ifEmpty { schedules("", null, "effective,id LIMIT 1") }
                    }

                    override fun history(
                        at: Long,
                        until: Long,
                        startDate: LocalDate,
                        endDate: LocalDate,
                    ): WorkHistory {
                        checkActive()
                        val schedule =
                            scheduleCandidates(at) +
                                schedules(
                                    "WHERE effective>? AND effective<=?",
                                    arrayOf(at.toString(), until.toString()),
                                    "effective,id",
                                )
                        val years = linkedMapOf<Int, CalendarYear>()
                        val holidays = linkedMapOf<LocalDate, String>()
                        val makeup = linkedSetOf<LocalDate>()
                        val overrides = linkedMapOf<LocalDate, DateOverride>()
                        val leaves = linkedMapOf<Long, LeaveRecord>()
                        sql.rawQuery(
                                QueryTag.WORK_ROWS,
                                setOf(SqlTable.CALENDAR_YEARS),
                                "SELECT year,source,published,version FROM calendar_years WHERE year BETWEEN ? AND ?",
                                arrayOf(startDate.year.toString(), endDate.year.toString()),
                            )
                            .use { c ->
                                while (c.moveToNext()) {
                                    val y =
                                        CalendarYear(
                                            c.getInt(0),
                                            c.getString(1),
                                            c.getString(2),
                                            c.getString(3),
                                        )
                                    years[y.year] = y
                                }
                            }
                        sql.rawQuery(
                                QueryTag.WORK_ROWS,
                                setOf(SqlTable.CALENDAR_DAYS),
                                "SELECT date,working,name FROM calendar_days WHERE date BETWEEN ? AND ?",
                                arrayOf(startDate.toString(), endDate.toString()),
                            )
                            .use { c ->
                                while (c.moveToNext()) {
                                    val date = LocalDate.parse(c.getString(0))
                                    if (c.getInt(1) == 1) makeup += date
                                    else holidays[date] = c.getString(2)
                                }
                            }
                        sql.rawQuery(
                                QueryTag.WORK_ROWS,
                                setOf(SqlTable.OVERRIDES),
                                "SELECT date,windows FROM overrides WHERE date BETWEEN ? AND ?",
                                arrayOf(startDate.toString(), endDate.toString()),
                            )
                            .use { c ->
                                while (c.moveToNext()) {
                                    val date = LocalDate.parse(c.getString(0))
                                    val windows = WorkConfigCodec.windows(JSONArray(c.getString(1)))
                                    overrides[date] =
                                        DateOverride(
                                            date,
                                            if (windows.isEmpty()) DateOverrideKind.REST
                                            else DateOverrideKind.WORK,
                                            windows,
                                        )
                                }
                            }
                        sql.rawQuery(
                                QueryTag.WORK_ROWS,
                                setOf(SqlTable.LEAVES),
                                "SELECT id,start,end FROM leaves WHERE start<=? AND end>=?",
                                arrayOf(until.toString(), at.toString()),
                            )
                            .use { c ->
                                while (c.moveToNext()) {
                                    val row =
                                        LeaveRecord(c.getLong(0), c.getLong(1), c.getLong(2), "")
                                    leaves[row.id] = row
                                }
                            }
                        val version =
                            sql.stringForQuery(
                                    QueryTag.SCOPED_SETTING,
                                    setOf(SqlTable.SETTINGS),
                                    "SELECT coalesce((SELECT value FROM settings WHERE key='leave_revision'),'0')",
                                    null,
                                )
                                .toLong()
                        return WorkHistory(
                            schedule,
                            years,
                            holidays,
                            makeup,
                            overrides,
                            leaves,
                            version,
                        )
                    }
                },
            )
        } finally {
            active = false
        }
    }
}
