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
class NumberRuleSnapshot(rules: List<NumberRule>, val revision: Long) {
    val rules: List<NumberRule> = java.util.Collections.unmodifiableList(ArrayList(rules))

    init { require(revision >= 0) }

    fun matches(normalized: String, matcher: NumberMatcher): Boolean =
        rules.any { matcher.matches(normalized, it) }

    override fun equals(other: Any?): Boolean =
        other is NumberRuleSnapshot && rules == other.rules && revision == other.revision
    override fun hashCode(): Int = 31 * rules.hashCode() + revision.hashCode()
    override fun toString(): String = "NumberRuleSnapshot(rules=$rules, revision=$revision)"
}
data class NumberMatchFact(val revision: Long, val matched: Boolean)
