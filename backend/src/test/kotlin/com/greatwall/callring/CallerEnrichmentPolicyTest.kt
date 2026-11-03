package com.greatwall.callring

import com.greatwall.callring.domain.calling.CallerEnrichmentPolicy
import org.junit.Assert.*
import org.junit.Test

class CallerEnrichmentPolicyTest {
    @Test fun fillsOnlyEligibleFields() {
        assertEquals("杭州", CallerEnrichmentPolicy.apply("", false, 0, 0, "杭州", "我在杭州工作"))
        assertNull(CallerEnrichmentPolicy.apply("上海", false, 0, 0, "杭州", "我在杭州工作"))
        assertEquals("杭州", CallerEnrichmentPolicy.apply("上海", true, 1, 1, "杭州", "我在杭州工作"))
        assertNull(CallerEnrichmentPolicy.apply("上海", true, 2, 1, "杭州", "我在杭州工作"))
        assertNull(CallerEnrichmentPolicy.apply("上海", true, 1, 1, "上海", "我在上海工作"))
        assertNull(CallerEnrichmentPolicy.apply("", false, 0, 0, "杭州", ""))
    }
}
