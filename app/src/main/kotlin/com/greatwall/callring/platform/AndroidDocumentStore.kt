package com.greatwall.callring.platform

import com.greatwall.callring.data.codec.StrictJson

import android.content.ContentResolver
import android.net.Uri
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.data.WorkConfigCodec
import com.greatwall.callring.domain.CalendarImport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * ContentResolver enforces the caller's current grant; no filesystem paths or broad grants are
 * used.
 */
class AndroidDocumentStore(private val resolver: ContentResolver) : DocumentStore {
    private fun uri(reference: DocumentRef): Uri {
        try {
            validateDocumentUri(reference.uri)
        } catch (_: IllegalArgumentException) {
            throw BackendFailure(ApiError(ErrorCode.VALIDATION, "document.invalid_uri"))
        }
        return Uri.parse(reference.uri)
    }

    override fun <T> read(source: DocumentRef, block: (InputStream) -> T): T = permissionChecked {
        (resolver.openInputStream(uri(source)) ?: throw java.io.IOException("document.open_failed"))
            .use(block)
    }

    override fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T =
        permissionChecked {
            (resolver.openOutputStream(uri(destination), "wt")
                    ?: throw java.io.IOException("document.open_failed"))
                .use(block)
        }

    private fun <T> permissionChecked(block: () -> T): T =
        try {
            block()
        } catch (_: SecurityException) {
            throw BackendFailure(
                ApiError(ErrorCode.PERMISSION_REQUIRED, "document.permission_required")
            )
        }

    override fun readCalendar(source: DocumentRef): CalendarImport =
        read(source) { input ->
            val bytes = ByteArrayOutputStream()
            try {
                copyBounded(input, bytes, 1024 * 1024L)
            } catch (_: DocumentLimitExceeded) {
                throw BackendFailure(ApiError(ErrorCode.VALIDATION, "calendar.too_large"))
            }
            try {
                val text =
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                        .toString()
                // JSONTokener's parser is deliberately permissive on Android. Validate grammar
                // first.
                StrictJson.validate(text)
                WorkConfigCodec.calendar(text)
            } catch (failure: BackendFailure) {
                throw failure
            } catch (_: Exception) {
                throw BackendFailure(ApiError(ErrorCode.VALIDATION, "calendar.invalid_json"))
            }
        }
}
