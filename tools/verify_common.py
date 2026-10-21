"""The shared, finite verification gate and evidence/isolated-APK checks."""
from pathlib import Path, PureWindowsPath
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

BUILD_TIMEOUT = 3600
DEVICE_TIMEOUT = 180
GATE_TASKS = (
    ':contracts:test', ':backend:test', ':ui:testDebugUnitTest', ':app:testDebugUnitTest',
    ':ui:checkDebugCompileClasspath', ':ui:assembleDebug', ':app:assembleDebug',
    ':app:assembleRelease', ':app:assembleBackendQa', ':ui:assembleDebugAndroidTest',
    ':app:assembleDebugAndroidTest', ':ui:lintDebug', ':app:lintDebug',
)
PYTHON_CHECKS = (
    ('tools/check_architecture.py',),
    ('tools/check_backend_boundaries.py',),
    ('-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_architecture.py'),
    ('-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_backend_boundaries.py'),
    ('-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_verification.py'),
)


def gradle_entry(root, windows=None):
    if windows is None: windows = os.name == 'nt'
    if windows: return str(root / 'gradlew.bat')
    found = shutil.which('gradle')
    if not found: raise RuntimeError('Gradle 8.13 must be installed on PATH')
    return found


def new_summary(root, serial):
    return {'status': 'running', 'root': str(root), 'commit': None, 'requested_serial': serial,
            'device': 'not_run', 'cleanup': {'status': 'not_needed', 'errors': []}, 'commands': []}


class VerificationRun:
    def __init__(self, root, artifacts, summary):
        self.root, self.artifacts, self.summary = root, artifacts, summary
        artifacts.mkdir(parents=True, exist_ok=True)
        self.write_summary()

    def write_summary(self):
        (self.artifacts/'summary.json').write_text(json.dumps(self.summary, indent=2, ensure_ascii=False)+'\n', encoding='utf-8', newline='\n')

    def run(self, argv, log_name, timeout=BUILD_TIMEOUT):
        record = {'argv': [str(v) for v in argv], 'log': str(self.artifacts/log_name),
                  'exit_code': None, 'timeout': False, 'timeout_seconds': timeout}
        self.summary['commands'].append(record)
        output = ''
        try:
            result = subprocess.run(record['argv'], cwd=self.root, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, encoding='utf-8', errors='replace', timeout=timeout)
            output = result.stdout or ''
            record['exit_code'] = result.returncode
            if result.returncode: raise RuntimeError(f'Command failed ({result.returncode}); see {self.artifacts/log_name}')
            return output
        except subprocess.TimeoutExpired as error:
            output = error.stdout or ''
            if isinstance(output, bytes): output = output.decode('utf-8', errors='replace')
            record['timeout'] = True
            record['error'] = f'Command timed out after {timeout}s'
            raise RuntimeError(record['error'] + f'; see {self.artifacts/log_name}') from error
        except OSError as error:
            record['error'] = str(error)
            raise
        finally:
            (self.artifacts/log_name).write_text(output, encoding='utf-8', newline='\n')
            record['log_sha256'] = hashlib.sha256((self.artifacts/log_name).read_bytes()).hexdigest()
            self.write_summary()


def task_state(output, task):
    match = re.search(r'^> Task[ \t]+' + re.escape(task) + r'(?:[ \t]+([^\r\n]*))?[ \t]*$', output, re.M)
    if not match: raise RuntimeError(f'No exact task line for {task} in this run')
    state = (match.group(1) or '').strip()
    if state not in ('', 'UP-TO-DATE', 'FROM-CACHE', 'NO-SOURCE'):
        raise RuntimeError(f'Unexpected task state for {task}: {state}')
    return state or 'EXECUTED'


def test_counts(folder):
    files = sorted(folder.glob('TEST-*.xml'), key=lambda p: str(p))
    if not files: raise RuntimeError(f'No test results in {folder}')
    counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    digest = hashlib.sha256()
    for path in files:
        raw = path.read_bytes()
        digest.update(str(path).encode('utf-8') + b'\n' + raw + b'\n')
        suite = ET.fromstring(raw)
        if suite.tag != 'testsuite': raise RuntimeError(f'Not a JUnit testsuite: {path}')
        for key in counts:
            value = int(suite.get(key, 0))
            if value < 0: raise RuntimeError(f'Negative JUnit count: {path}')
            counts[key] += value
    if counts['tests'] <= counts['skipped'] or counts['failures'] or counts['errors']:
        raise RuntimeError(f'Missing, skipped or failing tests: {folder}: {counts}')
    return {**counts, 'xml_files': [str(p) for p in files], 'xml_sha256': digest.hexdigest()}


