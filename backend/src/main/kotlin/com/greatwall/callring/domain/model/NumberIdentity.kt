package com.greatwall.callring.domain.model

/** Number normalization shared by profile validation and incoming decisions. */
data class NumberIdentity(val normalized: String, val validProfileNumber: Boolean, val matchesRule: Boolean) {
    companion object {
        fun parse(raw: String): NumberIdentity {
            val normalized = raw.filterNot {
                it == '-' || it == '(' || it == ')' || it.isWhitespace() || Character.isSpaceChar(it)
            }
            return NumberIdentity(normalized, normalized.matches(Regex("\\+?[0-9]{1,30}")), normalized.matches(Regex("3[0-9]{4}")))
        }
    }
}
