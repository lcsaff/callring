package com.greatwall.callring.platform.ai

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.*
import java.util.concurrent.CancellationException

/** Resumable JSON projection. A string buffer never exceeds 4097 Unicode code points. */
internal class ArchiveStreamParser(private val db:SQLiteDatabase,private val id:Long,private val generation:Long,saved:AiJson.Obj?=null) {
    private data class Frame(val objectValue:Boolean,val path:String,var phase:Int=0,var key:String="",val fields:MutableMap<String,AiJson> = linkedMapOf(),val values:MutableList<AiJson> = mutableListOf())
    private val frames=mutableListOf<Frame>()
    var offset=0L;private set
    var sourceOffset=0L;private set
    private var root:AiJson=AiJson.Null
    private var done=false
    private var mode=""
    private var keyToken=false
    private var path=""
    private var buffer=StringBuilder()
    private var bufferedPoints=0
    private var written=0L
    private var textField=false
    private var discard=false
    private var escape=0
    private var hex=0
    private var digits=0
    private var high=-1
    init {if(saved!=null){
        offset=saved["offset"].long();sourceOffset=saved["sourceOffset"].long();root=saved["root"];done=saved["done"].boolean();mode=saved["mode"].string()
        keyToken=saved["keyToken"].boolean();path=saved["path"].string();buffer.append(saved["buffer"].string());bufferedPoints=buffer.codePointCount(0,buffer.length)
        written=saved["written"].long();textField=saved["textField"].boolean();discard=saved["discard"].boolean()
        escape=saved["escape"].long().toInt();hex=saved["hex"].long().toInt();digits=saved["digits"].long().toInt();high=saved["high"].long().toInt()
        (saved["frames"] as AiJson.Arr).values.forEach{value->val f=value as AiJson.Obj;frames+=Frame(f["object"].boolean(),f["path"].string(),f["phase"].long().toInt(),f["key"].string(),(f["fields"] as AiJson.Obj).fields.toMutableMap(),(f["values"] as AiJson.Arr).values.toMutableList())}
    }}
    fun checkpoint():String=aiObject("sourceOffset" to aiNumber(sourceOffset),"offset" to aiNumber(offset),"root" to root,"done" to aiBool(done),"mode" to aiString(mode),"keyToken" to aiBool(keyToken),"path" to aiString(path),"buffer" to aiString(buffer.toString()),"written" to aiNumber(written),"textField" to aiBool(textField),"discard" to aiBool(discard),"escape" to aiNumber(escape),"hex" to aiNumber(hex),"digits" to aiNumber(digits),"high" to aiNumber(high),"frames" to AiJson.Arr(frames.map{f->aiObject("object" to aiBool(f.objectValue),"path" to aiString(f.path),"phase" to aiNumber(f.phase),"key" to aiString(f.key),"fields" to AiJson.Obj(f.fields),"values" to AiJson.Arr(f.values))})).encode().also{if(it.toByteArray(Charsets.UTF_8).size>131072)capacity()}
    fun consume(raw:String,sourceBytes:Int) {
        var at=0
        while(at<raw.length){checkActive();val point=raw.codePointAt(at);accept(point);offset++;at+=Character.charCount(point)}
        sourceOffset+=sourceBytes
    }
    fun finish():AiJson.Obj {
        if(mode=="atom")endAtom()
        require(done&&frames.isEmpty()&&mode.isEmpty()){"ai.archive_source_shape"}
        return root as? AiJson.Obj?:throw IllegalArgumentException("ai.archive_source_shape")
    }
    private fun checkActive(){if(Thread.currentThread().isInterrupted)throw CancellationException("ai.cancelled")}
    private fun capacity():Nothing=throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.archive_metadata_capacity"))
    private fun valuePath():String {
        val frame=frames.lastOrNull()?:return ""
        require(if(frame.objectValue)frame.phase==2 else frame.phase in setOf(0,2))
        return (if(frame.objectValue)if(frame.path.isEmpty())frame.key else "${frame.path}.${frame.key}" else "${frame.path}[${frame.values.size}]").also{if(it.length>2048)capacity()}
    }
    private fun emit(value:AiJson) {
        val frame=frames.lastOrNull()
        if(frame==null){require(!done);root=value;done=true;return}
        if(frame.objectValue){require(frame.phase==2);frame.fields[frame.key]=value;frame.phase=3;if(frame.fields.size>512)capacity()}
        else{require(frame.phase in setOf(0,2));frame.values+=value;frame.phase=1;if(frame.values.size>512)capacity()}
    }
    private fun accept(point:Int) {
        if(mode=="string"){stringPoint(point);return}
        if(mode=="atom"){
            if(point in listOf(32,9,10,13,44,93,125)){endAtom();accept(point)}
            else{require(point<128);if(buffer.length>=128)capacity();buffer.append(point.toChar())};return
        }
        if(point in listOf(32,9,10,13))return
        require(!done)
        val frame=frames.lastOrNull()
        when(point){
            34->{
                keyToken=frame?.objectValue==true&&frame.phase in setOf(0,4)
                path=if(keyToken)""else valuePath();mode="string";buffer=StringBuilder();bufferedPoints=0;written=0;escape=0;hex=0;digits=0;high=-1
                discard=!keyToken&&path=="audio"
                textField=!keyToken&&!discard&&(path=="transcript"||path.startsWith("analysis."))
                if(textField)db.execSQL("INSERT OR REPLACE INTO archive_text_fields VALUES(?,?,?,0)",arrayOf<Any>(id,generation,path))
            }
            123,91->{val p=valuePath();if(frames.size>=32||p=="audio")capacity();frames+=Frame(point==123,p)}
            125,93->{
                require(frame!=null&&frame.objectValue==(point==125)&&frame.phase in if(frame.objectValue)setOf(0,3)else setOf(0,1))
                frames.removeAt(frames.lastIndex);emit(if(frame.objectValue)AiJson.Obj(frame.fields)else AiJson.Arr(frame.values))
            }
            58->{require(frame?.objectValue==true&&frame.phase==1);frame.phase=2}
            44->{require(frame!=null&&frame.phase==if(frame.objectValue)3 else 1);frame.phase=if(frame.objectValue)4 else 2}
            else->{valuePath();require(point==45||point in 48..57||point in listOf(116,102,110));mode="atom";buffer=StringBuilder().append(point.toChar())}
        }
    }
    private fun endAtom(){
        val token=buffer.toString()
        val value=when(token){"true"->aiBool(true);"false"->aiBool(false);"null"->AiJson.Null;else->{require(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")));AiJson.Num(token)}}
        val audio=valuePath()=="audio";mode="";buffer=StringBuilder()
        if(audio){frames.last().key="audioPresent";emit(aiBool(value!=AiJson.Null))}else emit(value)
    }
    private fun stringPoint(point:Int){
        when(escape){
            2->{require(point<128);val digit=Character.digit(point.toChar(),16);require(digit>=0);hex=(hex shl 4)+digit;digits++;if(digits==4){decoded(hex);escape=0;digits=0;hex=0};return}
            1->{escape=0;when(point){34,47,92->decoded(point);98->decoded(8);102->decoded(12);110->decoded(10);114->decoded(13);116->decoded(9);117->{escape=2;digits=0;hex=0};else->error("ai.archive_source_shape")};return}
        }
        when(point){
            92->escape=1
            34->{
                require(high<0);mode=""
                if(keyToken){val f=frames.last();val raw=buffer.toString();val key=if(f.path.isEmpty())names[raw]?:raw else if(f.path=="analysis")when(raw){"identity"->"inferredIdentity";"actions"->"actionItems";else->raw}else raw;require(key !in f.fields&&!(raw=="audio"&&"audioPresent" in f.fields));f.key=key;f.phase=1}
                else if(discard){frames.last().key="audioPresent";emit(aiBool(true))}
                else if(textField){flush();db.execSQL("UPDATE archive_text_fields SET total=? WHERE call_id=? AND generation=? AND field=?",arrayOf<Any>(written,id,generation,path));emit(AiJson.Null)}
                else emit(aiString(buffer.toString()))
                buffer=StringBuilder();bufferedPoints=0
            }
            else->{require(point>=32);decoded(point)}
        }
    }
    private fun decoded(point:Int){
        if(point in 0xD800..0xDBFF){require(high<0);high=point;return}
        val cp=if(high>=0){require(point in 0xDC00..0xDFFF);Character.toCodePoint(high.toChar(),point.toChar()).also{high=-1}}else{require(point !in 0xDC00..0xDFFF);point}
        if(discard)return
        buffer.appendCodePoint(cp);bufferedPoints++
        if(keyToken){if(bufferedPoints>256)capacity();return}
        if(!textField&&bufferedPoints>4096){textField=true;db.execSQL("INSERT OR REPLACE INTO archive_text_fields VALUES(?,?,?,0)",arrayOf<Any>(id,generation,path))}
        if(textField&&bufferedPoints>=4096)flush()
    }
    private fun flush(){
        if(bufferedPoints==0)return
        checkActive();val next=written+bufferedPoints
        db.execSQL("INSERT OR REPLACE INTO archive_text_chunks VALUES(?,?,?,?,?,?)",arrayOf<Any>(id,generation,path,written,next,buffer.toString()))
        written=next;buffer=StringBuilder();bufferedPoints=0
    }
    companion object {private val names=mapOf("id" to "callId","started" to "startedAtEpochMillis","quality" to "audioQuality","duration" to "durationMillis","next" to "nextAttemptAt","analyzed" to "analyzedAt","protocol" to "analysisProtocol","manual" to "manualAnalysis")}
}
