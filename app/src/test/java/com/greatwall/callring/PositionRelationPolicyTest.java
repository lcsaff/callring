package com.greatwall.callring;

import org.junit.Test;
import static org.junit.Assert.*;

public class PositionRelationPolicyTest {
    @Test public void smallerPositiveLevelShowsUpArrow() { assertEquals("↑", PositionRelationPolicy.compare(1, 3)); }
    @Test public void largerPositiveLevelShowsDownArrow() { assertEquals("↓", PositionRelationPolicy.compare(4, 3)); }
    @Test public void equalPositiveLevelsShowFullwidthEquals() { assertEquals("＝", PositionRelationPolicy.compare(3, 3)); }
    @Test public void missingOrInvalidLevelsShowEmDash() {
        assertEquals("—", PositionRelationPolicy.compare(null, 3));
        assertEquals("—", PositionRelationPolicy.compare(3, null));
        assertEquals("—", PositionRelationPolicy.compare(0, 3));
        assertEquals("—", PositionRelationPolicy.compare(3, -1));
    }
}
