package com.greatwall.callring;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;

/** Fixed test numbers only; called by the DUMP-protected debug receiver. */
final class DebugManagementFixture {
    static String run(Context context, Intent intent) throws Exception {
        Context app = context.getApplicationContext();
        SharedPreferences state = app.getSharedPreferences(DebugQaApplication.STATE, Context.MODE_PRIVATE);
        String op = intent.getStringExtra("op");
        if ("qa_begin".equals(op)) {
            if (state.getBoolean("active", false)) throw new IllegalStateException("QA already active");
            RingPlaybackService.stop(app); CallerOverlayService.closeActive();
            resetStore();
            app.deleteDatabase(DebugQaApplication.DATABASE);
            if (!state.edit().putBoolean("previous_enabled", AppSettings.enabled(app)).putBoolean("active", true).commit())
                throw new IllegalStateException("Cannot persist QA state");
            CallerRepository store = CallerRepository.get(app);
            store.setStatisticsEnabled(true, System.currentTimeMillis() - 60000);
            long me = store.savePosition(0, "测试员工", 2, true), caller = store.savePosition(0, "测试主管", 1, true);
            store.setMyPosition(me);
            store.saveCaller("31234", "测试部门", "模拟来电", caller, false);
            store.setOverlayEnabled(false);
            AppSettings.enabled(app, true);
            return "ok:isolated";
        }
        if (!state.getBoolean("active", false)) throw new IllegalStateException("QA is inactive");
        if ("qa_finish".equals(op)) {
            RingPlaybackService.stop(app); CallerOverlayService.closeActive();
            resetStore();
            boolean previous = state.getBoolean("previous_enabled", false);
            if (!state.edit().putBoolean("active", false).remove("previous_enabled").commit())
                throw new IllegalStateException("Cannot restore QA state");
            AppSettings.enabled(app, previous);
            app.deleteDatabase(DebugQaApplication.DATABASE);
            ManagementEvents.changed(app);
            return "ok:restored";
        }
        CallerRepository store = CallerRepository.get(app);
        if ("qa_config".equals(op)) {
            String schedule = intent.getStringExtra("schedule");
            boolean working = "work".equals(schedule) || "leave".equals(schedule);
            LocalDate today = LocalDate.now(store.workConfig().zone);
            store.saveOverride(today, working ? Arrays.asList(new WorkSchedulePolicy.Window(0, 1439)) : Collections.emptyList());
            for (WorkSchedulePolicy.Leave leave : store.leaves()) store.deleteLeave(leave.id);
            if ("leave".equals(schedule)) store.saveLeave(0, System.currentTimeMillis()-1000, System.currentTimeMillis()+60000, "模拟请假");
            String quiet = intent.getStringExtra("quiet");
            store.setQuietMode(QuietHoursPolicy.Mode.valueOf(quiet == null ? "OFF" : quiet));
            java.util.Map<Integer,java.util.List<WorkSchedulePolicy.Window>> windows = new java.util.HashMap<>();
            for (int day=1;day<=7;day++) windows.put(day, Arrays.asList(new WorkSchedulePolicy.Window(0,1439)));
            store.saveQuietWindows(windows);
            store.setAllNumbers(intent.getBooleanExtra("all", false));
            CallerRepository.Caller caller = store.caller("31234");
            store.saveCaller(caller.number, caller.department, caller.name, caller.positionId, intent.getBooleanExtra("white", false));
            store.setOverlayEnabled(intent.getBooleanExtra("overlay", false));
            AppSettings.enabled(app, intent.getBooleanExtra("rule", true));
            ManagementEvents.changed(app);
            return "ok:configured";
        }
        if ("qa_status".equals(op)) {
            CallerRepository.CallRecord latest = store.latestCall("31234");
            CallerRepository.Report report = store.recentReport("31234");
            return "qa=true;count="+report.score.total+";score="+report.score.points+";pending="+report.pending
                    +";latest="+(latest==null?"none":latest.decision+";working="+latest.working+";result="+latest.result+";duration="+latest.duration)
                    +";nonmatching="+store.calls("41234",0,System.currentTimeMillis()+1).size();
        }
        throw new IllegalArgumentException("Unknown QA operation");
    }
    private static void resetStore() throws Exception {
        synchronized (CallerRepository.class) {
            Field field = CallerRepository.class.getDeclaredField("instance"); field.setAccessible(true);
            CallerRepository store = (CallerRepository) field.get(null);
            if (store != null) store.close();
            field.set(null,null);
        }
    }
}
