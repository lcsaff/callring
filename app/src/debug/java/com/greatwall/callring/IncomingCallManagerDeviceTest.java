package com.greatwall.callring;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Collections;

/** A failed event write must not turn an already matched quiet policy into a ringing call. */
public class IncomingCallManagerDeviceTest extends InstrumentationTestCase {
    private CallerRepository store, previous;
    private Context context;
    private Field instance;
    @Override protected void setUp() throws Exception {
        super.setUp(); context=getInstrumentation().getTargetContext().getApplicationContext();
        context.deleteDatabase("incoming_manager_qa.db");
        store=new CallerRepository(context,"incoming_manager_qa.db");
        instance=CallerRepository.class.getDeclaredField("instance");instance.setAccessible(true);
        previous=(CallerRepository)instance.get(null);instance.set(null,store);
        store.setStatisticsEnabled(true,System.currentTimeMillis()-60000);
        store.saveOverride(LocalDate.now(store.workConfig().zone),Collections.emptyList());
        store.setQuietMode(QuietHoursPolicy.Mode.NON_WORK);
    }
    @Override protected void tearDown() throws Exception {
        instance.set(null,previous);store.close();context.deleteDatabase("incoming_manager_qa.db");super.tearDown();
    }
    public void testEventWriteFailurePreservesMatchedQuietDecision() throws Exception {
        Field field=CallerRepository.class.getDeclaredField("db");field.setAccessible(true);
        SQLiteDatabase db=(SQLiteDatabase)field.get(store);
        db.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON calls BEGIN SELECT RAISE(ABORT,'test write failure'); END");
        IncomingCallManager.Decision decision=IncomingCallManager.inspect(context,"31234",System.currentTimeMillis(),true);
        assertTrue("event storage failure must preserve quiet",decision.silence);
        assertEquals(-1,decision.recordId);
    }
    public void testWhiteListBypassesQuietWithRuleDisabled() {
        store.saveCaller("31234","","",null,true);
        IncomingCallManager.Decision decision=IncomingCallManager.inspect(context,"31234",System.currentTimeMillis(),true);
        assertTrue(decision.white);assertFalse(decision.silence);
        assertEquals("白名单放行",decision.label);
        assertEquals(1,store.calls("31234",0,System.currentTimeMillis()+1).size());
    }
    public void testNewWorkScheduleDoesNotReclassifyAnEarlierCall() {
        WorkSchedulePolicy.Config next=store.workConfig();next.weekly.clear();next.makeup.clear();
        store.saveWorkConfig(next);
        long at=LocalDate.of(2026,1,5).atTime(10,0).atZone(next.zone).toInstant().toEpochMilli();
        IncomingCallManager.Decision decision=IncomingCallManager.inspect(context,"31234",at,false);
        assertFalse("later work settings must not silence an earlier working-time call",decision.silence);
    }
}
