package com.greatwall.callring.platform.ai

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class ArchiveBoundedParserTest {
    @Test fun versionThreeCompleteAndPartiallyStagedRecordsUpgradeWithoutChangingPayloads() {
        val context=RuntimeEnvironment.getApplication();val namespace="v3-parser-upgrade"
        val cipher=object:RecordingCipher{override fun encrypt(value:String)=value;override fun decrypt(value:String)=value}
        val first=CallArchive(1,"999",1,generation=1,transcript="already indexed")
        AndroidRecordingStore(context,namespace,cipher).use{assertTrue(it.compareAndSet(1,null,first))}
        val second=RecordingJson.archive(CallArchive(2,"888",2,generation=1,transcript="😀\\\"".repeat(180000)+"UPGRADE-END")).toString()
        val path=context.getDatabasePath(namespace+"_recording.db")
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            db.execSQL("DROP TABLE archive_parse_progress");db.version=3
            db.execSQL("INSERT INTO archives VALUES(2,1,?)",arrayOf(second))
            db.execSQL("INSERT INTO archive_index_progress VALUES(2,1,?)",arrayOf(second.codePointCount(0,second.length)))
            db.execSQL("INSERT INTO archive_raw_chunks VALUES(2,1,0,?)",arrayOf(second.take(16384)))
        }
        repeat(8){
            AndroidRecordingStore(context,namespace,cipher).use{store->store.scopedReads().query(QuerySpec(AiDomain.D16),null)}
        }
        AndroidRecordingStore(context,namespace,cipher).use{store->
            val page=store.scopedReads().query(QuerySpec(AiDomain.D16),null)
            assertTrue(page.complete);assertEquals(2L,page.matchedTotal);assertEquals(2,page.rows.size)
        }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
            assertEquals(4,db.version);assertEquals(second,DatabaseUtils.stringForQuery(db,"SELECT payload FROM archives WHERE call_id=2",null))
            assertEquals(RecordingJson.archive(first).toString(),DatabaseUtils.stringForQuery(db,"SELECT payload FROM archives WHERE call_id=1",null))
            assertEquals(0L,count(db,"archive_raw_chunks"));assertEquals(0L,count(db,"archive_parse_progress"))
        }
    }
    private fun seed(db:SQLiteDatabase,payload:String){db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,payload TEXT NOT NULL)");ArchiveProjectionIndex.create(db);db.execSQL("INSERT INTO archives VALUES(1,1,?)",arrayOf<Any>(payload))}
    private fun payload(text:String,generation:Long=1)=RecordingJson.archive(CallArchive(1,"999",1,generation=generation,transcript=text)).toString()
    private fun count(db:SQLiteDatabase,table:String)=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM $table",null)
    @Test fun fullyStagedLegacyRecordDoesNoWorkWithZeroOrInsufficientBudget(){
        SQLiteDatabase.create(null).use{db->
            val raw=payload("T".repeat(2*1024*1024));seed(db,raw)
            db.execSQL("INSERT INTO archive_index_progress VALUES(1,1,?)",arrayOf(raw.length))
            db.execSQL("INSERT INTO archive_raw_chunks VALUES(1,1,0,?)",arrayOf(raw.take(16384)))
            var reads=0;val index=ArchiveProjectionIndex(db){reads+=it}
            for(budget in 0..3){assertFalse(index.advance(1,1,budget).complete);assertEquals(0,reads);assertEquals(0L,count(db,"archive_parse_progress"));assertEquals(0L,count(db,"archive_projection"))}
            val step=index.advance(1,1,31);assertFalse(step.complete);assertTrue(step.sourceBytes<=31);assertEquals(step.sourceBytes,reads)
            assertTrue(DatabaseUtils.longForQuery(db,"SELECT source_offset FROM archive_parse_progress",null)>0)
            assertEquals(0L,count(db,"archive_projection"))
        }
    }
    @Test fun giantEscapedUnicodeFieldContinuesWithinByteAndTransactionLimits(){
        SQLiteDatabase.create(null).use{db->
            val text="😀\"\\\t\n".repeat(120000)+"END-尾"
            val raw=payload(text).replace("😀","\\ud83d\\ude00")
            seed(db,raw);var reads=0;val index=ArchiveProjectionIndex(db){reads+=it};var complete=false;var rounds=0;var previous=0L
            while(!complete&&rounds++<160){
                val before=reads;val step=index.advance(1,1,32768);assertEquals(reads-before,step.sourceBytes);assertTrue(step.sourceBytes<=32768);assertTrue(step.transactions<=10);complete=step.complete
                if(!complete){assertEquals(0L,count(db,"archive_projection"));val offset=DatabaseUtils.longForQuery(db,"SELECT source_offset FROM archive_parse_progress",null);assertTrue(offset>previous);previous=offset
                    assertTrue(DatabaseUtils.longForQuery(db,"SELECT length(CAST(state AS BLOB)) FROM archive_parse_progress",null)<=131072)}
            }
            assertTrue(complete)
            val actual=db.rawQuery("SELECT text FROM archive_text_chunks WHERE field='transcript' ORDER BY start",null).use{c->buildString{while(c.moveToNext()){val part=c.getString(0);assertTrue(part.codePointCount(0,part.length)<=4097);append(part)}}}
            assertEquals(text,actual);assertEquals(raw,DatabaseUtils.stringForQuery(db,"SELECT payload FROM archives",null))
            assertEquals(0L,count(db,"archive_parse_progress"))
        }
    }
    @Test fun interruptInTheMiddleOfAStringReopensAtPersistedOffset(){
        val path=RuntimeEnvironment.getApplication().getDatabasePath("bounded-resume.db");path.parentFile!!.mkdirs();val raw=payload("T".repeat(2*1024*1024))
        var committed=0L
        SQLiteDatabase.openOrCreateDatabase(path,null).use{db->
            seed(db,raw);var read=0
            val index=ArchiveProjectionIndex(db){read+=it;if(read>=12288)Thread.currentThread().interrupt()}
            try{index.advance(1,1,1024*1024);fail("Expected cancellation inside field")}catch(_:CancellationException){}finally{Thread.interrupted()}
            committed=DatabaseUtils.longForQuery(db,"SELECT source_offset FROM archive_parse_progress",null)
            assertTrue(committed in 1..12287);assertTrue(read-committed<=4096);assertEquals(0L,count(db,"archive_projection"))
        }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            var reads=0;val index=ArchiveProjectionIndex(db){reads+=it};var step=index.advance(1,1,1024*1024)
            while(!step.complete)step=index.advance(1,1,1024*1024)
            assertEquals(raw.toByteArray(Charsets.UTF_8).size.toLong()-committed,reads.toLong())
            assertEquals(2*1024*1024L,DatabaseUtils.longForQuery(db,"SELECT total FROM archive_text_fields WHERE field='transcript'",null))
        }
    }
    @Test fun changedGenerationDiscardsOldParserStateAndCorruptionNeverPublishes(){
        SQLiteDatabase.create(null).use{db->
            seed(db,payload("old".repeat(20000)));val index=ArchiveProjectionIndex(db);assertFalse(index.advance(1,1,32768).complete)
            val replacement=payload("new 😀",2);db.execSQL("UPDATE archives SET generation=2,payload=?",arrayOf(replacement))
            assertTrue(index.advance(1,2,32768).complete)
            assertEquals("new 😀",DatabaseUtils.stringForQuery(db,"SELECT text FROM archive_text_chunks WHERE field='transcript'",null))
            assertEquals(2L,DatabaseUtils.longForQuery(db,"SELECT generation FROM archive_projection",null))
        }
        for(raw in listOf("{\"id\":1,}","{\"id\":1,\"id\":1}","{\"transcript\":\"\\uD83D\"}","{\"transcript\":\"\\q\"}"))SQLiteDatabase.create(null).use{db->
            seed(db,raw);assertTrue(runCatching{ArchiveProjectionIndex(db).advance(1,1,32768)}.isFailure);assertEquals(0L,count(db,"archive_projection"));assertEquals(raw,DatabaseUtils.stringForQuery(db,"SELECT payload FROM archives",null))
        }
    }
}
