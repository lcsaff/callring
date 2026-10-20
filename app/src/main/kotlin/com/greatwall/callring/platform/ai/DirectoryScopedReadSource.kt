package com.greatwall.callring.platform.ai

import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.conversation.ForegroundResourceRegistry
import java.util.UUID

internal class DirectoryScopedReadSource(private val folder:()->String?,private val versionProvider:()->Long,private val resources:ForegroundResourceRegistry,private val granted:(String)->Boolean,private val children:(String,String)->Cursor,private val inspect:(String,DocumentRef)->RecordingFile?):ScopedReadPort {
    override val domains=setOf(AiDomain.D17)
    override fun version(domain:AiDomain)=versionProvider()
    override fun consistency(domain:AiDomain)="provider_walk_not_atomic"
    override fun scope(domain:AiDomain)="authorized_tree_observed"
    private data class Walk(val root:String,val pending:ArrayDeque<Pair<String,Int>>,val seen:MutableSet<String>,var cursor:Cursor?=null,var depth:Int=0,var matched:Long=0,var expires:Long=System.currentTimeMillis()+600000)
    private val walks=mutableMapOf<String,Walk>()
    @Synchronized override fun reset(){walks.values.forEach{it.cursor?.close()};walks.clear()}
    private fun root():String? {val value=folder()?:return null;val uri=Uri.parse(value);require(uri.scheme=="content"&&DocumentsContract.isTreeUri(uri));if(!granted(value))throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED,"recording.permission_required"));return value}
    private fun file(root:String,id:String,name:String,mime:String,c:Cursor):RecordingFile? {
        if(!mime.startsWith("audio/")&&name.substringAfterLast('.').lowercase() !in setOf("m4a","mp3","wav","amr","ogg","aac","flac"))return null
        val modified=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);val size=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        return RecordingFile(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root),id).toString(),name,if(c.isNull(modified))0 else c.getLong(modified),if(c.isNull(size))null else c.getLong(size),!c.isNull(modified))
    }
    private fun row(file:RecordingFile):SourceRow {val handle=resources.issue(file);return SourceRow(handle,aiObject("handle" to aiString(handle),"name" to aiString(file.name),"modifiedAt" to if(file.modifiedKnown)aiNumber(file.modifiedAt)else AiJson.Null,"size" to aiNumber(file.size)))}
    @Synchronized override fun query(spec:QuerySpec,after:String?):SourcePage {
        require(spec.projection=="default"&&spec.filter.copy(text=null)==QueryFilter())
        val root=root()?:return SourcePage(emptyList(),null,null,false,QueryStatus.NOT_CONFIGURED,"NO_AUTHORIZED_FOLDER")
        val now=System.currentTimeMillis();walks.entries.removeAll{(_,w)->if(w.expires<now){w.cursor?.close();true}else false}
        val token=after?:UUID.randomUUID().toString()
        val walk=if(after!=null) walks[token]?:throw BackendFailure(ApiError(ErrorCode.CONFLICT,"ai.cursor_stale")) else {
            if(walks.size>=8)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.capacity_exceeded"))
            val id=DocumentsContract.getTreeDocumentId(Uri.parse(root));Walk(root,ArrayDeque(listOf(id to 0)),hashSetOf(id)).also{walks[token]=it}
        }
        require(walk.root==root)
        val result=mutableListOf<SourceRow>();var visited=0
        try{
            while(result.size<spec.limit&&visited<maxOf(200,spec.limit)){
                if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("ai.cancelled")
                if(walk.cursor==null){if(walk.pending.isEmpty())break;val(id,depth)=walk.pending.removeFirst();walk.depth=depth;walk.cursor=children(root,id)}
                val cursor=walk.cursor!!
                if(!cursor.moveToNext()){cursor.close();walk.cursor=null;continue}
                visited++
                val id=cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));val name=cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));val mime=cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                if(!walk.seen.add(id))throw BackendFailure(ApiError(ErrorCode.IO_FAILURE,"recording.enumeration_cycle"))
                if(walk.seen.size>100000||walk.depth>32)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.capacity_exceeded"))
                if(mime==DocumentsContract.Document.MIME_TYPE_DIR)walk.pending.addLast(id to walk.depth+1)
                else file(root,id,name,mime,cursor)?.let{f->if(spec.filter.text==null||f.name.contains(spec.filter.text!!)){result+=row(f);walk.matched++}}
            }
            val complete=walk.cursor==null&&walk.pending.isEmpty()
            if(complete)walks.remove(token)
            return SourcePage(result,if(complete)walk.matched else null,if(complete)null else token,complete,if(complete)QueryStatus.COMPLETE else QueryStatus.PARTIAL,if(complete)null else "PROVIDER_TOTAL_UNKNOWN; continue nextCursor")
        }catch(e:Exception){walk.cursor?.close();walks.remove(token);if(e is SecurityException)throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED,"recording.permission_required"));throw e}
    }
    override fun get(domain:AiDomain,key:String,projection:String):SourceRow? {val root=root()?:return null;val document=resources.lookup(key);val file=inspect(root,document)?:return null;resources.verify(key,file);return row(file)}
    override fun aggregate(spec:AggregateSpec):AiJson = throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.aggregate_incomplete",fieldErrors=mapOf("reason" to "PROVIDER_NO_ATOMIC_TOTAL; traverse data_query to complete")))
}
