package com.greatwall.callring;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared persistent storage; callers never have to hold an Activity or a process-local session. */
public final class CallerRepository {
    public static final class Position { public long id; public String name; public int level; public boolean active; }
    public static final class Caller { public String number, department, name; public Long positionId; public boolean whitelist; }
    public static final class CallRecord {
        public long id, time; public Long duration;
        public String number, result, decision, originalReason, reason, source, logId, account;
        public long configVersion, originalConfigVersion, leaveVersion, originalLeaveVersion;
        public String calendarVersion, originalCalendarVersion, zone, originalZone;
        public boolean working, originalWorking, verified, counted;
    }
    public static final class Report {
        public List<CallRecord> records; public CallerScore.Result score;
        public long knownDurationSeconds; public int unknownDurationCount, missed, rejected, pending;
    }
    private static CallerRepository instance;
    private final Database helper;
    private final SQLiteDatabase db;
    private boolean closed;
    public static synchronized CallerRepository get(Context context) {
        if (instance == null || instance.closed) instance = new CallerRepository(context, "callring.db");
        return instance;
    }
    CallerRepository(Context context, String database) {
        helper = new Database(context.getApplicationContext(), database);
        helper.setWriteAheadLoggingEnabled(true); db = helper.getWritableDatabase();
    }
    public synchronized void close() { helper.close(); closed = true; }