def execute_gate(session):
    session.summary['commit'] = session.run(['git', 'rev-parse', 'HEAD'], 'commit.log', timeout=30).strip()
    for i, check in enumerate(PYTHON_CHECKS):
        print(session.run([sys.executable, *check], f'python-{i+1}.log').strip(), flush=True)
    gradle = gradle_entry(session.root)
    version = session.run([gradle, '--version'], 'gradle-version.log', timeout=120)
    if not re.search(r'^Gradle 8\.13[ \t]*$', version, re.M): raise RuntimeError('Verification requires Gradle 8.13')
    output = session.run([gradle, *GATE_TASKS, '--console=plain'], 'build.log')
    states = {task: task_state(output, task) for task in GATE_TASKS}
    session.summary['gradle_tasks'] = states
    for module, task, folder in (
        ('contracts', ':contracts:test', 'contracts/build/test-results/test'),
        ('backend', ':backend:test', 'backend/build/test-results/test'),
        ('ui', ':ui:testDebugUnitTest', 'ui/build/test-results/testDebugUnitTest'),
        ('app', ':app:testDebugUnitTest', 'app/build/test-results/testDebugUnitTest'),
    ):
        state = states[task]
        if module == 'contracts' and state == 'NO-SOURCE':
            session.summary[module] = {'state': state, 'tests': 0}
        else:
            if state == 'NO-SOURCE': raise RuntimeError(f'{module} tests were NO-SOURCE')
            session.summary[module] = {'state': state, **test_counts(session.root/folder)}
    session.summary['lint'] = {}
    for module in ('ui', 'app'):
        report = session.root/f'{module}/build/reports/lint-results-debug.xml'
        tree = ET.parse(report)
        if any(issue.get('severity') in ('Error', 'Fatal') for issue in tree.getroot().iter('issue')):
            raise RuntimeError(f'Lint errors in {report}')
        session.summary['lint'][module] = {'report': str(report), 'sha256': hashlib.sha256(report.read_bytes()).hexdigest()}
    session.summary['apk_builds'] = {task: states[task] for task in GATE_TASKS if ':assemble' in task}


def single_apk(metadata_path, package):
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    if not isinstance(metadata, dict): raise RuntimeError('APK metadata must be an object')
    elements = metadata.get('elements', [])
    if metadata.get('applicationId') != package or not isinstance(elements, list) or len(elements) != 1 or not isinstance(elements[0], dict):
        raise RuntimeError('Refusing APK metadata outside the single isolated package')
    output = elements[0].get('outputFile')
    if not isinstance(output, str) or Path(output).is_absolute() or bool(PureWindowsPath(output).drive):
        raise RuntimeError('Refusing absolute APK output path')
    apk = (metadata_path.parent/output).resolve()
    if apk.parent != metadata_path.parent.resolve() or not apk.is_file():
        raise RuntimeError('Refusing missing APK or output outside its output directory')
    return apk


def instrumentation_count(output):
    if re.search(r'FAILURES|shortMsg\s*=|INSTRUMENTATION_(?:FAILED|ABORTED)|INSTRUMENTATION_RESULT:\s*(?:Error|error)|Process crashed', output, re.I):
        raise RuntimeError('Instrumentation reported failure')
    match = re.search(r'^OK[ \t]*\((\d+)[ \t]+tests?\)', output, re.M)
    if not match or int(match.group(1)) <= 0: raise RuntimeError('Instrumentation did not report OK (N tests), N > 0')
    return int(match.group(1))


def find_android_tool(name):
    executable = name + ('.exe' if os.name == 'nt' else '')
    if found := shutil.which(executable): return found
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if not os.environ.get(variable): continue
        sdk = Path(os.environ[variable])
        candidates = [sdk/'platform-tools'/executable] if name == 'adb' else sorted((sdk/'build-tools').glob(f'*/{executable}'), reverse=True)
        for candidate in candidates:
            if candidate.is_file(): return str(candidate)
    raise RuntimeError(f'Cannot find {name}; set ANDROID_HOME or PATH')
