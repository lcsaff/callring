package com.greatwall.callring;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A short-lived caller card, independent of the audio playback service. */
public class CallerOverlayService extends Service {
    private static final String SHOW = "com.greatwall.callring.overlay.SHOW";
    private static final String CLOSE = "com.greatwall.callring.overlay.CLOSE";
    private static volatile CallerOverlayService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout card, body, editor;
    private TextView numberText, profileText, positionText, scoreText, reasonText, workText, decisionText, editStatus;
    private EditText nameEdit, departmentEdit;
    private Spinner positionEdit;
    private List<CallerRepository.Position> positions;
    private Button whiteButton, editButton, collapseButton;
    private String number, decision;
    private boolean watchCall, editing, pendingClose, closed, attached, collapsed, saving, receiverRegistered;
    private boolean screenHidden;
    private long sessionAt, started;
    private int generation;
    private SessionGate gate;

    public static void show(Context context, String number, String decision, boolean watchCall) {
        show(context, number, decision, watchCall, System.currentTimeMillis());
    }
    public static void show(Context context, String number, String decision, boolean watchCall, long at) {
        Context app = context.getApplicationContext();
        String normalized = NumberNormalizer.normalize(number);
        if (!normalized.matches("\\+?[0-9]{1,30}")) return;
        if (!Settings.canDrawOverlays(app)) { EventLog.add(app, "来电卡片未显示：悬浮窗未授权"); return; }
        try {
            app.startForegroundService(new Intent(app, CallerOverlayService.class).setAction(SHOW)
                    .putExtra("number", normalized).putExtra("decision", decision == null ? "正常放行" : decision)
                    .putExtra("watch_call", watchCall).putExtra("call_at", at));
        } catch (RuntimeException error) { EventLog.add(app, "来电卡片未显示：后台启动受限"); }
    }
    public static void refreshActive() {
        CallerOverlayService service = active;
        if (service != null) service.main.post(() -> { if (!service.closed) service.refresh(); });
    }
    public static void closeActive() {
        CallerOverlayService service = active;
        if (service != null) service.main.post(() -> service.finish("来电卡片已关闭"));
    }
    public static void onCallState(Context context, String state) {
        CallerOverlayService service = active;
        if (service == null) return;
        service.main.post(() -> {
            if (service.closed || !service.watchCall) return;
            if (service.gate == null) return;
            boolean ringing = TelephonyManager.EXTRA_STATE_RINGING.equals(state);
            boolean offHook = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state);
            if (!ringing && !offHook && !TelephonyManager.EXTRA_STATE_IDLE.equals(state)) return;
            if (service.gate.update(ringing, offHook, SystemClock.elapsedRealtime()) == SessionGate.STOP)
                service.callEnded();
        });
    }
    @Override public void onCreate() {
        super.onCreate();
        active = this;
        windows = getSystemService(WindowManager.class);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || CLOSE.equals(intent.getAction())) { finish("来电卡片已关闭"); return START_NOT_STICKY; }
        if (!SHOW.equals(intent.getAction())) { finish("来电卡片请求无效"); return START_NOT_STICKY; }
        try { startForeground(32, notification()); }
        catch (RuntimeException error) { finish("来电卡片未显示：通知服务不可用"); return START_NOT_STICKY; }
        String next = NumberNormalizer.normalize(intent.getStringExtra("number"));
        if (!next.matches("\\+?[0-9]{1,30}") || !Settings.canDrawOverlays(this)) {
            finish("来电卡片未显示：号码或权限不可用"); return START_NOT_STICKY;
        }
        if (editing && number != null && !number.equals(next)) {
            EventLog.add(this, "新来电保留当前卡片编辑内容"); return START_NOT_STICKY;
        }
        if (editing) { refresh(); return START_NOT_STICKY; }
        number = next; decision = intent.getStringExtra("decision");
        watchCall = intent.getBooleanExtra("watch_call", true);
        closed = false; pendingClose = false; screenHidden = false;
        started = SystemClock.elapsedRealtime(); sessionAt = intent.getLongExtra("call_at", System.currentTimeMillis());
        gate = new SessionGate("overlay", false, started);
        removeWindow();
        card = null; body = null; editor = null; collapsed = false;
        if (watchCall && !AppSettings.phonePermission(this)) {
            finish("来电卡片未显示：电话状态权限不可用"); return START_NOT_STICKY;
        }
        refresh();
        main.removeCallbacks(poll); main.post(poll);
        return START_NOT_STICKY;
    }
    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("caller_overlay", "来电卡片", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 32, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close = PendingIntent.getService(this, 33,
                new Intent(this, CallerOverlayService.class).setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "caller_overlay").setSmallIcon(R.drawable.ic_callring)
                .setContentTitle("来电信息卡片已开启").setContentText("通话结束后自动关闭，编辑中的信息可继续保存")
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null, "关闭卡片", close).build()).build();
    }
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenHidden = true; hideForPrivacy();
                if (!watchCall && !editing) finish("来电卡片已随息屏关闭");
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                screenHidden = false;
                if (watchCall && !ringingNow()) { callEnded(); if (!editing) return; }
                if (pendingClose && !editing) { finish("来电卡片随通话结束关闭"); return; }
                if (!closed && (watchCall || editing)) showWindow();
            }
        }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || number == null) return;
            if (!Settings.canDrawOverlays(CallerOverlayService.this)) { finish("来电卡片已关闭：悬浮窗授权撤销"); return; }
            if (watchCall && !pendingClose) {
                try {
                    int state = getSystemService(TelephonyManager.class).getCallState();
                    boolean ringing = state == TelephonyManager.CALL_STATE_RINGING;
                    boolean activeCall = CallStateSnapshot.active(state, getSystemService(AudioManager.class).getMode());
                    if (gate.update(ringing, activeCall, SystemClock.elapsedRealtime()) == SessionGate.STOP) callEnded();
                } catch (RuntimeException error) { callEnded(); }
            }
            if (closed) return;
            if (!safeToShow()) hideForPrivacy();
            else if (!attached && !screenHidden && (editing || !watchCall || ringingNow())) showWindow();
            main.postDelayed(this, 300);
        }
    };
    private boolean ringingNow() {
        try { return AppSettings.phonePermission(this)
                && getSystemService(TelephonyManager.class).getCallState() == TelephonyManager.CALL_STATE_RINGING
                && !CallStateSnapshot.active(TelephonyManager.CALL_STATE_IDLE, getSystemService(AudioManager.class).getMode()); }
        catch (RuntimeException error) { return false; }
    }
    private boolean safeToShow() {
        return !screenHidden && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void callEnded() {
        if (closed) return;
        pendingClose = true;
        if (editing) {
            if (editStatus != null) editStatus.setText("通话已结束，保存或取消后关闭卡片");
        } else finish("来电卡片随通话结束关闭");
    }
    private void refresh() {
        if (closed || number == null) return;
        final int version = ++generation;
        final String requestedNumber = number;
        worker.execute(() -> {
            try {
                CallerRepository store = CallerRepository.get(this);
                if (watchCall && !store.overlayEnabled()) {
                    main.post(() -> finish("来电卡片已关闭：功能停用")); return;
                }
                CallerRepository.Caller caller = store.caller(requestedNumber);
                CallerRepository.Report report = store.recentReport(requestedNumber);
                long total = 0;
                for (CallerRepository.CallRecord call : store.calls(requestedNumber, 0, System.currentTimeMillis() + 1))
                    if (call.counted) total++;
                final long accumulated = total;
                int[] weights = store.scoreWeights();
                WorkSchedulePolicy.Result work = WorkSchedulePolicy.evaluate(store.getConfigAt(sessionAt), sessionAt);
                String position = store.positionSummary(caller);
                String mode = CallerWidgetProvider.quietLabel(store.quietMode());
                main.post(() -> {
                    if (closed || version != generation || !requestedNumber.equals(number)) return;
                    if (card == null) buildCard();
                    numberText.setText(number);
                    profileText.setText(CallerWidgetProvider.empty(caller.name, "未填写姓名") + " · " + CallerWidgetProvider.empty(caller.department, "未填写部门"));
                    positionText.setText(position);
                    positionText.setContentDescription(position + "，" + CallerWidgetProvider.relationDescription(position));
                    scoreText.setText("近 " + weights[3] + " 天号码评分：" + Ui.score(report.score)
                            + "\n区间 " + report.score.total + " 次 · 累计 " + accumulated + " 次");
                    reasonText.setText("分数越低，号码越不受欢迎\n" + Ui.scoreReason(report.score, weights)
                            + "\n基于当前已记录来电" + (report.pending > 0 ? " · 尚有待补全" : ""));
                    workText.setText((watchCall ? "本次：" : "最近来电：") + CallerWidgetProvider.workLabel(work));
                    decisionText.setText("本次决策：" + CallerWidgetProvider.empty(decision, "正常放行")
                            + "\n应用全局免打扰：" + mode + " · " + (caller.whitelist ? "白名单" : "非白名单"));
                    whiteButton.setText(caller.whitelist ? "移出白名单" : "加入白名单");
                    showWindow();
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (closed || version != generation) return;
                    if (card == null) buildCard();
                    numberText.setText(requestedNumber); profileText.setText("读取失败，可重试或打开应用");
                    scoreText.setText("评分暂不可用"); reasonText.setText(""); workText.setText(""); decisionText.setText("");
                    showWindow();
                });
                EventLog.add(this, "来电卡片读取失败");
            }
        });
    }
    private void buildCard() {
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12), dp(4), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(245, 248, 246));
        background.setCornerRadius(dp(16)); background.setStroke(dp(1), Color.rgb(150, 184, 172)); card.setBackground(background);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); card.addView(header);
        TextView drag = new TextView(this); drag.setText("来电档案 · 拖动"); drag.setTextColor(Color.rgb(18,107,96)); drag.setTextSize(15);
        drag.setContentDescription("拖动此处移动来电卡片"); drag.setPadding(0, dp(8), 0, dp(8));
        header.addView(drag, new LinearLayout.LayoutParams(0, dp(48), 1)); drag.setOnTouchListener(new DragListener());
        collapseButton = smallButton(header, "收起", v -> toggleCollapsed());
        smallButton(header, "关闭", v -> finish("来电卡片已关闭"));
        ScrollView scroll = new ScrollView(this); card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        numberText = text(body, "", 22); numberText.setTypeface(null, Typeface.BOLD);
        profileText = text(body, "", 16); positionText = text(body, "", 15); scoreText = text(body, "", 17);
        reasonText = text(body, "", 12); workText = text(body, "", 14); decisionText = text(body, "", 13);
        LinearLayout actions = new LinearLayout(this); body.addView(actions);
        editButton = smallButton(actions, "编辑信息", v -> beginEdit());
        whiteButton = smallButton(actions, "白名单", v -> toggleWhite());
        smallButton(actions, "报表", v -> open(CallerReportActivity.class));
        smallButton(body, "刷新", v -> refresh());
        editor = new LinearLayout(this); editor.setOrientation(LinearLayout.VERTICAL); editor.setVisibility(View.GONE); body.addView(editor);
        params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.x = AppSettings.prefs(this).getInt("overlay_x", dp(12));
        params.y = AppSettings.prefs(this).getInt("overlay_y", dp(64));
        card.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (attached && (r-l != oldR-oldL || b-t != oldB-oldT)) main.post(this::clampAndUpdate);
        });
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(30,47,43));
        view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view, new LinearLayout.LayoutParams(-1,-2)); return view;
    }
    private Button smallButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setTextSize(12); button.setAllCaps(false); button.setMinWidth(0);
        button.setMinimumWidth(0); button.setPadding(dp(6), 0, dp(6), 0); button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(-2, dp(48))); return button;
    }
    private void beginEdit() {
        if (editing || saving || closed) return;
        if (!safeToShow()) { hideForPrivacy(); return; }
        final String requestedNumber=number;
        final int requestedGeneration=generation;
        worker.execute(() -> {
            try {
                CallerRepository store = CallerRepository.get(this);
                CallerRepository.Caller caller = store.caller(requestedNumber);
                List<CallerRepository.Position> options = Ui.positionOptions(store, caller.positionId, false);
                main.post(() -> {
                    if (closed || editing || pendingClose || requestedGeneration!=generation || editor==null
                            || !requestedNumber.equals(number) || !safeToShow()) return;
                    editor.removeAllViews(); editing = true; collapsed = false; body.setVisibility(View.VISIBLE);
                    collapseButton.setText("收起"); editButton.setEnabled(false);
                    nameEdit = Ui.field(editor, "姓名", caller.name, false);
                    departmentEdit = Ui.field(editor, "部门", caller.department, false);
                    nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
                    positions = options;
                    positionEdit = Ui.choice(editor, "预配置职位", Ui.positionLabels(positions), Ui.positionIndex(positions, caller.positionId));
                    if (positions.size() == 1) text(editor, "尚无职位选项，请在应用管理设置中配置。", 12);
                    editStatus = text(editor, "职位：↑ 高于我 · ↓ 低于我 · ＝ 同级 · — 未设置", 12);
                    LinearLayout buttons = new LinearLayout(this); editor.addView(buttons);
                    smallButton(buttons, "保存", v -> saveEdit());
                    smallButton(buttons, "取消", v -> endEdit());
                    smallButton(buttons, "职位设置", v -> open(ManagementSettingsActivity.class));
                    editor.setVisibility(View.VISIBLE);
                    params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    clampAndUpdate(); nameEdit.requestFocus();
                    main.post(() -> { if (editing && attached && safeToShow())
                        getSystemService(InputMethodManager.class).showSoftInput(nameEdit, InputMethodManager.SHOW_IMPLICIT); });
                });
            } catch (RuntimeException error) { main.post(() -> toast("编辑内容读取失败，请重试")); }
        });
    }
    private void saveEdit() {
        if (!editing || saving) return;
        String person = nameEdit.getText().toString(), department = departmentEdit.getText().toString();
        Long position = Ui.selectedPosition(positionEdit, positions);
        saving = true; editStatus.setText("正在保存…");
        worker.execute(() -> {
            try {
                CallerRepository store = CallerRepository.get(this);
                CallerRepository.Caller current = store.caller(number);
                store.saveCaller(current.number, department, person, position, current.whitelist);
                main.post(() -> {
                    saving = false;
                    if (closed) return;
                    endEdit(); ManagementEvents.changed(this);
                });
            } catch (RuntimeException error) {
                main.post(() -> { saving = false; if (!closed && editStatus != null) editStatus.setText("保存失败，内容已保留，请重试"); });
            }
        });
    }
    private void endEdit() {
        if (saving) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(card.getWindowToken(), 0);
        editing = false; editor.setVisibility(View.GONE); editButton.setEnabled(true); card.clearFocus();
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (pendingClose) finish("编辑完成，来电卡片已关闭"); else { clampAndUpdate(); refresh(); }
    }
    private void toggleWhite() {
        if (closed || saving) return;
        whiteButton.setEnabled(false);
        final String requestedNumber = number;
        worker.execute(() -> {
            try {
                CallerRepository.get(this).toggleWhitelist(requestedNumber);
                main.post(() -> { if (!closed) { whiteButton.setEnabled(true); ManagementEvents.changed(this); } });
            } catch (RuntimeException error) {
                main.post(() -> { if (!closed) { whiteButton.setEnabled(true); toast("白名单保存失败，请重试"); } });
            }
        });
    }
    private void toggleCollapsed() {
        if (editing) { toast("请先保存或取消编辑"); return; }
        collapsed = !collapsed; body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapseButton.setText(collapsed ? "展开" : "收起"); main.post(this::clampAndUpdate);
    }
    private void open(Class<?> activity) {
        try { startActivity(new Intent(this, activity).putExtra("number", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException error) { toast("暂时无法打开，请从应用进入"); }
    }
    private void showWindow() {
        if (closed || card == null || !safeToShow() || (pendingClose && !editing)) return;
        if (!Settings.canDrawOverlays(this)) { finish("来电卡片已关闭：悬浮窗授权撤销"); return; }
        clamp();
        try {
            if (!attached) { windows.addView(card, params); attached = true; }
            else windows.updateViewLayout(card, params);
        } catch (RuntimeException error) { finish("来电卡片未显示：系统拒绝窗口"); }
    }
    private void hideForPrivacy() {
        if (card != null && attached) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(card.getWindowToken(), 0);
        removeWindow();
    }
    private void removeWindow() {
        if (!attached || card == null) return;
        try { windows.removeViewImmediate(card); }
        catch (RuntimeException ignored) { }
        attached = false;
    }
    private Rect safeBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.left += insets.left; bounds.top += insets.top; bounds.right -= insets.right; bounds.bottom -= insets.bottom;
            if (editing) {
                Insets ime = metrics.getWindowInsets().getInsets(WindowInsets.Type.ime());
                bounds.bottom = Math.min(bounds.bottom, metrics.getBounds().bottom - ime.bottom);
            }
            return bounds;
        }
        Rect bounds = new Rect(); windows.getDefaultDisplay().getRectSize(bounds);
        int status = getResources().getIdentifier("status_bar_height", "dimen", "android");
        bounds.top += status > 0 ? getResources().getDimensionPixelSize(status) : dp(24);
        bounds.bottom -= dp(24); return bounds;
    }
    private void clamp() {
        if (params == null || card == null) return;
        Rect safe = safeBounds(); int margin = dp(8);
        params.width = Math.max(1, Math.min(dp(360), safe.width() - margin * 2));
        int maximum = Math.max(1, Math.min(safe.height() - margin * 2, Math.round(safe.height() * (editing ? .80f : .64f))));
        card.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST));
        params.height = Math.min(card.getMeasuredHeight(), maximum);
        params.x = Math.max(safe.left + margin, Math.min(params.x, safe.right - params.width - margin));
        params.y = Math.max(safe.top + margin, Math.min(params.y, safe.bottom - params.height - margin));
    }
    private void clampAndUpdate() {
        if (closed || card == null) return;
        clamp();
        if (attached) {
            try { windows.updateViewLayout(card, params); }
            catch (RuntimeException error) { finish("来电卡片已关闭：窗口不可用"); }
        }
    }
    private final class DragListener implements View.OnTouchListener {
        float downX, downY; int originalX, originalY;
        @Override public boolean onTouch(View view, MotionEvent event) {
            if (params == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX(); downY = event.getRawY(); originalX = params.x; originalY = params.y; return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                params.x = originalX + Math.round(event.getRawX()-downX); params.y = originalY + Math.round(event.getRawY()-downY);
                clampAndUpdate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                AppSettings.prefs(CallerOverlayService.this).edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply();
                view.performClick(); return true;
            }
            return false;
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { if (!closed) Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void finish(String status) {
        if (closed) return;
        closed = true; generation++; main.removeCallbacksAndMessages(null); hideForPrivacy();
        stopForeground(STOP_FOREGROUND_REMOVE); EventLog.add(this, status); stopSelf();
    }
    @Override public void onDestroy() {
        closed = true; generation++; main.removeCallbacksAndMessages(null); hideForPrivacy();
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        worker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE);
        if (active == this) active = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
