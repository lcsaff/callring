from pathlib import Path
import tempfile
import unittest
import check_architecture

class ArchitectureTests(unittest.TestCase):
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
        for value in ('store as SqliteCoreStorageResource', 'store as? com.greatwall.callring.data.SqliteCoreStorageResource', 'store.asSqliteCoreStorageResource()', '(SqliteCoreStorageResource) store'):
            with self.subTest(value=value):
                self.check_bad(lambda r: (r/'app/src/main/kotlin/Bad.kt').write_text('val source = ' + value))
    def test_other_sealed_or_keystore_casts_are_allowed(self):
        root = self.tree(lambda r: (r/'app/src/main/kotlin/Good.kt').write_text('val json = value as? AiJson.Obj\nval key = value as java.security.KeyStore'))
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
            for expression in ("when (definition.legacyIndex) { 7 -> save() }", "if (index in setOf(7,8)) save()", "when (definition.displayOrder) { 37 -> save() }", "val id = definition.operationCode.removePrefix(\"O\").toInt(); when(id) { 7 -> save() }", "when (operation.ordinal) { 7 -> save() }"):
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
if __name__ == '__main__': unittest.main()
