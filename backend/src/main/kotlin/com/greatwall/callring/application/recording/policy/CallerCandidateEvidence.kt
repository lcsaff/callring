package com.greatwall.callring.application.recording.policy

import com.greatwall.callring.contracts.recording.CallArchive

internal fun CallArchive.validatedCallerCandidates(): Map<String, Pair<String, String>> {
    val result = analysis ?: return emptyMap()
    val claims = mapOf("name" to result.name, "department" to result.department,
        "selfReportedPosition" to result.position, "region" to result.region)
    return claims.mapNotNull { (field, claim) ->
        val quote = result.fieldEvidence[field].orEmpty()
        if (claim.isNotBlank() && claim.length <= 500 && quote.isNotBlank() && quote.length <= 4000 &&
            transcript.contains(quote) && quote.contains(claim)) field to (claim.trim() to quote) else null
    }.toMap()
}
