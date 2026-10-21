package com.greatwall.callring.domain.model


fun sameRecordingNumber(raw: String, normalized: String): Boolean = raw == normalized ||
    raw.filter(Char::isDigit).let { it.isNotEmpty() && it == normalized.filter(Char::isDigit) }
