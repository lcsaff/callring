package com.greatwall.callring;

import android.content.Context;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class EventLog {
    static synchronized void add(Context c, String message) {
        // Only fixed status messages: never log a call handle or a selected file URI.
        Log.i("RuleRing", message);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message;
        String[] old = read(c).split("\n");
        StringBuilder result = new StringBuilder(line);
        for (int i = 0; i < Math.min(old.length, 17); i++) {
            if (!old[i].isEmpty()) result.append('\n').append(old[i]);
        }
        AppSettings.prefs(c).edit().putString("events", result.toString()).apply();
    }
    static String read(Context c) { return AppSettings.prefs(c).getString("events", ""); }
}
