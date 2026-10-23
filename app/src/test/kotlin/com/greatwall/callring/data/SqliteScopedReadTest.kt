package com.greatwall.callring.data

import com.greatwall.callring.data.database.CallringDatabase
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.long
import com.greatwall.callring.data.database.text
import com.greatwall.callring.data.query.SqliteScopedReadAdapter
import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class SqliteScopedReadTest {

    @Test fun configurationExposesSavedRuleWithoutCompleteCapture() = runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "scoped-number-rule.db").use { store ->
            val coordinator = BusinessRevisionCoordinator()
            val source = store.fixtureScopedReads(coordinator)
            val q = QueryService(listOf(source))
            val loads = store.completeCaptures.get()
            val version = source.version(AiDomain.D10)
            val directoryVersion = source.version(AiDomain.D01)
            val default = aiObject("prefix" to aiString("3"), "length" to aiNumber(5), "revision" to aiNumber(0))
            assertEquals(default, q.query(QuerySpec(AiDomain.D10)).requiredValue().items.single()["numberRule"])
            val saved = com.greatwall.callring.contracts.settings.NumberRuleSettings("8", 6, 1)
            store.sessions.writeNumberRule { it.saveNumberRule(saved) }
            coordinator.publishCore(setOf("numberRule"), com.greatwall.callring.application.coordination.ChangeImpact.wide(
                setOf(com.greatwall.callring.contracts.change.DataDomain.NUMBER_RULES)))
            assertTrue(source.version(AiDomain.D10) > version)
            assertEquals(directoryVersion, source.version(AiDomain.D01))
            val expected = aiObject("prefix" to aiString("8"), "length" to aiNumber(6), "revision" to aiNumber(1))
            assertEquals(expected, q.query(QuerySpec(AiDomain.D10)).requiredValue().items.single()["numberRule"])
            assertEquals(expected, source.get(AiDomain.D10, "current", "default")!!.value["numberRule"])
            assertEquals(loads, store.completeCaptures.get())
        }
    }

    @Test fun callCursorIgnoresQuietCommitButRejectsChangedCalls()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"scoped-dependencies.db").use { store ->
            val fact=ClassificationFact(false,WorkReason.OUTSIDE_WORK_HOURS,null,true,"UTC",1,null,0)
            store.fixtureSql { state ->
                state.seedCaller(ProfileRecord("31234"))
                repeat(3) { i -> state.seedCall(RawCallRecord(i+1L,"31234",1000L+i,30,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SYSTEM_CALL_LOG.toDomain(),null,null,true,fact.toDomain(),fact.toDomain()))}
                state.seedSequence("calls", 4)
            }
            val coordinator=BusinessRevisionCoordinator()
            val query=QueryService(listOf(store.fixtureScopedReads(coordinator)))
            val first=query.query(QuerySpec(AiDomain.D04,limit=1)).requiredValue()
            store.sessions.writeQuiet { writer -> val old=writer.quiet();writer.saveQuiet(old.copy(settings=old.settings.copy(allNumbers=!old.settings.allNumbers))) }
            coordinator.publishCore(setOf("quiet"),com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(com.greatwall.callring.contracts.change.DataDomain.QUIET_HOURS)))
            val second=query.query(QuerySpec(AiDomain.D04,cursor=first.nextCursor,limit=1)).requiredValue()
            assertEquals(first.stamp.sourceVersion,second.stamp.sourceVersion)
            store.sessions.writeCalling { writer -> val old=writer.callingCall(1)!!;writer.updateCallingCalls(listOf(old to old.copy(duration=99))) }
            coordinator.publishCore(setOf("calls"),com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(com.greatwall.callring.contracts.change.DataDomain.CALLS)))
            assertTrue(query.query(QuerySpec(AiDomain.D04,cursor=second.nextCursor,limit=1)) is ApiResult.Failure)
        }
    }
    @Test @Config(sdk=[29]) fun searchMatchesUiNumberNameDepartmentCaseAndTrimAcrossPages()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"search-ui-equivalence.db").use{store->
            val records=listOf(ProfileRecord("+31234",name="Synthetic",department="UNIT"),ProfileRecord("91234",name="Συνθετικό",department="Kelvin K"),ProfileRecord("56789",name="x*y[z]?",department="unit"))
            store.fixtureSql {s->records.forEach{s.seedCaller(it)}}
            val q=QueryService(listOf(store.fixtureScopedReads(BusinessRevisionCoordinator())))
            for(text in listOf("123","+312"," syNTHETIC ","uNiT","kelvin k","συνθετικό","*y[z]?","absent","   ")) {
                val expected=records.filter{p->listOf(p.number,p.name,p.department).any{it.contains(text.trim(),true)}}.map{it.number}.sorted()
                val actual=mutableListOf<String>();var cursor:String?=null
                do{val page=q.query(QuerySpec(AiDomain.D01,filter=QueryFilter(text=text),limit=1,cursor=cursor)).requiredValue();assertEquals(expected.size.toLong(),page.matchedTotal);actual+=page.items.map{it["number"].string()};cursor=page.nextCursor;if(cursor==null)assertTrue(page.complete)}while(cursor!=null)
                assertEquals(expected,actual)
                val legacy=com.greatwall.callring.application.conversation.query.ScopedToolReader(q,com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher).read(OperationId.CALLERS_SEARCH,aiObject("text" to aiString(text))) as AiJson.Obj
                assertEquals(expected.size.toLong(),legacy["matchedTotal"].long())
            }
        }
    }
    @Test @Config(sdk=[29]) fun minimumApiUsesDomainProjectionContractsAndNativeCallFilters()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"scoped_minimum.db").use{store->
            val original=ClassificationFact(false,WorkReason.OUTSIDE_WORK_HOURS,null,true,"UTC",1,null,0)
            store.fixtureSql {s->
                s.seedCaller(ProfileRecord("31234"))
                listOf(1000L,9000L,9000L).forEachIndexed{i,time->val current=original.copy(working=i>0,reason=if(i==1)WorkReason.MAKEUP_WORKING else if(i==2)WorkReason.WORKING else WorkReason.OUTSIDE_WORK_HOURS);s.seedCall(RawCallRecord(i+1L,"31234",time,30,(if(i==1)CallResult.MISSED else CallResult.ANSWERED).toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SYSTEM_CALL_LOG.toDomain(),null,null,true,current.toDomain(),original.toDomain()))}
                s.seedSequence("calls", 4)
            }
            val q=QueryService(listOf(store.fixtureScopedReads(BusinessRevisionCoordinator())))
            assertEquals(2L,q.query(QuerySpec(AiDomain.D04,filter=QueryFilter(kind="WORKING"))).requiredValue().matchedTotal)
            assertEquals(2L,q.query(QuerySpec(AiDomain.D04,filter=QueryFilter(status="ANSWERED"))).requiredValue().matchedTotal)
            assertTrue(q.query(QuerySpec(AiDomain.D01,"years")) is ApiResult.Failure)
        }
    }
    @Test @Config(sdk=[29]) fun minimumApiTimeOrderCursorAlsoSurvivesByteLimitedPages()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"scoped_cursor_minimum.db").use{store->
            val fact=ClassificationFact(false,WorkReason.OUTSIDE_WORK_HOURS,null,true,"UTC",1,null,0)
            store.fixtureSql {s->s.seedCaller(ProfileRecord("31234"));listOf(1000L,9000L,9000L).forEachIndexed{i,time->s.seedCall(RawCallRecord(i+1L,"31234",time,30,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SYSTEM_CALL_LOG.toDomain(),null,null,true,fact.toDomain(),fact.toDomain()))};s.seedSequence("calls", 4)}
            val q=QueryService(listOf(store.fixtureScopedReads(BusinessRevisionCoordinator())))
            val bytes=q.query(QuerySpec(AiDomain.D04,limit=1)).requiredValue().items.single().encode().toByteArray(Charsets.UTF_8).size+1
            val ids=mutableListOf<Long>();var cursor:String?=null
            do {val page=q.query(QuerySpec(AiDomain.D04,cursor=cursor,limit=3,maxBytes=bytes)).requiredValue();ids+=page.items.map{it["id"].long()};cursor=page.nextCursor}while(cursor!=null)
            assertEquals(listOf(3L,2L,1L),ids)
        }
    }
    @Test fun everyCoreDomainIsImplementedAndStatisticsScanMatchingRows()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"scoped_aggregate.db").use{store->
            val fact=ClassificationFact(false,WorkReason.OUTSIDE_WORK_HOURS,null,true,"UTC",1,null,0)
            store.fixtureSql {s->
                s.seedCaller(ProfileRecord("31234"))
                listOf(1000L,61000L,900000L).forEachIndexed{i,at->s.seedCall(RawCallRecord(i+1L,"31234",at,if(i==2)null else 60L,(if(i==2)CallResult.MISSED else CallResult.ANSWERED).toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SYSTEM_CALL_LOG.toDomain(),null,null,true,fact.copy(working=i==1).toDomain(),fact.toDomain()))}
                s.seedSequence("calls", 4)
            }
            val api=QueryService(listOf(store.fixtureScopedReads(BusinessRevisionCoordinator())))
            (AiDomain.entries.take(10)+AiDomain.D21).forEach{domain->api.query(QuerySpec(domain)).requiredValue()}
            val aggregate=api.aggregate(AggregateSpec(AiDomain.D05,"reportTotals",QueryFilter(number="31234"))).requiredValue().value as AiJson.Obj
            assertEquals(120L,((aggregate["totals"] as AiJson.Obj)["knownDurationSeconds"] as AiJson.Num).value.toLong())
            assertEquals(88L,((aggregate["score"] as AiJson.Obj)["points"] as AiJson.Num).value.toLong())
        }
    }
    @Test fun corePagesAndAuditTextReachTailWithoutLoadingState()=runBlocking {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(),"scoped_core.db").use { store ->
            store.fixtureSql { state ->
                (1..251).forEach { state.seedCaller(ProfileRecord(it.toString().padStart(5,'0'),name="合成档案$it"))}
                state.seedAudit(AuditRecord("test","2020","a".repeat(9000)+"😀尾哨兵",1))
            }
            val loads=store.completeCaptures.get();val source=store.fixtureScopedReads(BusinessRevisionCoordinator());val api=QueryService(listOf(source))
            var cursor:String?=null;var count=0
            do {val page=api.query(QuerySpec(AiDomain.D01,limit=80,cursor=cursor)).requiredValue();assertEquals(251L,page.matchedTotal);count+=page.returnedCount;cursor=page.nextCursor} while(cursor!=null)
            assertEquals(251,count);assertEquals(loads,store.completeCaptures.get())
            val audit=api.query(QuerySpec(AiDomain.D21,filter=QueryFilter(kind="test"))).requiredValue().items.single()
            val ref=(((audit["textFields"] as AiJson.Obj)["raw"] as AiJson.Obj)["reference"] as AiJson.Str).value
            val text=StringBuilder();cursor=null
            do {val chunk=api.readText(ref,cursor,1024).requiredValue();text.append(chunk.text);cursor=chunk.nextCursor}while(cursor!=null)
            assertTrue(text.endsWith("😀尾哨兵"));assertEquals(loads,store.completeCaptures.get())
        }
    }
    @Test fun versionFenceIsNeverRequestedInsideSqliteReadTransaction()=runBlocking {
        val helper=CallringDatabase(RuntimeEnvironment.getApplication(),"scoped_lock.db")
        val db=helper.writableDatabase
        val source=SqliteScopedReadAdapter(db,{ check(!db.inTransaction()){ "SQL to fence lock inversion" };0L })
        QueryService(listOf(source)).query(QuerySpec(AiDomain.D01)).requiredValue()
        helper.close()
    }
}
