package com.greatwall.callring.platform.ai

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.QueryService
import com.greatwall.callring.application.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class ArchiveProjectionMigrationTest {
    private val cipher=object:RecordingCipher{override fun encrypt(value:String)=value;override fun decrypt(value:String)=value}
    @Test fun overOneMiBRecordProgressSurvivesReopenAndReachesUnicodeTail()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val namespace="large_legacy_archive"
        val path=context.getDatabasePath(namespace+"_recording.db");path.parentFile!!.mkdirs()
        val transcript="😀\"\\\n".repeat(160000)+"LEGACY-尾"
        assertTrue(RecordingJson.archive(CallArchive(-1,"999",1,generation=1,transcript=transcript)).toString().toByteArray(Charsets.UTF_8).size>1024*1024)
        SQLiteDatabase.openOrCreateDatabase(path,null).use{db->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE deleted_archives(call_id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE recording_events(id INTEGER PRIMARY KEY,session_id TEXT,number TEXT,started INTEGER,account TEXT)")
            db.execSQL("CREATE TABLE recording_statistics_links(statistics_id INTEGER PRIMARY KEY,session_id TEXT)")
            db.execSQL("INSERT INTO archives VALUES(?,?,?)",arrayOf<Any>(-1,1,RecordingJson.archive(CallArchive(-1,"999",1,generation=1,transcript=transcript)).toString()))
            db.version=2
        }
        AndroidRecordingStore(context,namespace,cipher).use{store->
            val page=QueryService(listOf(store.scopedReads())).query(QuerySpec(AiDomain.D16)).requiredValue()
            assertFalse(page.complete);assertEquals(QueryStatus.PARTIAL,page.status)
        }
        AndroidRecordingStore(context,namespace,cipher).use{store->
            val api=QueryService(listOf(store.scopedReads()));var page=api.query(QuerySpec(AiDomain.D16)).requiredValue();var attempts=1
            while(!page.complete&&attempts++<8)page=api.query(QuerySpec(AiDomain.D16,cursor=page.nextCursor)).requiredValue()
            assertTrue("must finish a record larger than its per-query source budget",page.complete)
            assertEquals(1L,page.matchedTotal)
            val ref=((((page.items.single()["textFields"] as AiJson.Obj)["transcript"] as AiJson.Obj)["reference"]) as AiJson.Str).value
            var cursor:String?=null;val result=StringBuilder()
            do{val chunk=api.readText(ref,cursor,16384).requiredValue();result.append(chunk.text);cursor=chunk.nextCursor}while(cursor!=null)
            assertEquals(transcript,result.toString())
        }
    }
}
