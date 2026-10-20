package com.greatwall.callring.backend

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.platform.UiNotificationEntryPoints
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers

/**
 * A real composition root with disposable persistence and a local document provider. No manifest
 * provider, Application replacement, global graph, jobs, services or UI are activated.
 */
internal class BackendApiFixture(private val base: Context) : Closeable {
    val id = "qa-api-${UUID.randomUUID()}"
    val root = File(base.cacheDir, id).apply { check(mkdirs()) }
    val audioDirectory = File(root, "audio")
    val documentsDirectory = File(root, "documents").apply { check(mkdirs()) }
    val clock = FixtureClock(Instant.parse("2026-06-01T08:00:00Z"))
    val defaultAppearance =
        AppearanceSettings(ThemeSelection.PRESET, "fixture-default", null, ColorMode.FOLLOW_SYSTEM)
    val configuration =
        BackendConfiguration(
            defaultAppearance,
            "$id.db",
            "$id-ring",
            "$id-widgets",
            "$id-appearance",
            audioDirectory,
            clock,
            Dispatchers.IO,
        )
    val ringPreferences =
        ControlledPreferences(
            base.getSharedPreferences(configuration.ringPreferencesName, Context.MODE_PRIVATE)
        )
    private val provider =
        FixtureDocumentProvider("$id.documents", documentsDirectory).apply {
            attachInfo(
                base,
                ProviderInfo().apply {
                    authority = "$id.documents"
                    exported = false
                    applicationInfo = base.applicationInfo
                    packageName = base.packageName
                },
            )
        }
    val context =
        FixtureContext(
            base,
            configuration,
            audioDirectory,
            ContentResolver.wrap(provider),
            ringPreferences,
        )
    val ui = FixtureUi(base, id)
    var graph = BackendGraph(context, configuration, ui, ui)
        private set

    val databaseFile
        get() = base.getDatabasePath(configuration.databaseName)

    fun document(name: String, bytes: ByteArray): DocumentRef = provider.document(name, bytes)

    fun document(name: String, text: String): DocumentRef =
        document(name, text.toByteArray(Charsets.UTF_8))

    fun output(name: String): DocumentRef = provider.document(name, byteArrayOf())

    fun documentBytes(name: String): ByteArray = File(documentsDirectory, name).readBytes()

    fun reopen(configuration: BackendConfiguration = this.configuration) {
        graph.close()
        graph = BackendGraph(context, configuration, ui, ui)
    }

    fun rawFacts(): List<List<String?>> =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery(
                        "SELECT id,number,time,duration,result,decision,source,log_id,screening_key,original_working,original_reason,original_verified,original_config_version,original_calendar_version,original_leave_version,original_zone,account FROM calls ORDER BY id",
                        null,
                    )
                    .use { rows ->
                        val result = mutableListOf<List<String?>>()
                        while (rows.moveToNext()) result +=
                            (0 until rows.columnCount).map {
                                if (rows.isNull(it)) null else rows.getString(it)
                            }
                        result
                    }
            }

    override fun close() {
        try {
            graph.close()
        } finally {
            ui.close()
            provider.shutdown()
            base.deleteDatabase(configuration.databaseName)
            listOf(
                    configuration.ringPreferencesName,
                    configuration.widgetPreferencesName,
                    configuration.appearancePreferencesName,
                )
                .forEach { base.deleteSharedPreferences(it) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.walkBottomUp().forEach { check(it.delete() || !it.exists()) }
        }
    }
}

internal class FixtureClock(initial: Instant) : Clock() {
    @Volatile private var current = initial

    fun at(value: String) {
        current = Instant.parse(value)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

    override fun instant(): Instant = current
}

internal class ControlledPreferences(private val delegate: SharedPreferences) :
    SharedPreferences by delegate {
    val failNextCommit = AtomicBoolean(false)
    @Volatile var commitEntered: CountDownLatch? = null
    @Volatile var allowCommit: CountDownLatch? = null

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commitEntered?.countDown()
                check(allowCommit?.await(5, TimeUnit.SECONDS) != false) { "fixture.commit_timeout" }
                val saved = editor.commit()
                return if (failNextCommit.getAndSet(false)) false else saved
            }

