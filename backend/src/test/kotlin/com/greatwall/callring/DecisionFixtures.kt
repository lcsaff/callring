package com.greatwall.callring

import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.model.DecisionInputs

internal fun BackendState.decisionInputs() =
    DecisionInputs(
        schedules,
        calendarYears,
        holidays,
        makeupDates,
        overrides,
        leaves,
        leaveVersion,
        profiles.filterValues { it.whitelisted }.keys,
        quiet,
        statisticsIntervals,
        overlayEnabled,
    )
