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
        for value in ('store as SqliteBackendStore', 'store as? com.greatwall.callring.data.SqliteBackendStore', 'store.asSqliteBackendStore()', '(SqliteBackendStore) store'):
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
if __name__ == '__main__': unittest.main()
