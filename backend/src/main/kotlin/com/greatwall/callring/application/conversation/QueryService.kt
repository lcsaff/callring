package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.ports.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

class QueryService(
    sources: List<ScopedReadPort>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val revision: () -> Long = { 0L },
    private val redactor: () -> SecretRedactor = { SecretRedactor(emptyList()) },
) : AiQueryApi {
    private val sources =
        sources
            .flatMap { s -> s.domains.map { it to s } }
            .also { require(it.map { it.first }.distinct().size == it.size) }
            .toMap()

    private data class Cursor(
        val identity: String,
        val domain: AiDomain,
        val version: Long,
        val after: String?,
        val expires: Long,
        val pending: SourcePage? = null,
    )

    private data class Ref(
        val domain: AiDomain,
        val key: String,
        val field: String,
        val version: Long,
        val expires: Long,
    )

    private val sessionLock = Any()
    private val cursors = ConcurrentHashMap<String, Cursor>()
    private val refs = ConcurrentHashMap<String, Ref>()

    private data class Observation(val revision: Long, val owner: ReadAttempt?)

    private enum class AttemptState {
        ACTIVE,
        SUCCEEDED,
        FAILED,
    }

    private class ReadAttempt(val parent: ReadAttempt?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ReadAttempt>

        var state = AttemptState.ACTIVE
        val references = linkedSetOf<String>()
        val cursors = linkedSetOf<String>()
        val observations = linkedMapOf<Pair<AiDomain, String>, Observation?>()
    }

    private val ownerLocal = ThreadLocal<ReadAttempt?>()
    private val observations = ConcurrentHashMap<Pair<AiDomain, String>, Observation>()

    /** Read-only composition boundary. Stateful provider walks must never be replayed. */
    suspend fun <T> coherent(domains: Set<AiDomain>, block: suspend () -> T): T {
        require(AiDomain.D17 !in domains)
        val before = domains.associateWith { source(it).version(it) }
        val parent = currentCoroutineContext()[ReadAttempt]
        val attempt = ReadAttempt(parent)
        try {
            return withContext(attempt) {
                val value = block()
                if (before.any { (domain, version) -> source(domain).version(domain) != version })
                    fail("query_changed", ErrorCode.CONFLICT)
                synchronized(sessionLock) {
                    parent?.let { p ->
                        p.references += attempt.references
                        p.cursors += attempt.cursors
                        attempt.observations.forEach { (key, old) ->
                            if (!p.observations.containsKey(key)) p.observations[key] = old
                        }
                    }
                    attempt.state = AttemptState.SUCCEEDED
                    observations.entries.forEach { (key, observation) ->
                        if (observation.owner === attempt)
                            observations[key] = Observation(observation.revision, parent)
                    }
                    attempt.references.clear()
                    attempt.cursors.clear()
                    // A sibling can still hold this child's old observation. Keep its
                    // predecessor until the parent settles, so a failed parent can unwind it.
                    if (parent == null) attempt.observations.clear()
                }
                value
            }
        } catch (failure: Throwable) {
            synchronized(sessionLock) {
                attempt.state = AttemptState.FAILED
                attempt.references.forEach { refs.remove(it) }
                attempt.cursors.forEach { cursors.remove(it) }
                attempt.observations.forEach { (key, old) ->
                    if (observations[key]?.owner === attempt) {
                        val predecessor = survivingObservation(key, old)
                        if (predecessor == null) observations.remove(key)
                        else observations[key] = predecessor
                    }
                }
                attempt.references.clear()
                attempt.cursors.clear()
            }
            throw failure
        }
    }

    /** Called under sessionLock. Failed owners cannot become evidence through another rollback. */
    private fun survivingObservation(key: Pair<AiDomain, String>, old: Observation?): Observation? {
        var candidate = old
        while (candidate != null) {
            val owner = candidate.owner ?: return candidate
            var ancestor: ReadAttempt? = owner
            var failed = false
            while (ancestor != null) {
                if (ancestor.state == AttemptState.FAILED) {
                    failed = true
                    break
                }
                ancestor = ancestor.parent
            }
            candidate =
                when {
                    failed -> owner.observations[key]
                    owner.state == AttemptState.SUCCEEDED ->
                        Observation(candidate.revision, owner.parent)
                    else -> return candidate
                }
        }
        return null
    }

    private fun canonical(domain: AiDomain, key: String) =
        if (domain == AiDomain.D01) key else key.toLongOrNull()?.toString() ?: key

    fun observedRevision(domain: AiDomain, key: String) =
        observations[domain to canonical(domain, key)]?.revision

    fun clearObservations() {
        observations.clear()
    }

    internal fun snapshotObservations() = observations.mapValues { it.value.revision }

    internal fun restoreObservations(values: Map<Pair<AiDomain, String>, Long>) {
        observations.clear()
        observations.putAll(values.mapValues { Observation(it.value, null) })
    }

    private fun recordObservation(key: Pair<AiDomain, String>, at: Long) {
        val owner = ownerLocal.get()
        if (owner != null && !owner.observations.containsKey(key))
            owner.observations[key] = observations[key]
        observations[key] = Observation(at, owner)
    }

    private fun observe(domain: AiDomain, key: String, at: Long) {
        if (
            observations.size >= 4096 && !observations.containsKey(domain to canonical(domain, key))
        )
            fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
        recordObservation(domain to canonical(domain, key), at)
    }

    val sourceQueries = AtomicLong()
    val returnedRows = AtomicLong()

    private fun release(cursor: Cursor) {
        val source = source(cursor.domain)
        cursor.pending?.let { source.discard(it.rows) }
        if (cursor.domain == AiDomain.D17) cursor.after?.let { source.closeCursor(it) }
    }

    private fun pruneCursors() {
        cursors.entries.removeIf {
            if (it.value.expires < clock()) {
                release(it.value)
                true
            } else false
        }
    }

    override fun reset() {
        synchronized(sessionLock) {
            cursors.values.forEach { release(it) }
            cursors.clear()
            refs.clear()
            observations.clear()
            sources.values.distinct().forEach { it.reset() }
        }
    }

    private fun fail(code: String, error: ErrorCode = ErrorCode.VALIDATION): Nothing =
        throw BackendFailure(ApiError(error, "ai.$code"))

    private fun source(domain: AiDomain) =
        sources[domain] ?: fail("query_not_implemented", ErrorCode.INTERNAL)

    private fun stamp(domain: AiDomain, version: Long, s: ScopedReadPort, at: Long) =
        SourceStamp(domain, version, clock(), s.scope(domain), s.consistency(domain), at)

    private fun token(cursor: Cursor): String {
        pruneCursors()
        if (cursors.size >= 512) fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
        return UUID.randomUUID().toString().also {
            cursors[it] = cursor
            ownerLocal.get()?.cursors?.add(it)
        }
    }

    private fun cursor(value: String, identity: String, domain: AiDomain, version: Long): String {
        val c = cursors[value] ?: fail("cursor_stale", ErrorCode.CONFLICT)
        if (
            c.expires < clock() ||
                c.domain != domain ||
                c.version != version ||
                c.identity != identity
        )
            fail("cursor_stale", ErrorCode.CONFLICT)
        return c.after ?: fail("cursor_stale", ErrorCode.CONFLICT)
    }

    private suspend fun <T> result(block: () -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val attempt = currentCoroutineContext()[ReadAttempt]
            try {
                runInterruptible {
                    synchronized(sessionLock) {
                        val previous = ownerLocal.get()
                        ownerLocal.set(attempt)
                        try {
                            ApiResult.Success(block())
                        } finally {
                            ownerLocal.set(previous)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendFailure) {
                ApiResult.Failure(e.error)
            } catch (_: IllegalArgumentException) {
                ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "ai.query_invalid"))
            } catch (_: Exception) {
                ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "ai.query_source_failed"))
            }
        }

    private data class Projection(val value: AiJson.Obj, val references: Map<String, Ref>)

    private fun project(row: SourceRow, domain: AiDomain, version: Long): Projection {
        val pending = linkedMapOf<String, Ref>()
        val references =
            row.textFields.mapValues { (field, total) ->
                val handle = UUID.randomUUID().toString()
                pending[handle] = Ref(domain, row.key, field, version, clock() + 600000)
                aiObject("reference" to aiString(handle), "totalCodePoints" to aiNumber(total))
            }
        val value =
            redactor()
                .redact(
                    AiJson.Obj(
                        row.value.fields +
                            mapOf("entityKey" to aiString(row.key)) +
                            if (references.isEmpty()) emptyMap()
                            else mapOf("textFields" to AiJson.Obj(references)),
                    ),
                ) as AiJson.Obj
        return Projection(value, pending)
    }

    private fun publish(
        projections: List<Projection>,
        domain: AiDomain,
        keys: Set<String>,
        at: Long,
        next: Pair<String, Cursor>? = null,
        replacing: String? = null,
    ) {
        val now = clock()
        refs.entries.removeIf { it.value.expires < now }
        pruneCursors()
        val extra = projections.flatMap { it.references.entries }.associate { it.toPair() }
        val observedKeys = keys.map { domain to canonical(domain, it) }.toSet()
        if (refs.size + extra.size > 512 || observations.keys.union(observedKeys).size > 4096)
            fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
        if (next != null) {
            if (
                cursors.size -
                    (if (replacing != null && cursors.containsKey(replacing)) 1 else 0) >= 512
            )
                fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
            fun size(c: Cursor) =
                c.pending?.rows?.sumOf {
                    it.value.encode().toByteArray(Charsets.UTF_8).size.toLong()
                } ?: 0L
            val retained = cursors.entries.filter { it.key != replacing }.sumOf { size(it.value) }
            if (retained + size(next.second) > 4L * 1024 * 1024)
                fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
        }
        // No externally visible session changes until every capacity check succeeds.
        replacing?.let { cursors.remove(it) }
        next?.let {
            cursors[it.first] = it.second
            ownerLocal.get()?.cursors?.add(it.first)
        }
        refs.putAll(extra)
        ownerLocal.get()?.references?.addAll(extra.keys)
        observedKeys.forEach { recordObservation(it, at) }
    }

    override suspend fun query(spec: QuerySpec) = result {
        require(spec.limit in 1..200 && spec.maxBytes in 1..131072 && spec.projection.length <= 64)
        require(spec.filter.text?.length ?: 0 <= 4096)
        pruneCursors()
        val s = source(spec.domain)
        val before = s.version(spec.domain)
        val observed = revision()
        val identity = spec.copy(cursor = null, limit = 50, maxBytes = 32768).toString()
        val saved =
            spec.cursor?.let { key ->
                val c = cursors[key] ?: fail("cursor_stale", ErrorCode.CONFLICT)
                if (c.version != before && c.domain == spec.domain && c.domain == AiDomain.D17) {
                    release(c)
                    cursors.remove(key)
                    fail("cursor_stale", ErrorCode.CONFLICT)
                }
                if (
                    c.expires < clock() ||
                        c.domain != spec.domain ||
                        c.version != before ||
                        c.identity != identity
                )
                    fail("cursor_stale", ErrorCode.CONFLICT)
                c
            }
        var captured: SourcePage? = null
        try {
            if (saved?.pending != null) s.validateRead(spec.domain, saved.pending.rows)
            val page =
                saved?.pending
                    ?: run {
                        sourceQueries.incrementAndGet()
                        s.query(spec, saved?.after)
                    }
            captured = page
            if (s.version(spec.domain) != before) fail("cursor_stale", ErrorCode.CONFLICT)
            val items = mutableListOf<AiJson.Obj>()
            val projections = mutableListOf<Projection>()
            var bytes = 0
            for (row in page.rows) {
                if (items.size == spec.limit) break
                val projection = project(row, spec.domain, before)
                val item = projection.value
                val size = item.encode().toByteArray(Charsets.UTF_8).size
                if (bytes + size > spec.maxBytes) {
                    if (items.isEmpty()) fail("item_too_large", ErrorCode.SYSTEM_RESTRICTED)
                    break
                }
                projections += projection
                items += item
                bytes += size
            }
            val tail = page.rows.drop(items.size)
            val complete = page.complete && page.nextKey == null && tail.isEmpty()
            val stateful = spec.domain == AiDomain.D17
            val nextCursor =
                if (tail.isEmpty() && page.nextKey == null) null
                else {
                    val after =
                        if (stateful || tail.isEmpty()) page.nextKey
                        else page.rows[items.size - 1].cursorKey
                    val pending =
                        if (stateful && tail.isNotEmpty()) page.copy(rows = tail) else null
                    UUID.randomUUID().toString() to
                        Cursor(identity, spec.domain, before, after, clock() + 600000, pending)
                }
            val observedKeys = page.rows.take(items.size).mapTo(linkedSetOf()) { it.key }
            if (spec.filter == QueryFilter()) observedKeys += "*"
            if (complete && page.matchedTotal == 0L) spec.filter.number?.let { observedKeys += it }
            publish(
                projections,
                spec.domain,
                observedKeys,
                observed,
                nextCursor,
                if (stateful) spec.cursor else null,
            )
            s.delivered(page.rows.take(items.size))
            returnedRows.addAndGet(items.size.toLong())
            val next = nextCursor?.first
            ScopedPage(
                items,
                stamp(spec.domain, before, s, observed)
                    .copy(projection = spec.projection, filter = spec.filter),
                page.matchedTotal,
                next,
                complete,
                if (
                    page.status in
                        setOf(
                            QueryStatus.NOT_CONFIGURED,
                            QueryStatus.DENIED,
                            QueryStatus.UNAVAILABLE,
                        )
                )
                    page.status
                else if (complete && items.isEmpty() && page.status == QueryStatus.COMPLETE)
                    QueryStatus.EMPTY
                else if (!complete) QueryStatus.PARTIAL else page.status,
                page.reason,
            )
        } catch (failure: Throwable) {
            if (spec.domain == AiDomain.D17) {
                val page = captured ?: saved?.pending
                page?.let { s.discard(it.rows) }
                (page?.nextKey ?: saved?.after)?.let { s.closeCursor(it) }
                spec.cursor?.let { cursors.remove(it) }
            }
            throw failure
        }
    }

    override suspend fun get(domain: AiDomain, key: String, projection: String) = result {
        require(key.isNotBlank() && key.length <= 1024)
        val s = source(domain)
        val before = s.version(domain)
        val observed = revision()
        sourceQueries.incrementAndGet()
        val row = s.get(domain, key, projection) ?: fail("not_found", ErrorCode.NOT_FOUND)
        try {
            if (s.version(domain) != before) fail("cursor_stale", ErrorCode.CONFLICT)
            val projected = project(row, domain, before)
            val value = projected.value
            if (value.encode().toByteArray(Charsets.UTF_8).size > 131072)
                fail("item_too_large", ErrorCode.SYSTEM_RESTRICTED)
            publish(listOf(projected), domain, setOf(key, row.key), observed)
            s.delivered(listOf(row))
            ScopedValue(value, stamp(domain, before, s, observed).copy(projection = projection))
        } catch (failure: Throwable) {
            s.discard(listOf(row))
            throw failure
        }
    }

    override suspend fun aggregate(spec: AggregateSpec) = result {
        require(spec.metric in setOf("count", "sumDuration", "score", "reportTotals"))
        require(
            spec.groupBy == null || spec.groupBy in setOf("number", "status", "direction", "year"),
        )
        val s = source(spec.domain)
        val before = s.version(spec.domain)
        val observed = revision()
        sourceQueries.incrementAndGet()
        val value = s.aggregate(spec)
        if (s.version(spec.domain) != before) fail("aggregate_incomplete", ErrorCode.CONFLICT)
        if (value.encode().toByteArray(Charsets.UTF_8).size > 131072)
            fail("aggregate_incomplete", ErrorCode.SYSTEM_RESTRICTED)
        observe(spec.domain, spec.filter.number ?: "*", observed)
        ScopedAggregate(
            redactor().redact(value),
            stamp(spec.domain, before, s, observed).copy(filter = spec.filter),
        )
    }

    override suspend fun readText(reference: String, cursor: String?, maxChars: Int) = result {
        require(maxChars in 1..16384)
        val r = refs[reference] ?: fail("text_reference_stale", ErrorCode.NOT_FOUND)
        val s = source(r.domain)
        val v = s.version(r.domain)
        val observed = revision()
        if (v != r.version || r.expires < clock()) fail("cursor_stale", ErrorCode.CONFLICT)
        val start = cursor?.let { cursor(it, reference, r.domain, v).toLong() } ?: 0L
        val sanitizer = redactor()
        val overlap = sanitizer.overlapCodePoints.toLong()
        val expandedStart = maxOf(0L, start - overlap)
        val requested = maxChars.toLong() + (start - expandedStart) + overlap
        if (requested > 131072) fail("capacity_exceeded", ErrorCode.SYSTEM_RESTRICTED)
        sourceQueries.incrementAndGet()
        val chunk = s.text(r.domain, r.key, r.field, expandedStart, requested.toInt())
        if (s.version(r.domain) != v) fail("cursor_stale", ErrorCode.CONFLICT)
        require(
            chunk.start == expandedStart &&
                chunk.end >= start &&
                chunk.end <= chunk.total &&
                chunk.text.codePointCount(0, chunk.text.length) <= requested,
        )
        val end = minOf(start + maxChars, chunk.total)
        val complete = end == chunk.total
        TextChunk(
            reference,
            stamp(r.domain, v, s, observed),
            start,
            end,
            chunk.total,
            if (complete) null
            else token(Cursor(reference, r.domain, v, end.toString(), clock() + 600000)),
            complete,
            sanitizer.textRange(
                chunk.text,
                (start - expandedStart).toInt(),
                (end - expandedStart).toInt(),
            ),
        )
    }
}
