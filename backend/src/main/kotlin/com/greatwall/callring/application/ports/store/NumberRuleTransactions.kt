package com.greatwall.callring.application.ports.store

import com.greatwall.callring.contracts.settings.NumberRuleSettings

interface NumberRuleReader { fun numberRule(): NumberRuleSettings }
interface NumberRuleWriter : NumberRuleReader { fun saveNumberRule(value: NumberRuleSettings) }
interface NumberRuleTransactions {
    fun <T> readNumberRule(block: (NumberRuleReader) -> T): T
    fun <T> writeNumberRule(block: (NumberRuleWriter) -> T): T
}
