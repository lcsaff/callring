package com.greatwall.callring.application

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun <T> BackendContext.paged(rows: List<T>, p: PageRequest, meta: SnapshotMeta): Page<T> {
    val offset = p.pageIndex.toLong() * p.pageSize
    val result =
        if (offset >= rows.size) emptyList()
        else rows.subList(offset.toInt(), minOf(rows.size.toLong(), offset + p.pageSize).toInt())
    return Page(frozenList(result), p.pageIndex, p.pageSize, rows.size.toLong(), meta)
}

internal fun csvCell(value: String): String {
    val first = value.trimStart().firstOrNull()
    val safe =
        if (first in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r'))
            "'$value"
        else value
    return "\"${safe.replace("\"","\"\"")}\""
}
