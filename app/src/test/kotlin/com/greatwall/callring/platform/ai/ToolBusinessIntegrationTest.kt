package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.ports.platform.CalendarSource
import com.greatwall.callring.application.ports.platform.RuntimeProbe
import com.greatwall.callring.application.ports.platform.SyncScheduler
import com.greatwall.callring.application.ports.recording.AiClient

import com.greatwall.callring.application.conversation.execution.ConversationService
import com.greatwall.callring.application.conversation.execution.ReceiptVerifier
import com.greatwall.callring.application.conversation.execution.ToolBatchEngine
import com.greatwall.callring.application.conversation.execution.ToolExecutionContext
import com.greatwall.callring.application.conversation.execution.ToolExecutor
import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.foreground.ForegroundActionService
import com.greatwall.callring.application.conversation.foreground.ForegroundCommandValidator
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.conversation.query.json
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.application.runtime.ReadbackDocumentStore
import com.greatwall.callring.data.preferences.AndroidPreferences
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import com.greatwall.callring.domain.model.CalendarImport
import com.greatwall.callring.platform.audio.AndroidAudioController
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import com.greatwall.callring.platform.document.AndroidDocumentStore
import com.greatwall.callring.platform.recording.SystemRecordingSource

import com.greatwall.callring.data.fixtureScopedReads
import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*

import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.bootstrap.scopedQueryApi
import com.greatwall.callring.data.codec.parseAiJson

