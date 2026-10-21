package com.greatwall.callring.application.ports.platform

import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.domain.model.CalendarImport
import java.io.InputStream
import java.io.OutputStream

fun interface CalendarDocumentReader { fun readCalendar(source: DocumentRef): CalendarImport }

interface DocumentStore : CalendarDocumentReader {
    fun <T> read(source: DocumentRef, block: (InputStream) -> T): T

    fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T

    /** Parse UTF-8 JSON with a hard 1 MiB limit; reject syntax errors. Core validates semantics. */
    override fun readCalendar(source: DocumentRef): CalendarImport
}
