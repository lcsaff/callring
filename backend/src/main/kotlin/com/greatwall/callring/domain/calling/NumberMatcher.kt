package com.greatwall.callring.domain.calling

data class NumberRule(val prefix: String, val length: Int) {
    init {
        require(prefix.all { it in '0'..'9' }) { "Prefix must contain only ASCII digits" }
        require(length in 1..30) { "Length must be between 1 and 30" }
        require(prefix.length <= length) { "Prefix must not exceed number length" }
    }

    companion object {
        val DEFAULT = NumberRule("3", 5)
    }
}

fun interface NumberMatcher {
    fun matches(normalized: String, rule: NumberRule): Boolean
}

object PrefixLengthNumberMatcher : NumberMatcher {
    override fun matches(normalized: String, rule: NumberRule): Boolean =
        normalized.length == rule.length &&
            normalized.all { it in '0'..'9' } &&
            normalized.startsWith(rule.prefix)
}

/** Immutable input and first assessment facts carried across one call. */
data class NumberRuleSnapshot(val rule: NumberRule, val revision: Long)
data class NumberMatchFact(val revision: Long, val matched: Boolean)
