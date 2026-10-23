package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.ReceiptVerifier
import com.greatwall.callring.application.conversation.execution.b
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.conversation.query.ScopedToolReader
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.application.ports.query.SourceText

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuerySnapshotConsistencyTest {
    private fun observationQueries() =
        QueryService(
            listOf(
                object : ScopedReadPort {
                    override val domains = setOf(AiDomain.D01)

                    override fun version(domain: AiDomain) = 0L

                    override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)

                    override fun get(domain: AiDomain, key: String, projection: String) =
                        SourceRow(key, aiObject())
                },
            ),
            revision = { 7L },
        )

    @Test
    fun overlappingFailedAttemptsNeverRestoreAnotherFailedObservation() = runBlocking {
        for (seed in listOf(false, true)) {
            val query = observationQueries()
            if (seed) query.get(AiDomain.D01, "31234").requiredValue()
            val aEntered = CompletableDeferred<Unit>()
            val bEntered = CompletableDeferred<Unit>()
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()
            fun CoroutineScope.attempt(
                entered: CompletableDeferred<Unit>,
                release: CompletableDeferred<Unit>,
            ) = async {
                runCatching {
                    query.coherent(setOf(AiDomain.D01)) {
                        query.get(AiDomain.D01, "31234").requiredValue()
                        entered.complete(Unit)
                        release.await()
                        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "test.failed_attempt"))
                    }
                }
            }
            val a = attempt(aEntered, aRelease)
            aEntered.await()
            val b = attempt(bEntered, bRelease)
            bEntered.await()
            aRelease.complete(Unit)
            assertTrue(a.await().isFailure)
            bRelease.complete(Unit)
            assertTrue(b.await().isFailure)
            assertEquals(if (seed) 7L else null, query.observedRevision(AiDomain.D01, "31234"))
        }
    }

    @Test
    fun failedParentRemovesSuccessfulOverlappingChildObservations() = runBlocking {
        for (seed in listOf(false, true)) {
            val query = observationQueries()
            if (seed) query.get(AiDomain.D01, "31234").requiredValue()
            val failure = runCatching {
                query.coherent(setOf(AiDomain.D01)) {
                    coroutineScope {
                        val entered = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        val first = async {
                            query.coherent(setOf(AiDomain.D01)) {
                                query.get(AiDomain.D01, "31234").requiredValue()
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                        entered.await()
                        query.coherent(setOf(AiDomain.D01)) {
                            query.get(AiDomain.D01, "31234").requiredValue()
                        }
                        release.complete(Unit)
                        first.await()
                    }
                    throw BackendFailure(ApiError(ErrorCode.CONFLICT, "test.failed_parent"))
                }
            }
            assertTrue(failure.isFailure)
            assertEquals(if (seed) 7L else null, query.observedRevision(AiDomain.D01, "31234"))
        }
    }

    @Test
    fun failedAttemptPreservesAnotherConcurrentSuccessfulRead() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D01)

                override fun version(domain: AiDomain) = 0L

                override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)

                override fun get(domain: AiDomain, key: String, projection: String) =
                    SourceRow(key, aiObject("name" to AiJson.Null), mapOf("name" to 3L))

                override fun text(
                    domain: AiDomain,
                    key: String,
                    field: String,
                    start: Long,
                    maxChars: Int,
                ) = SourceText("abc", 0, 3, 3)
            }
        val query = QueryService(listOf(source))
        fun reference(value: ScopedValue) =
            (((value.value["textFields"] as AiJson.Obj)["name"] as AiJson.Obj)["reference"])
                .string()
        var rejected = ""
        val failed = async {
            runCatching {
                query.coherent(setOf(AiDomain.D01)) {
                    rejected = reference(query.get(AiDomain.D01, "31234").requiredValue())
                    entered.complete(Unit)
                    release.await()
                    throw BackendFailure(ApiError(ErrorCode.CONFLICT, "test.concurrent"))
                }
            }
        }
        entered.await()
        val accepted = reference(query.get(AiDomain.D01, "31234").requiredValue())
        release.complete(Unit)
        assertTrue(failed.await().exceptionOrNull() is BackendFailure)
        assertNotNull(query.observedRevision(AiDomain.D01, "31234"))
        assertTrue(query.readText(rejected) is ApiResult.Failure)
        assertEquals("abc", query.readText(accepted).requiredValue().text)
    }

    @Test
    fun repeatedCombinationConflictDoesNotConsumeTextReferenceCapacity() = runBlocking {
        var generation = 0L
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D01)

                override fun version(domain: AiDomain) = generation

                override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)

                override fun get(domain: AiDomain, key: String, projection: String) =
                    SourceRow(key, aiObject("name" to AiJson.Null), mapOf("name" to 10000L))
            }
        val query = QueryService(listOf(source))
        repeat(513) {
            val failure = runCatching {
                query.coherent(setOf(AiDomain.D01)) {
                    query.get(AiDomain.D01, "31234").let {
                        if (it is com.greatwall.callring.contracts.common.ApiResult.Failure)
                            throw BackendFailure(it.error)
                    }
                    generation++
                }
            }
            assertTrue(failure.exceptionOrNull() is BackendFailure)
        }
        assertTrue(
            query.get(AiDomain.D01, "31234")
                is com.greatwall.callring.contracts.common.ApiResult.Success,
        )
    }

    @Test
    fun reportCombinationRejectsOldZoneWithNewFactsAndRestoresObservations() = runBlocking {
        var generation = 1L
        var zoneRead = false
        var changed = false
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D04, AiDomain.D05, AiDomain.D06)

                override fun version(domain: AiDomain): Long {
                    if (domain == AiDomain.D05 && zoneRead && !changed) {
                        generation++
                        changed = true
                    }
                    return generation
                }

                override fun query(spec: QuerySpec, after: String?): SourcePage {
                    if (spec.domain == AiDomain.D06) {
                        zoneRead = true
                        return SourcePage(
                            listOf(
                                SourceRow(
                                    "zone",
                                    aiObject("snapshot" to aiObject("zone" to aiString("UTC"))),
                                ),
                            ),
                            1,
                        )
                    }
                    return SourcePage(emptyList(), 0)
                }

                override fun aggregate(spec: AggregateSpec) =
                    aiObject("generation" to aiNumber(generation))
            }
        val query = QueryService(listOf(source))
        val result = runCatching {
            ScopedToolReader(query,com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher)
                .read(
                    OperationId.REPORTS_LOAD,
                    aiObject(
                        "number" to aiString("31234"),
                        "range" to
                            aiObject(
                                "startDate" to aiString("2026-01-01"),
                                "endDateInclusive" to aiString("2026-01-02"),
                            ),
                    ),
                )
        }
        assertTrue(result.exceptionOrNull() is BackendFailure)
        assertTrue(query.snapshotObservations().isEmpty())
    }

    @Test
    fun adoptionReadbackRejectsAnArchiveChangedBetweenArchiveAndProfileReads() = runBlocking {
        var archiveVersion = 0L
        var archiveRead = false
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D01, AiDomain.D16)

                override fun version(domain: AiDomain): Long {
                    if (domain == AiDomain.D01 && archiveRead) archiveVersion = 1
                    return if (domain == AiDomain.D16) archiveVersion else 0
                }

                override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)

                override fun get(domain: AiDomain, key: String, projection: String): SourceRow {
                    val value =
                        if (domain == AiDomain.D16) {
                            archiveRead = true
                            aiObject(
                                "number" to aiString("31234"),
                                "generation" to aiNumber(1),
                                "analysis" to
                                    aiObject(
                                        "name" to aiString("name"),
                                        "organization" to aiString("unit"),
                                    ),
                            )
                        } else
                            aiObject("name" to aiString("name"), "department" to aiString("unit"))
                    return SourceRow(key, value)
                }
            }
        val query = QueryService(listOf(source))
        val receipt =
            ReceiptVerifier(query)
                .verify(
                    OperationId.RECORDINGS_ADOPT_IDENTITY,
                    ConversationToolCall("c", "recording_adopt", aiObject("callId" to aiNumber(1))),
                    ToolReceipt(
                        "op",
                        "c",
                        ToolStatus.COMMITTED,
                        commitRevision = RevisionVector("test", emptyMap()),
                    ),
                )
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED, receipt.verification)
        assertEquals(2L, query.sourceQueries.get())
    }

    @Test
    fun deletionReadbackCannotVerifyAnAbsenceBeforeProfileRecreation() = runBlocking {
        var profileVersion = 0L
        var absentRead = false
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D01, AiDomain.D03, AiDomain.D16, AiDomain.D18)

                override fun version(domain: AiDomain): Long {
                    if (domain == AiDomain.D03 && absentRead) profileVersion = 1
                    return if (domain == AiDomain.D01) profileVersion else 0
                }

                override fun get(domain: AiDomain, key: String, projection: String): SourceRow? {
                    absentRead = true
                    return null
                }

                override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)
            }
        val query = QueryService(listOf(source))
        val receipt =
            ReceiptVerifier(query)
                .verify(
                    OperationId.CALLERS_DELETE_NUMBER,
                    ConversationToolCall(
                        "c",
                        "number_delete",
                        aiObject("number" to aiString("31234")),
                    ),
                    ToolReceipt(
                        "op",
                        "c",
                        ToolStatus.COMMITTED,
                        commitRevision = RevisionVector("test", emptyMap()),
                    ),
                )
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED, receipt.verification)
        assertEquals(ToolStatus.COMMITTED, receipt.status)
        assertTrue(query.snapshotObservations().isEmpty())
    }
}
