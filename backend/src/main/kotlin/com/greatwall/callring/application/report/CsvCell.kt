package com.greatwall.callring.application.report

internal fun csvCell(value: String): String {
    val first = value.trimStart().firstOrNull()
    val safe =
        if (first in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r'))
            "'$value"
        else value
    return "\"${safe.replace("\"","\"\"")}\""
}
