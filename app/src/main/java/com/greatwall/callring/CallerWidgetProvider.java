package com.greatwall.callring;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each widget owns one number. Widget clicks carry immutable, per-action identities. */
public class CallerWidgetProvider extends AppWidgetProvider {
    static final String ACTION_WHITE = "com.greatwall.callring.widget.WHITE";
    static final String ACTION_QUIET = "com.greatwall.callring.widget.QUIET";
    static final String ACTION_REFRESH = "com.greatwall.callring.widget.REFRESH";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("caller_widgets", Context.MODE_PRIVATE);
    }
    public static String binding(Context context, int widgetId) {
        return preferences(context).getString("number_" + widgetId, "");
    }
    public static void bind(Context context, int widgetId, String number) {
        String normalized = NumberNormalizer.normalize(number);
        if (widgetId <= 0 || !normalized.matches("\\+?[0-9]{1,30}"))
            throw new IllegalArgumentException("请选择或填写有效号码");
        if (!preferences(context).edit().putString("number_" + widgetId, normalized).commit())
            throw new IllegalStateException("小组件号码保存失败，请重试");
    }
    static PendingIntent actionPendingIntent(Context context, int widgetId, String action) {
        Intent intent = new Intent(context, CallerWidgetProvider.class).setAction(action)
                .setData(identity(widgetId, action))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static Uri identity(int widgetId, String action) {
        return new Uri.Builder().scheme("callring").authority("widget")
                .appendPath(Integer.toString(widgetId)).appendPath(action).build();
    }
    private static PendingIntent activityPendingIntent(Context context, int widgetId, String action,
            Class<?> activity, String number) {
        Intent intent = new Intent(context, activity).setData(identity(widgetId, action))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!number.isEmpty()) intent.putExtra("number", number);
        return PendingIntent.getActivity(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static void updateAll(Context context) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            for (int widgetId : manager.getAppWidgetIds(new ComponentName(app, CallerWidgetProvider.class)))
                render(app, manager, widgetId);
        });
    }
    public static void update(Context context, int widgetId) {
        Context app = context.getApplicationContext();
        worker.execute(() -> render(app, AppWidgetManager.getInstance(app), widgetId));
    }
    private static boolean belongsToProvider(Context context, int widgetId) {
        if (widgetId <= 0) return false;
        int[] active = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, CallerWidgetProvider.class));
        for (int id : active) if (id == widgetId) return true;
        return false;
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!ACTION_WHITE.equals(action) && !ACTION_QUIET.equals(action) && !ACTION_REFRESH.equals(action)) {
            super.onReceive(context, intent); return;
        }
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (!belongsToProvider(context, widgetId)) return;
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        worker.execute(() -> {
            try {
                // Recheck after queuing: a widget may have been removed in the meantime.
                if (!belongsToProvider(app, widgetId)) return;
                CallerRepository store = CallerRepository.get(app);
                if (ACTION_WHITE.equals(action)) {
                    String number = binding(app, widgetId);
                    if (number.isEmpty()) return;
                    store.toggleWhitelist(number);
                    ManagementEvents.changed(app);
                } else if (ACTION_QUIET.equals(action)) {
                    store.toggleQuiet();
                    ManagementEvents.changed(app);
                } else render(app, AppWidgetManager.getInstance(app), widgetId);
            } catch (RuntimeException error) {
                EventLog.add(app, "小组件操作失败，请打开应用重试");
            } finally { if (result != null) result.finish(); }
        });
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        worker.execute(() -> {
            try { for (int widgetId : widgetIds) render(app, AppWidgetManager.getInstance(app), widgetId); }
            finally { if (result != null) result.finish(); }
        });
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId,
            android.os.Bundle options) { update(context, widgetId); }
    @Override public void onDeleted(Context context, int[] widgetIds) {
        SharedPreferences.Editor edit = preferences(context).edit();
        for (int widgetId : widgetIds) edit.remove("number_" + widgetId);
        edit.commit();
    }
    private static void render(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.caller_widget);
        String number = binding(context, widgetId);
        PendingIntent configure = activityPendingIntent(context, widgetId, "configure", CallerWidgetConfigActivity.class, "");
        views.setOnClickPendingIntent(R.id.widget_choose, configure);
        views.setOnClickPendingIntent(R.id.widget_number, number.isEmpty() ? configure
                : activityPendingIntent(context, widgetId, "report_title", CallerReportActivity.class, number));
        views.setOnClickPendingIntent(R.id.widget_app,
                activityPendingIntent(context, widgetId, "app", MainActivity.class, ""));
        views.setViewVisibility(R.id.widget_choose, number.isEmpty() ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_details, number.isEmpty() ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.widget_number, number.isEmpty() ? "选择号码" : number);
        if (!number.isEmpty()) {
            try {
                CallerRepository store = CallerRepository.get(context);
                CallerRepository.Caller caller = store.caller(number);
                CallerRepository.Report report = store.recentReport(number);
                int days = store.scoreWeights()[3];
                long now = System.currentTimeMillis();
                WorkSchedulePolicy.Config config = store.workConfig();
                WorkSchedulePolicy.Result work = WorkSchedulePolicy.evaluate(config, now);
                String position = store.positionSummary(caller);
                views.setTextViewText(R.id.widget_profile, empty(caller.name, "未填写姓名") + " · " + empty(caller.department, "未填写部门"));
                views.setTextViewText(R.id.widget_position, position);
                views.setContentDescription(R.id.widget_position, position + "，" + relationDescription(position));
                views.setTextViewText(R.id.widget_score, "近 " + days + " 天：" + Ui.score(report.score) + " · " + report.score.total + " 次");
                views.setTextViewText(R.id.widget_score_note, "分数越低，号码越不受欢迎\n基于当前已记录来电" + (report.pending > 0 ? " · 尚有待补全" : ""));
                CallerRepository.CallRecord recent = store.latestCall(number);
                views.setTextViewText(R.id.widget_recent, recent == null ? "最近来电：暂无记录"
                        : "最近来电：" + Ui.time(recent.time, config.zone));
                views.setTextViewText(R.id.widget_work, workLabel(work) + "\n更新于 " + Ui.time(now, config.zone));
                views.setTextViewText(R.id.widget_status, "应用全局免打扰：" + quietLabel(store.quietMode())
                        + " · " + (caller.whitelist ? "白名单放行" : "非白名单"));
                views.setTextViewText(R.id.widget_white, caller.whitelist ? "移出白名单" : "加入白名单");
                views.setTextViewText(R.id.widget_quiet, store.quietMode() == QuietHoursPolicy.Mode.OFF ? "开启全局免打扰" : "关闭全局免打扰");
                views.setOnClickPendingIntent(R.id.widget_edit, activityPendingIntent(context, widgetId, "edit", CallerEditActivity.class, number));
                views.setOnClickPendingIntent(R.id.widget_report, activityPendingIntent(context, widgetId, "report", CallerReportActivity.class, number));
                views.setOnClickPendingIntent(R.id.widget_white, actionPendingIntent(context, widgetId, ACTION_WHITE));
                views.setOnClickPendingIntent(R.id.widget_quiet, actionPendingIntent(context, widgetId, ACTION_QUIET));
                views.setOnClickPendingIntent(R.id.widget_refresh, actionPendingIntent(context, widgetId, ACTION_REFRESH));
            } catch (RuntimeException error) {
                views.setTextViewText(R.id.widget_profile, "读取失败，请打开应用重试");
                views.setTextViewText(R.id.widget_score, "评分暂不可用");
                views.setTextViewText(R.id.widget_status, "状态暂不可用");
                views.setOnClickPendingIntent(R.id.widget_refresh, actionPendingIntent(context, widgetId, ACTION_REFRESH));
                EventLog.add(context, "小组件读取失败");
            }
        }
        try { manager.updateAppWidget(widgetId, views); }
        catch (RuntimeException error) { EventLog.add(context, "小组件刷新暂不可用"); }
    }
    static String empty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
    static String quietLabel(QuietHoursPolicy.Mode mode) {
        return mode == QuietHoursPolicy.Mode.OFF ? "关闭" : mode == QuietHoursPolicy.Mode.NON_WORK ? "全部非工作时间" : "自定义非工作时段";
    }
    static String workLabel(WorkSchedulePolicy.Result work) {
        return (work.working ? "工作中" : work.reason.equals("请假期间") ? "请假中" : "非工作时间")
                + " · " + work.reason + (work.verified ? "" : " · 节假日未核验");
    }
    static String relationDescription(String summary) {
        if (summary.endsWith("↑")) return "高于我的职位";
        if (summary.endsWith("↓")) return "低于我的职位";
        if (summary.endsWith("＝")) return "与我的职位同级";
        return "无法比较职位";
    }
}