import com.greatwall.callring.application.mapping.*
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.domain.*
import com.greatwall.callring.platform.*
import com.greatwall.callring.platform.update.*
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
        override fun operation(cancelled: () -> Boolean): AiClient = this
        override fun cancel() = Unit
        var tests = 0
        override fun test(connection: AiConnection, transcription: Boolean) { connection.validate(); if(transcription) require(connection.protocol == AiProtocol.OPENAI); tests++ }
        override fun analyze(connection: AiConnection, transcript: String): CallAnalysis = error("No paid analysis in tools test")
        override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String = error("No paid transcription in tools test")
    }
    private inner class Fixture(namespaceTag: String) : AutoCloseable {
        val namespace = "tool_${namespaceTag}"
        val coordinator = BusinessRevisionCoordinator()
        val store = SqliteCoreStorageResource(context,"$namespace.db")
        val docs = ReadbackDocumentStore(AndroidDocumentStore(context.contentResolver))
        val audio = AndroidAudioController(context,context.getSharedPreferences("${namespace}_ring",0),{ File(context.filesDir,"${namespace}_audio") },docs)
        val preferences = AndroidPreferences(context.getSharedPreferences("${namespace}_appearance",0),context.getSharedPreferences("${namespace}_widgets",0),AppearanceSettings(ThemeSelection.PRESET,"default",null,ColorMode.FOLLOW_SYSTEM))
        val client = Client()
        val calls = ConcurrentLinkedQueue<String>()
        private val updateApk = byteArrayOf(1, 2, 3)
        private val updateHttp = successfulUpdateTransport(releaseJson(apkSize = 3), manifestJson(updateApk), updateApk)
        private val updates = AndroidAppUpdate(context,
            build = { UpdateBuildInfo(AppVersionInfo("0.3.0", 3), CALLRING_PACKAGE, true, 33) },
            directory = { File(context.cacheDir, "${namespace}_update") },
            persistence = MemoryUpdatePersistence(), openConnection = updateHttp,
            installer = FakeInstaller())
        val backend = CallringBackend(preferences,docs,object : RuntimeProbe {
            override fun getStatus() = RuntimeStatus(Capability.values().associateWith { AccessState.GRANTED },Audibility.AUDIBLE,PhoneActivity.IDLE,PlaybackStatus(PlaybackMode.NONE,PlaybackState.IDLE),true,now)
            override fun getDiagnostics() = listOf(DiagnosticEntry(now,null,"fixture"))
            override fun getInstalledWidgets() = WidgetInstallState(listOf(1),AccessState.GRANTED)
        },audio,object : SyncScheduler { override fun requestSync(atEpochMillis: Long) = SyncRequestReceipt(SyncRequestState.QUEUED,atEpochMillis) },Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC),calendarSource = CalendarSource { year -> CalendarImport(CalendarYear(year,"fixture","2026-01-01","v1").toDomain(),mapOf(LocalDate.of(year,1,1) to "fixture holiday"),emptySet()) },coordinator = coordinator, coreTransactions = store.sessions, numberRecordings = object : com.greatwall.callring.application.callers.NumberDeletionRecordings {
            override suspend fun archives(number: String) = runtime.service.numberArchives(number)
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext) = runtime.service.delete(callId, expectedGeneration, command)
        }, identityArchive = { runtime.service.getArchive(it) }, operationActive = { true }, conversationProvider = { com.greatwall.callring.testing.FixtureConversationApi }, aiSettingsProvider = { com.greatwall.callring.testing.FixtureAiSettingsApi }, updateProvider = { com.greatwall.callring.testing.FixtureAppUpdateApi }, snapshotsProvider = { com.greatwall.callring.testing.FixtureAiSnapshotApi }, storageProvider = { com.greatwall.callring.testing.FixtureStorageApi }, backupProvider = { com.greatwall.callring.testing.FixtureBackupApi }, recordingsProvider = { com.greatwall.callring.testing.FixtureRecordingApi }, persistenceActive = { true })
        val recordingStore = AndroidRecordingStore(context,namespace,object : RecordingCipher { override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray()); override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value)) }, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val runtime = RecordingAssembly(context,namespace,{ null },{ null },{}, { recordingStore },metadataReader = { 1000L to "wav" },coordinator = coordinator,clientFactory = { client }, operationActive = { true })
        val diagnostics = AndroidDiagnosticsStore(context.getSharedPreferences("${namespace}_diagnostics",0),Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC),coordinator)
        val queriedDomains = linkedSetOf<AiDomain>()
        private fun traced(port:ScopedReadPort)=object:ScopedReadPort by port {
            override fun query(spec:QuerySpec,after:String?):SourcePage {queriedDomains+=spec.domain;return port.query(spec,after)}
            override fun get(domain:AiDomain,key:String,projection:String):SourceRow? {queriedDomains+=domain;return port.get(domain,key,projection)}
            override fun aggregate(spec:AggregateSpec):AiJson {queriedDomains+=spec.domain;return port.aggregate(spec)}
        }
        val queries by lazy {
            val core=traced(store.fixtureScopedReads(coordinator))
            scopedQueryApi(core,{runtime},{api()},{preferences},
                {diagnostics},
                SystemRecordingSource(context.contentResolver),coordinator,resources,
                {PlaybackStatus(PlaybackMode.NONE,PlaybackState.IDLE)},
                {WidgetInstallState(listOf(1),AccessState.GRANTED)}, {com.greatwall.callring.application.conversation.query.ScheduledJobsObservation(false, emptyList())}, Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
        }
        var recordingId = -1L
        val resources = ForegroundResourceRegistry()
        val foreground = ForegroundActionService(checkCommand = ForegroundCommandValidator(coordinator)::check)
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
                override val update = spy(AppUpdateApi::class.java,"update",updates)
                override val recordings = spy(RecordingApi::class.java,"recordings",command?.let(this@Fixture.runtime.service::bound) ?: this@Fixture.runtime.service)
                override val aiSettings = spy(AiSettingsApi::class.java,"ai",command?.let(this@Fixture.runtime.aiSettings::bound) ?: this@Fixture.runtime.aiSettings)
                override fun commandApi(context: CommandContext) = api(context)
                override fun checkNumber(raw: String): NumberCheck { calls += "root.checkNumber"; return base.checkNumber(raw) }
                override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> { calls += "root.adoptIdentity"; return com.greatwall.callring.application.recording.IdentityAdoptionService(recordings::getArchive, { callers.getEditor(it) }, { base, value, cmd -> backend.commandApi(cmd).edits.profile(base, value) }, coordinator, command, active = { true }).adoptIdentity(callId,expectedGeneration) }
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
            recordingStore.persistSettings(RecordingSettings(recordingEnabled = true,analysis = connection,transcription = connection.copy(apiKey = "fixture-secret-two")))
            val identity = runtime.service.observeIncoming("session","31234",now,null)!!
            recordingId = identity.id
            runtime.service.importRecording(identity.id,identity.number,identity.startedAt,DocumentRef("content://tool-fixture/audio")).requiredValue()
            val archive = recordingStore.get(recordingId)!!
            recordingStore.compareAndSet(recordingId,archive.generation,archive.copy(generation = archive.generation+1,transcript = "FULL-TAIL",analysis = CallAnalysis(name = "self",organization = "new unit",inferredIdentity = "inference")))
            foreground.attachHost()
            calls.clear()
        }
        override fun close() { updates.close(); foreground.invalidate(); runtime.close(); audio.close(); diagnostics.close(); store.close() }
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
                val file = if(uri.lastPathSegment == "calendar") calendar else if(uri.lastPathSegment == "csv"||mode.contains("w")) csv else audio
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

    @Test fun adoptionPreservesNativeIdsAndFields()=runBlocking {
        provider()
        val cases=listOf("short","blank-name","blank-department","long-unicode","legacy-unindexed","linked-positive","statistics-off","mismatch","generation-change","unrelated-change","deleted","changed-native-after-disclosure","changed-linked-after-disclosure")
        for((offset,kind) in cases.withIndex())Fixture("identity-$offset").use{f->
            f.seed()
            if(kind=="statistics-off")f.backend.settings.setStatisticsEnabled(false).requiredValue()
            val name=when(kind){"blank-name"->"   ";"long-unicode"->"馃榾鍚堟垚".repeat(1500);else->" Synthetic "}
            val department=if(kind=="blank-department")" \t "else" Unit "
            val old=f.recordingStore.get(f.recordingId)!!
            f.runtime.archiveCommands.compareAndSet(old.callId,old.generation,old.copy(generation=old.generation+1,analysis=CallAnalysis(name=name,organization=department)), null, false)
            if(kind=="legacy-unindexed")android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("${f.namespace}_recording.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{db->listOf("archive_projection","archive_text_fields","archive_text_chunks").forEach{db.delete(it,null,null)}}
            val requested=if(kind in setOf("linked-positive","changed-linked-after-disclosure")){f.recordingStore.insertStatisticsLink(1,"session");1L}else f.recordingId
            if(kind=="deleted")f.runtime.service.delete(f.recordingId).requiredValue()
            val root=f.api()
            val wrapped=object:CallringUiApi by root {
                override fun commandApi(command:CommandContext):CallringUiApi {

                    val bound=root.commandApi(command)
                    return object:CallringUiApi by bound {
                        override suspend fun adoptIdentity(callId:Long,expectedGeneration:Long):ApiResult<CallerProfile> {
                            val result=bound.adoptIdentity(callId,expectedGeneration)
                            if(kind=="mismatch")f.backend.callers.saveProfile(SaveCallerProfile("31234","incorrect","incorrect",1,true)).requiredValue()
                            return result
                        }
                    }
                }
            }
            val queries=QueryService(listOf(f.store.fixtureScopedReads(f.coordinator),f.recordingStore.scopedReads()),revision=f.coordinator::marker)
            if(kind.endsWith("after-disclosure")){
                queries.get(AiDomain.D16,requested.toString()).requiredValue()
                val disclosed=f.recordingStore.get(f.recordingId)!!
                f.runtime.archiveCommands.compareAndSet(disclosed.callId,disclosed.generation,disclosed.copy(generation=disclosed.generation+1), null, false)
            }
            val executor=ToolExecutor({wrapped},f.coordinator,f.foreground,f.resources,queries=queries, numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher, documentWriteVerified={false})
            val engine=ToolBatchEngine({wrapped},executor,queries,f.coordinator,"s","t",1,{true})
            val host=launch {f.foreground.pending.filterNotNull().collect{ticket->
                val token=f.foreground.attachHost()
                if(f.foreground.beginLaunch(ticket.requestId,token)){
                    if(kind=="generation-change"){val archive=f.recordingStore.get(f.recordingId)!!;f.runtime.archiveCommands.compareAndSet(archive.callId,archive.generation,archive.copy(generation=archive.generation+1), null, false)}
                    if(kind=="unrelated-change")f.backend.callers.saveProfile(SaveCallerProfile("99999","unrelated","",null)).requiredValue()
                    f.foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.VERIFIED))
                }
            }}
            try{
                val receipt=withTimeout(10000){engine.execute(listOf(ConversationToolCall("adopt","recordings_adoptIdentity",aiObject("callId" to aiNumber(requested)))))}.single()
                val actual=f.backend.callers.getEditor("31234").requiredValue().draft
                when(kind){
                    "deleted","generation-change","changed-native-after-disclosure","changed-linked-after-disclosure"->{assertEquals(kind,ToolStatus.FAILED,receipt.status);assertEquals("old",actual.name)}
                    "mismatch"->{assertEquals(ToolStatus.COMMITTED,receipt.status);assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,receipt.verification);assertFalse(engine.complete)}
                    else->{assertEquals(kind,VerificationStatus.VERIFIED,receipt.verification);assertTrue(kind,engine.complete);assertEquals(name.ifBlank{"old"}.trim(),actual.name);assertEquals(department.ifBlank{"old unit"}.trim(),actual.department);assertEquals(1L,actual.positionId);assertTrue(actual.whitelisted)}
                }
            }finally{host.cancelAndJoin()}
        }
    }

    @Test fun diagnosticsCaptureRealServiceAndCodecMetricsWithoutBusinessPayloads()=runBlocking<Unit> {
        provider();Fixture("999").use{f->
            f.seed();var round=0;val sent=mutableListOf<org.json.JSONObject>()
            fun response(name:String?,arguments:AiJson.Obj=aiObject()):String {
                val message=org.json.JSONObject().put("role","assistant").put("content",if(name==null)"done"else org.json.JSONObject.NULL)
                if(name!=null)message.put("tool_calls",org.json.JSONArray().put(org.json.JSONObject().put("id","c$round").put("type","function").put("function",org.json.JSONObject().put("name",name).put("arguments",arguments.encode()))))
                return org.json.JSONObject().put("choices",org.json.JSONArray().put(org.json.JSONObject().put("finish_reason",if(name==null)"stop"else"tool_calls").put("message",message))).toString()
            }
            val client=CompatibleConversationClient{url->object:java.net.HttpURLConnection(url){
                val output=java.io.ByteArrayOutputStream()
                override fun connect()=Unit;override fun disconnect()=Unit;override fun usingProxy()=false
                override fun getOutputStream()=output;override fun getResponseCode():Int{sent+=org.json.JSONObject(output.toString("UTF-8"));return 200}
                override fun getInputStream():java.io.InputStream=java.io.ByteArrayInputStream(when(round++){0->response("discover_tools",aiObject("exactNames" to AiJson.Arr(listOf(aiString("root_checkNumber")))));1->response("root_checkNumber",aiObject("raw" to aiString("31234")));else->response(null)}.toByteArray())
            }}
            val queries=QueryService(listOf(f.store.fixtureScopedReads(f.coordinator)),revision=f.coordinator::marker)
            val configured=f.recordingStore.settings().copy(analysis=f.recordingStore.settings().analysis.copy(model="qwen3.8-flash"),conversationCompatibility=ConversationCompatibility.QWEN_FAST)
            val service=ConversationService({f.api()},queries,{configured},f.coordinator,{client}, numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher, documentWriteVerified={false})
            try{
                service.send(service.state.value.sessionId,"synthetic diagnostic capture");withTimeout(10000){service.state.first{!it.busy}}
                val state=service.state.value;assertEquals(ConversationPhase.COMPLETED,state.phase);assertEquals(3,sent.size);assertEquals(3,state.exchanges.size)
                state.exchanges.zip(sent).forEach{(m,wire)->assertEquals(wire.getJSONArray("tools").length(),m.schemaCount);assertEquals(wire.getJSONArray("messages").length(),m.contextMessages);assertEquals(mapOf("enable_thinking" to false,"preserve_thinking" to false),m.requestFlags);assertEquals(200,m.httpStatus)}
                assertTrue(state.receipts.isNotEmpty());assertTrue(state.phaseSpans.isNotEmpty());assertTrue(state.diagnosticsComplete)
                val serialize=Class.forName("com.greatwall.callring.RealAiConversationDeviceTestKt").getDeclaredMethod("roundJson",String::class.java,ConversationState::class.java).apply{isAccessible=true}
                val sentinel="SYNTHETIC-PRIVATE-BUSINESS"
                val diagnostic=serialize.invoke(null,"synthetic capture",state.copy(receipts=state.receipts.map{it.copy(value=aiString(sentinel))},outcome=aiObject("complete" to aiBool(true),"plans" to AiJson.Arr(listOf(aiObject("expectedCount" to aiNumber(1),"targets" to AiJson.Arr(listOf(aiString(sentinel))))))))) as org.json.JSONObject
                assertFalse(diagnostic.toString().contains(sentinel));assertFalse(diagnostic.toString().contains("fixture-secret"))
                assertEquals(state.receipts.map{it.operationId},(0 until diagnostic.getJSONArray("receipts").length()).map{diagnostic.getJSONArray("receipts").getJSONObject(it).getString("stepId")})
                val proof=serialize.invoke(null,"synthetic capture",state) as org.json.JSONObject
                proof.put("source","synthetic HTTP boundary; production service/codec; isolated local API fixture").put("actualProviderRun",false)
                java.io.File("build/qa/review-diagnostics.json").apply{parentFile.mkdirs();writeText(proof.toString(2))}
            }finally{service.close()}
        }
    }
    @Test fun toolsUseProductionApisAndRejectBadSchema() = runBlocking {
        provider()
        val registry = ToolRegistry(OperationCatalog(OperationCatalog.defaultDefinitions().shuffled(kotlin.random.Random(1202))))
        val covered = mutableSetOf<OperationId>()
        for(definition in registry.definitions.filter { it.id !in setOf(OperationId.DISCOVER_TOOLS,OperationId.DATA_QUERY,OperationId.DATA_GET,OperationId.DATA_AGGREGATE,OperationId.DATA_READ_TEXT,OperationId.TASK_PREPARE,OperationId.CALLERS_SAVE_PROFILES,OperationId.CALLERS_GET_CALL_DETAIL,OperationId.CALLERS_DELETE_NUMBER) }) Fixture(definition.id.name).use { fixture ->
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
            val executor = ToolExecutor({ root },fixture.coordinator,fixture.foreground,fixture.resources,registry,queries=fixture.queries,numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher, documentWriteVerified={fixture.docs.verifyWrite(it)})
            fun scope(id: String) = ToolExecutionContext("session","turn",1,CommandContext(id,fixture.coordinator.capture(),1) { true })
            val call = ConversationToolCall("call",definition.name,parseAiJson(arguments(definition.id,fixture.recordingId)) as AiJson.Obj)
            val engine=ToolBatchEngine({root},executor,fixture.queries,fixture.coordinator,"session","turn",1,{true})
            val beforeSourceQueries=fixture.queries.sourceQueries.get()
            val receipt = withTimeout(10000) { engine.execute(listOf(call)).single() }
            assertTrue("${definition.id} ${definition.name}: $receipt",receipt.status in setOf(ToolStatus.READ,ToolStatus.COMMITTED,ToolStatus.QUEUED,ToolStatus.LAUNCHED))
            assertNull("${definition.id}: ${receipt.error}",receipt.error)
            val expected = when(definition.id) { OperationId.CALLERS_SAVE_PROFILE -> "edits.profile"; OperationId.SETTINGS_SAVE_APPEARANCE -> "edits.appearance"; OperationId.RECORDINGS_GET_SETTINGS -> "ai.getRecordingView"; OperationId.RECORDINGS_SAVE_SETTINGS -> "ai.patchCombined"; OperationId.RECORDINGS_TEST_CONNECTION -> "ai.testSlot"; OperationId.RECORDINGS_ADOPT_IDENTITY -> "root.adoptIdentity"; OperationId.AI_GET_SETTINGS -> "ai.getAiView"; OperationId.AI_PATCH_SETTINGS -> "ai.patchAi"; OperationId.AI_EDIT_CREDENTIAL -> "ai.patchAi"; OperationId.SYSTEM_REQUEST_CAPABILITY,OperationId.SYSTEM_OPEN_APPLICATION_SETTINGS -> "runtime.getStatus"; OperationId.SYSTEM_PIN_WIDGET -> "widgets.listBindings"; OperationId.SYSTEM_TEST_OVERLAY -> "foreground.TEST_OVERLAY"; OperationId.SYSTEM_PREVIEW_CALL -> "callers.getCallDetail"; OperationId.AI_COPY_CONNECTION -> "ai.copyConnection"; else -> definition.name.replace('_','.') }
            if(definition.execution==ExecutionStrategy.SOURCE) {
                assertTrue("${definition.id} must access authoritative source",fixture.queries.sourceQueries.get()>beforeSourceQueries)
                assertTrue("${definition.id} missing source proof",receipt.value!=AiJson.Null)
                val expectedDomains=when(definition.id) {
                    OperationId.HOME_LOAD -> setOf(AiDomain.D10,AiDomain.D06,AiDomain.D19,AiDomain.D05)
                    OperationId.CALLERS_SEARCH -> setOf(AiDomain.D01)
                    OperationId.CALLERS_GET_EDITOR -> setOf(AiDomain.D01,AiDomain.D02)
                    OperationId.CALLERS_GET_OVERVIEW -> setOf(AiDomain.D01,AiDomain.D05,AiDomain.D06,AiDomain.D04)
                    OperationId.CALLERS_GET_LATEST_CALL -> setOf(AiDomain.D04)
                    OperationId.REPORTS_LOAD -> setOf(AiDomain.D05,AiDomain.D04)
                    OperationId.ROOT_CHECK_NUMBER,OperationId.SETTINGS_GET_NUMBER_RULE,OperationId.SETTINGS_GET_SUMMARY,OperationId.SETTINGS_GET_QUIET_HOURS,OperationId.SETTINGS_GET_SCORE_SETTINGS -> setOf(AiDomain.D10)
                    OperationId.SETTINGS_GET_POSITIONS -> setOf(AiDomain.D02)
                    OperationId.SETTINGS_GET_WORK_SCHEDULE -> setOf(AiDomain.D06)
                    OperationId.SETTINGS_GET_CALENDAR -> setOf(AiDomain.D07)
                    OperationId.SETTINGS_GET_LEAVES -> setOf(AiDomain.D09)
                    OperationId.SETTINGS_GET_APPEARANCE -> setOf(AiDomain.D11)
                    OperationId.RINGTONE_GET_SETTINGS -> setOf(AiDomain.D12)
                    OperationId.RUNTIME_GET_STATUS -> setOf(AiDomain.D19)
                    OperationId.RUNTIME_GET_DIAGNOSTICS -> setOf(AiDomain.D20)
                    OperationId.WIDGETS_GET_BINDING,OperationId.WIDGETS_LIST_BINDINGS -> setOf(AiDomain.D18)
                    OperationId.RECORDINGS_LIST_ARCHIVES,OperationId.RECORDINGS_GET_ARCHIVE -> setOf(AiDomain.D16)
                    OperationId.RECORDINGS_GET_SETTINGS -> setOf(AiDomain.D13,AiDomain.D14)
                    OperationId.RECORDINGS_SYSTEM_RECORDINGS -> setOf(AiDomain.D17)
                    OperationId.AI_GET_SETTINGS -> setOf(AiDomain.D14)
                    else -> error("Missing source-domain test expectation for ${definition.id}")
                }
                expectedDomains.forEach { domain -> assertTrue("${definition.id} missing $domain source stamp",(receipt.value.encode()+receipt.verificationEvidence.encode()).contains("\"domain\":\"${domain.name}\"")) }

            } else assertTrue("${definition.id} missing real API $expected in ${fixture.calls}",expected in fixture.calls)
            if(definition.id==OperationId.RECORDINGS_GET_SETTINGS) {
                assertTrue("O51 must read both safe source views",fixture.queries.sourceQueries.get()-beforeSourceQueries>=2)
                assertFalse("O51 must not read raw credential settings", "recordings.getSettings" in fixture.calls)
            }
            assertFalse(receipt.value.encode().contains("fixture-secret-"))
            assertFalse(receipt.value.encode().contains("content://"))
            if(definition.id==OperationId.CALLERS_SAVE_PROFILE) { val saved = root.callers.getEditor("31234").requiredValue().draft; assertEquals("changed",saved.name); assertEquals(1L,saved.positionId); assertTrue(saved.whitelisted) }
            if(definition.id==OperationId.REPORTS_EXPORT_CSV) assertTrue(File(context.cacheDir,"tool-output.csv").readText().contains("31234"))
            if(definition.id in setOf(OperationId.REPORTS_EXPORT_CSV,OperationId.RECORDINGS_PLAY,OperationId.RECORDINGS_STOP)) {
                val queries=QueryService(listOf(object:ScopedReadPort {
                    override val domains=setOf(AiDomain.D19)
                    override fun version(domain:AiDomain)=0L
                    override fun query(spec:QuerySpec,after:String?)=error("unused")
                    override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,aiObject("recordingPlayback" to com.greatwall.callring.application.conversation.query.recordingPlaybackValue(fixture.runtime.service.playbackObservation())))
                }))
                assertEquals("Authoritative readback ${definition.id}",VerificationStatus.VERIFIED,ReceiptVerifier(queries).verify(definition.id,call,receipt).verification)
            }
            if(definition.id==OperationId.RECORDINGS_IMPORT_RECORDING) assertNotNull(fixture.recordingStore.get(fixture.recordingId)!!.audioPath)
            if(definition.id==OperationId.RECORDINGS_RETRY) assertEquals(ArchiveStatus.QUEUED,fixture.recordingStore.get(fixture.recordingId)!!.status)
            if(definition.id==OperationId.RECORDINGS_DELETE) assertTrue(fixture.recordingStore.isDeleted(fixture.recordingId))
            if(definition.id==OperationId.RECORDINGS_ADOPT_IDENTITY) { val saved = root.callers.getEditor("31234").requiredValue().draft; assertEquals("self",saved.name); assertEquals(1L,saved.positionId); assertTrue(saved.whitelisted) }
            if(definition.id==OperationId.AI_COPY_CONNECTION) assertEquals("fixture-secret-one",fixture.recordingStore.settings().transcription.apiKey)
            val before = fixture.coordinator.capture()
            val invalid = executor.execute(call.copy(id = "invalid",arguments = AiJson.Obj(call.arguments.fields + ("unexpected" to aiBool(true)))),scope("invalid-op"))
            assertEquals(ToolStatus.FAILED,invalid.status)
            assertEquals(before,fixture.coordinator.capture())
            covered += definition.id
            println("TOOL_PROOF ${definition.id} ${definition.name} status=${receipt.status} api=$expected invalid=FAILED")
            host.cancelAndJoin()
        }
        assertEquals(76,covered.size)
    }
    @Test fun allExtensionsExecuteThroughShuffledCatalogAndCompositeChildReadback()=runBlocking {
        provider()
        Fixture("named-extensions").use { f ->
            f.seed()
            val registry=ToolRegistry(OperationCatalog(OperationCatalog.defaultDefinitions().shuffled(kotlin.random.Random(1203))))
            val executor=ToolExecutor({f.api()},f.coordinator,f.foreground,f.resources,registry,queries=f.queries,numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher, documentWriteVerified={f.docs.verifyWrite(it)})
            val engine=ToolBatchEngine({f.api()},executor,f.queries,f.coordinator,"session","turn",1,{true})
            var sequence=0
            val covered=linkedSetOf<OperationId>()
            suspend fun call(id:OperationId,args:AiJson.Obj=aiObject()):ToolReceipt {
                val result=withTimeout(10000){engine.execute(listOf(ConversationToolCall("extension-${sequence++}",registry.catalog[id].name,args))).single()}
                assertNull("$id: $result",result.error)
                covered+=id
                return result
            }
            val before=f.queries.sourceQueries.get()
            call(OperationId.DISCOVER_TOOLS)
            assertEquals(before,f.queries.sourceQueries.get())
            val page=call(OperationId.DATA_QUERY,aiObject("domain" to aiString("D01"),"filter" to aiObject("number" to aiString("31234"))))
            assertTrue(page.value.encode().contains("old unit"));assertTrue(AiDomain.D01 in f.queriedDomains)
            val archive=call(OperationId.DATA_GET,aiObject("domain" to aiString("D16"),"key" to aiString(f.recordingId.toString()))).value as AiJson.Obj
            val fields=(archive["value"] as AiJson.Obj)["textFields"] as AiJson.Obj
            val reference=(fields["transcript"] as AiJson.Obj)["reference"]
            val text=call(OperationId.DATA_READ_TEXT,aiObject("reference" to reference))
            assertEquals("FULL-TAIL",(text.value as AiJson.Obj)["text"].string())
            val count=call(OperationId.DATA_AGGREGATE,aiObject("domain" to aiString("D01")))
            assertTrue(count.value.encode().contains("count"))
            val detail=call(OperationId.CALLERS_GET_CALL_DETAIL,aiObject("callId" to aiNumber(1)))
            assertTrue(detail.value.encode().contains("31234"));assertTrue(AiDomain.D03 in f.queriedDomains)
            val plan=call(OperationId.TASK_PREPARE,aiObject("kind" to aiString("CREATE_PROFILES"),"expectedCount" to aiNumber(2))).value as AiJson.Obj
            val batch=call(OperationId.CALLERS_SAVE_PROFILES,aiObject("taskId" to plan["taskId"],"items" to AiJson.Arr(listOf("34567","34568").map { aiObject("number" to aiString(it),"name" to aiString("synthetic-$it")) })))
            assertEquals(VerificationStatus.VERIFIED,batch.verification)
            assertEquals(2,(batch.value as AiJson.Obj)["verifiedCount"].long().toInt())
            assertEquals("synthetic-34567",f.backend.callers.getEditor("34567").requiredValue().draft.name)
            val host=launch { f.foreground.pending.filterNotNull().collect { ticket ->
                val token=f.foreground.attachHost()
                if(f.foreground.beginLaunch(ticket.requestId,token)) f.foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.VERIFIED))
            } }
            try {
                val deleted=call(OperationId.CALLERS_DELETE_NUMBER,aiObject("number" to aiString("31234")))
                assertEquals(VerificationStatus.VERIFIED,deleted.verification)
                assertTrue(f.backend.callers.getEditor("31234").requiredValue().draft.name.isEmpty())
                assertTrue(f.recordingStore.isDeleted(f.recordingId))
                assertTrue(engine.complete)
                assertEquals(setOf(OperationId.DISCOVER_TOOLS,OperationId.DATA_QUERY,OperationId.DATA_GET,OperationId.DATA_AGGREGATE,OperationId.DATA_READ_TEXT,OperationId.TASK_PREPARE,OperationId.CALLERS_SAVE_PROFILES,OperationId.CALLERS_GET_CALL_DETAIL,OperationId.CALLERS_DELETE_NUMBER),covered)
            } finally {host.cancelAndJoin()}
        }
    }
    @Test fun realConfirmationTargetsCannotReuseCancelledOrChangedApproval() = runBlocking {
        provider()
        for(operation in listOf(OperationId.SETTINGS_DELETE_POSITION,OperationId.SETTINGS_DELETE_LEAVE,OperationId.RECORDINGS_TEST_CONNECTION,OperationId.RECORDINGS_DELETE,OperationId.RECORDINGS_ADOPT_IDENTITY)) Fixture("confirm-${operation.name}").use { fixture ->
            fixture.seed()
            val root = fixture.api()
            val executor = ToolExecutor({ root },fixture.coordinator,fixture.foreground,fixture.resources, queries=fixture.queries, numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher, documentWriteVerified={false})
            val definition = executor.registry.definitions.single { it.id == operation }
            for(changed in listOf(false,true)) {
                val command = CommandContext(java.util.UUID.randomUUID().toString(),fixture.coordinator.capture(),1) { true }
                val result = async { executor.execute(ConversationToolCall("call",definition.name,parseAiJson(arguments(operation,fixture.recordingId)) as AiJson.Obj),ToolExecutionContext("s","t",1,command)) }
                val ticket = withTimeout(5000) { fixture.foreground.pending.filterNotNull().first() }
                when(val target = ticket.confirmation) {
                    is ConfirmationTarget.Position -> { assertEquals(2L,target.id); assertEquals("Role2",target.name) }
                    is ConfirmationTarget.Leave -> { assertEquals(1L,target.id); assertEquals("note",target.note) }
                    is ConfirmationTarget.Connection -> { assertEquals("https://example.com/v1",target.connection.url); assertEquals(AiSlot.ANALYSIS,target.slot) }
                    is ConfirmationTarget.Recording -> { assertEquals(fixture.recordingId,target.id); assertEquals("31234",target.number); if(operation == OperationId.RECORDINGS_ADOPT_IDENTITY) assertEquals("self",target.adoption!!.name) }
                    is ConfirmationTarget.NumberDeletion -> fail("Number deletion is covered by its own tool test")
                    null -> fail("Missing target $operation")
                }
                val token = fixture.foreground.attachHost()
                assertTrue(fixture.foreground.beginLaunch(ticket.requestId,token))
                if(changed) when(operation) {
                    OperationId.SETTINGS_DELETE_POSITION -> fixture.backend.settings.savePosition(SavePosition(2,"changed-role",2,true)).requiredValue()
                    OperationId.SETTINGS_DELETE_LEAVE -> fixture.backend.settings.saveLeave(SaveLeave(1,LeavePeriod.Timed(now+1000,now+2000),"changed-note")).requiredValue()
                    OperationId.RECORDINGS_TEST_CONNECTION -> fixture.runtime.aiSettings.patchAi(fixture.runtime.aiSettings.getAiView().requiredValue(),AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("changed-model")))).requiredValue()
                    else -> { val old = fixture.recordingStore.get(fixture.recordingId)!!; assertTrue(fixture.runtime.archiveCommands.compareAndSet(old.callId,old.generation,old.copy(generation = old.generation+1,analysis = CallAnalysis(name = "changed-self")), null, false)) }
                }
                fixture.foreground.deliver(ticket.requestId,ForegroundHostResult(if(changed) ForegroundResultStatus.VERIFIED else ForegroundResultStatus.CANCELLED))
                assertEquals(if(changed) ToolStatus.FAILED else ToolStatus.CANCELLED,withTimeout(5000) { result.await() }.status)
                assertFalse(fixture.recordingStore.isDeleted(fixture.recordingId))
                assertEquals("old",fixture.backend.callers.getEditor("31234").requiredValue().draft.name)
                assertEquals(0,fixture.client.tests)
                assertTrue(fixture.backend.settings.getPositions().requiredValue().options.any { it.id == 2L })
                assertTrue(fixture.backend.settings.getLeaves().requiredValue().any { it.id == 1L })
            }
        }
    }
    private fun arguments(operation: OperationId, recordingId: Long): String = when(operation) {
        OperationId.ROOT_CHECK_NUMBER -> """{"raw":"31234"}"""
        OperationId.CALLERS_SEARCH -> """{"text":"31234","page":{"pageSize":200}}"""
        OperationId.CALLERS_GET_EDITOR,OperationId.CALLERS_GET_OVERVIEW -> """{"number":"31234"}"""
        OperationId.CALLERS_SAVE_PROFILE -> """{"number":"31234","name":"changed"}"""
        OperationId.CALLERS_SET_WHITELISTED -> """{"number":"31234","enabled":false}"""
        OperationId.REPORTS_LOAD,OperationId.REPORTS_EXPORT_CSV -> """{"number":"31234","range":{"startDate":"2026-01-01","endDateInclusive":"2026-12-31","zoneId":"UTC"}}"""
        OperationId.SETTINGS_SET_STATISTICS_ENABLED,OperationId.SETTINGS_SET_OVERLAY_ENABLED,OperationId.SETTINGS_SET_QUIET_ENABLED,OperationId.RINGTONE_SET_ENABLED -> """{"enabled":true}"""
        OperationId.SETTINGS_SAVE_QUIET_HOURS -> """{"mode":"CUSTOM","weeklyWindows":{"1":[{"startMinute":540,"endMinute":600}]}}"""
        OperationId.SETTINGS_SET_QUIET_MODE -> """{"mode":"NON_WORK"}"""
        OperationId.SETTINGS_SAVE_POSITION -> """{"id":1,"name":"changed role"}"""
        OperationId.SETTINGS_DELETE_POSITION,OperationId.SETTINGS_SET_MY_POSITION -> """{"id":2}"""
        OperationId.SETTINGS_SAVE_WORK_SCHEDULE -> """{"zoneId":"UTC"}"""
        OperationId.SETTINGS_SYNCHRONIZE_CALENDAR -> """{"year":2026}"""
        OperationId.SETTINGS_SAVE_DATE_OVERRIDE -> """{"date":"2026-10-19","kind":"REST","windows":[]}"""
        OperationId.SETTINGS_DELETE_DATE_OVERRIDE -> """{"date":"2026-10-19"}"""
        OperationId.SETTINGS_SAVE_LEAVE -> """{"id":1,"note":"changed note"}"""
        OperationId.SETTINGS_DELETE_LEAVE -> """{"id":1}"""
        OperationId.SETTINGS_SAVE_NUMBER_RULE -> """{"base":{"rules":[{"prefix":"3","length":5}],"revision":0},"rules":[{"prefix":"8","length":6}]}"""
        OperationId.SETTINGS_GET_NUMBER_RULE -> """{}"""
        OperationId.SETTINGS_SAVE_SCORE_SETTINGS -> """{"repeatMinutes":15}"""
        OperationId.SETTINGS_SAVE_APPEARANCE -> """{"colorMode":"DARK"}"""
        OperationId.WIDGETS_GET_BINDING,OperationId.WIDGETS_REMOVE_BINDING -> """{"widgetId":1}"""
        OperationId.WIDGETS_SAVE_BINDING -> """{"widgetId":2,"number":"31234"}"""
        OperationId.RECORDINGS_SAVE_SETTINGS -> """{"recording":{"automaticAnalysis":false},"ai":{"analysis":{"model":"changed"}}}"""
        OperationId.RECORDINGS_TEST_CONNECTION -> """{"slot":"ANALYSIS"}"""
        OperationId.RECORDINGS_GET_ARCHIVE,OperationId.RECORDINGS_IMPORT_RECORDING,OperationId.RECORDINGS_RETRY,OperationId.RECORDINGS_DELETE,OperationId.RECORDINGS_PLAY,OperationId.RECORDINGS_ADOPT_IDENTITY -> """{"callId":$recordingId}"""
        OperationId.AI_PATCH_SETTINGS -> """{"context":65536}"""
        OperationId.AI_EDIT_CREDENTIAL -> """{"slot":"TRANSCRIPTION"}"""
        OperationId.SYSTEM_REQUEST_CAPABILITY -> """{"capability":"MICROPHONE"}"""
        OperationId.SYSTEM_PREVIEW_CALL -> """{"callId":1}"""
        OperationId.AI_COPY_CONNECTION -> """{"from":"ANALYSIS","to":"TRANSCRIPTION"}"""
        else -> "{}"
    }
}
