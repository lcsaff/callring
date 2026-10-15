package com.greatwall.callring;

import android.test.InstrumentationTestCase;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.content.ContentValues;
import android.os.SystemClock;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device tests shipped only in the debug APK; each case uses its own disposable database. */
public class ManagementStoreDeviceTest extends InstrumentationTestCase {
    public void testSystemRecordCanArriveBeforeScreening() {
        store.setStatisticsEnabled(true,start);
        store.importCallLog("before", "31234", start+1000, 8L, "已接听");
        store.recordIncoming("31234", start+1000, "白名单放行");
        store.importCallLog("before", "31234", start+1000, 8L, "已接听");
        assertEquals(1,store.report("31234",start,start+10000).score.total);
        assertEquals("白名单放行",store.calls("31234",start,start+10000).get(0).decision);
    }
    public void testTwoProviderRecordsMustNotGuessTheScreeningAssociation() {
        store.setStatisticsEnabled(true,start);
        store.recordIncoming("31234",start+1000,"白名单放行");
        store.importCallLog("sim-a","31234",start+1000,5L,"已接听");
        store.importCallLog("sim-b","31234",start+1000,7L,"已接听");
        List<CallerRepository.CallRecord> calls=store.calls("31234",start,start+10000);
        boolean pending=false;
        for(CallerRepository.CallRecord r:calls){
            if(r.logId==null&&!r.counted&&"白名单放行".equals(r.decision))pending=true;
            if(r.logId!=null)assertFalse("ambiguous record inherited decision","白名单放行".equals(r.decision));
        }
        assertTrue("screening event must remain pending verification",pending);
        assertEquals(2,store.report("31234",start,start+10000).score.total);
    }
    public void testReimportRefreshesFinalDuration() {
        store.setStatisticsEnabled(true,start);
        store.importCallLog("updated","31234",start+1000,0L,"已接听");
        store.importCallLog("updated","31234",start+1000,20L,"已接听");
        assertEquals(Long.valueOf(20),store.calls("31234",start,start+10000).get(0).duration);
    }
    private CallerRepository store;
    private String database;
    private final long start = 1780275600000L;

    @Override protected void setUp() throws Exception {
        super.setUp();
        database = "qa-management-" + UUID.randomUUID() + ".db";
        store = new CallerRepository(getInstrumentation().getTargetContext(), database);
    }
    @Override protected void tearDown() throws Exception {
        if (store != null) store.close();
        if (database != null && database.startsWith("qa-management-"))
            getInstrumentation().getTargetContext().deleteDatabase(database);
        super.tearDown();
    }
    public void testPositionAndCallerPersistTogether() {
        long position = store.savePosition(0, "经理", 2, true);
        store.setMyPosition(position);
        store.saveCaller("3 1234", "技术部", "张三", position, true);
        CallerRepository.Caller caller = store.caller("31234");
        assertEquals("张三", caller.name);
        assertEquals("技术部", caller.department);
        assertEquals(Long.valueOf(position), caller.positionId);
        assertTrue(caller.whitelist);
        assertEquals(Long.valueOf(position), store.myPositionId());
        store.close();
        store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        assertEquals("张三", store.caller("31234").name);
    }
    public void testReferencedPositionCannotBeDeleted() {
        long position = store.savePosition(0, "主管", 3, true);
        store.saveCaller("31234", "", "", position, false);
        try { store.deletePosition(position); fail("in-use position deleted"); }
        catch (IllegalStateException expected) { }
        store.savePosition(position, "高级主管", 2, false);
        assertEquals("高级主管", store.position(position).name);
        assertEquals(Long.valueOf(position), store.caller("31234").positionId);
    }
    public void testStatisticsPauseExcludesTheGap() {
        store.setStatisticsEnabled(true, start);
        assertTrue(store.recordIncoming("31234", start + 1000, "正常响铃") > 0);
        store.setStatisticsEnabled(false, start + 2000);
        assertEquals(-1L, store.recordIncoming("31234", start + 3000, "正常响铃"));
        store.setStatisticsEnabled(true, start + 4000);
        assertTrue(store.recordIncoming("31234", start + 5000, "正常响铃") > 0);
        store.importCallLog("gap", "31234", start + 3000, 12L, "已接听");
        assertEquals(2, store.calls("31234", start, start + 10000).size());
    }
    public void testCallLogCompletesWithoutDuplicatingAndZeroIsKnown() {
        store.setStatisticsEnabled(true, start);
        store.recordIncoming("31234", start + 1000, "正常响铃");
        assertNull(store.calls("31234", start, start + 10000).get(0).duration);
        store.importCallLog("log-1", "31234", start + 1100, 0L, "未接");
        store.importCallLog("log-1", "31234", start + 1100, 0L, "未接");
        List<CallerRepository.CallRecord> calls = store.calls("31234", start, start + 10000);
        assertEquals(1, calls.size());
        assertEquals(Long.valueOf(0), calls.get(0).duration);
        assertEquals("未接", calls.get(0).result);
        assertEquals("正常响铃", calls.get(0).decision);
    }