            // Editor methods must return this wrapper so a chained commit retains fault injection.
            override fun putBoolean(key: String, value: Boolean) = apply {
                editor.putBoolean(key, value)
            }

            override fun putString(key: String, value: String?) = apply {
                editor.putString(key, value)
            }

            override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }

            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }

            override fun remove(key: String) = apply { editor.remove(key) }
        }
    }
}

internal class FixtureContext(
    base: Context,
    private val configuration: BackendConfiguration,
    private val isolatedFiles: File,
    private val documents: ContentResolver,
    private val ring: SharedPreferences,
) : ContextWrapper(base) {
    val filesDirectoryReads = AtomicInteger()
    val databaseOpens = AtomicInteger()
    val serviceCommands = CopyOnWriteArrayList<String?>()

    override fun getApplicationContext(): Context = this

    override fun getContentResolver(): ContentResolver = documents

    override fun getFilesDir(): File {
        filesDirectoryReads.incrementAndGet()
        return isolatedFiles
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        when (name) {
            configuration.ringPreferencesName -> ring
            configuration.widgetPreferencesName,
            configuration.appearancePreferencesName -> super.getSharedPreferences(name, mode)
            else -> throw AssertionError("Unexpected preferences access")
        }

    override fun getSystemService(name: String): Any? =
        if (name == JOB_SCHEDULER_SERVICE) null else super.getSystemService(name)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase {
        check(name == configuration.databaseName)
        databaseOpens.incrementAndGet()
        return super.openOrCreateDatabase(name, mode, factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase {
        check(name == configuration.databaseName)
        databaseOpens.incrementAndGet()
        return super.openOrCreateDatabase(name, mode, factory, errorHandler)
    }

    override fun startService(service: Intent): ComponentName {
        serviceCommands += service.action
        return service.component ?: throw AssertionError("Service command must be explicit")
    }

    override fun startForegroundService(service: Intent): ComponentName = startService(service)
}

internal class FixtureUi(private val context: Context, private val id: String) :
    UiNotificationEntryPoints, IncomingCallUiSink, Closeable {
    val requests = CopyOnWriteArrayList<IncomingCallUiRequest>()
    private val pending = CopyOnWriteArrayList<PendingIntent>()

    override fun show(request: IncomingCallUiRequest) {
        requests += request
    }

    override fun home(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent("com.greatwall.callring.QA_HOME")
                    .setPackage(context.packageName)
                    .setData(Uri.parse("fixture://$id/home")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            .also { pending += it }

    override fun playback(preview: Boolean, stopAction: PendingIntent): Notification =
        Notification.Builder(context, id)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fixture playback")
            .setContentIntent(home(if (preview) 1 else 2))
            .addAction(Notification.Action.Builder(null, "Fixture stop", stopAction).build())
            .build()

    override fun close() {
        pending.forEach { it.cancel() }
        pending.clear()
    }
}

private class FixtureDocumentProvider(private val authority: String, private val root: File) :
    ContentProvider() {
    fun document(name: String, bytes: ByteArray): DocumentRef {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        File(root, name).writeBytes(bytes)
        return DocumentRef(
            Uri.Builder().scheme("content").authority(authority).appendPath(name).build().toString()
        )
    }

    private fun file(uri: Uri): File {
        if (uri.authority != authority || uri.pathSegments.size != 1)
            throw FileNotFoundException("fixture.invalid_document")
        val result = File(root, uri.lastPathSegment ?: throw FileNotFoundException())
        if (result.canonicalFile.parentFile != root.canonicalFile)
            throw FileNotFoundException("fixture.invalid_document")
        return result
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
