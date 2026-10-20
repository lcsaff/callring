package com.greatwall.callring.platform.ai

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScopedDirectoryTest {
    @Test
    fun slowlyConsumedPendingTailCannotOutliveItsResourceLease() = runBlocking {
        var now = 0L
        val clock =
            object : java.time.Clock() {
                override fun getZone() = java.time.ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId) = this

                override fun instant() = java.time.Instant.ofEpochMilli(now)
            }
        val resources = ForegroundResourceRegistry(clock)
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val provider =
            MatrixCursor(columns).apply {
                repeat(3) {
                    addRow(
                        arrayOf<Any>("file$it", "a".repeat(512) + "$it.wav", "audio/wav", 1L, 1L),
                    )
                }
            }
        val physical =
            DirectoryScopedReadSource(
                { "content://scoped-tree/tree/root" },
                { 0L },
                resources,
                { true },
                { _, _ -> provider },
                { _, _ -> null },
            )
        var captured: com.greatwall.callring.application.ports.SourcePage? = null
        val source =
            object : com.greatwall.callring.application.ports.ScopedReadPort by physical {
                override fun query(spec: QuerySpec, after: String?) =
                    physical.query(spec, after).also { captured = it }
            }
        val query = QueryService(listOf(source), clock = clock::millis)
        val first = query.query(QuerySpec(AiDomain.D17, limit = 3, maxBytes = 800)).requiredValue()
        val firstHandle = first.items.single()["handle"].string()
        now = 6 * 60000L
        val second =
            query
                .query(
                    QuerySpec(AiDomain.D17, limit = 3, maxBytes = 800, cursor = first.nextCursor),
                )
                .requiredValue()
        assertNotNull(resources.lookup(second.items.single()["handle"].string()))
        now = 12 * 60000L
        val third =
            query.query(
                QuerySpec(AiDomain.D17, limit = 3, maxBytes = 800, cursor = second.nextCursor),
            )
        assertTrue(third is ApiResult.Failure)
        assertEquals(ErrorCode.CONFLICT, (third as ApiResult.Failure).error.code)
        assertTrue(provider.isClosed)
        assertTrue(runCatching { resources.lookup(captured!!.rows.last().key) }.isFailure)
        assertTrue(runCatching { resources.lookup(firstHandle) }.isFailure)
    }

    @Test
    fun rejectedFirstRowClosesWalkAndReleasesUnpublishedHandles() = runBlocking {
        val resources = ForegroundResourceRegistry()
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val provider =
            MatrixCursor(columns).apply {
                repeat(3) {
                    addRow(
                        arrayOf<Any>("file$it", "a".repeat(512) + "$it.wav", "audio/wav", 1L, 1L),
                    )
                }
            }
        val physical =
            DirectoryScopedReadSource(
                { "content://scoped-tree/tree/root" },
                { 0L },
                resources,
                { true },
                { _, _ -> provider },
                { _, _ -> null },
            )
        var captured: com.greatwall.callring.application.ports.SourcePage? = null
        val source =
            object : com.greatwall.callring.application.ports.ScopedReadPort by physical {
                override fun query(spec: QuerySpec, after: String?) =
                    physical.query(spec, after).also { captured = it }
            }
        val query = QueryService(listOf(source))
        val result = query.query(QuerySpec(AiDomain.D17, limit = 3, maxBytes = 1))
        assertTrue(result is ApiResult.Failure)
        assertEquals("ai.item_too_large", (result as ApiResult.Failure).error.message)
        assertTrue(provider.isClosed)
        assertEquals(0L, query.returnedRows.get())
        captured!!.rows.forEach { assertTrue(runCatching { resources.lookup(it.key) }.isFailure) }
    }

    @Test
    fun pendingResourcesAreReleasedOnResetExpiryAndFolderRemoval() = runBlocking {
        for (mode in listOf("reset", "expiry", "folder")) {
            var now = 0L
            var folder: String? = "content://scoped-tree/tree/root"
            val resources =
                ForegroundResourceRegistry(
                    java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                )
            val columns =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
            val provider =
                MatrixCursor(columns).apply {
                    repeat(3) {
                        addRow(
                            arrayOf<Any>(
                                "file$it",
                                "a".repeat(512) + "$it.wav",
                                "audio/wav",
                                1L,
                                1L,
                            ),
                        )
                    }
                }
            val physical =
                DirectoryScopedReadSource(
                    { folder },
                    { 0L },
                    resources,
                    { true },
                    { _, _ -> provider },
                    { _, _ -> null },
                )
            var captured: com.greatwall.callring.application.ports.SourcePage? = null
            val source =
                object : com.greatwall.callring.application.ports.ScopedReadPort by physical {
                    override fun query(spec: QuerySpec, after: String?) =
                        physical.query(spec, after).also { captured = it }
                }
            val query = QueryService(listOf(source), clock = { now })
            val first =
                query.query(QuerySpec(AiDomain.D17, limit = 3, maxBytes = 800)).requiredValue()
            val rows = captured!!.rows
            assertNotNull(resources.lookup(rows[1].key))
            when (mode) {
                "reset" -> query.reset()
                "expiry" -> {
                    now = 600001
                    assertTrue(
                        query.query(QuerySpec(AiDomain.D17, cursor = first.nextCursor))
                            is ApiResult.Failure,
                    )
                }
                else -> {
                    folder = null
                    assertTrue(
                        query.query(QuerySpec(AiDomain.D17, cursor = first.nextCursor))
                            is ApiResult.Failure,
                    )
                }
            }
            assertTrue(provider.isClosed)
            assertTrue(runCatching { resources.lookup(rows[1].key) }.isFailure)
            assertNotNull(resources.lookup(rows[0].key))
        }
    }

    @Test
    fun pendingTailRechecksAuthorizationBeforeDelivery() = runBlocking {
        var granted = true
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val source =
            DirectoryScopedReadSource(
                { "content://scoped-tree/tree/root" },
                { 0L },
                ForegroundResourceRegistry(),
                { granted },
                { _, _ ->
                    MatrixCursor(columns).apply {
                        repeat(3) {
                            addRow(
                                arrayOf<Any>(
                                    "file$it",
                                    "a".repeat(512) + "$it.wav",
                                    "audio/wav",
                                    1L,
                                    1L,
                                ),
                            )
                        }
                    }
                },
                { _, _ -> null },
            )
        val query = QueryService(listOf(source))
        val first = query.query(QuerySpec(AiDomain.D17, limit = 4, maxBytes = 800)).requiredValue()
        granted = false
        val denied =
            query.query(
                QuerySpec(AiDomain.D17, cursor = first.nextCursor, limit = 4, maxBytes = 800),
            )
        assertTrue(denied is ApiResult.Failure)
        assertEquals(ErrorCode.PERMISSION_REQUIRED, (denied as ApiResult.Failure).error.code)
    }

    @Test
    fun byteLimitedContinuationRetainsTailAfterProviderCompletes() = runBlocking {
        for (initialLimit in listOf(3, 4)) {
            val columns =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
            var opens = 0
            val source =
                DirectoryScopedReadSource(
                    { "content://scoped-tree/tree/root" },
                    { 0L },
                    ForegroundResourceRegistry(),
                    { true },
                    { _, _ ->
                        opens++
                        MatrixCursor(columns).apply {
                            repeat(3) {
                                addRow(
                                    arrayOf<Any>(
                                        "file$it",
                                        "a".repeat(512) + "$it.wav",
                                        "audio/wav",
                                        1L,
                                        1L,
                                    ),
                                )
                            }
                        }
                    },
                    { _, _ -> null },
                )
            val api = QueryService(listOf(source))
            var cursor: String? = null
            val names = mutableListOf<String>()
            var rounds = 0
            do {
                val page =
                    api.query(
                            QuerySpec(
                                AiDomain.D17,
                                limit = if (rounds == 0) initialLimit else 2,
                                maxBytes = 800,
                                cursor = cursor,
                            ),
                        )
                        .requiredValue()
                names += page.items.map { it["name"].string() }
                cursor = page.nextCursor
                assertTrue(++rounds <= 4)
                if (cursor == null) assertTrue(page.complete)
            } while (cursor != null)
            assertEquals(3, names.size)
            assertEquals(3, names.distinct().size)
            assertEquals(1, opens)
        }
    }

    @Test
    fun providerCursorPagesPastOldFiveHundredLimitAndRevocationIsNotEmpty() = runBlocking {
        val provider =
            object : ContentProvider() {
                override fun onCreate() = true

                override fun query(
                    uri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    args: Array<out String>?,
                    sort: String?,
                ): Cursor =
                    MatrixCursor(projection!!).apply {
                        repeat(701) { i ->
                            addRow(
                                projection
                                    .map<String, Any> { column ->
                                        when (column) {
                                            DocumentsContract.Document.COLUMN_DOCUMENT_ID ->
                                                "file$i"
                                            DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                                                "合成$i.wav"
                                            DocumentsContract.Document.COLUMN_MIME_TYPE ->
                                                "audio/wav"
                                            else -> 1L
                                        }
                                    }
                                    .toTypedArray(),
                            )
                        }
                    }

                override fun getType(uri: Uri) = "vnd.android.document/directory"

                override fun insert(uri: Uri, values: ContentValues?): Uri? = null

                override fun update(
                    uri: Uri,
                    values: ContentValues?,
                    selection: String?,
                    args: Array<out String>?,
                ) = 0

                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            }
        ShadowContentResolver.registerProviderInternal("scoped-tree", provider)
        var granted = true
        val source =
            SystemRecordingSource(RuntimeEnvironment.getApplication().contentResolver) { granted }
        val query =
            QueryService(
                listOf(
                    source.scopedReads(
                        { "content://scoped-tree/tree/root" },
                        { 0L },
                        ForegroundResourceRegistry(),
                    ),
                ),
            )
        var cursor: String? = null
        var count = 0
        var page: ScopedPage
        do {
            page =
                query.query(QuerySpec(AiDomain.D17, limit = 100, cursor = cursor)).requiredValue()
            count += page.returnedCount
            cursor = page.nextCursor
        } while (cursor != null)
        assertTrue(page.complete)
        assertEquals(701, count)
        assertFalse(page.json().encode().contains("content://"))
        granted = false
        assertTrue(query.query(QuerySpec(AiDomain.D17)) is ApiResult.Failure)
    }
}
