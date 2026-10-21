package com.greatwall.callring.application.calling

import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.domain.model.RawCallRecord

internal fun <T> callingChanges(
    value: T,
    changes: List<Pair<RawCallRecord?, RawCallRecord>>,
    profiles: Set<String>,
): MutationDelta<T> {
    val numbers =
        changes.flatMap { (before, after) -> listOfNotNull(before?.number, after.number) }.toSet()
    val keys = buildSet {
        if (changes.isNotEmpty()) add("calls")
        changes.forEach { (before, after) ->
            add("call:${after.id}")
            add("calls:${after.number}")
            before?.let { add("calls:${it.number}") }
        }
        if (profiles.isNotEmpty()) add("profiles")
        profiles.forEach { n ->
            add("profile:$n")
            add("profile:$n:exists")
            add("profile:$n:name")
            add("profile:$n:department")
            add("profile:$n:whitelisted")
        }
    }
    return MutationDelta(
        value,
        keys,
        ChangeImpact(
            buildMap {
                if (numbers.isNotEmpty()) {
                    put(DataDomain.CALLS, numbers)
                    put(DataDomain.REPORTS, numbers)
                }
                if (profiles.isNotEmpty()) put(DataDomain.CALLERS, profiles)
            },
        ),
    )
}
