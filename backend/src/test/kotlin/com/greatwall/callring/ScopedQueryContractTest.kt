package com.greatwall.callring

import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopedQueryContractTest {
    private class Source : ScopedReadPort {
        override val domains = setOf(AiDomain.D01)
        var version = 1L
        var reads = 0

        override fun version(domain: AiDomain) = version

        override fun query(spec: QuerySpec, after: String?): SourcePage {
            reads++
            val start = after?.toInt() ?: 0
            val rows =
                (start until minOf(start + spec.limit, 3)).map {
                    SourceRow((it + 1).toString(), aiObject("id" to aiNumber(it + 1L)))
                }
            return SourcePage(rows, 3, if (start + rows.size < 3) rows.last().key else null)
        }
    }

    @Test
    fun rejectedOversizedTextRowsDoNotAllocateSessionReferences() = runBlocking {
        val source =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D01)

                override fun version(domain: AiDomain) = 0L

                override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)

                override fun get(domain: AiDomain, key: String, projection: String) =
                    SourceRow(
                        key,
                        aiObject(
                            "name" to aiString(if (key == "large") "a".repeat(131073) else "ok"),
                        ),
                        mapOf("name" to 200000L),
                    )
            }
        val api = QueryService(listOf(source))
        repeat(513) { assertTrue(api.get(AiDomain.D01, "large") is ApiResult.Failure) }
        api.get(AiDomain.D01, "small").requiredValue()
        Unit
    }

    @Test
    fun scopedPagesProveEndAndRejectStaleOrForeignCursor() = runBlocking {
        val source = Source()
        val api = QueryService(listOf(source))
        val first = api.query(QuerySpec(AiDomain.D01, limit = 2)).requiredValue()
        assertEquals(3L, first.matchedTotal)
        assertFalse(first.complete)
        assertNotNull(first.nextCursor)
        val end =
            api.query(QuerySpec(AiDomain.D01, limit = 2, cursor = first.nextCursor)).requiredValue()
        assertTrue(end.complete)
        assertEquals(1, end.items.size)
        source.version++
        assertEquals(
            "ai.cursor_stale",
            (api.query(QuerySpec(AiDomain.D01, limit = 2, cursor = first.nextCursor))
                    as ApiResult.Failure)
                .error
                .message,
        )
        assertEquals(2, source.reads)
    }

    @Test
    fun sourceFailureCannotBecomeEmptyOrUnavailableAndBytesCannotSilentlyTruncate() = runBlocking {
        val source = Source()
        val api = QueryService(listOf(source))
        assertTrue(api.query(QuerySpec(AiDomain.D01, limit = 201)) is ApiResult.Failure)
        assertEquals(0, source.reads)
        val result = api.query(QuerySpec(AiDomain.D01, limit = 1, maxBytes = 1))
        assertTrue(result is ApiResult.Failure)
        assertTrue(api.query(QuerySpec(AiDomain.D02)) is ApiResult.Failure)
    }

    @Test
    fun observationsKeepTheRevisionOfTheActualReadInsteadOfRefreshingBeforeCommit() = runBlocking {
        val source = Source()
        var revision = 20L
        val api = QueryService(listOf(source), revision = { revision })
        api.query(QuerySpec(AiDomain.D01, limit = 1)).requiredValue()
        revision = 30L
        assertEquals(20L, api.observedRevision(AiDomain.D01, "1"))
    }
}
