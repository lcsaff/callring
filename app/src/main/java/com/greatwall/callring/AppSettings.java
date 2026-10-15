package com.greatwall.callring;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import java.io.File;

final class AppSettings {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ring_settings", 0); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static void enabled(Context c, boolean enabled) { prefs(c).edit().putBoolean("enabled", enabled).apply(); }
    static boolean phonePermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean audible(Context c) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        NotificationManager notifications = c.getSystemService(NotificationManager.class);
        return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_RING) > 0
                && notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
    }
    static File customAudio(Context c) { return new File(c.getFilesDir(), "selected_audio"); }
    static String audioLabel(Context c) {
        return prefs(c).getBoolean("custom", false) ? "已选择的音频" : "内置 · 三音短旋律";
    }
}
