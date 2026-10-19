package com.greatwall.callring.platform.ai

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class ScopedDirectoryTest {
    @Test fun providerCursorPagesPastOldFiveHundredLimitAndRevocationIsNotEmpty()=runBlocking {
        val provider=object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor=MatrixCursor(projection!!).apply{
                repeat(701){i->addRow(projection.map<String,Any>{column->when(column){DocumentsContract.Document.COLUMN_DOCUMENT_ID->"file$i";DocumentsContract.Document.COLUMN_DISPLAY_NAME->"合成$i.wav";DocumentsContract.Document.COLUMN_MIME_TYPE->"audio/wav";else->1L}}.toTypedArray())}
            }
            override fun getType(uri:Uri)="vnd.android.document/directory"
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
        }
        ShadowContentResolver.registerProviderInternal("scoped-tree",provider)
        var granted=true
        val source=SystemRecordingSource(RuntimeEnvironment.getApplication().contentResolver){granted}
        val query=QueryService(listOf(source.scopedReads({"content://scoped-tree/tree/root"},{0L},ForegroundResourceRegistry())))
        var cursor:String?=null;var count=0;var page:ScopedPage
        do{page=query.query(QuerySpec(AiDomain.D17,limit=100,cursor=cursor)).requiredValue();count+=page.returnedCount;cursor=page.nextCursor}while(cursor!=null)
        assertTrue(page.complete);assertEquals(701,count);assertFalse(page.json().encode().contains("content://"))
        granted=false
        assertTrue(query.query(QuerySpec(AiDomain.D17)) is ApiResult.Failure)
    }
}
