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
    def test_valid_tree(self): self.assertEqual([], check_architecture.check(self.tree()))
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
