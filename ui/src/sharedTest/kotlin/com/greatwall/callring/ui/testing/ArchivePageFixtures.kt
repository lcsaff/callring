package com.greatwall.callring.ui.testing

import com.greatwall.callring.contracts.recording.*

fun CallArchive.listItemForTest() = ArchiveListItem(callId, generation, number, startedAtEpochMillis,
    status, source, durationMillis, audioPath != null, analysis?.summary.orEmpty())
fun archivePageForTest(vararg archives: CallArchive) = ArchiveListPage(
    archives.sortedWith(compareByDescending<CallArchive> { it.startedAtEpochMillis }.thenBy { it.callId })
        .map { it.listItemForTest() }, null, true)
