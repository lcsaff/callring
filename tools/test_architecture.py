from pathlib import Path
import tempfile
import unittest
import check_architecture

class ArchitectureTests(unittest.TestCase):
    def query_source(self, root, module, package, text, name='Projection.kt'):
        path = root / module / 'src/main/kotlin' / name
        path.write_text('package com.greatwall.callring.' + package + '\n' + text)

    def test_bootstrap_cannot_reintroduce_query_projection(self):
        for body in ('fun rows() = SourceRow("current", value)', 'fun view() = safeValue(settings)',
                     'fun parts() = SnapshotPart(data, count = 1)', 'fun value() = aiObject("x" to aiBool(true))',
                     'fun sourceCount(id: String, data: AiJson) = 1L'):
            with self.subTest(body=body):
                self.check_bad(lambda root: self.query_source(root, 'app', 'bootstrap', body))

    def test_query_projector_cannot_take_a_complete_facade(self):
        for dependency in ('BackendGraph', 'CallringBackend', 'CallringUiApi', 'RecordingAssembly', 'CoreAssembly'):
            with self.subTest(dependency=dependency):
                self.check_bad(lambda root: self.query_source(root, 'backend', 'application.conversation.query',
                    'class Projection(private val source: ' + dependency + ')'))

    def test_query_wiring_and_explicit_json_payload_are_allowed(self):
        root = self.tree()
        self.query_source(root, 'app', 'bootstrap', 'val view = WidgetQueryProjection({ stored() }, ::installed)')
        self.query_source(root, 'backend', 'application.conversation.query',
            'class Projection(private val read: () -> AppearanceSettings) { fun json(value: Map<String, Any?>) = safeValue(value) }')
        self.assertEqual([], check_architecture.check(root))

    def test_removed_mixed_files_and_json_observers_cannot_return(self):
        for module, package, body, name in (
            ('app', 'platform', 'fun copyBounded() {}', 'PlatformSafety.kt'),
            ('backend', 'application', 'interface AiClient', 'RecordingPorts.kt'),
            ('backend', 'application', 'class IncomingRecordingSession', 'RecordingAssociations.kt'),
            ('backend', 'application.ports', 'interface RuntimeProbe', 'BackendPorts.kt'),
            ('app', 'data.preferences', 'fun scopedBindingRows() = rows', 'Preferences.kt'),
            ('backend', 'application.recording', 'fun playbackSnapshot() = json', 'RecordingService.kt'),
            ('backend', 'application.recording', 'fun activitySnapshot() = json', 'RecordingWorkflow.kt')):
            self.check_bad(lambda root: self.query_source(root, module, package, body, name))

    def test_designsystem_rejects_business_dependencies_and_old_entries(self):
        bodies = ['import com.greatwall.callring.contracts.settings.AppearanceSettings',
                  'import com.greatwall.callring.ui.state.MutationState',
                  'import com.greatwall.callring.ui.feature.shared.CallerCard',
                  'import com.greatwall.callring.ui.app.bridge.ThemeRegistry',
                  'val DefaultAppearance = appearance', 'class ThemeRegistry',
                  'fun AppearanceSettings.usesDarkColors(systemDark: Boolean) = true',
                  'fun CallerCard() {}', 'fun rememberFormBack() {}',
                  'fun CallringTheme(appearance: AppearanceSettings, content: () -> Unit) {}',
                  'typealias CallerCard = Other']
        for body in bodies:
            with self.subTest(body=body):
                self.check_bad(lambda root: self.query_source(root, 'ui', 'ui.designsystem.components', body))

    def test_old_designsystem_entries_cannot_be_imported_or_qualified(self):
        for symbol in ('DefaultAppearance', 'ThemeRegistry', 'usesDarkColors', 'components.CallerCard', 'components.rememberFormBack'):
            with self.subTest(symbol=symbol):
                self.check_bad(lambda root: self.query_source(root, 'ui', 'ui.feature.sample',
                    'import com.greatwall.callring.ui.designsystem.' + symbol))

    def test_visual_designsystem_and_feature_business_mapping_are_allowed(self):
        root = self.tree()
        self.query_source(root, 'ui', 'ui.designsystem',
            'data class ThemeSpec(val definition: ThemeDefinition, val dark: Boolean)\n'
            'fun CallringTheme(spec: ThemeSpec, content: () -> Unit) {}\n'
            'fun MutationSuccessEffect(completed: Long, busy: Boolean) {}\n'
            'val data: Map<String, Any?> = emptyMap()')
        self.assertEqual([], check_architecture.check(root))

    def test_entrypoint_cannot_receive_complete_graph_context_or_api(self):
        for dependency in ('BackendGraph', 'BackendProvider', 'CallringBackend', 'CallringUiApi',
                           'Context', 'Service', 'JobService', 'RecordingApi', 'SettingsApi'):
            with self.subTest(dependency=dependency):
                self.check_bad(lambda root: self.query_source(root, 'app', 'entrypoint.calling',
                    'class Controller(private val resource: ' + dependency + ')'))

    def test_registered_shell_cannot_reintroduce_workflow(self):
        for name, body in (('RuntimeReceiver.kt', 'worker.execute { session.idle(now()) }'),
                           ('CalendarSyncJobService.kt', 'for (year in CalendarRefreshPolicy.years(today)) {}'),
                           ('SyncJobService.kt', 'var retry = false'),
                           ('RecordingJobService.kt', 'val token = AtomicBoolean(false)'),
                           ('CallCaptureService.kt', 'synchronized(this) { destroyed = true }'),
                           ('RingPlaybackService.java', 'private PreparationGate preparation;')):
            self.check_bad(lambda root: self.query_source(root, 'app', '', body, name))

    def test_typed_resources_and_android_shell_wiring_are_allowed(self):
        root = self.tree()
        self.query_source(root, 'app', 'entrypoint.recording',
            'class Controller(val newMedia: () -> Media, val plan: () -> CapturePlan) { interface Media { fun prepare() } }')
        self.query_source(root, 'app', '',
            'import android.app.Service\nclass CallCaptureService : Service() { '
            'val controller = CaptureServiceController({ graph.recordings.service.capturePlan(true, true, false) })\n'
            'fun media() { val recorder = MediaRecorder(); recorder.prepare() } }', 'CallCaptureService.kt')
        self.assertEqual([], check_architecture.check(root))

    def tree(self, mutation=None):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for name, dependencies in {'contracts': '', 'backend': "implementation project(':contracts')", 'ui': "implementation project(':contracts')", 'app': "implementation project(':contracts')\nimplementation project(':backend')\nimplementation project(':ui')"}.items():
            (root/name/'src/main/kotlin').mkdir(parents=True)
            (root/name/'build.gradle').write_text(dependencies)
        if mutation: mutation(root)
        return root
    def check_bad(self, mutation):
        self.assertTrue(check_architecture.check(self.tree(mutation)))
    def test_domain_contract_import(self): self.check_bad(lambda r: (r/'backend/src/main/kotlin/Bad.kt').write_text('package com.greatwall.callring.domain\nimport com.greatwall.callring.contracts.settings.TimeWindow'))
    def test_domain_application_import(self): self.check_bad(lambda r: (r/'backend/src/main/kotlin/Bad.kt').write_text('package com.greatwall.callring.domain.model\nimport com.greatwall.callring.application.Bad'))
    def domain_source(self, root, text, source_set="main"):
        path = root/f"backend/src/{source_set}/kotlin/Domain.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package com.greatwall.callring.domain.model\n" + text)
    def test_domain_native_values_and_uri_are_allowed(self):
        root = self.tree(lambda r: self.domain_source(r, "import java.time.ZoneId\nimport java.net.URI\nimport com.greatwall.callring.domain.model.TimeWindow"))
        self.assertEqual([], check_architecture.check(root))
    def test_domain_io_is_rejected(self):
        for dependency in ("java.io.File", "java.net.URL", "java.nio.file.Files", "java.sql.Connection", "kotlinx.coroutines.Dispatchers"):
            with self.subTest(dependency=dependency):
                self.check_bad(lambda r: self.domain_source(r, "import " + dependency))
    def test_domain_fully_qualified_contract_is_rejected(self):
        self.check_bad(lambda r: self.domain_source(r, "val value: com.greatwall.callring.contracts.settings.TimeWindow? = null"))
    def test_domain_other_source_set_is_checked(self):
        self.check_bad(lambda r: self.domain_source(r, "import com.greatwall.callring.contracts.settings.TimeWindow", "test"))
    def test_valid_tree(self): self.assertEqual([], check_architecture.check(self.tree()))
    def test_concrete_backend_store_cast_is_rejected(self):
        for value in ('store as SqliteCoreStorageResource', 'store as? com.greatwall.callring.data.database.SqliteCoreStorageResource', 'store.asSqliteCoreStorageResource()', '(SqliteCoreStorageResource) store'):
            with self.subTest(value=value):
                self.check_bad(lambda r: (r/'app/src/main/kotlin/Bad.kt').write_text('val source = ' + value))
    def test_fully_qualified_relocated_resource_cast_is_rejected(self):
        resource = 'com.greatwall.callring.data.database.SqliteCoreStorageResource'
        for value in (f'store as {resource}', f'store as? {resource}', f'({resource}) store'):
            with self.subTest(value=value):
                self.check_bad(lambda r: (r/'app/src/main/kotlin/Bad.kt').write_text('val source = ' + value))
    def test_other_sealed_or_keystore_casts_are_allowed(self):
        root = self.tree(lambda r: (r/'app/src/main/kotlin/Good.kt').write_text('val json = value as? AiJson.Obj\nval key = value as java.security.KeyStore\nval other = value as? com.greatwall.callring.data.database.OtherResource'))
        self.assertEqual([], check_architecture.check(root))
    def test_contract_project_dependency(self): self.check_bad(lambda r: (r/'contracts/build.gradle').write_text("api project(':backend')"))
    def test_contract_android_import(self): self.check_bad(lambda r: (r/'contracts/src/main/kotlin/Bad.kt').write_text('import android.net.Uri'))
    def test_ui_test_backend_dependency(self): self.check_bad(lambda r: (r/'ui/build.gradle').write_text("testImplementation project(':backend')"))
    def test_ui_backend_import(self): self.check_bad(lambda r: (r/'ui/src/main/kotlin/Bad.kt').write_text('import com.greatwall.callring.application.Bad'))
    def test_backend_android(self): self.check_bad(lambda r: (r/'backend/src/main/kotlin/Bad.kt').write_text('import androidx.lifecycle.ViewModel'))
    def test_backend_requires_contracts(self): self.check_bad(lambda r: (r/'backend/build.gradle').write_text(''))
    def test_app_requires_contracts(self): self.check_bad(lambda r: (r/'app/build.gradle').write_text("implementation project(':backend')\nimplementation project(':ui')"))
    def test_ui_shared_test_import(self):
        def mutate(r):
            p=r/'ui/src/sharedTest/kotlin/Bad.kt';p.parent.mkdir(parents=True);p.write_text('import com.greatwall.callring.application.api.RecordingApi')
        self.check_bad(mutate)
    def test_legacy_change_event_is_rejected_in_every_source_set(self):
        for module, source_set in (("contracts", "main"), ("ui", "sharedTest"), ("app", "debug"), ("backend", "test")):
            def mutate(root):
                path = root / f"{module}/src/{source_set}/kotlin/Old.kt"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("val old: DataChange? = null")
            self.check_bad(mutate)
    def test_nullable_or_default_cumulative_feed_is_rejected(self):
        for declaration in ("fun snapshots(): StateFlow<InvalidationSnapshot>?", "fun snapshots(): StateFlow<InvalidationSnapshot> = emptyFlow()", "fun observe(): Flow<InvalidationSnapshot>"):
            self.check_bad(lambda root: (root/'contracts/src/main/kotlin/Old.kt').write_text("interface ChangeFeed { " + declaration + " }"))
    def test_dual_subscription_mode_is_rejected(self):
        for declaration in ("val refreshFromChanges = false", "fun observeChanges() {}"):
            self.check_bad(lambda root: (root/'ui/src/main/kotlin/Old.kt').write_text(declaration))
    def test_removed_coordinator_publishers_are_rejected(self):
        for name in ("changed", "commit", "publishScopes", "runtimeChanged", "observe"):
            self.check_bad(lambda root: (root/'backend/src/main/kotlin/BusinessRevisionCoordinator.kt').write_text(f"class BusinessRevisionCoordinator {{ fun {name}() {{}} }}"))
    def test_operation_dispatch_cannot_use_protocol_numbers_or_discovery_order(self):
        for filename in ("ToolExecutor.kt", "ToolBatchEngine.kt", "OperationScopePlanner.kt", "ConfirmationTargets.kt", "ReceiptVerifier.kt", "ScopedToolReader.kt", "OperationCatalog.kt"):
            for expression in ("when (definition.legacyIndex) { 7 -> save() }", "val index = definition.legacyIndex; if (index in setOf(7,8)) save()", "when (definition.displayOrder) { 37 -> save() }", "val id = definition.operationCode.removePrefix(\"O\").toInt(); when(id) { 7 -> save() }", "fun run(operation: OperationId) { when (operation.ordinal) { 7 -> save() } }"):
                with self.subTest(filename=filename, expression=expression):
                    self.check_bad(lambda root: (root/f"backend/src/main/kotlin/{filename}").write_text(expression))
    def test_operation_protocol_metadata_and_schema_numbers_are_allowed(self):
        text = 'val d = OperationDefinition(legacyIndex=7, displayOrder=7, operationCode="O07"); val ordered=rows.sortedBy { it.displayOrder }; val unique=rows.map { it.legacyIndex }.distinct(); val schema=shape("max" to aiNumber(32)); when(operation) { OperationId.CALLERS_SAVE_PROFILE -> save() }'
        root=self.tree(lambda root: (root/'backend/src/main/kotlin/OperationCatalog.kt').write_text(text))
        self.assertEqual([],check_architecture.check(root))
    def test_removed_operation_projection_is_rejected(self):
        for declaration in ('data class OperationContract(val id:OperationId)', 'fun ToolRegistry.operationContracts() = definitions.map { it }'):
            with self.subTest(declaration=declaration):
                self.check_bad(lambda root: (root/'backend/src/main/kotlin/Old.kt').write_text(declaration))
    def test_removed_full_state_protocol_is_rejected_in_all_source_sets(self):
        for source_set in ("main", "test", "debug"):
            for text in ("interface BackendStore", "val state: BackendState", "val old = StorageSession(db)", "class SqliteBackendStore", "val n = stateLoadCount()", "val x = resource.legacy", "fun load(): BackendState", "fun transaction(block: (BackendState) -> Unit)"):
                with self.subTest(source_set=source_set, text=text):
                    def mutate(root):
                        path=root/f"app/src/{source_set}/kotlin/Old.kt"
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text(text)
                    self.check_bad(mutate)
    def test_removed_full_state_implementation_helpers_are_rejected(self):
        for source_set in ("main", "test", "debug"):
            for name in ("CallStore", "ProfileStore", "ScheduleStore", "SettingsStore"):
                for text in (f"class {name}", f"val old: {name} = make()"):
                    def mutate(root):
                        path=root/f"app/src/{source_set}/kotlin/Old.kt"
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text(text)
                    with self.subTest(source_set=source_set, text=text): self.check_bad(mutate)
            for text in ("fun fullDiff() {}", "val old = fullDiff()", "val stateLoads = counter", "val n = store.stateLoads.get()"):
                def mutate(root):
                    path=root/f"app/src/{source_set}/kotlin/Old.kt"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text)
                with self.subTest(source_set=source_set, text=text): self.check_bad(mutate)
    def test_current_narrow_stores_are_allowed(self):
        text="class SqliteCallStore; class SqliteCallerStore; class RecordingSettingsStore; class WidgetBindingStore"
        self.assertEqual([], check_architecture.check(self.tree(lambda root: (root/'app/src/main/kotlin/Good.kt').write_text(text))))
    def test_snapshot_facts_and_value_codecs_are_allowed(self):
        text="val facts: FullSnapshotFacts = reader.capture(); val codec = LegacyValueCodec; val x = payload as? AiJson.Obj; val y = row.legacyResult"
        self.assertEqual([], check_architecture.check(self.tree(lambda root: (root/'app/src/main/kotlin/Good.kt').write_text(text))))

    def test_renamed_operation_helpers_and_local_aliases(self):
        for name in ('RenamedOperationHelper.kt', 'NewHelper.java', 'OperationCatalog.kt'):
            for body in ('when (definition.legacyIndex) { 7 -> save() }',
                         'val n = definition.displayOrder; val alias = n; if (alias == 7) save()',
                         'fun run(id: OperationId) { when(id.ordinal) { 7 -> save() } }',
                         'void run(OperationId id) { switch(id.ordinal()) { case 7: save(); } }',
                         'val n = definition.operationCode.substring(1).toInt()',
                         'int n = definition.legacyIndex; switch(n) { case 7: save(); }'):
                with self.subTest(name=name, body=body):
                    self.check_bad(lambda r: (r/'backend/src/main/kotlin'/name).write_text(body))

    def test_operation_definition_id_and_constant_ordinal_provenance(self):
        for body in (
            'fun run(definition: OperationDefinition) { when(definition.id.ordinal) { 7 -> save() } }',
            'fun run(definition: OperationDefinition) { val id = definition.id; when(id.ordinal) { 7 -> save() } }',
            'fun run(definition: OperationDefinition) { val id = definition.id; val alias = id; if(alias.ordinal == 7) save() }',
            'when(OperationId.CALLERS_SAVE_PROFILE.ordinal) { 7 -> save() }',
            'void run(OperationDefinition definition) { switch(definition.id.ordinal()) { case 7: save(); } }',
            'val definition = OperationDefinition(); when(definition.id.ordinal) { 7 -> save() }',
            'void run(OperationDefinition definition) { switch(definition.getLegacyIndex()) { case 7: save(); } }',
            'void run(OperationDefinition definition) { if(definition.getDisplayOrder() == 7) save(); }',
            'void run(OperationDefinition definition) { switch(definition.getId().ordinal()) { case 7: save(); } }',
            'void run(OperationDefinition definition) { OperationId id = definition.getId(); int n = id.ordinal(); switch(n) { case 7: save(); } }',
            'void run(OperationDefinition definition) { int n = Integer.parseInt(definition.getOperationCode().substring(1)); switch(n) { case 7: save(); } }',
            'void run(OperationDefinition definition) { int n = definition.getLegacyIndex(); int alias = n; switch(alias) { case 7: save(); } }',
        ):
            with self.subTest(body=body):
                self.check_bad(lambda r: (r/'backend/src/main/kotlin/RenamedHelper.kt').write_text(body))

    def test_ordinary_indexes_ordinals_and_metadata_are_allowed(self):
        for name in ('ToolExecutor.kt', 'RenamedOperationHelper.kt', 'OperationCatalog.kt'):
            body = ('if (index > 0) page(index); when(color.ordinal) { 1 -> paint() }; '
                    'void run(OtherDefinition ordinary) { switch(ordinary.getId().ordinal()) { case 7: paint(); } if(ordinary.getDisplayOrder() > 1) page(); }; '
                    'val number = json.toInt(); val rows = definitions.sortedBy { it.displayOrder }; '
                    'require(definitions.map { it.legacyIndex }.distinct().size == definitions.size); '
                    'val export = definitions.map { it.operationCode to it.legacyIndex }')
            self.assertEqual([], check_architecture.check(self.tree(lambda r: (r/'backend/src/main/kotlin'/name).write_text(body))))

    def test_concrete_cast_import_and_type_aliases_across_source_sets(self):
        for source_set in ('main', 'test', 'debug', 'sharedTest'):
            for body in ('import com.greatwall.callring.data.database.SqliteCoreStorageResource as Sqlite\nval v = source as? Sqlite',
                         'typealias Sqlite = com.greatwall.callring.data.database.SqliteCoreStorageResource\ntypealias Local = Sqlite\nval v = source as Local',
                         'import com.greatwall.callring.data.database.SqliteCoreStorageResource; class X { Object v = (SqliteCoreStorageResource) source; }'):
                def mutate(r):
                    p = r/f'app/src/{source_set}/kotlin/Cast.kt'; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(body)
                self.check_bad(mutate)

    def test_direct_concrete_whitebox_and_owned_tokens_allowed(self):
        self.assertEqual([], check_architecture.check(self.tree(lambda r: (r/'app/src/main/kotlin/Whitebox.kt').write_text(
            'val resource: SqliteCoreStorageResource = create(); val db = resource.database; db.rawQuery(sql, null); '
            'val token = prepared as? OwnedMediaToken; val obj = json as? AiJson.Obj'))))

    def test_removed_precise_protocols_and_default_capabilities(self):
        for body in ('class BackendContext', 'fun coreScopeValues() = values', 'class LazyBackendStore',
                     'val old = renamedResource.legacy', 'class Universal(val core: CoreAssembly, val recording: RecordingAssembly, val conversation: ConversationAssembly)',
                     'class Service(val context: CoreAssembly)',
                     'class CapabilityFallbacks', 'fun aiUnavailable() = unsupported()',
                     'interface ChangeFeed { fun snapshots(): StateFlow<InvalidationSnapshot>? = null }'):
            self.check_bad(lambda r: (r/'app/src/main/kotlin/Old.kt').write_text(body))
        for body in ('interface CallringUiApi { val ai: AiApi get() = missing() }',
                     'interface CallringUiApi { fun commandApi(context: CommandContext): CallringUiApi = this }',
                     'interface CallringUiApi { fun commandApi(context: CommandContext): CallringUiApi { return this } }'):
            self.check_bad(lambda r: (r/'contracts/src/main/kotlin/Old.kt').write_text(body))

    def test_narrow_ports_defaults_fakes_and_root_are_allowed(self):
        r = self.tree()
        (r/'contracts/src/main/kotlin/Good.kt').write_text('interface CallringUiApi { val ai: AiApi; fun commandApi(context: CommandContext): CallringUiApi }\nfun product(limit: Int = 10) = limit')
        self.query_source(r, 'app', 'bootstrap', 'class BackendGraph(val core: CoreAssembly, val recording: RecordingAssembly, val conversation: ConversationAssembly)', 'BackendGraph.kt')
        p = r/'app/src/test/kotlin/Fake.kt'; p.parent.mkdir(parents=True); p.write_text('class CapabilityFallbacks { fun fake() = throw UnsupportedOperationException() }')
        self.assertEqual([], check_architecture.check(r))

    def test_complete_context_in_bootstrap_and_renamed_capability_bag_rejected(self):
        for package, body in (
            ('bootstrap', 'class NewService(val core: CoreAssembly)'),
            ('application', 'class Renamed(val callers: CallersApi, val settings: SettingsApi, val recording: RecordingApi, val conversation: ConversationApi)'),
            ('bootstrap', 'class AnotherRoot(val core: CoreAssembly, val recording: RecordingAssembly, val conversation: ConversationAssembly)')):
            self.check_bad(lambda r: self.query_source(r, 'app', package, body, 'NewService.kt'))

    def test_typed_resource_projection_and_old_dual_paths_rejected(self):
        for body in ('fun read(storage: CoreStorageResource) = storage.legacy',
                     'class UnavailableHomeApi : HomeApi',
                     'fun commandApi(context: CommandContext): CallringUiApi = this',
                     'class RenamedSession { fun load(): FullSnapshotFacts = capture(); fun save(value: FullSnapshotFacts) {} }',
                     'class BusinessRevisionCoordinator { val changes = MutableSharedFlow<Any>(); val snapshots = MutableStateFlow(value) }',
                     'class SnapshotViewModel { val request = RequestLoader(scope); val refresh = RefreshCoordinator(scope) }'):
            self.check_bad(lambda r: (r/'app/src/main/kotlin/New.kt').write_text(body))

    def test_ordinary_legacy_field_and_oneoff_loader_allowed(self):
        self.assertEqual([], check_architecture.check(self.tree(lambda r: (r/'app/src/main/kotlin/Good.kt').write_text(
            'f.legacy = RecordingCall(8); val request = RequestLoader(scope); val fallback = local; val snapshot: Map<String, Any?> = payload'))))

    def test_removed_default_unit_of_work_types_only_in_production(self):
        for name in ('UnavailableCoreUnitOfWork', 'UnavailableQuietTransactions'):
            with self.subTest(name=name):
                self.check_bad(lambda r: (r/'backend/src/main/kotlin/Default.kt').write_text('class ' + name))
                root = self.tree()
                path = root/'backend/src/test/kotlin/Fake.kt'; path.parent.mkdir(parents=True)
                path.write_text('class ' + name + ' { fun run() = throw UnsupportedOperationException() }')
                (root/'backend/src/main/kotlin/State.kt').write_text('sealed class Availability { object Unavailable : Availability() }; val unavailableReason = reason')
                self.assertEqual([], check_architecture.check(root))

if __name__ == '__main__': unittest.main()
