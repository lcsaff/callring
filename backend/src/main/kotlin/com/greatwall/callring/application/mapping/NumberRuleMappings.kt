package com.greatwall.callring.application.mapping

import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import com.greatwall.callring.domain.calling.NumberMatcher
import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.model.NumberIdentity

fun NumberRuleSettings.toSnapshot(): NumberRuleSnapshot {
    require(revision >= 0)
    return NumberRuleSnapshot(NumberRule(prefix, length), revision)
}
fun checkNumber(raw: String, snapshot: NumberRuleSnapshot, matcher: NumberMatcher): NumberCheck {
    val identity = NumberIdentity.parse(raw)
    return NumberCheck(identity.normalized, identity.validProfileNumber, matcher.matches(identity.normalized, snapshot.rule))
}
