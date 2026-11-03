package com.greatwall.callring.domain.calling

object CallerEnrichmentPolicy {
    fun apply(current: String, disputed: Boolean, revision: Long, expectedRevision: Long,
              candidate: String, evidence: String): String? {
        val next = candidate.trim()
        if (revision != expectedRevision || next.isEmpty() || evidence.isBlank()) return null
        if (current.isNotBlank() && !disputed) return null
        return next.takeIf { it != current.trim() }
    }
}
