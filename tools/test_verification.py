"""Fault tests for the shared verification gate; never contact Gradle or adb."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import verify_common as common
import verify_backend as backend


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        printer = patch('builtins.print'); printer.start(); self.addCleanup(printer.stop)
        self.root = Path(self.temp.name)
        self.artifacts = self.root / 'qa'

    def apk(self, output='only.apk', elements=None, package=backend.QA_PACKAGE):
        directory = self.root / 'apk'; directory.mkdir(exist_ok=True)
        (directory/'only.apk').write_bytes(b'apk')
        metadata = directory/'output-metadata.json'
        metadata.write_text(json.dumps({'applicationId': package, 'elements': elements if elements is not None else [{'outputFile': output}]}))
        return metadata

    def test_single_apk_and_reject_wrong_multi_missing_escape(self):
        self.assertEqual('only.apk', common.single_apk(self.apk(), backend.QA_PACKAGE).name)
        for name in ('../only.apk', 'C:/escape.apk', 'C:escape.apk', str(self.root/'outside.apk'), 'missing.apk'):
            with self.subTest(name=name), self.assertRaises((RuntimeError, ValueError)):
                common.single_apk(self.apk(name), backend.QA_PACKAGE)
        for entries in ([], [{'outputFile': 'only.apk'}]*2):
            with self.assertRaises(RuntimeError): common.single_apk(self.apk(elements=entries), backend.QA_PACKAGE)
        with self.assertRaises(RuntimeError): common.single_apk(self.apk(package='com.greatwall.callring'), backend.QA_PACKAGE)

    def test_bad_metadata(self):
        path = self.apk(); path.write_text('{')
        with self.assertRaises(ValueError): common.single_apk(path, backend.QA_PACKAGE)
        for shape in ([], {'applicationId': backend.QA_PACKAGE, 'elements': [None]}, {'applicationId': backend.QA_PACKAGE, 'elements': None}):
            path.write_text(json.dumps(shape))
            with self.assertRaises(RuntimeError): common.single_apk(path, backend.QA_PACKAGE)

    def test_instrumentation_positive_and_failure_markers(self):
        self.assertEqual(4, common.instrumentation_count('OK (4 tests)\n'))
        for text in ('OK (0 tests)', 'OK (4 tests)\nFAILURES!!!', 'shortMsg=crash\nOK (2 tests)',
                     'INSTRUMENTATION_RESULT: Error=bad\nOK (1 test)', 'Process crashed', 'INSTRUMENTATION_ABORTED'):
            with self.subTest(text=text), self.assertRaises(RuntimeError): common.instrumentation_count(text)

    def test_task_state_exact_line_not_testclasses_or_next_line(self):
        self.assertEqual('UP-TO-DATE', common.task_state('> Task :backend:test UP-TO-DATE\n', ':backend:test'))
        self.assertEqual('EXECUTED', common.task_state('> Task :backend:test\n> Task :backend:jar\n', ':backend:test'))
        self.assertEqual('NO-SOURCE', common.task_state('> Task :contracts:test NO-SOURCE\n', ':contracts:test'))
        for text in ('> Task :backend:testClasses\n', '> Task :backend:testExtra UP-TO-DATE\n', ''):
            with self.assertRaises(RuntimeError): common.task_state(text, ':backend:test')

    def test_xml_rejects_zero_all_skipped_failure_and_bad_xml(self):
        folder = self.root/'xml'; folder.mkdir()
        path = folder/'TEST-one.xml'
        for body in ('<testsuite tests="0"/>', '<testsuite tests="2" skipped="2"/>',
                     '<testsuite tests="1" failures="1"/>', 'broken'):
            path.write_text(body)
            with self.assertRaises((RuntimeError, ValueError, common.ET.ParseError)): common.test_counts(folder)
        path.write_text('<testsuite tests="2" skipped="1" failures="0" errors="0"/>')
        self.assertEqual(2, common.test_counts(folder)['tests'])

    def test_gradle_entry_windows_and_linux(self):
        self.assertEqual(str(self.root/'gradlew.bat'), common.gradle_entry(self.root, windows=True))
        with patch('verify_common.shutil.which', return_value='/opt/gradle/bin/gradle'):
            self.assertEqual('/opt/gradle/bin/gradle', common.gradle_entry(self.root, windows=False))
        with patch('verify_common.shutil.which', return_value=None), self.assertRaises(RuntimeError):
            common.gradle_entry(self.root, windows=False)

    def test_failed_command_and_timeout_are_recorded(self):
        for effect in (subprocess.CompletedProcess(['bad'], 9, 'failed'), subprocess.TimeoutExpired(['bad'], 1, output=b'partial')):
            with self.subTest(effect=effect):
                summary = common.new_summary(self.root, None)
                session = common.VerificationRun(self.root, self.artifacts, summary)
                with patch('verify_common.subprocess.run', side_effect=[effect] if isinstance(effect, subprocess.CompletedProcess) else effect):
                    with self.assertRaises(RuntimeError): session.run(['bad'], 'bad.log', timeout=1)
                self.assertEqual(['bad'], summary['commands'][0]['argv'])
                self.assertEqual(isinstance(effect, subprocess.TimeoutExpired), summary['commands'][0]['timeout'])
                self.assertNotEqual(0, summary['commands'][0]['exit_code'])

    def test_boundary_failure_overwrites_previous_success_finally(self):
        self.artifacts.mkdir()
        (self.artifacts/'summary.json').write_text('{"status":"passed"}')
        with patch.object(backend, 'ROOT', self.root), patch.object(backend, 'ARTIFACTS', self.artifacts), \
             patch('verify_common.subprocess.run', side_effect=[subprocess.CompletedProcess([], 0, 'commit'), subprocess.CompletedProcess([], 1, 'failure')]):
            with self.assertRaises(RuntimeError): backend.main(['--device', 'offline'])
        summary = json.loads((self.artifacts/'summary.json').read_text())
        self.assertEqual('failed', summary['status']); self.assertEqual('offline', summary['requested_serial'])
        self.assertEqual('not_run', summary['device']); self.assertTrue(summary['commands'])

    def test_main_build_failure_and_timeout_write_failed_summary(self):
        for failure in (subprocess.CompletedProcess([], 1, 'build failed'), subprocess.TimeoutExpired([], 3, output=b'timed')):
            def command(argv, **kwargs):
                if argv[:2] == ['git', 'rev-parse']: return subprocess.CompletedProcess(argv, 0, 'head')
                if '--version' in argv: return subprocess.CompletedProcess(argv, 0, 'Gradle 8.13\n')
                if ':backend:test' in argv:
                    if isinstance(failure, Exception): raise failure
                    return failure
                return subprocess.CompletedProcess(argv, 0, 'checks passed')
            with patch.object(backend, 'ROOT', self.root), patch.object(backend, 'ARTIFACTS', self.artifacts), \
                 patch('verify_common.gradle_entry', return_value='gradle'), patch('verify_common.subprocess.run', side_effect=command):
                with self.assertRaises(RuntimeError): backend.main([])
            summary = json.loads((self.artifacts/'summary.json').read_text())
            self.assertEqual('failed', summary['status']); self.assertEqual('head', summary['commit'])
            self.assertEqual(isinstance(failure, subprocess.TimeoutExpired), summary['commands'][-1]['timeout'])

    def test_cached_xml_requires_current_exact_task_evidence(self):
        folder = self.root/'backend/build/test-results/test'; folder.mkdir(parents=True)
        (folder/'TEST-old.xml').write_text('<testsuite tests="20"/>')
        summary = common.new_summary(self.root, None)
        session = common.VerificationRun(self.root, self.artifacts, summary)
        def run(argv, log, timeout=3600):
            if '--version' in argv: return 'Gradle 8.13\n'
            if ':backend:test' in argv: return '> Task :backend:testClasses UP-TO-DATE\n'
            return 'ok'
        with patch.object(session, 'run', side_effect=run), patch('verify_common.gradle_entry', return_value='gradle'):
            with self.assertRaises(RuntimeError): common.execute_gate(session)
        self.assertNotIn('backend', summary)

    def test_backend_device_failures_cleanup_only_qa(self):
        for outputs in (['offline'], ['device', "package: name='wrong'"],
                        ['device', "package: name='"+backend.QA_PACKAGE+"'", 'Failure'],
                        ['device', "package: name='"+backend.QA_PACKAGE+"'", 'Success', 'instrumentation:wrong/runner (target=wrong)'],
                        ['device', "package: name='"+backend.QA_PACKAGE+"'", 'Success', backend.REGISTRY, 'OK (0 tests)']):
            summary = common.new_summary(self.root, 'serial')
            calls = []
            def run(argv, log, timeout=common.DEVICE_TIMEOUT):
                calls.append(argv)
                if 'force-stop' in argv: return ''
                return outputs.pop(0)
            with patch.object(backend, 'run', side_effect=run), patch.object(backend, 'verified_apk', return_value=self.root/'qa.apk'), patch.object(common, 'find_android_tool', return_value='adb'):
                with self.assertRaises(RuntimeError): backend.device_checks('serial', summary)
            cleanups = [call for call in calls if 'force-stop' in call]
            self.assertEqual(backend.QA_PACKAGE, cleanups[-1][-1])
            self.assertFalse(any(call[-1] == 'com.greatwall.callring' for call in cleanups))

    def test_nonzero_instrumentation_with_ok_is_failure(self):
        summary = common.new_summary(self.root, 'serial')
        session = common.VerificationRun(self.root, self.artifacts, summary)
        with patch('verify_common.subprocess.run', return_value=subprocess.CompletedProcess([], 1, 'OK (4 tests)')):
            with self.assertRaises(RuntimeError): session.run(['adb', 'shell', 'am', 'instrument'], 'device.log', timeout=1)


if __name__ == '__main__': unittest.main()
