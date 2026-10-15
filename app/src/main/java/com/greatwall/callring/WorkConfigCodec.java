package com.greatwall.callring;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

final class WorkConfigCodec {
    private WorkConfigCodec() { }

    static String encode(WorkSchedulePolicy.Config config) {
        try {
            return new JSONObject().put("zone", config.zone.getId())
                    .put("weekly", weeklyJson(config.weekly)).put("makeup", windowsJson(config.makeup)).toString();
        } catch (Exception e) { throw new IllegalArgumentException("工作时间保存失败", e); }
    }
    static WorkSchedulePolicy.Config decode(String raw) {
        try {
            WorkSchedulePolicy.Config config = new WorkSchedulePolicy.Config();
            JSONObject object = new JSONObject(raw);
            config.zone = ZoneId.of(object.getString("zone"));
            config.weekly = weekly(object.getJSONObject("weekly"));
            config.makeup = windows(object.getJSONArray("makeup"));
            return config;
        } catch (Exception e) { throw new IllegalArgumentException("工作时间数据损坏", e); }
    }
    static JSONObject weeklyJson(Map<Integer,List<WorkSchedulePolicy.Window>> windows) throws Exception {
        JSONObject object = new JSONObject();
        for (int day = 1; day <= 7; day++)
            object.put(Integer.toString(day), windowsJson(windows.getOrDefault(day, Collections.emptyList())));
        return object;
    }
    static Map<Integer,List<WorkSchedulePolicy.Window>> weekly(JSONObject object) throws Exception {
        Map<Integer,List<WorkSchedulePolicy.Window>> result = new HashMap<>();
        for (int day = 1; day <= 7; day++) result.put(day, windows(object.optJSONArray(Integer.toString(day))));
        return result;
    }
    static JSONArray windowsJson(List<WorkSchedulePolicy.Window> windows) {
        JSONArray array = new JSONArray();
        if (windows != null) for (WorkSchedulePolicy.Window w : windows)
            array.put(new JSONArray().put(w.startMinute).put(w.endMinute));
        return array;
    }
    static List<WorkSchedulePolicy.Window> windows(JSONArray array) throws Exception {
        List<WorkSchedulePolicy.Window> windows = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONArray pair = array.getJSONArray(i);
            if (pair.length() != 2) throw new IllegalArgumentException("时间段必须有开始和结束");
            windows.add(new WorkSchedulePolicy.Window(pair.getInt(0), pair.getInt(1)));
        }
        return windows;
    }
    static List<WorkSchedulePolicy.Window> parseWindows(String text) {
        List<WorkSchedulePolicy.Window> result = new ArrayList<>();
        if (text.trim().isEmpty()) return result;
        for (String range : text.replace('，', ',').split(",", -1)) {
            String[] pieces = range.trim().split("-", -1);
            if (pieces.length != 2) throw new IllegalArgumentException("时间段格式示例：09:00-12:00");
            result.add(new WorkSchedulePolicy.Window(minute(pieces[0]), minute(pieces[1])));
        }
        return result;
    }
    private static int minute(String text) {
        String value=text.trim();
        if(!value.matches("[0-9]{2}:[0-9]{2}"))throw new IllegalArgumentException("时间格式应为 HH:mm");
        LocalTime t = LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT));
        return t.getHour() * 60 + t.getMinute();
    }
    static Map<Integer,List<WorkSchedulePolicy.Window>> parseWeekly(String raw) {
        Map<Integer,List<WorkSchedulePolicy.Window>> result = new HashMap<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+", 2);
            int day = Integer.parseInt(parts[0]);
            if (day < 1 || day > 7 || result.containsKey(day)) throw new IllegalArgumentException("星期须为 1–7，且每个星期只写一行");
            result.put(day, parts.length == 1 ? new ArrayList<>() : parseWindows(parts[1]));
        }
        return result;
    }
    static String formatWindows(List<WorkSchedulePolicy.Window> windows) {
        List<String> parts = new ArrayList<>();
        if (windows != null) for (WorkSchedulePolicy.Window window : windows)
            parts.add(String.format(Locale.ROOT, "%02d:%02d-%02d:%02d", window.startMinute / 60, window.startMinute % 60,
                    window.endMinute / 60, window.endMinute % 60));
        return String.join(",", parts);
    }
    static String formatWeekly(Map<Integer,List<WorkSchedulePolicy.Window>> weekly) {
        List<String> lines = new ArrayList<>();
        for (int day = 1; day <= 7; day++) if (weekly.containsKey(day))
            lines.add(day + " " + formatWindows(weekly.get(day)));
        return String.join("\n", lines);
    }
    static final class CalendarData {
        int year; String source, published, version;
        Map<LocalDate,String> holidays = new HashMap<>(); Set<LocalDate> workdays = new HashSet<>();
    }
    static CalendarData calendar(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            CalendarData data = new CalendarData();
            data.year = json.getInt("year");
            if (data.year < 1970 || data.year > 9998) throw new IllegalArgumentException("日历年份不合法");
            data.source = json.getString("source").trim(); data.version = json.getString("version").trim();
            data.published = LocalDate.parse(json.getString("published")).toString();
            if (data.source.isEmpty() || data.version.isEmpty()) throw new IllegalArgumentException("日历需要来源和版本");
            JSONArray holidays = json.getJSONArray("holidays");
            for (int i = 0; i < holidays.length(); i++) {
                JSONObject item = holidays.getJSONObject(i);
                LocalDate first = LocalDate.parse(item.getString("start")), last = LocalDate.parse(item.getString("end"));
                String name = item.getString("name").trim();
                if (first.getYear() != data.year || last.getYear() != data.year || last.isBefore(first) || name.isEmpty())
                    throw new IllegalArgumentException("放假日期范围或名称不合法");
                for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1))
                    if (data.holidays.put(date, name) != null) throw new IllegalArgumentException("放假日期重复");
            }
            JSONArray workdays = json.getJSONArray("workdays");
            for (int i = 0; i < workdays.length(); i++) {
                LocalDate date = LocalDate.parse(workdays.getString(i));
                if (date.getYear() != data.year || data.holidays.containsKey(date) || !data.workdays.add(date))
                    throw new IllegalArgumentException("补班日期重复、年份不符或与放假冲突");
            }
            return data;
        } catch (Exception e) { throw new IllegalArgumentException("日历导入失败：" + e.getMessage(), e); }
    }
}
