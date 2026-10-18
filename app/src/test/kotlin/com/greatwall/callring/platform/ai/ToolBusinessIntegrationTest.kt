package com.greatwall.callring.platform.ai

import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.data.SqliteBackendStore
import com.greatwall.callring.domain.*
import com.greatwall.callring.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import java.io.File
import java.lang.reflect.*
import java.time.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Production APIs/stores/commands with deterministic Android permission, media and AI service boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ToolBusinessIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()
    private class Client : AiClient {
        var tests = 0
        override fun test(connection: AiConnection, transcription: Boolean) { connection.validate(); if(transcription) require(connection.protocol == AiProtocol.OPENAI); tests++ }
        override fun analyze(connection: AiConnection, transcript: String): CallAnalysis = error("No paid analysis in tools test")
        override fun transcribe(connection: AiConnection, audio: File): String = error("No paid transcription in tools test")
    }
    private inner class Fixture(index: Int) : AutoCloseable {
        val namespace = "tool_${index}"
        val coordinator = BusinessRevisionCoordinator()
        val store = SqliteBackendStore(context,"$namespace.db")
        val docs = AndroidDocumentStore(context.contentResolver)
        val audio = AndroidAudioController(context,context.getSharedPreferences("${namespace}_ring",0),{ File(context.filesDir,"${namespace}_audio") },docs,coordinator)
        val preferences = AndroidPreferences(context.getSharedPreferences("${namespace}_appearance",0),context.getSharedPreferences("${namespace}_widgets",0),AppearanceSettings(ThemeSelection.PRESET,"default",null,ColorMode.FOLLOW_SYSTEM),coordinator)
        val client = Client()
        val calls = ConcurrentLinkedQueue<String>()
        val backend = CallringBackend(store,preferences,docs,object : RuntimeProbe {
            override fun getStatus() = RuntimeStatus(Capability.values().associateWith { AccessState.GRANTED },Audibility.AUDIBLE,PhoneActivity.IDLE,PlaybackStatus(PlaybackMode.NONE,PlaybackState.IDLE),true,now)
            override fun getDiagnostics() = listOf(DiagnosticEntry(now,null,"fixture"))
            override fun getInstalledWidgets() = WidgetInstallState(listOf(1),AccessState.GRANTED)
        },audio,object : SyncScheduler { override fun requestSync(atEpochMillis: Long) = SyncRequestReceipt(SyncRequestState.QUEUED,atEpochMillis) },Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC),calendarSource = CalendarSource { year -> CalendarImport(CalendarYear(year,"fixture","2026-01-01","v1"),mapOf(LocalDate.of(year,1,1) to "fixture holiday"),emptySet()) },coordinator = coordinator)
        val recordingStore = AndroidRecordingStore(context,namespace,object : RecordingCipher { override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray()); override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value)) },coordinator)
        val runtime = AndroidRecordingRuntime(context,namespace,{ null },{ null },{}, { recordingStore },metadataReader = { 1000L to "wav" },coordinator = coordinator,clientFactory = { client })
        var recordingId = -1L
        val resources = ForegroundResourceRegistry()
        val foreground = ForegroundActionService(checkCommand = { cmd -> cmd == null || coordinator.committed(cmd.operationId) != null || runCatching { coordinator.guard(cmd.copy(mayCommit = { true })) {} }.isSuccess })
        @Suppress("UNCHECKED_CAST") fun <T> spy(type: Class<T>, label: String, value: T): T = Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args ->
            calls += "$label.${method.name}"
            try { method.invoke(value,*(args ?: emptyArray())) } catch(e: InvocationTargetException) { throw e.targetException }
        } as T
        fun api(command: CommandContext? = null): CallringUiApi {
            val base = command?.let(backend::commandApi) ?: backend
            return object : CallringUiApi by base {
                override val callers = spy(CallersApi::class.java,"callers",base.callers)
                override val reports = spy(ReportsApi::class.java,"reports",base.reports)
                override val settings = spy(SettingsApi::class.java,"settings",base.settings)
                override val edits = spy(UiEditsApi::class.java,"edits",base.edits)
                override val ringtone = spy(RingtoneApi::class.java,"ringtone",base.ringtone)
                override val home = spy(HomeApi::class.java,"home",base.home)
                override val widgets = spy(WidgetBindingsApi::class.java,"widgets",base.widgets)
                override val runtime = spy(RuntimeApi::class.java,"runtime",base.runtime)
                override val recordings = spy(RecordingApi::class.java,"recordings",command?.let(this@Fixture.runtime::bound) ?: this@Fixture.runtime)
                override val aiSettings = spy(AiSettingsApi::class.java,"ai",command?.let(this@Fixture.runtime.aiSettings::bound) ?: this@Fixture.runtime.aiSettings)
                override fun commandApi(context: CommandContext) = api(context)
                override fun checkNumber(raw: String): NumberCheck { calls += "root.checkNumber"; return base.checkNumber(raw) }
                override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> { calls += "root.adoptIdentity"; return SharedBusinessCommands(this).adoptIdentity(callId,expectedGeneration) }
            }
        }
        suspend fun seed() {
            backend.settings.setStatisticsEnabled(true).requiredValue()
            backend.settings.savePosition(SavePosition(name = "Role1",level = 1,active = true)).requiredValue()
            backend.settings.savePosition(SavePosition(name = "Role2",level = 2,active = true)).requiredValue()
            backend.callers.saveProfile(SaveCallerProfile("31234","old","old unit",1,true)).requiredValue()
            backend.recordIncoming("31234",now,CallDecision.NORMAL_RINGING)
            backend.settings.saveLeave(SaveLeave(period = LeavePeriod.Timed(now+1000,now+2000),note = "note")).requiredValue()
            backend.settings.saveDateOverride(DateOverride(LocalDate.parse("2026-10-19"),DateOverrideKind.REST,emptyList())).requiredValue()
            backend.widgets.saveBinding(1,"31234").requiredValue()
            val connection = AiConnection(url = "https://example.com/v1",apiKey = "fixture-secret-one",model = "fixture")
            recordingStore.saveSettings(RecordingSettings(recordingEnabled = true,analysis = connection,transcription = connection.copy(apiKey = "fixture-secret-two")))
            val identity = runtime.observeIncoming("session","31234",now,null)!!
            recordingId = identity.id
            runtime.importRecording(identity.id,identity.number,identity.startedAt,DocumentRef("content://tool-fixture/audio")).requiredValue()
            val archive = recordingStore.get(recordingId)!!
            recordingStore.compareAndSet(recordingId,archive.generation,archive.copy(generation = archive.generation+1,transcript = "FULL-TAIL",analysis = CallAnalysis(name = "self",organization = "new unit",inferredIdentity = "inference")))
            foreground.attachHost()
            calls.clear()
        }
        override fun close() { foreground.invalidate(); runtime.close(); audio.close(); store.close() }
    }
    private fun provider() {
        val audio = File(context.cacheDir,"tool-source.wav").apply { writeBytes(ByteArray(128)) }
        val calendar = File(context.cacheDir,"tool-calendar.json").apply { writeText("""{"year":2026,"source":"fixture","version":"v1","published":"2026-01-01","holidays":[],"workdays":[]}""") }
        val csv = File(context.cacheDir,"tool-output.csv")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = if(uri.lastPathSegment == "calendar") "application/json" else "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor = MatrixCursor(projection ?: arrayOf("_display_name")).apply {
                if(!uri.toString().contains("children")) addRow((projection ?: arrayOf("_display_name")).map { if(it == "_size") audio.length() else "fixture.wav" }.toTypedArray<Any>())
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val file = if(uri.lastPathSegment == "calendar") calendar else if(mode.contains("w")) csv else audio
                return ParcelFileDescriptor.open(file,if(mode.contains("w")) ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY else ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply { authority = "tool-fixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("tool-fixture",provider)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000,0) }
    }
    @Test fun all72ToolsInvokeProductionBusinessApisAndInvalidSchemaNeverMutates() = runBlocking {
        provider()
        val registry = ToolRegistry()
        val covered = mutableSetOf<String>()
        for(definition in registry.definitions) Fixture(definition.index).use { fixture ->
            fixture.seed()
            val root = fixture.api()
            val host = launch { fixture.foreground.pending.filterNotNull().collect { ticket ->
                val token = fixture.foreground.attachHost()
                if(fixture.foreground.beginLaunch(ticket.requestId,token)) {
                    fixture.calls += "foreground.${ticket.kind}"
                    val local = ticket.commandContext?.let(root::commandApi) ?: root
                    val document = when(ticket.kind) {
                        ForegroundActionKind.IMPORT_AUDIO,ForegroundActionKind.IMPORT_RECORDING -> DocumentRef("content://tool-fixture/audio")
                        ForegroundActionKind.IMPORT_CALENDAR -> DocumentRef("content://tool-fixture/calendar")
                        ForegroundActionKind.EXPORT_CSV -> DocumentRef("content://tool-fixture/csv")
                        ForegroundActionKind.SELECT_FOLDER -> DocumentRef("content://tool-fixture/tree/root")
                        else -> null
                    }
                    if(ticket.kind == ForegroundActionKind.CREDENTIAL) {
                        val base = local.aiSettings.getAiView().requiredValue()
                        local.aiSettings.patchAi(base,AiSettingsPatch(),transcriptionSecret = LocalSecretChange.Replace("fixture-secret-new")).requiredValue()
                    }
                    if(ticket.kind == ForegroundActionKind.COPY_CONNECTION) local.aiSettings.copyConnection(AiSlot.ANALYSIS,AiSlot.TRANSCRIPTION,local.aiSettings.getAiView().requiredValue().revision).requiredValue()
                    if(ticket.kind == ForegroundActionKind.PREVIEW_CALL) local.callers.getCallDetail(1).requiredValue()
                    val status = if(ticket.kind in setOf(ForegroundActionKind.PIN_WIDGET,ForegroundActionKind.TEST_OVERLAY,ForegroundActionKind.PREVIEW_CALL)) ForegroundResultStatus.LAUNCHED else ForegroundResultStatus.VERIFIED
                    fixture.foreground.deliver(ticket.requestId,ForegroundHostResult(status,document))
                }
            } }
            val executor = ToolExecutor({ root },fixture.coordinator,fixture.foreground,fixture.resources,registry)
            fun scope(id: String) = ToolExecutionContext("session","turn",1,CommandContext(id,fixture.coordinator.capture(),1) { true })
            val call = ConversationToolCall("call",definition.name,parseConversationJson(arguments(definition.index,fixture.recordingId)) as AiJson.Obj)
            val receipt = withTimeout(10000) { executor.execute(call,scope("op")) }
            assertTrue("${definition.id} ${definition.name}: $receipt",receipt.status in setOf(ToolStatus.READ,ToolStatus.COMMITTED,ToolStatus.QUEUED,ToolStatus.LAUNCHED))
            assertNull("${definition.id}: ${receipt.error}",receipt.error)
            val expected = when(definition.index) { 37 -> "edits.appearance"; 51 -> "recordings.getSettings"; 52 -> "ai.patchCombined"; 53 -> "ai.testSlot"; 63 -> "root.adoptIdentity"; 64 -> "ai.getAiView"; 65 -> "ai.patchAi"; 66 -> "ai.patchAi"; 67,68 -> "runtime.getStatus"; 69 -> "widgets.listBindings"; 70 -> "foreground.TEST_OVERLAY"; 71 -> "callers.getCallDetail"; 72 -> "ai.copyConnection"; else -> definition.name.replace('_','.') }
            assertTrue("${definition.id} missing real API $expected in ${fixture.calls}",expected in fixture.calls)
            assertFalse(receipt.value.encode().contains("fixture-secret-"))
            assertFalse(receipt.value.encode().contains("content://"))
            if(definition.index == 7) { val saved = root.callers.getEditor("31234").requiredValue().draft; assertEquals("changed",saved.name); assertEquals(1L,saved.positionId); assertTrue(saved.whitelisted) }
            if(definition.index == 10) assertTrue(File(context.cacheDir,"tool-output.csv").readText().contains("31234"))
            if(definition.index == 55) assertNotNull(fixture.recordingStore.get(fixture.recordingId)!!.audioPath)
            if(definition.index == 56) assertEquals(ArchiveStatus.QUEUED,fixture.recordingStore.get(fixture.recordingId)!!.status)
            if(definition.index == 57) assertTrue(fixture.recordingStore.isDeleted(fixture.recordingId))
            if(definition.index == 63) { val saved = root.callers.getEditor("31234").requiredValue().draft; assertEquals("self",saved.name); assertEquals(1L,saved.positionId); assertTrue(saved.whitelisted) }
            if(definition.index == 72) assertEquals("fixture-secret-one",fixture.recordingStore.settings().transcription.apiKey)
            val before = fixture.coordinator.capture()
            val invalid = executor.execute(call.copy(id = "invalid",arguments = AiJson.Obj(call.arguments.fields + ("unexpected" to aiBool(true)))),scope("invalid-op"))
            assertEquals(ToolStatus.FAILED,invalid.status)
            assertEquals(before,fixture.coordinator.capture())
            covered += definition.id
            println("TOOL_PROOF ${definition.id} ${definition.name} status=${receipt.status} api=$expected invalid=FAILED")
            host.cancelAndJoin()
        }
        assertEquals(72,covered.size)
    }
    private fun arguments(index: Int, recordingId: Long): String = when(index) {
        1 -> """{"raw":"31234"}"""
        3 -> """{"text":"31234","page":{"pageSize":200}}"""
        4,5 -> """{"number":"31234"}"""
        7 -> """{"number":"31234","name":"changed"}"""
        8 -> """{"number":"31234","enabled":false}"""
        9,10 -> """{"number":"31234","range":{"startDate":"2026-01-01","endDateInclusive":"2026-12-31","zoneId":"UTC"}}"""
        12,13,17,39 -> """{"enabled":true}"""
        15 -> """{"mode":"CUSTOM","weeklyWindows":{"1":[{"startMinute":540,"endMinute":600}]}}"""
        16 -> """{"mode":"NON_WORK"}"""
        19 -> """{"id":1,"name":"changed role"}"""
        20,21 -> """{"id":2}"""
        23 -> """{"zoneId":"UTC"}"""
        26 -> """{"year":2026}"""
        28 -> """{"date":"2026-10-19","kind":"REST","windows":[]}"""
        29 -> """{"date":"2026-10-19"}"""
        31 -> """{"id":1,"note":"changed note"}"""
        32 -> """{"id":1}"""
        34 -> """{"repeatMinutes":15}"""
        37 -> """{"colorMode":"DARK"}"""
        47,49 -> """{"widgetId":1}"""
        48 -> """{"widgetId":2,"number":"31234"}"""
        52 -> """{"recording":{"automaticAnalysis":false},"ai":{"analysis":{"model":"changed"}}}"""
        53 -> """{"slot":"ANALYSIS"}"""
        54,55,56,57,58,63 -> """{"callId":$recordingId}"""
        65 -> """{"context":65536}"""
        66 -> """{"slot":"TRANSCRIPTION"}"""
        67 -> """{"capability":"MICROPHONE"}"""
        71 -> """{"callId":1}"""
        72 -> """{"from":"ANALYSIS","to":"TRANSCRIPTION"}"""
        else -> "{}"
    }
}
