package com.greatwall.callring;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkConfigCodecTest {
    @Test public void invalidClockIsRejected(){assertThrows(RuntimeException.class,()->WorkConfigCodec.parseWindows("24:00-01:00"));}
    @Test public void emptySeparatedWindowsAreRejected(){assertThrows(RuntimeException.class,()->WorkConfigCodec.parseWindows(","));assertThrows(RuntimeException.class,()->WorkConfigCodec.parseWindows("09:00-12:00,"));}
    @Test public void multilineWeeklyInputRoundTrips(){
        String original="1 09:00-12:00,13:00-18:00\n2 22:00-06:00";
        assertEquals(original,WorkConfigCodec.formatWeekly(WorkConfigCodec.parseWeekly(original)));
    }
}