    public void testAmbiguousPendingCallsAreNotDoubleCounted() {
        store.setStatisticsEnabled(true, start);
        store.recordIncoming("31234", start + 1000, "正常响铃");
        store.recordIncoming("31234", start + 2000, "免打扰静音");
        store.importCallLog("ambiguous-1", "31234", start + 1500, 20L, "已接听");
        CallerRepository.Report report = store.report("31234", start, start + 10000);
        assertEquals(3, report.records.size());
        assertEquals(1, report.score.total);
        assertEquals(20L, report.knownDurationSeconds);
        assertEquals(2, report.pending);
    }

    public void testLeaveCorrectionPreservesOriginalClassificationAndDecision() {
        long at = LocalDate.of(2026, 6, 1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.setStatisticsEnabled(true, at - 1000);
        store.recordIncoming("31234", at, "正常响铃");
        long leave = store.saveLeave(0, at - 100, at + 100, "补录请假");
        CallerRepository.CallRecord corrected = store.calls("31234", at - 1000, at + 1000).get(0);
        assertTrue(corrected.originalWorking);
        assertFalse(corrected.working);
        assertEquals("请假期间", corrected.reason);
        assertEquals("正常响铃", corrected.decision);
        store.deleteLeave(leave);
        assertTrue(store.calls("31234", at - 1000, at + 1000).get(0).working);
    }

    public void testCalendarImportKeepsDateOverridesAndRejectsInvalidYearAtomically() {
        LocalDate date = LocalDate.of(2027, 1, 4);
        long at = date.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.setStatisticsEnabled(true, at - 1000);
        store.recordIncoming("31234", at, "正常响铃");
        assertFalse(store.calls("31234", at - 1000, at + 1000).get(0).verified);
        store.saveOverride(date, Collections.singletonList(new WorkSchedulePolicy.Window(540, 720)));
        store.importCalendar("{\"year\":2027,\"source\":\"qa\",\"published\":\"2026-12-01\",\"version\":\"qa.1\",\"holidays\":[{\"start\":\"2027-01-04\",\"end\":\"2027-01-04\",\"name\":\"测试假日\"}],\"workdays\":[]}");
        assertTrue(store.calls("31234", at - 1000, at + 1000).get(0).working);
        assertTrue(store.calls("31234", at - 1000, at + 1000).get(0).verified);
        try {
            store.importCalendar("{\"year\":2027,\"source\":\"qa\",\"published\":\"2026-12-01\",\"version\":\"bad\",\"holidays\":[],\"workdays\":[\"2028-01-01\"]}");
            fail("invalid year accepted");
        } catch (IllegalArgumentException expected) { }
        store.deleteOverride(date);
        assertFalse(store.calls("31234", at - 1000, at + 1000).get(0).working);
        assertEquals("节假日·测试假日", store.calls("31234", at - 1000, at + 1000).get(0).reason);
    }

    public void testStatisticsBoundariesScreeningDedupAndIndependentProviderRecords() {
        store.setStatisticsEnabled(true, start);
        long id = store.recordIncoming("3 (1234)", start, "正常响铃");
        assertEquals(id, store.recordIncoming("31234", start, "正常响铃"));
        assertEquals(-1L, store.recordIncoming("+8631234", start, "正常响铃"));
        store.setStatisticsEnabled(false, start + 1000);
        store.importCallLog("at-end", "31234", start + 1000, 1L, "已接听");
        store.importCallLog("independent-1", "31234", start, 0L, "未接");
        store.importCallLog("independent-2", "31234", start, 0L, "拒接");
        CallerRepository.Report report = store.report("31234", start, start + 1000);
        int providerRows = 0;
        for (CallerRepository.CallRecord record : report.records) if (record.logId != null) providerRows++;
        assertEquals(2, providerRows);
        assertEquals(2, report.score.total);
        assertEquals(1, report.score.repeats);
        assertEquals(0, report.unknownDurationCount);
        assertEquals(1, report.missed);
        assertEquals(1, report.rejected);
    }

    public void testPositionComparisonAndMyReferenceProtection() {
        long mine = store.savePosition(0, "我的职位", 2, true);
        long senior = store.savePosition(0, "高层职位", 1, true);
        store.setMyPosition(mine);
        store.saveCaller("+86 (123)-456", "", "", senior, false);
        assertEquals("高层职位 ↑", store.positionSummary(store.caller("+86123456")));
        try { store.deletePosition(mine); fail("my position deleted"); }
        catch (IllegalStateException expected) { }
        try { store.savePosition(0, " 高层职位 ", 3, true); fail("duplicate position accepted"); }
        catch (IllegalArgumentException expected) { }
        store.savePosition(senior, "改名职位", 3, false);
        assertEquals("改名职位 ↓", store.positionSummary(store.caller("+86123456")));
        assertEquals(1, store.positions(false).size());
        assertEquals(2, store.positions(true).size());
    }

    public void testSettingsDefaultsQuietRestoreAndPersistence() {
        assertFalse(store.statisticsEnabled());
        assertFalse(store.overlayEnabled());
        assertEquals(QuietHoursPolicy.Mode.OFF, store.quietMode());
        assertNull(store.report("31234", start, start + 1).score.points);
        store.toggleQuiet();
        assertEquals(QuietHoursPolicy.Mode.NON_WORK, store.quietMode());
        store.setQuietMode(QuietHoursPolicy.Mode.CUSTOM);
        store.toggleQuiet(); store.toggleQuiet();
        assertEquals(QuietHoursPolicy.Mode.CUSTOM, store.quietMode());
        store.saveQuietWindows(Collections.singletonMap(1, Collections.singletonList(new WorkSchedulePolicy.Window(1320, 420))));
        store.setOverlayEnabled(true); store.setAllNumbers(true); store.saveScoreWeights(7, 3, 15, 45);
        store.close(); store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        assertTrue(store.overlayEnabled()); assertTrue(store.allNumbers());
        assertEquals(QuietHoursPolicy.Mode.CUSTOM, store.quietMode());
        assertEquals(1320, store.quietWindows().get(1).get(0).startMinute);
        assertEquals(45, store.scoreWeights()[3]);
        try { store.saveScoreWeights(-1, 3, 15, 45); fail("invalid score weight accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(7, store.scoreWeights()[0]);
    }

    public void testRegularScheduleChangesDoNotReclassifyHistoricalCalls() {
        long at = LocalDate.of(2026, 6, 1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.setStatisticsEnabled(true, at - 1000); store.recordIncoming("31234", at, "正常响铃");
        WorkSchedulePolicy.Config changed = store.workConfig(); changed.weekly.clear(); changed.makeup.clear();
        store.saveWorkConfig(changed); store.reclassify();
        assertTrue(store.calls("31234", at - 1000, at + 1000).get(0).working);
        assertFalse(WorkSchedulePolicy.evaluate(store.workConfig(), at).working);
        store.importCallLog("historical-config", "31235", at, 12L, "已接听");
        assertTrue(store.calls("31235", at - 1000, at + 1000).get(0).working);
    }

    public void testProviderFirstThenMultipleScreeningEventsRemainsAmbiguous() {
        store.setStatisticsEnabled(true, start);
        store.importCallLog("first-provider", "31234", start + 1500, 10L, "已接听");
        long first = store.recordIncoming("31234", start + 1000, "白名单放行");
        store.recordIncoming("31234", start + 2000, "免打扰静音");
        store.updateDecision(first, "正常响铃");
        CallerRepository.Report report = store.report("31234", start, start + 10000);
        assertEquals(3, report.records.size()); assertEquals(1, report.score.total); assertEquals(2, report.pending);
        for (CallerRepository.CallRecord record : report.records) {
            if (record.logId != null) assertEquals("系统记录补全", record.decision);
            if (record.id == first) { assertEquals(start + 1000, record.time); assertEquals("正常响铃", record.decision); assertFalse(record.counted); }
        }
    }

    public void testKnownAccountDisambiguatesAndDecisionUpdatesKeepTheOriginalEventId() {
        store.setStatisticsEnabled(true, start);
        long screen = store.recordIncoming("31234", start + 1000, "白名单放行", "component/account-a");
        store.importCallLog("account-a", "31234", start + 1100, 5L, "已接听", "component/account-a");
        store.importCallLog("account-b", "31234", start + 1100, 7L, "已接听", "component/account-b");
        store.updateDecision(screen, "免打扰静音");
        List<CallerRepository.CallRecord> rows = store.calls("31234", start, start + 10000);
        assertEquals(2, rows.size());
        for (CallerRepository.CallRecord record : rows) {
            assertNotNull(record.logId);
            if (record.logId.equals("account-a")) { assertEquals("免打扰静音", record.decision); assertEquals("component/account-a", record.account); }
            else assertEquals("系统记录补全", record.decision);
        }
        store.close(); store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        assertEquals(2, store.report("31234", start, start + 10000).score.total);
        assertEquals(0, store.report("31234", start, start + 10000).pending);
    }

    public void testUnknownAccountCannotSelectOneOfTwoKnownAccounts() {
        store.setStatisticsEnabled(true, start);
        store.recordIncoming("31234", start + 1000, "白名单放行", null);
        store.importCallLog("known-a", "31234", start + 1000, 5L, "已接听", "component/account-a");
        store.importCallLog("known-b", "31234", start + 1000, 7L, "已接听", "component/account-b");
        CallerRepository.Report report = store.report("31234", start, start + 10000);
        assertEquals(3, report.records.size()); assertEquals(2, report.score.total); assertEquals(1, report.pending);
        for (CallerRepository.CallRecord record : report.records) {
            if (record.logId == null) { assertNull(record.account); assertFalse(record.counted); }
            else assertEquals("系统记录补全", record.decision);
        }
    }

    public void testProviderTimeRefreshCanUndoAssociationWithoutLosingScreeningFacts() {
        store.setStatisticsEnabled(true, start);
        long screen = store.recordIncoming("31234", start + 1000, "白名单放行");
        store.importCallLog("moved-provider", "31234", start + 1100, 5L, "已接听");
        store.importCallLog("moved-provider", "31234", start + 8000, 20L, "已接听");
        CallerRepository.Report report = store.report("31234", start, start + 10000);
        assertEquals(2, report.records.size()); assertEquals(2, report.score.total);
        assertEquals(20L, report.knownDurationSeconds); assertEquals(1, report.pending);
        for (CallerRepository.CallRecord record : report.records) {
            if (record.id == screen) { assertEquals(start + 1000, record.time); assertEquals("白名单放行", record.decision); }
            if (record.logId != null) { assertEquals(start + 8000, record.time); assertEquals("系统记录补全", record.decision); }
        }
    }

    public void testVersionOneMigrationPreservesProfilesAndTheOriginalScreeningId() {
        store.setStatisticsEnabled(true, start);
        long position = store.savePosition(0, "迁移职位", 2, true);
        store.setMyPosition(position); store.saveCaller("31234", "部门", "姓名", position, true);
        long screen = store.recordIncoming("31234", start + 1000, "白名单放行");
        makeLegacyMergedFixture(screen, start + 1100, false);
        store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        assertEquals("姓名", store.caller("31234").name); assertEquals(Long.valueOf(position), store.myPositionId());
        assertEquals(1, store.calls("31234", start, start + 10000).size());
        store.updateDecision(screen, "正常响铃");
        assertEquals("正常响铃", store.calls("31234", start, start + 10000).get(0).decision);
        store.importCallLog("new-provider", "31234", start + 1000, 4L, "已接听");
        List<CallerRepository.CallRecord> rows = store.calls("31234", start, start + 10000);
        assertEquals(3, rows.size()); assertEquals(2, store.report("31234", start, start + 10000).score.total);
        boolean originalFound = false;
        for (CallerRepository.CallRecord row : rows) if (row.id == screen) {
            originalFound = true; assertEquals(start + 1000, row.time); assertEquals("正常响铃", row.decision); assertFalse(row.counted);
        }
        assertTrue(originalFound);
    }

    private SQLiteDatabase fixtureDatabase() {
        return SQLiteDatabase.openDatabase(getInstrumentation().getTargetContext().getDatabasePath(database).getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }

    private void makeLegacyMergedFixture(long screen, long providerAt, boolean wrongCurrent) {
        store.close();
        // Recreate only this disposable QA table as the shipped v1 schema, with a merged row.
        try (SQLiteDatabase fixture = SQLiteDatabase.openDatabase(getInstrumentation().getTargetContext()
                .getDatabasePath(database).getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            fixture.execSQL("CREATE TABLE legacy_calls(id INTEGER PRIMARY KEY AUTOINCREMENT,number TEXT NOT NULL REFERENCES callers(number) ON DELETE RESTRICT,time INTEGER NOT NULL,duration INTEGER CHECK(duration IS NULL OR duration>=0),result TEXT NOT NULL,decision TEXT NOT NULL,source TEXT NOT NULL,log_id TEXT UNIQUE,screening_key TEXT UNIQUE,counted INTEGER NOT NULL CHECK(counted IN(0,1)),working INTEGER NOT NULL,verified INTEGER NOT NULL,reason TEXT NOT NULL,original_working INTEGER NOT NULL,original_verified INTEGER NOT NULL,original_reason TEXT NOT NULL,config_version INTEGER NOT NULL REFERENCES config_versions(id),original_config_version INTEGER NOT NULL REFERENCES config_versions(id),calendar_version TEXT NOT NULL,original_calendar_version TEXT NOT NULL,leave_version INTEGER NOT NULL,original_leave_version INTEGER NOT NULL,zone TEXT NOT NULL,original_zone TEXT NOT NULL)");
            fixture.execSQL("INSERT INTO legacy_calls SELECT id,number,time,duration,result,decision,source,log_id,screening_key,counted,working,verified,reason,original_working,original_verified,original_reason,config_version,original_config_version,calendar_version,original_calendar_version,leave_version,original_leave_version,zone,original_zone FROM calls");
            fixture.execSQL("DROP TABLE calls"); fixture.execSQL("ALTER TABLE legacy_calls RENAME TO calls");
            fixture.execSQL("CREATE INDEX calls_number_time ON calls(number,time)");
            fixture.execSQL("CREATE INDEX calls_pending ON calls(number,time) WHERE log_id IS NULL");
            fixture.execSQL("UPDATE calls SET log_id='legacy-provider',time=?,duration=8,result='已接听',source='筛选＋系统通话记录' WHERE id=?", new Object[] {providerAt, screen});
            if (wrongCurrent) fixture.execSQL("UPDATE calls SET working=0,reason='工作时段外',zone='UTC',config_version=(SELECT MAX(id) FROM config_versions)");
            fixture.setVersion(1);
        }
    }

    public void testMigrationReclassifiesRestoredScreeningTimeAcrossNoonBoundary() {
        long at = LocalDate.of(2026, 6, 1).atTime(11, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.setStatisticsEnabled(true, at - 1000);
        long screen = store.recordIncoming("31234", at, "白名单放行");
        WorkSchedulePolicy.Config later = store.workConfig(); later.zone = ZoneId.of("UTC"); store.saveWorkConfig(later);
        makeLegacyMergedFixture(screen, at + 1000, true);
        store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        store.importCallLog("legacy-provider", "31234", at + 30000, 8L, "已接听");
        boolean restored = false;
        for (CallerRepository.CallRecord row : store.calls("31234", at - 1000, at + 60000)) if (row.id == screen) {
            restored = true; assertTrue(row.counted); assertTrue(row.working); assertTrue(row.originalWorking);
            assertEquals("工作中", row.reason); assertEquals("白名单放行", row.decision);
        }
        assertTrue(restored);
        try (SQLiteDatabase fixture = fixtureDatabase(); Cursor cursor = fixture.rawQuery("SELECT config_version,zone FROM calls WHERE id=?", new String[] {Long.toString(screen)})) {
            assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0)); assertEquals(ZoneId.systemDefault().getId(), cursor.getString(1));
        }
    }

    public void testIgnoredSettingsWriteThrowsAndRollsBackOtherWeights() {
        try (SQLiteDatabase fixture = fixtureDatabase()) {
            fixture.execSQL("CREATE TRIGGER qa_ignore_weight BEFORE INSERT ON settings WHEN NEW.key='repeat_weight' BEGIN SELECT RAISE(IGNORE); END");
        }
        try { store.saveScoreWeights(7, 3, 15, 45); fail("ignored database write was reported as success"); }
        catch (IllegalStateException expected) { }
        assertEquals(5, store.scoreWeights()[0]); assertEquals(2, store.scoreWeights()[1]); assertEquals(30, store.scoreWeights()[3]);
    }

    public void testIgnoredMyPositionWriteThrows() {
        long position = store.savePosition(0, "我的职位", 2, true);
        try (SQLiteDatabase fixture = fixtureDatabase()) {
            fixture.execSQL("CREATE TRIGGER qa_ignore_position BEFORE INSERT ON settings WHEN NEW.key='my_position' BEGIN SELECT RAISE(IGNORE); END");
        }
        try { store.setMyPosition(position); fail("ignored database write was reported as success"); }
        catch (IllegalStateException expected) { }
        assertNull(store.myPositionId());
    }

    public void testBoundedReconciliationStillCountsCandidatesOutsideAffectedWindow() {
        store.setStatisticsEnabled(true, start);
        store.recordIncoming("31234", start + 6000, "白名单放行");
        store.recordIncoming("31234", start + 16000, "免打扰静音");
        store.importCallLog("center-provider", "31234", start + 11000, 5L, "已接听");
        store.importCallLog("moving-provider", "31234", start + 1000, 7L, "已接听");
        store.importCallLog("moving-provider", "31234", start + 61000, 7L, "已接听");
        CallerRepository.Report report = store.report("31234", start, start + 70000);
        assertEquals(4, report.records.size()); assertEquals(2, report.score.total); assertEquals(2, report.pending);
        for (CallerRepository.CallRecord row : report.records) if (row.logId != null) assertEquals("系统记录补全", row.decision);
    }

    public void testThousandStableProviderImportsDoNotRewriteHistory() {
        store.setStatisticsEnabled(true, start);
        long screen = store.recordIncoming("31234", start + 1000, "白名单放行");
        store.importCallLog("scale-0", "31234", start + 1100, 1L, "已接听");
        try (SQLiteDatabase fixture = fixtureDatabase()) {
            ContentValues screenTemplate = new ContentValues(), providerTemplate = new ContentValues();
            try (Cursor cursor = fixture.rawQuery("SELECT * FROM calls WHERE id=?", new String[] {Long.toString(screen)})) {
                assertTrue(cursor.moveToFirst()); DatabaseUtils.cursorRowToContentValues(cursor, screenTemplate);
            }
            try (Cursor cursor = fixture.rawQuery("SELECT * FROM calls WHERE log_id='scale-0'", null)) {
                assertTrue(cursor.moveToFirst()); DatabaseUtils.cursorRowToContentValues(cursor, providerTemplate);
            }
            screenTemplate.remove("id"); providerTemplate.remove("id"); fixture.beginTransaction();
            try {
                for (int i = 1; i < 1000; i++) {
                    long screenAt = start + 1000 + i * 60000L;
                    ContentValues raw = new ContentValues(screenTemplate); raw.put("time", screenAt); raw.put("screening_key", "31234:" + screenAt);
                    long rawId = fixture.insertOrThrow("calls", null, raw);
                    ContentValues provider = new ContentValues(providerTemplate); provider.put("time", screenAt + 100);
                    provider.put("log_id", "scale-" + i); provider.put("linked_screen_id", rawId); fixture.insertOrThrow("calls", null, provider);
                }
                fixture.setTransactionSuccessful();
            } finally { fixture.endTransaction(); }
            fixture.execSQL("CREATE TABLE qa_update_count(value INTEGER NOT NULL)"); fixture.execSQL("INSERT INTO qa_update_count VALUES(0)");
            fixture.execSQL("CREATE TRIGGER qa_count_call_update AFTER UPDATE ON calls BEGIN UPDATE qa_update_count SET value=value+1; END");
        }
        long before = SystemClock.elapsedRealtime();
        store.importCallLog("scale-0", "31234", start + 1100, 1L, "已接听");
        assertEquals("a stable provider import rewrote call rows", 0L, callUpdateCount());
        for (int i = 1; i < 1000; i++) store.importCallLog("scale-" + i, "31234", start + 1100 + i * 60000L, 1L, "已接听");
        long elapsed = SystemClock.elapsedRealtime() - before;
        assertEquals(0L, callUpdateCount()); assertTrue("1000 stable imports took " + elapsed + " ms", elapsed < 15000);
        store.importCallLog("scale-500", "31234", start + 1100 + 500 * 60000L, 20L, "已接听");
        assertEquals("duration correction rewrote unrelated call rows", 1L, callUpdateCount());
        CallerRepository.Report report = store.report("31234", start, start + 1000 * 60000L);
        assertEquals(1000, report.score.total); assertEquals(1019L, report.knownDurationSeconds); assertEquals(0, report.pending);
    }

    private long callUpdateCount() {
        try (SQLiteDatabase fixture = fixtureDatabase(); Cursor cursor = fixture.rawQuery("SELECT value FROM qa_update_count", null)) {
            assertTrue(cursor.moveToFirst()); return cursor.getLong(0);
        }
    }

    public void testLatestCallUsesTheSameProjectionForOneOrAllNumbers() {
        assertNull(store.latestCall(null));
        store.setStatisticsEnabled(true, start);
        store.recordIncoming("31234", start + 1000, "白名单放行");
        store.importCallLog("latest-provider", "31234", start + 1100, 5L, "已接听");
        store.recordIncoming("31235", start + 2000, "正常响铃");
        assertEquals("31235", store.latestCall(null).number);
        CallerRepository.CallRecord specific = store.latestCall("3 1234");
        assertEquals(start + 1100, specific.time); assertEquals("白名单放行", specific.decision);
        assertEquals(Long.valueOf(5), specific.duration); assertEquals("latest-provider", specific.logId);
        assertNull(store.latestCall("31236"));
    }

    public void testCorrectionMetadataPreservesLinkedScreeningVersions() {
        long at = LocalDate.of(2026, 6, 1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.setStatisticsEnabled(true, at - 1000);
        store.recordIncoming("31234", at, "正常响铃");
        CallerRepository.CallRecord original = store.latestCall("31234");
        assertEquals(1L, original.configVersion); assertEquals(1L, original.originalConfigVersion);
        assertEquals(0L, original.leaveVersion); assertEquals(0L, original.originalLeaveVersion);
        assertEquals("2026.1", original.calendarVersion); assertEquals("2026.1", original.originalCalendarVersion);
        assertEquals(ZoneId.systemDefault().getId(), original.zone); assertEquals(original.zone, original.originalZone);
        long leave = store.saveLeave(0, at - 1000, at + 1000, "更正依据测试");
        store.importCallLog("metadata-provider", "31234", at + 100, 5L, "已接听");
        CallerRepository.CallRecord corrected = store.latestCall("31234");
        assertEquals("metadata-provider", corrected.logId); assertEquals(1L, corrected.leaveVersion);
        assertEquals(0L, corrected.originalLeaveVersion); assertEquals(1L, corrected.originalConfigVersion);
        assertEquals("正常响铃", corrected.decision); assertTrue(corrected.originalWorking); assertFalse(corrected.working);
        // The provider itself was observed after the correction; its own original revision is 1.
        try (SQLiteDatabase fixture = fixtureDatabase(); Cursor cursor = fixture.rawQuery("SELECT original_leave_version FROM calls WHERE log_id='metadata-provider'", null)) {
            assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0));
        }
        store.importCalendar("{\"year\":2026,\"source\":\"qa\",\"published\":\"2026-01-01\",\"version\":\"qa.audit.2\",\"holidays\":[],\"workdays\":[]}");
        corrected = store.report("31234", at - 1000, at + 1000).records.get(0);
        assertEquals("qa.audit.2", corrected.calendarVersion); assertEquals("2026.1", corrected.originalCalendarVersion);
        assertEquals(1L, corrected.leaveVersion); assertEquals(0L, corrected.originalLeaveVersion);
        store.deleteLeave(leave);
        corrected = store.latestCall("31234");
        assertEquals(2L, corrected.leaveVersion); assertEquals(0L, corrected.originalLeaveVersion);
        assertEquals(original.originalZone, corrected.originalZone); assertEquals(original.originalConfigVersion, corrected.originalConfigVersion);
        assertTrue(corrected.working);
    }

    public void testQuietSettingsSaveRollsBackThenSucceedsAsOneChange() {
        Map<Integer,List<WorkSchedulePolicy.Window>> before = Collections.singletonMap(1,
                Collections.singletonList(new WorkSchedulePolicy.Window(1320, 1380)));
        Map<Integer,List<WorkSchedulePolicy.Window>> after = Collections.singletonMap(1,
                Collections.singletonList(new WorkSchedulePolicy.Window(1260, 1380)));
        store.saveQuietSettings(QuietHoursPolicy.Mode.NON_WORK, false, before);
        try (SQLiteDatabase fixture = fixtureDatabase()) {
            fixture.execSQL("CREATE TRIGGER qa_ignore_quiet_mode BEFORE INSERT ON settings WHEN NEW.key='quiet_mode' BEGIN SELECT RAISE(IGNORE); END");
        }
        try { store.saveQuietSettings(QuietHoursPolicy.Mode.CUSTOM, true, after); fail("ignored final write was reported as success"); }
        catch (IllegalStateException expected) { }
        assertEquals(1320, store.quietWindows().get(1).get(0).startMinute);
        assertFalse(store.allNumbers()); assertEquals(QuietHoursPolicy.Mode.NON_WORK, store.quietMode());
        try (SQLiteDatabase fixture = fixtureDatabase()) { fixture.execSQL("DROP TRIGGER qa_ignore_quiet_mode"); }
        store.saveQuietSettings(QuietHoursPolicy.Mode.CUSTOM, true, after);
        store.close(); store = new CallerRepository(getInstrumentation().getTargetContext(), database);
        assertEquals(1260, store.quietWindows().get(1).get(0).startMinute);
        assertTrue(store.allNumbers()); assertEquals(QuietHoursPolicy.Mode.CUSTOM, store.quietMode());
        store.toggleQuiet(); store.toggleQuiet(); assertEquals(QuietHoursPolicy.Mode.CUSTOM, store.quietMode());
    }

    public void testEmptyCustomQuietWindowIsRejectedBeforeAnyWrite() {
        Map<Integer,List<WorkSchedulePolicy.Window>> before = Collections.singletonMap(1,
                Collections.singletonList(new WorkSchedulePolicy.Window(1320, 1380)));
        store.saveQuietSettings(QuietHoursPolicy.Mode.NON_WORK, false, before);
        try { store.saveQuietSettings(QuietHoursPolicy.Mode.CUSTOM, true, Collections.emptyMap()); fail("empty custom window accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(1320, store.quietWindows().get(1).get(0).startMinute);
        assertFalse(store.allNumbers()); assertEquals(QuietHoursPolicy.Mode.NON_WORK, store.quietMode());
    }

    public void testWhitelistTogglePreservesProfileChangesDuringTheWrite() {
        long before = store.savePosition(0, "旧职位", 2, true), after = store.savePosition(0, "新职位", 1, true);
        store.saveCaller("31234", "旧部门", "旧姓名", before, false);
        try (SQLiteDatabase fixture = fixtureDatabase()) {
            fixture.execSQL("CREATE TRIGGER qa_edit_profile BEFORE UPDATE OF whitelist ON callers WHEN NEW.number='31234' BEGIN UPDATE callers SET name='新姓名',department='新部门',position_id=" + after + " WHERE number='31234'; END");
        }
        assertTrue(store.toggleWhitelist("3 (1234)"));
        CallerRepository.Caller profile = store.caller("31234");
        assertTrue(profile.whitelist); assertEquals("新姓名", profile.name); assertEquals("新部门", profile.department);
        assertEquals(Long.valueOf(after), profile.positionId);
    }

    public void testWhitelistToggleCreatesAnAbsentProfileWithoutOtherFields() {
        assertTrue(store.toggleWhitelist("3 (1234)"));
        assertEquals(1, store.callers().size());
        CallerRepository.Caller profile = store.caller("31234");
        assertEquals("", profile.name); assertEquals("", profile.department); assertNull(profile.positionId);
        assertFalse(store.toggleWhitelist("31234"));
    }

    public void testConcurrentWhitelistTogglesAcrossConnectionsDoNotLoseUpdates() throws Exception {
        store.saveCaller("31234", "部门", "姓名", null, false);
        CallerRepository other = new CallerRepository(getInstrumentation().getTargetContext(), database);
        try {
            CountDownLatch startTogether = new CountDownLatch(1), completed = new CountDownLatch(2);
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread first = toggleWorker(store, startTogether, completed, problem);
            Thread second = toggleWorker(other, startTogether, completed, problem);
            first.start(); second.start(); startTogether.countDown();
            assertTrue("concurrent toggles timed out", completed.await(15, TimeUnit.SECONDS));
            if (problem.get() != null) throw new AssertionError("concurrent toggle failed", problem.get());
            assertFalse(store.caller("31234").whitelist);
            assertEquals("姓名", store.caller("31234").name); assertEquals("部门", store.caller("31234").department);
        } finally { other.close(); }
    }

    private Thread toggleWorker(CallerRepository repository, CountDownLatch startTogether, CountDownLatch completed,
            AtomicReference<Throwable> problem) {
        return new Thread(() -> {
            try { startTogether.await(); for (int i = 0; i < 100; i++) repository.toggleWhitelist("31234"); }
            catch (Throwable error) { problem.compareAndSet(null, error); }
            finally { completed.countDown(); }
        });
    }
}
