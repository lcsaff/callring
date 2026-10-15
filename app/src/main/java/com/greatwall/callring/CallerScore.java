package com.greatwall.callring;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

public final class CallerScore {
    public static final class Entry {
        public final long timeMillis;
        public final boolean working;
        public Entry(long timeMillis, boolean working) { this.timeMillis = timeMillis; this.working = working; }
    }
    public static final class Result {
        public final Integer points;
        public final int total, offHours, repeats;
        public final long deductions;
        public Result(Integer points, int total, int offHours, int repeats, long deductions) {
            this.points = points; this.total = total; this.offHours = offHours; this.repeats = repeats; this.deductions = deductions;
        }
    }
    public static Result calculate(List<Entry> entries, long startInclusive, long endExclusive,
            int offHoursWeight, int repeatWeight, int repeatMinutes) {
        if (offHoursWeight < 0 || repeatWeight < 0 || repeatMinutes <= 0) {
            throw new IllegalArgumentException("扣分权重不可为负，重复来电间隔必须大于零");
        }
        List<Entry> filtered = new ArrayList<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry.timeMillis >= startInclusive && entry.timeMillis < endExclusive) filtered.add(entry);
            }
        }
        filtered.sort(Comparator.comparingLong(entry -> entry.timeMillis));
        if (filtered.isEmpty()) return new Result(null, 0, 0, 0, 0);

        int offHours = 0;
        int repeats = 0;
        long repeatMillis = repeatMinutes * 60000L;
        for (int i = 0; i < filtered.size(); i++) {
            Entry entry = filtered.get(i);
            if (!entry.working) offHours++;
            if (i > 0) {
                long gap = entry.timeMillis - filtered.get(i - 1).timeMillis;
                // An overflowing difference is negative; sorted timestamps cannot have a negative gap.
                if (gap >= 0 && gap <= repeatMillis) repeats++;
            }
        }
        long deductions = (long) offHours * offHoursWeight + (long) repeats * repeatWeight;
        int points = deductions >= 100 ? 0 : 100 - (int) deductions;
        return new Result(points, filtered.size(), offHours, repeats, deductions);
    }
}
