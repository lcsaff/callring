package com.greatwall.callring;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkSchedulePolicy {
    public static final class Window {
        public final int startMinute, endMinute;
        public Window(int startMinute, int endMinute) {
            if (startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439
                    || startMinute == endMinute) {
                throw new IllegalArgumentException("工作时段须使用不同的有效起止分钟");
            }
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }
    }
    public static final class Leave {
        public final long id, startMillis, endMillis;
        public final String note;
        public Leave(long id, long startMillis, long endMillis, String note) {
            this.id = id; this.startMillis = startMillis; this.endMillis = endMillis; this.note = note;
        }
    }
    public static final class Config {
        public ZoneId zone = ZoneId.systemDefault();
        public Map<Integer,List<Window>> weekly = new HashMap<>();
        public List<Window> makeup = new ArrayList<>();
        public Map<LocalDate,List<Window>> overrides = new HashMap<>();
        public Map<LocalDate,String> holidays = new HashMap<>();
        public Set<LocalDate> makeupDays = new HashSet<>();
        public Set<Integer> coveredYears = new HashSet<>();
        public List<Leave> leaves = new ArrayList<>();

        public Config() {
            for (int day = 1; day <= 5; day++) weekly.put(day, defaultWindows());
            makeup = defaultWindows();
        }
    }
    public static final class Result {
        public final boolean working, verified;
        public final String reason;
        public Result(boolean working, boolean verified, String reason) { this.working = working; this.verified = verified; this.reason = reason; }
    }
    public static Result evaluate(Config config, long epochMillis) {
        ZonedDateTime local = Instant.ofEpochMilli(epochMillis).atZone(config.zone);
        LocalDate date = local.toLocalDate();
        boolean verified = config.coveredYears.contains(date.getYear());
        for (Leave leave : config.leaves) {
            if (epochMillis >= leave.startMillis && epochMillis < leave.endMillis) {
                return new Result(false, verified, "请假期间");
            }
        }

        DayPlan current = planFor(config, date);
        if (current.explicitRest) return new Result(false, verified, current.restReason);

        int minute = local.getHour() * 60 + local.getMinute();
        if (inStartDayWindows(current.windows, minute)) {
            return new Result(true, verified, current.workReason);
        }
        DayPlan previous = planFor(config, date.minusDays(1));
        if (inOvernightCarry(previous.windows, minute)) {
            return new Result(true, verified, previous.workReason);
        }
        return new Result(false, verified, current.windows.isEmpty() ? "休息日" : "工作时段外");
    }

    /** ISO weekday keys, with overnight windows belonging to their start day. */
    public static boolean inWeeklyWindows(Map<Integer,List<Window>> windows, ZoneId zone, long epochMillis) {
        if (windows == null || windows.isEmpty()) return false;
        ZonedDateTime local = Instant.ofEpochMilli(epochMillis).atZone(zone);
        int minute = local.getHour() * 60 + local.getMinute();
        int today = local.getDayOfWeek().getValue();
        int previous = today == 1 ? 7 : today - 1;
        return inStartDayWindows(orEmpty(windows.get(today)), minute)
                || inOvernightCarry(orEmpty(windows.get(previous)), minute);
    }

    private static List<Window> defaultWindows() {
        List<Window> windows = new ArrayList<>();
        windows.add(new Window(540, 720));
        windows.add(new Window(780, 1080));
        return windows;
    }

    private static DayPlan planFor(Config config, LocalDate date) {
        if (config.overrides.containsKey(date)) {
            List<Window> windows = orEmpty(config.overrides.get(date));
            return new DayPlan(windows, windows.isEmpty(), "休息日", "工作中");
        }
        if (config.coveredYears.contains(date.getYear())) {
            if (config.holidays.containsKey(date)) {
                return new DayPlan(java.util.Collections.<Window>emptyList(), true,
                        "节假日·" + config.holidays.get(date), "工作中");
            }
            if (config.makeupDays.contains(date)) {
                return new DayPlan(orEmpty(config.makeup), false, "休息日", "调休上班");
            }
        }
        return new DayPlan(orEmpty(config.weekly.get(date.getDayOfWeek().getValue())), false, "休息日", "工作中");
    }

    private static List<Window> orEmpty(List<Window> windows) {
        return windows == null ? java.util.Collections.<Window>emptyList() : windows;
    }

    private static boolean inStartDayWindows(List<Window> windows, int minute) {
        for (Window window : windows) {
            if (window.startMinute < window.endMinute) {
                if (minute >= window.startMinute && minute < window.endMinute) return true;
            } else if (minute >= window.startMinute) {
                return true;
            }
        }
        return false;
    }

    private static boolean inOvernightCarry(List<Window> windows, int minute) {
        for (Window window : windows) {
            if (window.startMinute > window.endMinute && minute < window.endMinute) return true;
        }
        return false;
    }

    private static final class DayPlan {
        final List<Window> windows;
        final boolean explicitRest;
        final String restReason, workReason;

        DayPlan(List<Window> windows, boolean explicitRest, String restReason, String workReason) {
            this.windows = windows;
            this.explicitRest = explicitRest;
            this.restReason = restReason;
            this.workReason = workReason;
        }
    }
}
