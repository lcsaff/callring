package com.greatwall.callring;

import org.junit.Test;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class QuietHoursPolicyTest {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private long at(String local) {
        return ZonedDateTime.parse(local + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }
    private boolean silence(boolean match, boolean white, boolean all, QuietHoursPolicy.Mode mode, boolean working) {
        return QuietHoursPolicy.shouldSilence(match, white, all, mode, working, at("2026-10-14T22:00:00"), zone, Collections.<Integer,List<WorkSchedulePolicy.Window>>emptyMap());
    }

    @Test public void nonWorkSilencesMatchedNumbersOnlyOutsideWork() {
        assertTrue(silence(true, false, false, QuietHoursPolicy.Mode.NON_WORK, false));
        assertFalse(silence(true, false, false, QuietHoursPolicy.Mode.NON_WORK, true));
        assertFalse(silence(false, false, false, QuietHoursPolicy.Mode.NON_WORK, false));
    }
    @Test public void allNumbersExpandsScopeAndWhitelistAlwaysWins() {
        assertTrue(silence(false, false, true, QuietHoursPolicy.Mode.NON_WORK, false));
        assertFalse(silence(true, true, true, QuietHoursPolicy.Mode.NON_WORK, false));
        assertFalse(silence(false, true, true, QuietHoursPolicy.Mode.NON_WORK, false));
    }
    @Test public void offModeNeverSilences() {
        assertFalse(silence(true, false, true, QuietHoursPolicy.Mode.OFF, false));
    }
    @Test public void customRequiresIntersectionWithNonWorkTime() {
        Map<Integer,List<WorkSchedulePolicy.Window>> windows = new HashMap<>();
        windows.put(3, Collections.singletonList(new WorkSchedulePolicy.Window(1320, 120)));
        assertTrue(QuietHoursPolicy.shouldSilence(true, false, false, QuietHoursPolicy.Mode.CUSTOM, false, at("2026-10-14T22:00:00"), zone, windows));
        assertTrue(QuietHoursPolicy.shouldSilence(true, false, false, QuietHoursPolicy.Mode.CUSTOM, false, at("2026-10-15T01:00:00"), zone, windows));
        assertFalse(QuietHoursPolicy.shouldSilence(true, false, false, QuietHoursPolicy.Mode.CUSTOM, true, at("2026-10-14T22:00:00"), zone, windows));
        assertFalse(QuietHoursPolicy.shouldSilence(true, false, false, QuietHoursPolicy.Mode.CUSTOM, false, at("2026-10-15T02:00:00"), zone, windows));
        assertFalse(QuietHoursPolicy.shouldSilence(true, true, true, QuietHoursPolicy.Mode.CUSTOM, false, at("2026-10-14T22:00:00"), zone, windows));
    }
}
