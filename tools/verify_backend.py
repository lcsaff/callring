"""Run the shared build gate; optional device checks install only backendqa."""
from pathlib import Path
import argparse
import re
import sys
import xml.etree.ElementTree as ET
import verify_common as common

ROOT = Path(__file__).absolute().parents[1]
ARTIFACTS = ROOT / 'app/build/qa/backend-refactor'
QA_PACKAGE = 'com.greatwall.callring.backendqa'
RUNNER = 'android.test.InstrumentationTestRunner'
REGISTRY = f'instrumentation:{QA_PACKAGE}/{RUNNER} (target={QA_PACKAGE})'
SESSION = None


def run(argv, log, timeout=common.DEVICE_TIMEOUT):
    return SESSION.run(argv, log, timeout)


def verified_apk():
    return common.single_apk(ROOT/'app/build/outputs/apk/backendQa/output-metadata.json', QA_PACKAGE)


def device_checks(serial, summary):
    adb = [common.find_android_tool('adb'), '-s', serial]
    cleanup_errors = []
    try:
        if run([*adb, 'get-state'], 'device-state.log', timeout=30).strip() != 'device':
            raise RuntimeError(f'Device {serial} is not online')
        apk = verified_apk()
        badging = run([common.find_android_tool('aapt'), 'dump', 'badging', str(apk)], 'apk-badging.log', timeout=30)
        package = re.search(r"^package: name='([^']+)'", badging, re.M)
        if not package or package.group(1) != QA_PACKAGE: raise RuntimeError('APK actual package differs from backendqa')
        install = run([*adb, 'install', '-r', '-t', str(apk)], 'device-install.log')
        if not re.search(r'^Success[ \t]*$', install, re.M): raise RuntimeError('APK install did not report Success')
        registry = run([*adb, 'shell', 'pm', 'list', 'instrumentation'], 'device-runners.log', timeout=30)
        if REGISTRY not in registry.splitlines(): raise RuntimeError('Runner or target differs from backendqa')
        classes = ','.join(('com.greatwall.callring.data.SqliteCoreStorageResourceDeviceTest',
                            'com.greatwall.callring.backend.BackendApiDeviceTest',
                            'com.greatwall.callring.backend.BackendMutationDeviceTest',
                            'com.greatwall.callring.BackendSchedulerDeviceTest'))
        output = run([*adb, 'shell', 'am', 'instrument', '-w', '-e', 'class', classes, f'{QA_PACKAGE}/{RUNNER}'], 'device-tests.log')
        summary['device'] = {'serial': serial, 'package': QA_PACKAGE, 'tests': common.instrumentation_count(output)}
    finally:
        try:
            run([*adb, 'shell', 'am', 'force-stop', QA_PACKAGE], 'device-cleanup.log', timeout=30)
        except (OSError, RuntimeError) as error:
            cleanup_errors.append(str(error))
        summary['cleanup'] = {'status': 'failed' if cleanup_errors else 'passed', 'package': QA_PACKAGE, 'errors': cleanup_errors}
        if cleanup_errors: raise RuntimeError('backendqa cleanup failed: ' + '; '.join(cleanup_errors))


def main(argv=None):
    global SESSION
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', metavar='ADB_SERIAL', help='Install/run only the separate backendqa package')
    args = parser.parse_args(argv)
    summary = common.new_summary(ROOT, args.device)
    SESSION = common.VerificationRun(ROOT, ARTIFACTS, summary)
    try:
        common.execute_gate(SESSION)
        if args.device: device_checks(args.device, summary)
        summary['status'] = 'passed'
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        summary['status'] = 'failed'; summary['error'] = str(error)
        raise
    finally:
        SESSION.write_summary()
        print(__import__('json').dumps(summary, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    try: main()
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
