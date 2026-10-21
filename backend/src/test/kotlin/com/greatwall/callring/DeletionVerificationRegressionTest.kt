package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.ReceiptVerifier
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeletionVerificationRegressionTest {
    private suspend fun verify(residual: AiDomain? = null, partial: Boolean = false, error: ApiError? = null, status: QueryStatus = QueryStatus.COMPLETE): ToolReceipt {
        val q = QueryService(listOf(object : ScopedReadPort {
            override val domains = AiDomain.entries.toSet()
            override fun version(domain: AiDomain) = 0L
            override fun get(domain: AiDomain, key: String, projection: String): SourceRow? = null
            override fun query(spec: QuerySpec, after: String?): SourcePage {
                assertEquals("31234", spec.filter.number)
                val rows = if (spec.domain == residual) listOf(SourceRow("1", aiObject("number" to aiString("31234")))) else emptyList()
                return SourcePage(rows, rows.size.toLong(), complete = !partial, status = status)
            }
        }))
        return ReceiptVerifier(q).verify(OperationId.CALLERS_DELETE_NUMBER,
            ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234"))),
            ToolReceipt("op", "c", ToolStatus.COMMITTED, error = error, commitRevision = RevisionVector("test", emptyMap())))
    }

    @Test fun residualCallsArchivesOrBindingsMustNotBeVerified() = runBlocking {
        for (domain in listOf(AiDomain.D03, AiDomain.D16, AiDomain.D18))
            assertEquals(domain.name, VerificationStatus.COMMITTED_UNVERIFIED, verify(domain).verification)
    }
    @Test fun completeAbsenceIsVerified() = runBlocking { assertEquals(VerificationStatus.VERIFIED, verify().verification) }
    @Test fun partialReadbackMustNotBeVerified() = runBlocking { assertEquals(VerificationStatus.COMMITTED_UNVERIFIED, verify(partial = true).verification) }
    @Test fun unavailableSourcesAreNotEvidenceOfAbsence() = runBlocking {
        for (status in listOf(QueryStatus.UNAVAILABLE, QueryStatus.DENIED, QueryStatus.NOT_CONFIGURED))
            assertEquals(status.name, VerificationStatus.COMMITTED_UNVERIFIED, verify(status = status).verification)
    }
    @Test fun existingOperationFailureMustNotBeReportedAsVerified() = runBlocking {
        val error = ApiError(ErrorCode.IO_FAILURE, "cleanup.failed")
        val result = verify(error = error)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED, result.verification)
        assertEquals(error, result.error)
    }
}