    public synchronized List<Position> positions(boolean includeInactive) {
        List<Position> result = new ArrayList<>();
        try (Cursor cursor = db.query("positions", null, includeInactive ? null : "active=1", null, null, null,
                "level ASC,name COLLATE LOCALIZED ASC,id ASC")) {
            while (cursor.moveToNext()) result.add(readPosition(cursor));
        }
        return result;
    }
    public synchronized Position position(long id) {
        try (Cursor cursor = db.query("positions", null, "id=?", args(id), null, null, null)) {
            return cursor.moveToFirst() ? readPosition(cursor) : null;
        }
    }
    public synchronized long savePosition(long id, String name, int level, boolean active) {
        String trimmed = clean(name);
        if (trimmed.isEmpty() || level <= 0) throw new IllegalArgumentException("职位名称不能为空，层级须为正整数");
        ContentValues values = new ContentValues(); values.put("name", trimmed);
        values.put("level", level); values.put("active", active ? 1 : 0);
        try {
            if (id == 0) return db.insertOrThrow("positions", null, values);
            if (db.update("positions", values, "id=?", args(id)) != 1) throw new IllegalArgumentException("职位不存在");
            return id;
        } catch (SQLiteConstraintException e) { throw new IllegalArgumentException("职位名称已存在", e); }
    }
    public synchronized void deletePosition(long id) {
        Long mine = myPositionId();
        if ((mine != null && mine == id) || exists("callers", "position_id=?", args(id)))
            throw new IllegalStateException("该职位仍被引用，可以停用或修改");
        try { db.delete("positions", "id=?", args(id)); }
        catch (SQLiteConstraintException e) { throw new IllegalStateException("该职位仍被引用", e); }
    }
    public synchronized void setMyPosition(Long id) {
        requirePosition(id); ContentValues values = new ContentValues(); values.put("key", "my_position");
        if (id == null) { values.putNull("value"); values.putNull("position_id"); }
        else { values.put("value", id.toString()); values.put("position_id", id); }
        if (db.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IllegalStateException("当前职位保存失败");
    }
    public synchronized Long myPositionId() {
        String raw = setting("my_position", null); return raw == null ? null : Long.valueOf(raw);
    }
    public synchronized String positionSummary(Caller caller) {
        Position theirs = caller == null || caller.positionId == null ? null : position(caller.positionId);
        Long mineId = myPositionId(); Position mine = mineId == null ? null : position(mineId);
        return (theirs == null ? "未设置职位" : theirs.name) + " "
                + PositionRelationPolicy.compare(theirs == null ? null : theirs.level, mine == null ? null : mine.level);
    }
    public synchronized Caller caller(String number) {
        String normalized = validNumber(number);
        try (Cursor cursor = db.query("callers", null, "number=?", args(normalized), null, null, null)) {
            if (cursor.moveToFirst()) return readCaller(cursor);
        }
        Caller value = new Caller(); value.number = normalized; value.department = ""; value.name = ""; return value;
    }
    public synchronized List<Caller> callers() {
        List<Caller> result = new ArrayList<>();
        try (Cursor cursor = db.query("callers", null, null, null, null, null, "number ASC")) {
            while (cursor.moveToNext()) result.add(readCaller(cursor));
        }
        return result;
    }
    public synchronized void saveCaller(String number, String department, String name, Long position, boolean whitelist) {
        String normalized = validNumber(number); requirePosition(position);
        ContentValues values = new ContentValues(); values.put("number", normalized); values.put("department", clean(department));
        values.put("name", clean(name)); if (position == null) values.putNull("position_id"); else values.put("position_id", position);
        values.put("whitelist", whitelist ? 1 : 0);
        // REPLACE would first delete the profile referenced by saved calls.
        if (db.update("callers", values, "number=?", args(normalized)) == 0) db.insertOrThrow("callers", null, values);
    }
    public synchronized boolean toggleWhitelist(String number) {
        String normalized = validNumber(number);
        db.beginTransaction();
        try {
            ensureCaller(normalized);
            try (SQLiteStatement statement = db.compileStatement("UPDATE callers SET whitelist=1-whitelist WHERE number=?")) {
                statement.bindString(1, normalized);
                if (statement.executeUpdateDelete() != 1) throw new IllegalStateException("白名单保存失败");
            }
            boolean enabled = scalar("SELECT whitelist FROM callers WHERE number=?", args(normalized)) == 1;
            db.setTransactionSuccessful(); return enabled;
        } finally { db.endTransaction(); }
    }
    public synchronized boolean statisticsEnabled() { return exists("statistics_intervals", "end IS NULL", null); }
    public synchronized void setStatisticsEnabled(boolean enabled) { setStatisticsEnabled(enabled, System.currentTimeMillis()); }
    public synchronized void setStatisticsEnabled(boolean enabled, long at) {
        if (enabled == statisticsEnabled()) return;
        db.beginTransaction();
        try {
            if (enabled) {
                long lastEnd = scalar("SELECT COALESCE(MAX(end),0) FROM statistics_intervals", null);
                if (at < lastEnd) throw new IllegalArgumentException("统计启用时间早于上一生效区间");
                ContentValues values = new ContentValues(); values.put("start", at); db.insertOrThrow("statistics_intervals", null, values);
            } else {
                long start = scalar("SELECT start FROM statistics_intervals WHERE end IS NULL", null);
                if (at < start) throw new IllegalArgumentException("统计暂停时间早于启用时间");
                ContentValues values = new ContentValues(); values.put("end", at); db.update("statistics_intervals", values, "end IS NULL", null);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized boolean inStatisticsInterval(long at) {
        return exists("statistics_intervals", "start<=? AND (end IS NULL OR end>?)", args(at, at));
    }
    public synchronized long statisticsStart() { return scalar("SELECT COALESCE(MIN(start),0) FROM statistics_intervals", null); }
    public synchronized boolean overlayEnabled() { return "1".equals(setting("overlay", "0")); }
    public synchronized void setOverlayEnabled(boolean enabled) { putSetting("overlay", enabled ? "1" : "0"); }
    public synchronized boolean allNumbers() { return "1".equals(setting("all_numbers", "0")); }
    public synchronized void setAllNumbers(boolean enabled) { putSetting("all_numbers", enabled ? "1" : "0"); }
    public synchronized QuietHoursPolicy.Mode quietMode() {
        return QuietHoursPolicy.Mode.valueOf(setting("quiet_mode", QuietHoursPolicy.Mode.OFF.name()));
    }
    public synchronized void setQuietMode(QuietHoursPolicy.Mode mode) {
        if (mode == null) throw new IllegalArgumentException("免打扰模式不能为空");
        db.beginTransaction();
        try {
            putSetting("quiet_mode", mode.name());
            if (mode != QuietHoursPolicy.Mode.OFF) putSetting("last_quiet_mode", mode.name());
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void toggleQuiet() {
        setQuietMode(quietMode() == QuietHoursPolicy.Mode.OFF
                ? QuietHoursPolicy.Mode.valueOf(setting("last_quiet_mode", QuietHoursPolicy.Mode.NON_WORK.name())) : QuietHoursPolicy.Mode.OFF);
    }
    public synchronized int[] scoreWeights() {
        return new int[] {Integer.parseInt(setting("off_weight", "5")), Integer.parseInt(setting("repeat_weight", "2")),
                Integer.parseInt(setting("repeat_minutes", "10")), Integer.parseInt(setting("score_days", "30"))};
    }
    public synchronized void saveScoreWeights(int offWeight, int repeatWeight, int repeatMinutes, int days) {
        if (offWeight < 0 || repeatWeight < 0 || repeatMinutes <= 0 || days <= 0)
            throw new IllegalArgumentException("权重须非负，重复分钟和观察天数须为正整数");
        db.beginTransaction();
        try {
            putSetting("off_weight", Integer.toString(offWeight)); putSetting("repeat_weight", Integer.toString(repeatWeight));
            putSetting("repeat_minutes", Integer.toString(repeatMinutes)); putSetting("score_days", Integer.toString(days)); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized Map<Integer,List<WorkSchedulePolicy.Window>> quietWindows() {
        try { return WorkConfigCodec.weekly(new JSONObject(setting("quiet_windows", "{}"))); }
        catch (Exception e) { throw new IllegalStateException("静音时段数据损坏", e); }
    }
    public synchronized void saveQuietWindows(Map<Integer,List<WorkSchedulePolicy.Window>> windows) {
        validateWeekly(windows);
        try { putSetting("quiet_windows", WorkConfigCodec.weeklyJson(windows).toString()); }
        catch (Exception e) { throw new IllegalArgumentException("静音时段保存失败", e); }
    }
    public synchronized void saveQuietSettings(QuietHoursPolicy.Mode mode, boolean allNumbers,
            Map<Integer,List<WorkSchedulePolicy.Window>> windows) {
        if (mode == null) throw new IllegalArgumentException("免打扰模式不能为空");
        validateWeekly(windows);
        boolean hasWindow = false;
        for (List<WorkSchedulePolicy.Window> day : windows.values()) if (!day.isEmpty()) { hasWindow = true; break; }
        if (mode == QuietHoursPolicy.Mode.CUSTOM && !hasWindow) throw new IllegalArgumentException("自定义免打扰至少需要一个静音时段");
        db.beginTransaction();
        try {
            saveQuietWindows(windows); setAllNumbers(allNumbers); setQuietMode(mode);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized WorkSchedulePolicy.Config workConfig() {
        try (Cursor cursor = db.rawQuery("SELECT snapshot FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("工作配置缺失");
            WorkSchedulePolicy.Config config = WorkConfigCodec.decode(cursor.getString(0)); hydrate(config); return config;
        }
    }
    public synchronized WorkSchedulePolicy.Config getConfigAt(long at) { return scheduleState().at(at).config; }
    public synchronized void saveWorkConfig(WorkSchedulePolicy.Config config) {
        if (config == null || config.zone == null || config.makeup == null) throw new IllegalArgumentException("工作配置不完整");
        validateWeekly(config.weekly); validateWindows(config.makeup);
        String snapshot = WorkConfigCodec.encode(config); WorkConfigCodec.decode(snapshot);
        ContentValues values = new ContentValues(); values.put("effective", Math.max(System.currentTimeMillis(),
                scalar("SELECT MAX(effective) FROM config_versions", null) + 1)); values.put("snapshot", snapshot);
        db.insertOrThrow("config_versions", null, values);
    }
    public synchronized String calendarSummary() {
        List<String> summaries = new ArrayList<>();
        try (Cursor cursor = db.query("calendar_years", null, null, null, null, null, "year ASC")) {
            while (cursor.moveToNext()) summaries.add(integer(cursor, "year") + " · " + string(cursor, "version")
                    + " · " + string(cursor, "published") + "\n" + string(cursor, "source"));
        }
        return summaries.isEmpty() ? "未配置年度节假日数据" : String.join("\n\n", summaries);
    }
    public synchronized void importCalendar(String raw) {
        WorkConfigCodec.CalendarData data = WorkConfigCodec.calendar(raw);
        db.beginTransaction();
        try { replaceCalendar(db, data, raw); reclassifyInternal(); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    public synchronized void saveOverride(LocalDate date, List<WorkSchedulePolicy.Window> windows) {
        if (date == null || windows == null) throw new IllegalArgumentException("日期和时段不能为空");
        validateWindows(windows); String raw = WorkConfigCodec.windowsJson(windows).toString();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("date", date.toString()); values.put("windows", raw);
            db.insertWithOnConflict("overrides", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            audit("override", date.toString(), raw); reclassifyInternal(); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void deleteOverride(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("日期不能为空");
        db.beginTransaction();
        try {
            db.delete("overrides", "date=?", args(date)); audit("override-delete", date.toString(), "");
            reclassifyInternal(); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized List<WorkSchedulePolicy.Leave> leaves() {
        return readLeaves(db);
    }
    private static List<WorkSchedulePolicy.Leave> readLeaves(SQLiteDatabase db) {
        List<WorkSchedulePolicy.Leave> result = new ArrayList<>();
        try (Cursor cursor = db.query("leaves", null, null, null, null, null, "start DESC,id DESC")) {
            while (cursor.moveToNext()) result.add(new WorkSchedulePolicy.Leave(lng(cursor, "id"), lng(cursor, "start"), lng(cursor, "end"), string(cursor, "note")));
        }
        return result;
    }
    public synchronized long saveLeave(long id, long start, long end, String note) {
        if (end <= start) throw new IllegalArgumentException("请假结束时间须晚于开始时间");
        ContentValues values = new ContentValues(); values.put("start", start); values.put("end", end); values.put("note", clean(note));
        db.beginTransaction();
        try {
            long saved = id;
            if (id == 0) saved = db.insertOrThrow("leaves", null, values);
            else if (db.update("leaves", values, "id=?", args(id)) != 1) throw new IllegalArgumentException("请假记录不存在");
            nextLeaveRevision(); audit("leave", Long.toString(saved), start + ":" + end + ":" + clean(note));
            reclassifyInternal(); db.setTransactionSuccessful(); return saved;
        } finally { db.endTransaction(); }
    }
    public synchronized void deleteLeave(long id) {
        db.beginTransaction();
        try {
            if (db.delete("leaves", "id=?", args(id)) > 0) {
                nextLeaveRevision(); audit("leave-delete", Long.toString(id), ""); reclassifyInternal();
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void reclassify() {
        db.beginTransaction();
        try { reclassifyInternal(); db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    private void reclassifyInternal() {
        ScheduleState schedules = scheduleState(); List<long[]> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id,time FROM calls", null)) {
            while (cursor.moveToNext()) rows.add(new long[] {cursor.getLong(0), cursor.getLong(1)});
        }
        for (long[] row : rows) db.update("calls", classification(schedules, row[1], false), "id=?", args(row[0]));
    }

    public synchronized long recordIncoming(String number, long at, String decision) {
        return recordIncoming(number, at, decision, null);
    }
    public synchronized long recordIncoming(String number, long at, String decision, String account) {
        String normalized = NumberNormalizer.normalize(number);
        if (!RulePolicy.matches(normalized) || !inStatisticsInterval(at)) return -1;
        String knownAccount = optionalAccount(account);
        String key = normalized + ":" + at + (knownAccount == null ? "" : ":" + knownAccount);
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT id FROM calls WHERE screening_key=?", args(key))) {
                if (cursor.moveToFirst()) { db.setTransactionSuccessful(); return cursor.getLong(0); }
            }
            ensureCaller(normalized); ContentValues values = newCall(normalized, at, decision, "待补全", "筛选", null, scheduleState());
            values.put("screening_key", key); values.put("account", knownAccount);
            long id = db.insertOrThrow("calls", null, values); reconcileAround(db, normalized, at);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized void updateDecision(long id, String decision) {
        if (id <= 0) return;
        ContentValues values = new ContentValues(); values.put("decision", clean(decision)); db.update("calls", values, "id=?", args(id));
    }
    public synchronized void importCallLog(String logId, String number, long at, Long duration, String result) {
        importCallLog(logId, number, at, duration, result, null);
    }
    public synchronized void importCallLog(String logId, String number, long at, Long duration, String result, String account) {
        String normalized = NumberNormalizer.normalize(number);
        if (!RulePolicy.matches(normalized) || !inStatisticsInterval(at)) return;
        if (clean(logId).isEmpty()) throw new IllegalArgumentException("系统通话记录 ID 不能为空");
        if (duration != null && duration < 0) throw new IllegalArgumentException("通话时长不可为负");
        db.beginTransaction();
        try {
            Long existingId = null, previousDuration = null; long previousTime = at;
            String previousNumber = null, previousAccount = null, previousResult = null;
            try (Cursor cursor = db.rawQuery("SELECT id,number,account,time,duration,result FROM calls WHERE log_id=?", args(logId))) {
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(0); previousNumber = cursor.getString(1); previousAccount = cursor.getString(2);
                    previousTime = cursor.getLong(3); previousDuration = cursor.isNull(4) ? null : cursor.getLong(4);
                    previousResult = cursor.getString(5);
                }
            }
            String knownAccount = optionalAccount(account), finalAccount = knownAccount == null ? previousAccount : knownAccount;
            String finalResult = clean(result);
            boolean changedIdentity = existingId == null || previousTime != at || !Objects.equals(previousNumber, normalized)
                    || !Objects.equals(previousAccount, finalAccount);
            if (!changedIdentity && Objects.equals(previousDuration, duration) && Objects.equals(previousResult, finalResult)) {
                db.setTransactionSuccessful(); return;
            }
            ContentValues values;
            if (existingId == null) {
                ensureCaller(normalized);
                values = newCall(normalized, at, "系统记录补全", finalResult, "系统通话记录", logId, scheduleState());
            } else {
                values = previousTime != at || !Objects.equals(previousNumber, normalized)
                        ? classification(scheduleState(), at, false) : new ContentValues();
                if (!Objects.equals(previousNumber, normalized)) ensureCaller(normalized);
            }
            values.put("number", normalized); values.put("time", at); values.put("result", clean(result));
            if (changedIdentity) values.putNull("linked_screen_id");
            if (duration == null) values.putNull("duration"); else values.put("duration", duration);
            values.put("account", finalAccount);
            if (existingId == null) db.insertOrThrow("calls", null, values);
            else db.update("calls", values, "id=?", args(existingId));
            if (changedIdentity) {
                if (previousNumber != null) reconcileAround(db, previousNumber, previousTime);
                if (previousNumber == null || previousTime != at || !previousNumber.equals(normalized)) reconcileAround(db, normalized, at);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** A pair is associated only when each source has exactly one compatible candidate. */
    private static void reconcile(SQLiteDatabase db, String number) {
        reconcileRows(db, number, "number=?", args(number));
    }
    private static void reconcileAround(SQLiteDatabase db, String number, long at) {
        // Changing a source affects its direct neighbors and the neighbors on the opposite side.
        // Candidate degrees below always query the complete indexed range, beyond this write set.
        reconcileRows(db, number, "number=? AND time>=? AND time<=?", args(number, lower(at, 10000), upper(at, 10000)));
    }
    private static void reconcileRows(SQLiteDatabase db, String number, String where, String[] parameters) {
        List<CorrelationRow> affected = new ArrayList<>();
        try (Cursor cursor = db.query("calls", CORRELATION_COLUMNS, where, parameters, null, null, "time ASC")) {
            while (cursor.moveToNext()) affected.add(readCorrelation(cursor));
        }
        Map<Long,List<CorrelationRow>> cache = new HashMap<>();
        Map<Long,Long> changedLinks = new HashMap<>(); Map<Long,ContentValues> changedStatus = new HashMap<>();
        for (CorrelationRow row : affected) {
            List<CorrelationRow> candidates = candidates(db, number, row, cache);
            boolean associated = candidates.size() == 1 && candidates(db, number, candidates.get(0), cache).size() == 1;
            if (!row.screen) {
                Long link = associated ? candidates.get(0).id : null;
                if (!Objects.equals(row.link, link)) changedLinks.put(row.id, link);
            } else {
                int hidden = associated ? 1 : 0, counted = candidates.isEmpty() ? 1 : 0;
                String result = candidates.isEmpty() || associated ? "待补全" : "待核对·未计入";
                if (row.hidden != hidden || row.counted != counted || !result.equals(row.result)) {
                    ContentValues values = new ContentValues(); values.put("hidden", hidden); values.put("counted", counted);
                    values.put("result", result); changedStatus.put(row.id, values);
                }
            }
        }
        // Clear changed old links before assigning any new ones to preserve the UNIQUE reference.
        for (Long id : changedLinks.keySet()) {
            ContentValues clear = new ContentValues(); clear.putNull("linked_screen_id");
            db.update("calls", clear, "id=? AND linked_screen_id IS NOT NULL", args(id));
        }
        for (Map.Entry<Long,Long> change : changedLinks.entrySet()) if (change.getValue() != null) {
            ContentValues values = new ContentValues(); values.put("linked_screen_id", change.getValue());
            db.update("calls", values, "id=?", args(change.getKey()));
        }
        for (Map.Entry<Long,ContentValues> change : changedStatus.entrySet()) db.update("calls", change.getValue(), "id=?", args(change.getKey()));
    }
    private static final String[] CORRELATION_COLUMNS = {"id", "time", "account", "log_id", "linked_screen_id", "hidden", "counted", "result"};
    private static List<CorrelationRow> candidates(SQLiteDatabase db, String number, CorrelationRow row,
            Map<Long,List<CorrelationRow>> cache) {
        if (cache.containsKey(row.id)) return cache.get(row.id);
        String where = "number=? AND time>=? AND time<=? AND log_id IS " + (row.screen ? "NOT NULL" : "NULL");
        String[] values = args(number, lower(row.time, 5000), upper(row.time, 5000));
        if (row.account != null) {
            where += " AND (account IS NULL OR account=?)";
            values = args(number, lower(row.time, 5000), upper(row.time, 5000), row.account);
        }
        List<CorrelationRow> result = new ArrayList<>();
        // Two candidates are sufficient to establish ambiguity, including outside the write set.
        try (Cursor cursor = db.query("calls", CORRELATION_COLUMNS, where, values, null, null, null, "2")) {
            while (cursor.moveToNext()) result.add(readCorrelation(cursor));
        }
        cache.put(row.id, result); return result;
    }
    private static long lower(long at, long amount) { return at < Long.MIN_VALUE + amount ? Long.MIN_VALUE : at - amount; }
    private static long upper(long at, long amount) { return at > Long.MAX_VALUE - amount ? Long.MAX_VALUE : at + amount; }
    private static CorrelationRow readCorrelation(Cursor cursor) {
        return new CorrelationRow(lng(cursor, "id"), lng(cursor, "time"), string(cursor, "account"), string(cursor, "log_id") == null,
                nullableLong(cursor, "linked_screen_id"), integer(cursor, "hidden"), integer(cursor, "counted"), string(cursor, "result"));
    }
    private static final class CorrelationRow {
        final long id, time; final String account, result; final boolean screen; final Long link; final int hidden, counted;
        CorrelationRow(long id, long time, String account, boolean screen, Long link, int hidden, int counted, String result) {
            this.id = id; this.time = time; this.account = account; this.screen = screen; this.link = link;
            this.hidden = hidden; this.counted = counted; this.result = result;
        }
    }
    public synchronized List<CallRecord> calls(String number, long start, long end) {
        if (end < start) throw new IllegalArgumentException("查询结束时间早于开始时间");
        List<CallRecord> result = new ArrayList<>(); String selection = "c.hidden=0 AND c.time>=? AND c.time<?"; String[] values = args(start, end);
        if (number != null) { selection += " AND c.number=?"; values = args(start, end, validNumber(number)); }
        String sql = CALL_SELECT + " WHERE " + selection + " ORDER BY c.time DESC,c.id DESC";
        try (Cursor cursor = db.rawQuery(sql, values)) {
            while (cursor.moveToNext()) result.add(readCall(cursor));
        }
        return result;
    }
    private static final String CALL_SELECT = "SELECT c.*,s.decision AS screen_decision,s.original_working AS screen_original_working,"
            + "s.original_reason AS screen_original_reason,s.account AS screen_account,"
            + "s.original_config_version AS screen_original_config_version,s.original_calendar_version AS screen_original_calendar_version,"
            + "s.original_leave_version AS screen_original_leave_version,s.original_zone AS screen_original_zone FROM calls c "
            + "LEFT JOIN calls s ON c.linked_screen_id=s.id";
    /** Returns the newest visible detail, with null selecting across all callers. */
    public synchronized CallRecord latestCall(String number) {
        String where = "c.hidden=0"; String[] values = null;
        if (number != null) { where += " AND c.number=?"; values = args(validNumber(number)); }
        try (Cursor cursor = db.rawQuery(CALL_SELECT + " WHERE " + where + " ORDER BY c.time DESC,c.id DESC LIMIT 1", values)) {
            return cursor.moveToFirst() ? readCall(cursor) : null;
        }
    }
    public synchronized Report report(String number, long start, long end) {
        Report report = new Report(); report.records = calls(number, start, end); List<CallerScore.Entry> entries = new ArrayList<>();
        for (CallRecord call : report.records) {
            if (call.logId == null || call.result.startsWith("待")) report.pending++;
            if (!call.counted) continue;
            entries.add(new CallerScore.Entry(call.time, call.working));
            if (call.duration == null) report.unknownDurationCount++; else report.knownDurationSeconds = saturatedAdd(report.knownDurationSeconds, call.duration);
            if (call.result.contains("未接")) report.missed++;
            if (call.result.contains("拒接") || call.result.contains("拦截")) report.rejected++;
        }
        int[] weights = scoreWeights(); report.score = CallerScore.calculate(entries, start, end, weights[0], weights[1], weights[2]); return report;
    }
    public synchronized Report recentReport(String number) {
        long end = System.currentTimeMillis() + 1; return report(number, end - scoreWeights()[3] * 86400000L, end);
    }
    public static String readLimited(InputStream stream) {
        if (stream == null) throw new IllegalArgumentException("未读取到日历文件");
        final int limit = 1024 * 1024;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int length;
            while ((length = stream.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()))) != -1) {
                if (length == 0) continue;
                output.write(buffer, 0, length); if (output.size() > limit) throw new IllegalArgumentException("日历文件不得超过 1 MB");
            }
            String raw = new String(output.toByteArray(), StandardCharsets.UTF_8); return raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        } catch (IOException e) { throw new IllegalArgumentException("日历文件读取失败", e); }
    }

    private ContentValues newCall(String number, long at, String decision, String result, String source, String logId, ScheduleState schedules) {
        ContentValues values = classification(schedules, at, true); values.put("number", number); values.put("time", at);
        values.put("decision", clean(decision)); values.put("result", result); values.put("source", source); values.put("counted", 1);
        if (logId != null) values.put("log_id", logId); return values;
    }
    private static ContentValues classification(ScheduleState schedules, long at, boolean original) {
        Version version = schedules.at(at); WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(version.config, at);
        int year = java.time.Instant.ofEpochMilli(at).atZone(version.config.zone).getYear(); String calendar = schedules.calendarVersions.get(year);
        ContentValues values = new ContentValues(); values.put("working", result.working ? 1 : 0); values.put("verified", result.verified ? 1 : 0);
        values.put("reason", result.reason); values.put("config_version", version.id); values.put("calendar_version", calendar == null ? "未核验" : calendar);
        values.put("leave_version", schedules.leaveRevision); values.put("zone", version.config.zone.getId());
        if (original) {
            values.put("original_working", result.working ? 1 : 0); values.put("original_verified", result.verified ? 1 : 0);
            values.put("original_reason", result.reason); values.put("original_config_version", version.id);
            values.put("original_calendar_version", calendar == null ? "未核验" : calendar);
            values.put("original_leave_version", schedules.leaveRevision); values.put("original_zone", version.config.zone.getId());
        }
        return values;
    }
    private ScheduleState scheduleState() { return scheduleState(db); }
    private static ScheduleState scheduleState(SQLiteDatabase db) {
        ScheduleState state = new ScheduleState(); WorkSchedulePolicy.Config supplements = new WorkSchedulePolicy.Config(); hydrate(db, supplements);
        try (Cursor cursor = db.rawQuery("SELECT id,effective,snapshot FROM config_versions ORDER BY effective ASC,id ASC", null)) {
            while (cursor.moveToNext()) {
                WorkSchedulePolicy.Config config = WorkConfigCodec.decode(cursor.getString(2)); config.overrides = supplements.overrides;
                config.holidays = supplements.holidays; config.makeupDays = supplements.makeupDays;
                config.coveredYears = supplements.coveredYears; config.leaves = supplements.leaves;
                state.versions.add(new Version(cursor.getLong(0), cursor.getLong(1), config));
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT year,version FROM calendar_years", null)) {
            while (cursor.moveToNext()) state.calendarVersions.put(cursor.getInt(0), cursor.getString(1));
        }
        state.leaveRevision = Long.parseLong(setting(db, "leave_revision", "0")); return state;
    }
    private void hydrate(WorkSchedulePolicy.Config config) { hydrate(db, config); }
    private static void hydrate(SQLiteDatabase db, WorkSchedulePolicy.Config config) {
        try (Cursor cursor = db.query("calendar_years", new String[] {"year"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) config.coveredYears.add(cursor.getInt(0));
        }
        try (Cursor cursor = db.query("calendar_days", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                LocalDate date = LocalDate.parse(string(cursor, "date"));
                if (integer(cursor, "working") == 1) config.makeupDays.add(date); else config.holidays.put(date, string(cursor, "name"));
            }
        }
        try (Cursor cursor = db.query("overrides", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                try { config.overrides.put(LocalDate.parse(string(cursor, "date")), WorkConfigCodec.windows(new JSONArray(string(cursor, "windows")))); }
                catch (Exception e) { throw new IllegalStateException("日期调整数据损坏", e); }
            }
        }
        config.leaves = readLeaves(db);
    }
    private static final class Version {
        final long id, effective; final WorkSchedulePolicy.Config config;
        Version(long id, long effective, WorkSchedulePolicy.Config config) { this.id = id; this.effective = effective; this.config = config; }
    }
    private static final class ScheduleState {
        final List<Version> versions = new ArrayList<>(); final Map<Integer,String> calendarVersions = new HashMap<>(); long leaveRevision;
        Version at(long at) {
            if (versions.isEmpty()) throw new IllegalStateException("工作配置缺失");
            Version result = versions.get(0);
            for (Version version : versions) { if (version.effective > at) break; result = version; } return result;
        }
    }
    private void nextLeaveRevision() { putSetting("leave_revision", Long.toString(Long.parseLong(setting("leave_revision", "0")) + 1)); }
    private void audit(String kind, String target, String raw) {
        ContentValues values = new ContentValues(); values.put("kind", kind); values.put("target", target); values.put("raw", raw);
        values.put("created", System.currentTimeMillis()); db.insertOrThrow("calendar_audit", null, values);
    }
    private void requirePosition(Long id) { if (id != null && position(id) == null) throw new IllegalArgumentException("职位不存在"); }
    private void ensureCaller(String number) {
        ContentValues values = new ContentValues(); values.put("number", number); db.insertWithOnConflict("callers", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
    private String setting(String key, String fallback) {
        return setting(db, key, fallback);
    }
    private static String setting(SQLiteDatabase db, String key, String fallback) {
        try (Cursor cursor = db.query("settings", new String[] {"value"}, "key=?", args(key), null, null, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : fallback;
        }
    }
    private void putSetting(String key, String value) {
        ContentValues values = new ContentValues(); values.put("key", key); values.put("value", value);
        if (db.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IllegalStateException("设置保存失败");
    }
    private boolean exists(String table, String where, String[] values) {
        try (Cursor cursor = db.query(table, new String[] {"1"}, where, values, null, null, null, "1")) { return cursor.moveToFirst(); }
    }
    private long scalar(String sql, String[] values) {
        try (Cursor cursor = db.rawQuery(sql, values)) { return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0; }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String optionalAccount(String account) { String value = clean(account); return value.isEmpty() ? null : value; }
    private static String validNumber(String number) {
        String normalized = NumberNormalizer.normalize(number);
        if (!normalized.matches("\\+?[0-9]{1,30}")) throw new IllegalArgumentException("号码须为可带 + 的 1–30 位数字"); return normalized;
    }
    private static String[] args(Object... values) {
        String[] result = new String[values.length]; for (int i = 0; i < values.length; i++) result[i] = values[i].toString(); return result;
    }
    private static void validateWeekly(Map<Integer,List<WorkSchedulePolicy.Window>> weekly) {
        if (weekly == null) throw new IllegalArgumentException("每周时段不能为空");
        for (Map.Entry<Integer,List<WorkSchedulePolicy.Window>> entry : weekly.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1 || entry.getKey() > 7) throw new IllegalArgumentException("星期须为 1–7");
            validateWindows(entry.getValue());
        }
    }
    private static void validateWindows(List<WorkSchedulePolicy.Window> windows) {
        if (windows == null) throw new IllegalArgumentException("时段不能为空");
        for (WorkSchedulePolicy.Window window : windows) if (window == null) throw new IllegalArgumentException("时段不能为空");
    }
    private static long saturatedAdd(long left, long right) { return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right; }
    private static String string(Cursor cursor, String column) { return cursor.getString(cursor.getColumnIndexOrThrow(column)); }
    private static long lng(Cursor cursor, String column) { return cursor.getLong(cursor.getColumnIndexOrThrow(column)); }
    private static int integer(Cursor cursor, String column) { return cursor.getInt(cursor.getColumnIndexOrThrow(column)); }
    private static Long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column); return cursor.isNull(index) ? null : cursor.getLong(index);
    }
    private static Position readPosition(Cursor cursor) {
        Position value = new Position(); value.id = lng(cursor, "id"); value.name = string(cursor, "name");
        value.level = integer(cursor, "level"); value.active = integer(cursor, "active") == 1; return value;
    }
    private static Caller readCaller(Cursor cursor) {
        Caller value = new Caller(); value.number = string(cursor, "number"); value.department = string(cursor, "department"); value.name = string(cursor, "name");
        value.positionId = nullableLong(cursor, "position_id"); value.whitelist = integer(cursor, "whitelist") == 1; return value;
    }
    private static CallRecord readCall(Cursor cursor) {
        CallRecord value = new CallRecord(); value.id = lng(cursor, "id"); value.time = lng(cursor, "time"); value.number = string(cursor, "number");
        value.duration = nullableLong(cursor, "duration"); value.result = string(cursor, "result"); value.decision = string(cursor, "decision");
        value.originalReason = string(cursor, "original_reason"); value.reason = string(cursor, "reason"); value.source = string(cursor, "source");
        value.logId = string(cursor, "log_id"); value.account = string(cursor, "account");
        value.configVersion = lng(cursor, "config_version"); value.originalConfigVersion = lng(cursor, "original_config_version");
        value.calendarVersion = string(cursor, "calendar_version"); value.originalCalendarVersion = string(cursor, "original_calendar_version");
        value.leaveVersion = lng(cursor, "leave_version"); value.originalLeaveVersion = lng(cursor, "original_leave_version");
        value.zone = string(cursor, "zone"); value.originalZone = string(cursor, "original_zone");
        value.working = integer(cursor, "working") == 1; value.originalWorking = integer(cursor, "original_working") == 1;
        value.verified = integer(cursor, "verified") == 1; value.counted = integer(cursor, "counted") == 1;
        if (nullableLong(cursor, "linked_screen_id") != null) {
            value.decision = string(cursor, "screen_decision"); value.originalWorking = integer(cursor, "screen_original_working") == 1;
            value.originalReason = string(cursor, "screen_original_reason"); value.source = "筛选＋系统通话记录";
            value.originalConfigVersion = lng(cursor, "screen_original_config_version");
            value.originalCalendarVersion = string(cursor, "screen_original_calendar_version");
            value.originalLeaveVersion = lng(cursor, "screen_original_leave_version");
            value.originalZone = string(cursor, "screen_original_zone");
            if (value.account == null) value.account = string(cursor, "screen_account");
        }
        return value;
    }
    private static void replaceCalendar(SQLiteDatabase db, WorkConfigCodec.CalendarData data, String raw) {
        db.delete("calendar_days", "year=?", args(data.year)); ContentValues year = new ContentValues(); year.put("year", data.year);
        year.put("source", data.source); year.put("published", data.published); year.put("version", data.version);
        if (db.update("calendar_years", year, "year=?", args(data.year)) == 0) db.insertOrThrow("calendar_years", null, year);
        for (Map.Entry<LocalDate,String> entry : data.holidays.entrySet()) {
            ContentValues day = new ContentValues(); day.put("date", entry.getKey().toString()); day.put("year", data.year);
            day.put("working", 0); day.put("name", entry.getValue()); db.insertOrThrow("calendar_days", null, day);
        }
        for (LocalDate date : data.workdays) {
            ContentValues day = new ContentValues(); day.put("date", date.toString()); day.put("year", data.year);
            day.put("working", 1); day.put("name", "调休上班"); db.insertOrThrow("calendar_days", null, day);
        }
        ContentValues audit = new ContentValues(); audit.put("kind", "calendar"); audit.put("target", Integer.toString(data.year));
        audit.put("raw", raw); audit.put("created", System.currentTimeMillis()); db.insertOrThrow("calendar_audit", null, audit);
    }
    private static final class Database extends SQLiteOpenHelper {
        private final Context context;
        Database(Context context, String name) { super(context, name, null, 3); this.context = context; }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE positions(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,level INTEGER NOT NULL CHECK(level>0),active INTEGER NOT NULL CHECK(active IN(0,1)))");
            db.execSQL("CREATE TABLE callers(number TEXT PRIMARY KEY,department TEXT NOT NULL DEFAULT '',name TEXT NOT NULL DEFAULT '',position_id INTEGER REFERENCES positions(id) ON DELETE RESTRICT,whitelist INTEGER NOT NULL DEFAULT 0 CHECK(whitelist IN(0,1)))");
            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT,position_id INTEGER REFERENCES positions(id) ON DELETE RESTRICT)");
            db.execSQL("CREATE TABLE config_versions(id INTEGER PRIMARY KEY AUTOINCREMENT,effective INTEGER NOT NULL UNIQUE,snapshot TEXT NOT NULL)");
            db.execSQL("CREATE TABLE statistics_intervals(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER,CHECK(end IS NULL OR end>=start))");
            db.execSQL("CREATE UNIQUE INDEX statistics_one_open ON statistics_intervals((1)) WHERE end IS NULL");
            db.execSQL("CREATE INDEX statistics_range ON statistics_intervals(start,end)");
            db.execSQL("CREATE TABLE calendar_years(year INTEGER PRIMARY KEY,source TEXT NOT NULL,published TEXT NOT NULL,version TEXT NOT NULL)");
            db.execSQL("CREATE TABLE calendar_days(date TEXT PRIMARY KEY,year INTEGER NOT NULL REFERENCES calendar_years(year) ON DELETE RESTRICT,working INTEGER NOT NULL CHECK(working IN(0,1)),name TEXT NOT NULL)");
            db.execSQL("CREATE INDEX calendar_year ON calendar_days(year)");
            db.execSQL("CREATE TABLE overrides(date TEXT PRIMARY KEY,windows TEXT NOT NULL)");
            db.execSQL("CREATE TABLE leaves(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL CHECK(end>start),note TEXT NOT NULL)");
            db.execSQL("CREATE INDEX leaves_range ON leaves(start,end)");
            db.execSQL("CREATE TABLE calendar_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,target TEXT NOT NULL,raw TEXT NOT NULL,created INTEGER NOT NULL)");
            db.execSQL("CREATE TRIGGER calendar_audit_no_update BEFORE UPDATE ON calendar_audit BEGIN SELECT RAISE(ABORT,'Audit is immutable'); END");
            db.execSQL("CREATE TRIGGER calendar_audit_no_delete BEFORE DELETE ON calendar_audit BEGIN SELECT RAISE(ABORT,'Audit is immutable'); END");
            db.execSQL("CREATE TABLE calls(id INTEGER PRIMARY KEY AUTOINCREMENT,number TEXT NOT NULL REFERENCES callers(number) ON DELETE RESTRICT,time INTEGER NOT NULL,duration INTEGER CHECK(duration IS NULL OR duration>=0),result TEXT NOT NULL,decision TEXT NOT NULL,source TEXT NOT NULL,log_id TEXT UNIQUE,screening_key TEXT UNIQUE,counted INTEGER NOT NULL CHECK(counted IN(0,1)),working INTEGER NOT NULL,verified INTEGER NOT NULL,reason TEXT NOT NULL,original_working INTEGER NOT NULL,original_verified INTEGER NOT NULL,original_reason TEXT NOT NULL,config_version INTEGER NOT NULL REFERENCES config_versions(id),original_config_version INTEGER NOT NULL REFERENCES config_versions(id),calendar_version TEXT NOT NULL,original_calendar_version TEXT NOT NULL,leave_version INTEGER NOT NULL,original_leave_version INTEGER NOT NULL,zone TEXT NOT NULL,original_zone TEXT NOT NULL,account TEXT,hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN(0,1)),linked_screen_id INTEGER REFERENCES calls(id) ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX calls_number_time ON calls(number,time)");
            db.execSQL("CREATE INDEX calls_visible_time ON calls(time DESC,id DESC) WHERE hidden=0");
            db.execSQL("CREATE INDEX calls_pending ON calls(number,time) WHERE log_id IS NULL");
            db.execSQL("CREATE UNIQUE INDEX calls_screen_link ON calls(linked_screen_id) WHERE linked_screen_id IS NOT NULL");
            ContentValues config = new ContentValues(); config.put("effective", 0); config.put("snapshot", WorkConfigCodec.encode(new WorkSchedulePolicy.Config()));
            db.insertOrThrow("config_versions", null, config);
            try (InputStream stream = context.getAssets().open("holidays-2026.json")) {
                String raw = readLimited(stream); replaceCalendar(db, WorkConfigCodec.calendar(raw), raw);
            } catch (IOException e) { throw new IllegalStateException("内置年度日历读取失败", e); }
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 1 || oldVersion > 2 || newVersion != 3) throw new IllegalStateException("数据库版本需要明确迁移，不能删除已有数据");
            if (oldVersion == 1) migrateOneToTwo(db);
            // Repair v2 installations as well as newly split v1 events. Current facts follow the
            // restored screening timestamp; original screening decisions and facts stay intact.
            ScheduleState schedules = scheduleState(db); List<long[]> screens = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT id,time FROM calls WHERE log_id IS NULL", null)) {
                while (cursor.moveToNext()) screens.add(new long[] {cursor.getLong(0), cursor.getLong(1)});
            }
            for (long[] screen : screens) db.update("calls", classification(schedules, screen[1], false), "id=?", args(screen[0]));
            db.execSQL("CREATE INDEX IF NOT EXISTS calls_visible_time ON calls(time DESC,id DESC) WHERE hidden=0");
        }
        private static void migrateOneToTwo(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE calls ADD COLUMN account TEXT");
            db.execSQL("ALTER TABLE calls ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN(0,1))");
            db.execSQL("ALTER TABLE calls ADD COLUMN linked_screen_id INTEGER REFERENCES calls(id) ON DELETE RESTRICT");
            // Version 1 stored a uniquely merged provider in the original screening row. Split it
            // while retaining the incoming event ID, screening timestamp, and all original facts.
            List<ContentValues> merged = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT * FROM calls WHERE log_id IS NOT NULL AND screening_key IS NOT NULL", null)) {
                while (cursor.moveToNext()) {
                    ContentValues row = new ContentValues(); android.database.DatabaseUtils.cursorRowToContentValues(cursor, row); merged.add(row);
                }
            }
            for (ContentValues row : merged) {
                long screenId = row.getAsLong("id"); String key = row.getAsString("screening_key");
                long screenTime = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
                ContentValues provider = new ContentValues(row); provider.remove("id"); provider.putNull("screening_key");
                provider.put("decision", "系统记录补全"); provider.put("source", "系统通话记录"); provider.put("counted", 1);
                ContentValues screen = new ContentValues(); screen.putNull("log_id"); screen.putNull("duration");
                screen.put("time", screenTime); screen.put("source", "筛选"); screen.put("result", "待补全"); screen.put("counted", 1);
                // Free the provider's unique ID before inserting its independent canonical row.
                db.update("calls", screen, "id=?", args(screenId)); db.insertOrThrow("calls", null, provider);
            }
            db.execSQL("CREATE UNIQUE INDEX calls_screen_link ON calls(linked_screen_id) WHERE linked_screen_id IS NOT NULL");
            List<String> numbers = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT DISTINCT number FROM calls", null)) {
                while (cursor.moveToNext()) numbers.add(cursor.getString(0));
            }
            for (String number : numbers) reconcile(db, number);
        }
    }
}
