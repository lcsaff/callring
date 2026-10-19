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
        val docs = ReadbackDocumentStore(AndroidDocumentStore(context.contentResolver))
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

    @Test fun adoptionThroughProductionSourcesAndBatchEngineHandlesNativeIdsTextRefsAndPreservedFields()=runBlocking {
        provider()
        val cases=listOf("short","blank-name","blank-department","long-unicode","legacy-unindexed","linked-positive","statistics-off","mismatch","generation-change","unrelated-change","deleted","changed-native-after-disclosure","changed-linked-after-disclosure")
        for((offset,kind) in cases.withIndex())Fixture(800+offset).use{f->
            f.seed()
            if(kind=="statistics-off")f.backend.settings.setStatisticsEnabled(false).requiredValue()
            val name=when(kind){"blank-name"->"   ";"long-unicode"->"😀合成".repeat(1500);else->" Synthetic "}
            val department=if(kind=="blank-department")" \t "else" Unit "
            val old=f.recordingStore.get(f.recordingId)!!
            f.recordingStore.compareAndSet(old.callId,old.generation,old.copy(generation=old.generation+1,analysis=CallAnalysis(name=name,organization=department)))
            if(kind=="legacy-unindexed")android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("${f.namespace}_recording.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{db->listOf("archive_projection","archive_text_fields","archive_text_chunks").forEach{db.delete(it,null,null)}}
            val requested=if(kind in setOf("linked-positive","changed-linked-after-disclosure")){f.recordingStore.linkStatistics(1,"session");1L}else f.recordingId
            if(kind=="deleted")f.runtime.delete(f.recordingId).requiredValue()
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
            val queries=QueryService(listOf(f.store.scopedReads(f.coordinator),f.recordingStore.scopedReads()),revision=f.coordinator::marker)
            if(kind.endsWith("after-disclosure")){
                queries.get(AiDomain.D16,requested.toString()).requiredValue()
                val disclosed=f.recordingStore.get(f.recordingId)!!
                f.recordingStore.compareAndSet(disclosed.callId,disclosed.generation,disclosed.copy(generation=disclosed.generation+1))
            }
            val executor=ToolExecutor({wrapped},f.coordinator,f.foreground,f.resources,queries=queries)
            val engine=ToolBatchEngine({wrapped},executor,queries,f.coordinator,"s","t",1,{true})
            val host=launch {f.foreground.pending.filterNotNull().collect{ticket->
                val token=f.foreground.attachHost()
                if(f.foreground.beginLaunch(ticket.requestId,token)){
                    if(kind=="generation-change"){val archive=f.recordingStore.get(f.recordingId)!!;f.recordingStore.compareAndSet(archive.callId,archive.generation,archive.copy(generation=archive.generation+1))}
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
        provider();Fixture(999).use{f->
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
            val queries=QueryService(listOf(f.store.scopedReads(f.coordinator)),revision=f.coordinator::marker)
            val configured=f.recordingStore.settings().copy(analysis=f.recordingStore.settings().analysis.copy(model="qwen3.8-flash"),conversationCompatibility=ConversationCompatibility.QWEN_FAST)
            val service=ConversationService({f.api()},queries,{configured},f.coordinator,{client})
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
            val executor = ToolExecutor({ root },fixture.coordinator,fixture.foreground,fixture.resources,registry,documentWriteVerified={fixture.docs.verifyWrite(it)})
            fun scope(id: String) = ToolExecutionContext("session","turn",1,CommandContext(id,fixture.coordinator.capture(),1) { true })
            val call = ConversationToolCall("call",definition.name,parseConversationJson(arguments(definition.index,fixture.recordingId)) as AiJson.Obj)
            val receipt = withTimeout(10000) { executor.execute(call,scope("op")) }
            assertTrue("${definition.id} ${definition.name}: $receipt",receipt.status in setOf(ToolStatus.READ,ToolStatus.COMMITTED,ToolStatus.QUEUED,ToolStatus.LAUNCHED))
            assertNull("${definition.id}: ${receipt.error}",receipt.error)
            val expected = when(definition.index) { 7 -> "edits.profile"; 37 -> "edits.appearance"; 51 -> "recordings.getSettings"; 52 -> "ai.patchCombined"; 53 -> "ai.testSlot"; 63 -> "root.adoptIdentity"; 64 -> "ai.getAiView"; 65 -> "ai.patchAi"; 66 -> "ai.patchAi"; 67,68 -> "runtime.getStatus"; 69 -> "widgets.listBindings"; 70 -> "foreground.TEST_OVERLAY"; 71 -> "callers.getCallDetail"; 72 -> "ai.copyConnection"; else -> definition.name.replace('_','.') }
            assertTrue("${definition.id} missing real API $expected in ${fixture.calls}",expected in fixture.calls)
            assertFalse(receipt.value.encode().contains("fixture-secret-"))
            assertFalse(receipt.value.encode().contains("content://"))
            if(definition.index == 7) { val saved = root.callers.getEditor("31234").requiredValue().draft; assertEquals("changed",saved.name); assertEquals(1L,saved.positionId); assertTrue(saved.whitelisted) }
            if(definition.index == 10) assertTrue(File(context.cacheDir,"tool-output.csv").readText().contains("31234"))
            if(definition.index in setOf(10,58,59)) {
                val queries=QueryService(listOf(object:ScopedReadPort {
                    override val domains=setOf(AiDomain.D19)
                    override fun version(domain:AiDomain)=0L
                    override fun query(spec:QuerySpec,after:String?)=error("unused")
                    override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,aiObject("recordingPlayback" to fixture.runtime.playbackSnapshot()))
                }))
                assertEquals("Authoritative readback ${definition.index}",VerificationStatus.VERIFIED,ReceiptVerifier(queries).verify(definition.index,call,receipt).verification)
            }
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
    @Test fun realConfirmationTargetsCannotReuseCancelledOrChangedApproval() = runBlocking {
        provider()
        for(index in listOf(20,32,53,57,63)) Fixture(300+index).use { fixture ->
            fixture.seed()
            val root = fixture.api()
            val executor = ToolExecutor({ root },fixture.coordinator,fixture.foreground,fixture.resources)
            val definition = executor.registry.definitions.single { it.index == index }
            for(changed in listOf(false,true)) {
                val command = CommandContext(java.util.UUID.randomUUID().toString(),fixture.coordinator.capture(),1) { true }
                val result = async { executor.execute(ConversationToolCall("call",definition.name,parseConversationJson(arguments(index,fixture.recordingId)) as AiJson.Obj),ToolExecutionContext("s","t",1,command)) }
                val ticket = withTimeout(5000) { fixture.foreground.pending.filterNotNull().first() }
                when(val target = ticket.confirmation) {
                    is ConfirmationTarget.Position -> { assertEquals(2L,target.id); assertEquals("Role2",target.name) }
                    is ConfirmationTarget.Leave -> { assertEquals(1L,target.id); assertEquals("note",target.note) }
                    is ConfirmationTarget.Connection -> { assertEquals("https://example.com/v1",target.connection.url); assertEquals(AiSlot.ANALYSIS,target.slot) }
                    is ConfirmationTarget.Recording -> { assertEquals(fixture.recordingId,target.id); assertEquals("31234",target.number); if(index == 63) assertEquals("self",target.adoption!!.name) }
                    null -> fail("Missing target $index")
                }
                val token = fixture.foreground.attachHost()
                assertTrue(fixture.foreground.beginLaunch(ticket.requestId,token))
                if(changed) when(index) {
                    20 -> fixture.backend.settings.savePosition(SavePosition(2,"changed-role",2,true)).requiredValue()
                    32 -> fixture.backend.settings.saveLeave(SaveLeave(1,LeavePeriod.Timed(now+1000,now+2000),"changed-note")).requiredValue()
                    53 -> fixture.runtime.aiSettings.patchAi(fixture.runtime.aiSettings.getAiView().requiredValue(),AiSettingsPatch(analysis = ConnectionPatch(model = PatchField.Set("changed-model")))).requiredValue()
                    else -> { val old = fixture.recordingStore.get(fixture.recordingId)!!; assertTrue(fixture.recordingStore.compareAndSet(old.callId,old.generation,old.copy(generation = old.generation+1,analysis = CallAnalysis(name = "changed-self")))) }
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
