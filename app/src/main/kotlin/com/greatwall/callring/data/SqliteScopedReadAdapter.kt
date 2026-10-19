package com.greatwall.callring.data

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.safeValue
import com.greatwall.callring.application.StreamingReportAccumulator
import com.greatwall.callring.application.ReportTotalsAccumulator
import com.greatwall.callring.domain.*
import com.greatwall.callring.platform.ai.parseConversationJson
import java.time.*
import org.json.JSONArray

/** SELECT-only projections. No BackendStore.read/StorageSession, no coordinator access in a transaction. */
internal class SqliteScopedReadAdapter(private val db:SQLiteDatabase,private val versionProvider:()->Long,private val clock:()->Long=System::currentTimeMillis):ScopedReadPort {
    override val domains=(AiDomain.entries.take(10)+AiDomain.D21).toSet()
    override fun version(domain:AiDomain)=versionProvider()
    private data class Table(val sql:String,val columns:List<String>,val texts:Set<String> = emptySet(),val structured:Set<String> = emptySet())
    private fun table(domain:AiDomain,projection:String):Table {
        require(projection in setOf("default","history","current","work","years","days","intervals","counted","visible"))
        return when(domain){
            AiDomain.D01 -> Table("SELECT n.number entity_key,n.number,CASE WHEN c.number IS NULL THEN 0 ELSE 1 END stored,coalesce(c.name,'') name,coalesce(c.department,'') department,c.position_id,coalesce(c.whitelist,0) whitelisted,p.name position_name,p.level position_level,p.active position_active FROM (SELECT number FROM callers UNION SELECT number FROM calls) n LEFT JOIN callers c ON n.number=c.number LEFT JOIN positions p ON p.id=c.position_id",listOf("number","stored","name","department","position_id","whitelisted","position_name","position_level","position_active"),setOf("name","department","position_name"))
            AiDomain.D02 -> if(projection=="current") Table("SELECT 'current' entity_key,(SELECT position_id FROM settings WHERE key='my_position') myPositionId",listOf("myPositionId")) else Table("SELECT printf('%020d',id) entity_key,id,name,level,active,(SELECT position_id FROM settings WHERE key='my_position') myPositionId FROM positions",listOf("id","name","level","active","myPositionId"),setOf("name"))
            AiDomain.D03,AiDomain.D04 -> {
                val columns=listOf("id","number","time","duration","result","decision","source","log_id","screening_key","counted","working","verified","reason","original_working","original_verified","original_reason","config_version","original_config_version","calendar_version","original_calendar_version","leave_version","original_leave_version","zone","original_zone","account","hidden","linked_screen_id")
                Table("SELECT printf('%020d',id) entity_key,${columns.joinToString(",")} FROM calls"+if(domain==AiDomain.D04) " WHERE hidden=0"+(if(projection=="counted")" AND counted=1" else "") else "",columns,setOf("log_id","screening_key","reason","original_reason","calendar_version","original_calendar_version","account"))
            }
            AiDomain.D05 -> Table("SELECT printf('%020d',id) entity_key,id,start,end FROM statistics_intervals",listOf("id","start","end"))
            AiDomain.D06 -> Table("SELECT printf('%020d',id) entity_key,id,effective,snapshot FROM config_versions"+if(projection=="current") " WHERE id=(SELECT id FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1)" else "",listOf("id","effective","snapshot"),setOf("snapshot"),setOf("snapshot"))
            AiDomain.D07 -> {
                val year="SELECT 'year:'||year entity_key,year,source,published,version,NULL date,NULL working,NULL name,'year' kind FROM calendar_years"
                val days="SELECT 'day:'||date entity_key,year,NULL source,NULL published,NULL version,date,working,name,'day' kind FROM calendar_days"
                Table(when(projection){"years"->year;"days"->days;else->"$year UNION ALL $days"},listOf("year","source","published","version","date","working","name","kind"),setOf("source","published","version","name"))
            }
            AiDomain.D08 -> Table("SELECT date entity_key,date,windows FROM overrides",listOf("date","windows"),setOf("windows"),setOf("windows"))
            AiDomain.D09 -> Table("SELECT printf('%020d',id) entity_key,id,start,end,note,CASE WHEN start>${clock()} THEN 'UPCOMING' WHEN end>${clock()} THEN 'ACTIVE' ELSE 'FINISHED' END status FROM leaves",listOf("id","start","end","note","status"),setOf("note"))
            AiDomain.D10 -> {
                fun setting(key:String,default:String)="coalesce((SELECT value FROM settings WHERE key='$key'),'$default') $key"
                val values=listOf(setting("quiet_mode","OFF"),setting("all_numbers","0"),setting("quiet_windows","{}"),setting("last_quiet_mode","NON_WORK"),setting("off_weight","5"),setting("repeat_weight","2"),setting("repeat_minutes","10"),setting("score_days","30"),setting("overlay","0"),setting("leave_revision","0"),"EXISTS(SELECT 1 FROM statistics_intervals WHERE end IS NULL) statisticsEnabled","(SELECT position_id FROM settings WHERE key='my_position') myPositionId")
                Table("SELECT 'configuration' entity_key,"+values.joinToString(","),listOf("quiet_mode","all_numbers","quiet_windows","last_quiet_mode","off_weight","repeat_weight","repeat_minutes","score_days","overlay","leave_revision","statisticsEnabled","myPositionId"),setOf("quiet_windows"),setOf("quiet_windows"))
            }
            AiDomain.D21 -> Table("SELECT printf('%020d',id) entity_key,id,kind,target,raw,created FROM calendar_audit",listOf("id","kind","target","raw","created"),setOf("target","raw"))
            else -> throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.query_not_implemented"))
        }
    }
    private fun where(t:Table,f:QueryFilter):Pair<String,List<String>> {
        val clauses=mutableListOf<String>();val args=mutableListOf<String>()
        fun add(column:String,value:Any?,op:String="="){if(value!=null){require(column in t.columns);clauses+="$column $op ?";args+=value.toString()}}
        add("number",f.number);add("id",f.id);add("year",f.year);add("status",f.status);add("kind",f.kind);add("date",f.date)
        val time=when{ "time" in t.columns->"time";"start" in t.columns->"start";"created" in t.columns->"created";"effective" in t.columns->"effective";else->null}
        if(f.start!=null||f.end!=null){require(time!=null);add(time,f.start,">=");add(time,f.end,"<")}
        f.text?.let{text->val fields=t.texts.toList();require(fields.isNotEmpty());clauses+="("+fields.joinToString(" OR "){"instr($it,?)>0"}+")";repeat(fields.size){args+=text}}
        require(f.zone==null||runCatching{ZoneId.of(f.zone)}.isSuccess)
        return (if(clauses.isEmpty())"1" else clauses.joinToString(" AND ")) to args
    }
    private fun <T> read(block:()->T):T {
        db.beginTransactionNonExclusive()
        try{return block().also{db.setTransactionSuccessful()}}finally{db.endTransaction()}
    }
    private fun selection(t:Table)=listOf("entity_key")+t.columns.flatMap{c->if(c in t.texts)listOf("CASE WHEN length($c)>4096 THEN NULL ELSE $c END AS $c","length($c) AS __len_$c") else listOf(c)}
    private fun row(c:Cursor,t:Table):SourceRow {
        val refs=linkedMapOf<String,Long>()
        val values=t.columns.associateWith {name->
            val i=c.getColumnIndexOrThrow(name)
            if(name in t.texts&&c.getLong(c.getColumnIndexOrThrow("__len_$name"))>4096){refs[name]=c.getLong(c.getColumnIndexOrThrow("__len_$name"));AiJson.Null}
            else when(c.getType(i)){
                Cursor.FIELD_TYPE_NULL->AiJson.Null
                Cursor.FIELD_TYPE_INTEGER->aiNumber(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT->AiJson.Num(c.getDouble(i).toString())
                else->if(name in t.structured)parseConversationJson(c.getString(i)) else aiString(c.getString(i))
            }
        }
        return SourceRow(c.getString(c.getColumnIndexOrThrow("entity_key")),AiJson.Obj(values),refs)
    }
    override fun query(spec:QuerySpec,after:String?):SourcePage=read {
        if(spec.domain==AiDomain.D06&&spec.projection=="work")return@read SourcePage(listOf(SourceRow("work",safeValue(workStatus()) as AiJson.Obj)),1)
        val t=table(spec.domain,spec.projection);val (filter,args)=where(t,spec.filter)
        val total=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM (${t.sql}) WHERE $filter",args.toTypedArray())
        val continuation=if(after==null)"" else " AND entity_key>?"
        val rows=db.rawQuery("SELECT ${selection(t).joinToString(",")} FROM (${t.sql}) WHERE ($filter)$continuation ORDER BY entity_key LIMIT ?",(args+listOfNotNull(after)+(spec.limit+1).toString()).toTypedArray()).use{c->buildList{while(c.moveToNext())add(row(c,t))}}
        val page=rows.take(spec.limit)
        SourcePage(page,total,if(rows.size>spec.limit)page.last().key else null)
    }
    override fun get(domain:AiDomain,key:String,projection:String):SourceRow?=read {
        val t=table(domain,projection)
        val normalized=if("id" in t.columns) key.toLongOrNull()?.let{"%020d".format(it)}?:key else key
        db.rawQuery("SELECT ${selection(t).joinToString(",")} FROM (${t.sql}) WHERE entity_key=?",arrayOf(normalized)).use{c->if(c.moveToFirst())row(c,t) else null}
    }
    override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int):SourceText=read {
        val t=table(domain,"default");require(field in t.texts&&start>=0)
        db.rawQuery("SELECT substr($field,?,?),length($field) FROM (${t.sql}) WHERE entity_key=?",arrayOf((start+1).toString(),maxChars.toString(),key)).use{c->
            if(!c.moveToFirst())throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"ai.not_found"))
            val text=c.getString(0).orEmpty();val end=start+text.codePointCount(0,text.length)
            SourceText(text,start,end,c.getLong(1))
        }
    }
    override fun aggregate(spec:AggregateSpec):AiJson=read {
        val domain=if(spec.domain==AiDomain.D05)AiDomain.D04 else spec.domain
        val t=table(domain,"default");val (filter,args)=where(t,spec.filter)
        if(spec.metric!="count") {
            require(domain in setOf(AiDomain.D03,AiDomain.D04))
            return@read statistics(spec,t,filter,args)
        }
        val effectiveFilter=if(spec.domain==AiDomain.D05)"($filter) AND counted=1" else filter
        if(spec.groupBy==null)aiObject("count" to aiNumber(DatabaseUtils.longForQuery(db,"SELECT count(*) FROM (${t.sql}) WHERE $effectiveFilter",args.toTypedArray())))
        else {require(spec.groupBy in t.columns);AiJson.Arr(db.rawQuery("SELECT ${spec.groupBy},count(*) FROM (${t.sql}) WHERE $filter GROUP BY ${spec.groupBy} ORDER BY ${spec.groupBy}",args.toTypedArray()).use{c->buildList{while(c.moveToNext())add(aiObject("group" to aiString(c.getString(0).orEmpty()),"count" to aiNumber(c.getLong(1))))}})}
    }
    private fun statistics(spec:AggregateSpec,t:Table,filter:String,args:List<String>):AiJson {
        require(spec.groupBy==null||spec.groupBy=="number")
        require(spec.metric!="score"||spec.filter.number!=null||spec.groupBy=="number")
        fun setting(key:String,default:String)=DatabaseUtils.stringForQuery(db,"SELECT coalesce((SELECT value FROM settings WHERE key=?),?)",arrayOf(key,default))
        val settings=ScoreSettings(setting("off_weight","5").toInt(),setting("repeat_weight","2").toInt(),setting("repeat_minutes","10").toInt(),setting("score_days","30").toInt())
        val zone=spec.filter.zone?:db.rawQuery("SELECT snapshot FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1",null).use{c->if(c.moveToFirst())org.json.JSONObject(c.getString(0)).getString("zone") else "UTC"}
        ZoneId.of(zone)
        val range=TimeRange(spec.filter.start?:Long.MIN_VALUE,spec.filter.end?:Long.MAX_VALUE,zone)
        val totals=ReportTotalsAccumulator();val groups=linkedMapOf<String,StreamingReportAccumulator>()
        val deadline=System.nanoTime()+5_000_000_000L
        var scanned=0L;var unverified=false;var unresolved=false
        db.rawQuery("SELECT number,time,counted,working,duration,result,verified FROM (${t.sql}) WHERE $filter ORDER BY number,time,id",args.toTypedArray()).use{c->while(c.moveToNext()){
            if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.aggregate_incomplete"))
            scanned++;val number=c.getString(0);val at=c.getLong(1);val counted=c.getInt(2)!=0;val working=c.getInt(3)!=0;val duration=if(c.isNull(4))null else c.getLong(4);val result=LegacyValueCodec.result(c.getString(5))
            totals.accept(counted,working,duration,result)
            unverified=unverified||c.getInt(6)==0;unresolved=unresolved||result==CallResult.UNRESOLVED
            if(spec.filter.number!=null||spec.groupBy=="number"){
                if(number !in groups&&groups.size>=200)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.aggregate_incomplete"))
                groups.getOrPut(number){StreamingReportAccumulator(settings,range,spec.filter.end==null)}.accept(at,counted,working,duration,result)
            }
        }}
        if(spec.metric=="sumDuration")return aiObject("knownDurationSeconds" to aiNumber(totals.value().knownDurationSeconds),"unknownDurationCount" to aiNumber(totals.value().unknownDurationCount),"scanned" to aiNumber(scanned),"range" to safeValue(range))
        fun result(acc:StreamingReportAccumulator)=aiObject("totals" to safeValue(acc.totals()),"score" to safeValue(acc.score()),"range" to safeValue(range))
        if(spec.groupBy=="number")return AiJson.Arr(groups.map{(number,acc)->AiJson.Obj(result(acc).fields+("number" to aiString(number)))})
        val value=groups.values.singleOrNull()?.let(::result)?:aiObject("totals" to safeValue(totals.value()),"range" to safeValue(range),"scoreRequiresNumber" to aiBool(true))
        return AiJson.Obj(value.fields+mapOf("scanned" to aiNumber(scanned),"hasUnverifiedCalendar" to aiBool(unverified),"hasUnresolvedRecords" to aiBool(unresolved),"runtimeQualitySource" to aiString("D19"),"ruleVersion" to aiString("shared-score-v1")))
    }
    private fun workStatus():WorkStatus {
        val at=clock();val s=BackendState()
        db.rawQuery("SELECT id,effective,snapshot FROM config_versions WHERE effective<=? ORDER BY effective DESC,id DESC LIMIT 1",arrayOf(at.toString())).use{c->if(c.moveToFirst())s.schedules+=WorkConfigCodec.decode(c.getString(2),c.getLong(0),c.getLong(1))}
        if(s.schedules.isEmpty())db.rawQuery("SELECT id,effective,snapshot FROM config_versions ORDER BY effective,id LIMIT 1",null).use{c->if(c.moveToFirst())s.schedules+=WorkConfigCodec.decode(c.getString(2),c.getLong(0),c.getLong(1))}
        val zone=ZoneId.of(WorkClassifier.schedule(s,at).zoneId);val date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();val end=date.plusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()
        db.rawQuery("SELECT id,effective,snapshot FROM config_versions WHERE effective>? AND effective<=? ORDER BY effective,id",arrayOf(at.toString(),end.toString())).use{c->while(c.moveToNext())s.schedules+=WorkConfigCodec.decode(c.getString(2),c.getLong(0),c.getLong(1))}
        db.rawQuery("SELECT year,source,published,version FROM calendar_years WHERE year BETWEEN ? AND ?",arrayOf(date.minusDays(1).year.toString(),date.plusDays(10).year.toString())).use{c->while(c.moveToNext()){val y=CalendarYear(c.getInt(0),c.getString(1),c.getString(2),c.getString(3));s.calendarYears[y.year]=y}}
        db.rawQuery("SELECT date,working,name FROM calendar_days WHERE date BETWEEN ? AND ?",arrayOf(date.minusDays(1).toString(),date.plusDays(10).toString())).use{c->while(c.moveToNext()){val d=LocalDate.parse(c.getString(0));if(c.getInt(1)==1)s.makeupDates+=d else s.holidays[d]=c.getString(2)}}
        db.rawQuery("SELECT date,windows FROM overrides WHERE date BETWEEN ? AND ?",arrayOf(date.minusDays(1).toString(),date.plusDays(10).toString())).use{c->while(c.moveToNext()){val d=LocalDate.parse(c.getString(0));val w=WorkConfigCodec.windows(JSONArray(c.getString(1)));s.overrides[d]=DateOverride(d,if(w.isEmpty())DateOverrideKind.REST else DateOverrideKind.WORK,w)}}
        db.rawQuery("SELECT id,start,end,note FROM leaves WHERE start<=? AND end>=?",arrayOf(end.toString(),at.toString())).use{c->while(c.moveToNext()){val l=LeaveRecord(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3));s.leaves[l.id]=l}}
        s.leaveVersion=DatabaseUtils.stringForQuery(db,"SELECT coalesce((SELECT value FROM settings WHERE key='leave_revision'),'0')",null).toLong()
        return WorkClassifier.status(s,at)
    }
}
