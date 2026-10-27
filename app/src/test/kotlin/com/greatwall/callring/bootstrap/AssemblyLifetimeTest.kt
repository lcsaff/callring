package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.RecordingSettingsPatch
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.contracts.runtime.AccessState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AssemblyLifetimeTest {
    @Test fun configuredMatcherReachesRealConversationExecutorAndCancelledRuleSaveNeverAccessesSql()=runBlocking {
        val probe=com.greatwall.callring.data.database.SqlAccessProbe()
        val matcher=com.greatwall.callring.domain.calling.NumberMatcher { number,rule -> number=="91234"&&rule.prefix=="3" }
        graph("number-rule-ai-config",matcher,probe).use { graph ->
            graph.api.settings.getNumberRule().requiredValue()
            assertTrue(graph.api.checkNumber("91234").matchesRule)
            val conversation=graph.api.conversation as com.greatwall.callring.application.conversation.execution.ConversationService
            val executor=conversation.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(conversation) as com.greatwall.callring.application.conversation.execution.ToolExecutor
            conversation.foreground.attachHost()
            val context=com.greatwall.callring.application.conversation.execution.ToolExecutionContext("session","turn",1,CommandContext("root",graph.coordinator.capture(),1){true})
            val root=executor.execute(ConversationToolCall("root","root_checkNumber",aiObject("raw" to aiString("91234"))),context)
            assertEquals(aiBool(true),(root.value as AiJson.Obj)["matchesRule"])
            assertEquals(setOf("normalized","validProfileNumber","matchesRule"),root.value.let{it as AiJson.Obj}.fields.keys)
            assertTrue(root.verificationEvidence.encode().contains("\"domain\":\"D10\""))
            val marker=graph.coordinator.marker();val measurements=probe.snapshot()
            val cancelled=executor.execute(ConversationToolCall("cancel","settings_saveNumberRule",aiObject("base" to aiObject("rules" to AiJson.Arr(listOf(aiObject("prefix" to aiString("3"),"length" to aiNumber(5)))),"revision" to aiNumber(0)),"rules" to AiJson.Arr(listOf(aiObject("prefix" to aiString("8"),"length" to aiNumber(6)))))),context.copy(command=CommandContext("cancel",graph.coordinator.capture(),1){false}))
            assertEquals(ToolStatus.FAILED,cancelled.status);assertEquals("ai.cancelled",cancelled.error?.message)
            assertEquals("cancelled executor must not touch SQL",measurements,probe.snapshot())
            assertEquals(marker,graph.coordinator.marker())
            assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0),graph.api.settings.getNumberRule().requiredValue())
        }
    }

    @Test fun numberRulePreparationIsExplicitAndRetainedApisNeverReloadAfterClose() = runBlocking {
        val graph = graph("number-rule-lifetime")
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("number-rule-lifetime.db")
        val backend = graph.backend
        val api = graph.api
        assertFalse("construction must not open core persistence",file.exists())
        assertThrows(IllegalStateException::class.java) { api.checkNumber("31234") }
        val base = api.settings.getNumberRule().requiredValue()
        assertTrue(file.exists())
        assertTrue(api.checkNumber("31234").matchesRule)
        val bound = api.commandApi(CommandContext("rule-lifetime",graph.coordinator.capture(),0) { true })
        assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)), 1),bound.settings.saveNumberRule(SaveNumberRule(base, listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)))).requiredValue())
        assertTrue(api.checkNumber("812345").matchesRule)
        assertFalse(api.checkNumber("31234").matchesRule)
        graph.close()
        assertTrue(api.settings.getNumberRule() is ApiResult.Failure)
        assertTrue(backend.prepareNumberRule() is ApiResult.Failure)
        assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) { api.checkNumber("812345") }
        assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) { bound.checkNumber("812345") }
        Unit
    }

    @Test fun retainedSnapshotRejectsBeforeCredentialStoreInitialization() = runBlocking {
        val graph = graph("terminal-snapshot")
        val recording = graph.recordings
        val snapshots = graph.api.snapshots
        val delegate = recording.javaClass.getDeclaredField("storeDelegate").apply { isAccessible = true }.get(recording) as Lazy<*>
        assertFalse(delegate.isInitialized())
        val query = graph.queries
        val sessionLock = requireNotNull(query.javaClass.getDeclaredField("sessionLock").apply { isAccessible = true }.get(query))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(sessionLock) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { graph.close() }.apply { start() }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var closed = false
            while (!closed && System.nanoTime() < deadline) {
                closed = try { graph.api; false } catch (_: IllegalStateException) { true }
                Thread.yield()
            }
            assertTrue(closed)
            assertTrue(snapshots.captureComplete() is ApiResult.Failure)
            assertFalse("closed snapshot initialized credentials store", delegate.isInitialized())
        } finally { release.countDown(); holder.join(5000); closer.join(5000) }
        assertFalse(closer.isAlive)
    }

    @Test fun retainedQueriesAndSnapshotsCannotInitializeRecordingAfterClose() = runBlocking {
        val graph = graph("terminal-cold")
        val queries = graph.queries
        val recording = graph.recordings
        val snapshots = graph.api.snapshots
        val file = RuntimeEnvironment.getApplication().getDatabasePath("terminal-cold.db_recording.db")
        assertFalse(file.exists())
        graph.close()
        for (domain in listOf(AiDomain.D15, AiDomain.D16)) {
            assertTrue("retained $domain must fail", queries.query(QuerySpec(domain)) is ApiResult.Failure)
        }
        queries.reset()
        assertTrue(queries.query(QuerySpec(AiDomain.D15)) is ApiResult.Failure)
        assertTrue(snapshots.captureComplete() is ApiResult.Failure)
        assertFalse("closed owner created recording database", file.exists())
        assertThrows(IllegalStateException::class.java) { graph.recordings }
        assertThrows(IllegalStateException::class.java) { graph.queries }
        assertThrows(IllegalStateException::class.java) { recording.store }
        assertThrows(IllegalStateException::class.java) { recording.service }
        Unit
    }

    @Test fun recordingCloseWaitsForFirstStoreFactoryAndClosesItsDatabase() {
        val context = RuntimeEnvironment.getApplication()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = com.greatwall.callring.data.recording.AndroidRecordingStore(context, "terminal-race", recordingVersion = { 0L })
        store.captureRecordingText()
        val helper = store.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(store) as android.database.sqlite.SQLiteOpenHelper
        val database = helper.writableDatabase
        val assembly = RecordingAssembly(context, "terminal-race", { null }, { null }, {}, storeFactory = {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            store
        }, operationActive = { true })
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val reader = Thread { try { assembly.store } catch (e: Throwable) { failures.add(e) } }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { try { assembly.close() } catch (e: Throwable) { failures.add(e) } }.apply { start() }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("close must wait for factory ownership", Thread.State.BLOCKED, closer.state)
        } finally { release.countDown(); reader.join(5000); closer.join(5000) }
        assertFalse(reader.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
        assertFalse("created database leaked", database.isOpen)
        assembly.close()
        assertThrows(IllegalStateException::class.java) { assembly.store }
        assertThrows(IllegalStateException::class.java) { assembly.service }
    }

    @Test fun platformResumeDoesNotInitializeUnrelatedRecordingResources() = runBlocking {
        val graph = graph("t11-platform-resume")
        try {
            val field = BackendGraph::class.java.getDeclaredField("recordingDelegate").apply { isAccessible = true }
            val recording = field.get(graph) as Lazy<*>
            graph.reobservePlatform()
            assertFalse("home/platform observation must not read recording configuration", recording.isInitialized())
            val marker = graph.coordinator.marker()
            graph.reobservePlatform()
            assertEquals(marker, graph.coordinator.marker())
            assertFalse(recording.isInitialized())
        } finally { graph.close() }
    }
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumedFolderProviderRunsOffTheCallingUiDispatcherAndDoesNotBlockIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val probeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val probes = java.util.concurrent.atomic.AtomicInteger()
        var block = false
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri) = "vnd.android.document/directory"
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                probes.incrementAndGet()
                if (block) {
                    probeThread.set(Thread.currentThread()); entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return android.database.MatrixCursor(projection!!).apply { addRow(arrayOf("Recordings")) }
            }
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "t11-resume-folder"; exported = true })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("t11-resume-folder", provider)
        val graph = graph("t11-provider-dispatch")
        val ui = kotlinx.coroutines.newSingleThreadContext("t11-ui")
        var job: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val recording = graph.recordings
            recording.javaClass.getDeclaredField("sources\$delegate").apply { isAccessible = true }
                .set(recording, lazy { com.greatwall.callring.platform.recording.SystemRecordingSource(context.contentResolver) { true } })
            val cipher = object : com.greatwall.callring.data.recording.RecordingCipher {
                override fun encrypt(value: String) = value
                override fun decrypt(value: String) = value
            }
            recording.javaClass.getDeclaredField("storeDelegate").apply { isAccessible = true }.set(recording,
                lazy { com.greatwall.callring.data.recording.AndroidRecordingStore(context, graph.configuration.databaseName, cipher,
                    recordingVersion = { graph.coordinator.capture().values.getValue("recording") }) })
            recording.store.persistSettings(com.greatwall.callring.application.recording.model.RecordingSettings(systemFolder = "content://t11-resume-folder/tree/root"))
            assertEquals(AccessState.GRANTED, graph.api.aiSettings.getRecordingView().requiredValue().folderAccess)
            assertEquals(1, probes.get())
            val baseline = graph.coordinator.marker()
            block = true
            val caller = kotlinx.coroutines.withContext(ui) { Thread.currentThread() }
            job = kotlinx.coroutines.CoroutineScope(ui).async { graph.reobservePlatform() }
            assertTrue("provider probe was not reached", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotEquals("blocking provider I/O must leave the resume/UI dispatcher", caller, probeThread.get())
            kotlinx.coroutines.withTimeout(1000) { kotlinx.coroutines.withContext(ui) { assertEquals(caller, Thread.currentThread()) } }
            release.countDown(); job.await()
            assertEquals("one cached-folder provider reobservation", 2, probes.get())
            assertEquals("unchanged provider facts publish nothing", baseline, graph.coordinator.marker())
        } finally {
            release.countDown(); job?.join(); ui.close(); graph.close()
        }
    }

    @Test fun retainedPublicPreferenceWritersCannotMutateANewGraphAfterClose() = runBlocking {
        val old = graph("retained-public")
        val api = old.api
        val appearance = api.settings.getAppearance().requiredValue()
        api.widgets.saveBinding(7, "31234").requiredValue()
        old.close()
        val marker = old.coordinator.marker()
        val replacement = graph("retained-public")
        try {
            val changes = listOf(
                api.settings.saveAppearance(appearance.copy(colorMode = ColorMode.DARK)),
                api.edits.appearance(appearance, appearance.copy(colorMode = ColorMode.DARK)),
                api.widgets.saveBinding(7, "35678"),
                api.widgets.removeBinding(7),
            )
            changes.forEach { assertTrue("closed public writer must reject: $it", it is ApiResult.Failure) }
            assertEquals(marker, old.coordinator.marker())
            assertEquals(appearance, replacement.api.settings.getAppearance().requiredValue())
            assertEquals("31234", replacement.api.widgets.getBinding(7).requiredValue()!!.number)
        } finally { replacement.close() }
    }

    @Test fun retainedBoundAndNonSqlWritersKeepTheSameClosedOwner() = runBlocking {
        val old = graph("retained-nonsql")
        val api = old.api
        val appearance = api.settings.getAppearance().requiredValue()
        val settings = api.aiSettings
        val recording = settings.getRecordingView().requiredValue()
        val ai = settings.getAiView().requiredValue()
        val ringtone = api.ringtone
        val runtime = api.runtime
        val bound = api.commandApi(CommandContext("retained", old.coordinator.capture(), 0, active = { true },
            expectedScopes = old.coordinator.expect(setOf("preferences", "ringtone", "recordingConfig", "aiConfig"))))
        old.close()
        val marker = old.coordinator.marker()
        val results = listOf(
            bound.settings.saveAppearance(appearance.copy(colorMode = ColorMode.DARK)),
            bound.widgets.saveBinding(9, "31234"),
            settings.patchRecording(recording, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))),
            settings.copyConnection(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION, ai.revision),
            ringtone.setEnabled(false), ringtone.restoreBuiltIn(), ringtone.startPreview(),
            runtime.requestCallLogSync(),
        )
        results.forEach { assertTrue("closed owner must reject: $it", it is ApiResult.Failure) }
        assertEquals(marker, old.coordinator.marker())
    }

    @Test fun constructionAndClosingUnusedAssembliesNeverOpenPersistence() {
        val graph = graph("assembly-unopened")
        assertEquals(0L, graph.businessReadCounters().getValue("coreOpens"))
        assertEquals(0L, graph.businessReadCounters().getValue("sourceQueries"))
        graph.close()
        val context = RuntimeEnvironment.getApplication()
        assertFalse(context.getDatabasePath("assembly-unopened.db").exists())
        assertFalse(context.getDatabasePath("assembly-unopened.db_recording.db").exists())
    }

    @Test fun graphCloseDrainsQueuedIncomingStatisticsLinkBeforeClosingStorage() = runBlocking {
        val old = graph("graph-drain")
        old.api.settings.setStatisticsEnabled(true).requiredValue()
        old.backend.refreshDecisionSnapshot()
        val recording = old.recordings.store
        val incoming = old.incomingCalls
        val at = old.configuration.clock.millis() + 1
        val call = old.recordings.registrations.registerIncoming("queued-drain", "31234", at, null, null)
        var statisticsId = 0L
        var closer: Thread? = null
        try {
            synchronized(recording) {
                val session = incoming.inspect("queued-drain", "31234", at, null)
                statisticsId = session.record.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(statisticsId > 0)
                closer = Thread { old.close() }.apply { isDaemon = true; start() }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                var publiclyClosed = false
                while (System.nanoTime() < deadline && !publiclyClosed) {
                    publiclyClosed = try { old.api; false } catch (_: IllegalStateException) { true }
                    Thread.yield()
                }
                assertTrue("close must mark the public owner before drain", publiclyClosed)
                assertTrue("close must await the queued recording link", closer!!.isAlive)
            }
        } finally {
            closer?.join(5000)
            if (closer == null) old.close()
        }
        assertFalse("graph drain did not finish", closer!!.isAlive)
        val replacement = graph("graph-drain")
        try {
            assertEquals(call.id, replacement.recordings.store.linkedRecording(statisticsId)!!.id)
            assertNotNull(replacement.recordings.store.get(call.id))
            assertEquals("31234", replacement.api.callers.getCallDetail(statisticsId).requiredValue().number)
        } finally { replacement.close() }
    }

    @Test fun publicSqlWritersRejectWhileColdIncomingObservationDrains() = runBlocking {
        val old = graph("public-sql-drain")
        val api = old.api
        api.callers.saveProfile(SaveCallerProfile("31234", "before", "unit", null)).requiredValue()
        val quiet = api.settings.getQuietHours().requiredValue()
        val bound = api.commandApi(CommandContext("sql-drain", old.coordinator.capture(), 0, active = { true },
            expectedScopes = old.coordinator.expect(setOf("profile:31234", "quiet"))))
        old.backend.refreshDecisionSnapshot()
        val incoming = old.incomingCalls
        // Control scheduling of the real existing executor, without a production callback/facade.
        val observations = incoming.javaClass.getDeclaredField("observations").let {
            it.isAccessible = true
            it.get(incoming) as java.util.concurrent.ExecutorService
        }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        observations.submit { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        incoming.inspect("cold-drain", "31234", old.configuration.clock.millis(), null)
        val closer = Thread { old.close() }.apply { isDaemon = true; start() }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var publiclyClosed = false
            while (System.nanoTime() < deadline && !publiclyClosed) {
                publiclyClosed = try { old.api; false } catch (_: IllegalStateException) { true }
                Thread.yield()
            }
            assertTrue(publiclyClosed)
            assertTrue(closer.isAlive)
            assertTrue(api.callers.saveProfile(SaveCallerProfile("31234", "after", "unit", null)) is ApiResult.Failure)
            assertTrue(bound.settings.saveQuietHours(quiet.copy(allNumbers = !quiet.allNumbers)) is ApiResult.Failure)
        } finally { release.countDown(); closer.join(5000) }
        assertFalse(closer.isAlive)
        val replacement = graph("public-sql-drain")
        try {
            assertEquals("before", replacement.api.callers.getEditor("31234").requiredValue().draft.name)
            assertEquals(quiet, replacement.api.settings.getQuietHours().requiredValue())
            assertFalse(replacement.diagnostics.read().any { it.message.contains("call.record_failed") })
        } finally { replacement.close() }
    }

    private fun graph(name: String,matcher:com.greatwall.callring.domain.calling.NumberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher,probe:com.greatwall.callring.data.database.SqlAccessProbe?=null): BackendGraph {
        val context = RuntimeEnvironment.getApplication()
        return BackendGraph(context, BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            numberMatcher=matcher, sqlAccessProbe=probe, databaseName = "$name.db", ringPreferencesName = "${name}_ring",
            widgetPreferencesName = "${name}_widgets", appearancePreferencesName = "${name}_appearance"),
            object : UiNotificationEntryPoints {
                override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
                override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
            }, object : IncomingCallUiSink { override fun show(request: IncomingCallUiRequest) = Unit })
    }
}
