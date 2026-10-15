package com.greatwall.callring;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, audioName, events;
    private Switch enabled;
    private boolean refreshing;
    private long importGeneration;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 800); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(18, 107, 96));
        getWindow().setNavigationBarColor(Color.rgb(245, 248, 246));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(28));
        page.setBackgroundColor(Color.rgb(245, 248, 246));
        scroll.addView(page);
        setContentView(scroll);
        TextView title = text(page, "规则来电铃声", 28);
        title.setTypeface(null, Typeface.BOLD);
        text(page, "指定号码，换一种声音。接听仍用小米电话。", 14);
        button(page,"号码档案与来电报表",v->startActivity(new Intent(this,CallerListActivity.class)));
        button(page,"工作安排、请假与免打扰设置",v->startActivity(new Intent(this,ManagementSettingsActivity.class)));
        button(page,"显示最近来电卡片",v->{
            if(!Settings.canDrawOverlays(this)){toast("请先在管理设置中授权悬浮窗");return;}
            Ui.run(this,()->{
                CallerRepository.CallRecord latest=CallerRepository.get(this).latestCall(null);
                if(latest==null)throw new IllegalStateException("还没有已记录的来电");
                CallerOverlayService.show(this,latest.number,latest.decision,false,latest.time);
            },null);
        });
        button(page,"添加桌面小组件",v->{android.appwidget.AppWidgetManager widgets=getSystemService(android.appwidget.AppWidgetManager.class);
            if(widgets.isRequestPinAppWidgetSupported())widgets.requestPinAppWidget(new android.content.ComponentName(this,CallerWidgetProvider.class),null,null);
            else toast("请在桌面长按，选择小组件中的规则来电铃声");});
        TextView rule = text(page, "3 开头 · 共 5 位数字", 22);
        rule.setTextColor(Color.rgb(18, 107, 96));
        text(page, "例如 31234。通讯录来电的筛选需在管理设置中另行授权。", 14);
        status = text(page, "", 14);
        button(page, "1  授权电话状态与通知", v -> requestPermissionsNow());
        button(page, "2  设为来电识别应用", v -> requestRole());
        enabled = new Switch(this);
        enabled.setText("启用规则铃声"); enabled.setTextSize(19);
        enabled.setPadding(0, dp(16), 0, dp(16));
        page.addView(enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (checked && (!hasRole() || !AppSettings.phonePermission(this))) {
                toast("请先完成上方两项授权"); update(); return;
            }
            AppSettings.enabled(this, checked);
            if (!checked) RingPlaybackService.stop(this);
            EventLog.add(this, checked ? "规则已启用" : "规则已停用");
            update();
        });
        audioName = text(page, "", 17);
        button(page, "试听 8 秒", v -> {
            if (!AppSettings.phonePermission(this)) { requestPermissionsNow(); return; }
            if (!AppSettings.audible(this)) { toast("请先关闭勿扰并开启铃声音量"); return; }
            try { RingPlaybackService.preview(this); }
            catch (RuntimeException e) { toast("系统未允许启动试听，请查看应用设置"); }
        });
        button(page, "停止播放", v -> RingPlaybackService.stop(this));
        button(page, "选择手机中的音频", v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 20);
        });
        button(page, "恢复内置短旋律", v -> {
            importGeneration++;
            RingPlaybackService.stop(this);
            AppSettings.prefs(this).edit().putBoolean("custom", false).apply();
            update();
        });
        text(page, "遵守手机静音、振动、勿扰及铃声音量。锁屏收不到时，可在应用设置中允许后台运行。", 13);
        button(page, "打开应用设置", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        text(page, "检查号码规则", 18);
        EditText sample = new EditText(this);
        sample.setHint("输入测试号码，不拨打电话");
        sample.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        sample.setSingleLine(true); page.addView(sample);
        button(page, "检查是否匹配", v -> toast(RulePolicy.matches(sample.getText().toString()) ? "匹配规则" : "不匹配，保留系统铃声"));
        text(page, "最近状态（不记录号码）", 18);
        events = text(page, "", 13);
    }
    private void requestPermissionsNow() {
        requestPermissions(Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.READ_PHONE_STATE}, 10);
    }
    private boolean hasRole() {
        RoleManager role = getSystemService(RoleManager.class);
        return role != null && role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
                && role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
    }
    private void requestRole() {
        RoleManager role = getSystemService(RoleManager.class);
        if (role == null || !role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) { toast("系统未提供来电识别角色"); return; }
        if (hasRole()) { toast("已是来电识别应用"); return; }
        startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 11);
    }
    private void update() {
        refreshing = true;
        enabled.setChecked(AppSettings.enabled(this));
        refreshing = false;
        status.setText(getString(R.string.permission_status,
                AppSettings.phonePermission(this) ? "已授权" : "未授权",
                hasRole() ? "已设置" : "未设置",
                AppSettings.audible(this) ? "可响铃" : "静音、振动、勿扰或音量为零"));
        audioName.setText(AppSettings.audioLabel(this));
        String log = EventLog.read(this);
        events.setText(log.isEmpty() ? "还没有事件。可先试听，然后用真实来电验证。" : log);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); SyncJobService.schedule(this); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        RingPlaybackService.stop(this);
        toast("正在检查并导入音频");
        android.net.Uri uri = data.getData();
        long generation = ++importGeneration;
        new Thread(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("audio-import-", ".tmp", getCacheDir());
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temp)) {
                    if (input == null) throw new java.io.IOException();
                    byte[] buffer = new byte[8192]; int count, total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 20 * 1024 * 1024) throw new java.io.IOException();
                        output.write(buffer, 0, count);
                    }
                }
                MediaPlayer check = new MediaPlayer();
                try { check.setDataSource(temp.getAbsolutePath()); check.prepare(); if (check.getDuration() <= 0) throw new java.io.IOException(); }
                finally { check.release(); }
                File completed = temp;
                temp = null;
                handler.post(() -> {
                    try {
                        if (isDestroyed() || generation != importGeneration) return;
                        Files.move(completed.toPath(), AppSettings.customAudio(this).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        AppSettings.prefs(this).edit().putBoolean("custom", true).apply();
                        toast("音频已导入"); update();
                    } catch (Exception e) { toast("保存音频失败"); }
                    finally { completed.delete(); }
                });
            } catch (Exception e) {
                handler.post(() -> { if (!isDestroyed()) toast("导入失败：请选择 20 MB 以内可播放的音频"); });
            } finally {
                if (temp != null) temp.delete(); // Only this app's disposable import scratch file.
            }
        }, "audio-import").start();
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(30, 47, 43)); view.setPadding(0, dp(6), 0, dp(8));
        parent.addView(view); return view;
    }
    private void button(LinearLayout parent, String value, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false);
        button.setOnClickListener(listener); parent.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
