package com.greatwall.callring;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public final class QuietHoursPolicy {
    public enum Mode { OFF, NON_WORK, CUSTOM }
    public static boolean shouldSilence(boolean matches, boolean whitelist, boolean allNumbers, Mode mode,
            boolean working, long atMillis, ZoneId zone, Map<Integer,List<WorkSchedulePolicy.Window>> quietWindows) {
        if (whitelist || (!matches && !allNumbers) || mode == Mode.OFF || working) return false;
        if (mode == Mode.NON_WORK) return true;
        return mode == Mode.CUSTOM && WorkSchedulePolicy.inWeeklyWindows(quietWindows, zone, atMillis);
    }
}
