"""Build and verify the backend; optional device checks use a separate application package."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "app/build/qa/backend-refactor"
QA_PACKAGE = "com.greatwall.callring.backendqa"


def run(arguments, log_name):
    result = subprocess.run(arguments, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    (ARTIFACTS / log_name).write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        print(result.stdout[-8000:])
        raise RuntimeError(f"Command failed ({result.returncode}); see {ARTIFACTS / log_name}")
    return result.stdout


def test_counts(folder):
    files = list(folder.glob("TEST-*.xml"))
    if not files:
        raise RuntimeError(f"No test results in {folder}")
    counts = {name: sum(int(ET.parse(path).getroot().get(name, 0)) for path in files)
              for name in ("tests", "failures", "errors", "skipped")}
    if counts["failures"] or counts["errors"]:
        raise RuntimeError(f"Failing tests: {folder}: {counts}")
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-ui-pending", action="store_true",
                        help="Intermediate branch validation before the separate UI migration lands")
    parser.add_argument("--device", metavar="ADB_SERIAL", help="Install/run only the separate backendqa package")
    args = parser.parse_args()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    boundary = [sys.executable, "tools/check_backend_boundaries.py"]
    if args.legacy_ui_pending:
        boundary.append("--legacy-ui-pending")
    print(run(boundary, "boundaries.log").strip())
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    tasks = [":backend:test", ":app:testDebugUnitTest", ":app:assembleDebug", ":app:assembleRelease",
             ":app:lintDebug", ":app:assembleDebugAndroidTest", ":app:assembleBackendQa", "--console=plain"]
    if (ROOT / "ui/build.gradle").exists():
        tasks[:0] = [":ui:testDebugUnitTest", ":ui:assembleDebug"]
    run([gradle, *tasks], "build.log")
    summary = {
        "intermediateUiMigration": args.legacy_ui_pending,
        "backend": test_counts(ROOT / "backend/build/test-results/test"),
        "app": test_counts(ROOT / "app/build/test-results/testDebugUnitTest"),
        "device": "not_run",
    }
    if (ROOT / "ui/build.gradle").exists():
        summary["ui"] = test_counts(ROOT / "ui/build/test-results/testDebugUnitTest")
    if args.device:
        metadata_path = ROOT / "app/build/outputs/apk/backendQa/output-metadata.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if metadata["applicationId"] != QA_PACKAGE:
            raise RuntimeError("Refusing to install an APK outside the dedicated backendqa package")
        apk = metadata_path.parent / metadata["elements"][0]["outputFile"]
        adb = ["adb", "-s", args.device]
        run([*adb, "install", "-r", "-t", str(apk)], "device-install.log")
        classes = ",".join(("com.greatwall.callring.data.SqliteBackendStoreDeviceTest",
                            "com.greatwall.callring.backend.BackendApiDeviceTest"))
        output = run([*adb, "shell", "am", "instrument", "-w", "-e", "class", classes,
                      f"{QA_PACKAGE}/android.test.InstrumentationTestRunner"], "device-tests.log")
        # adb may return zero even when the instrumentation runner failed.
        if "OK (" not in output or any(marker in output for marker in
                ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "shortMsg=")):
            print(output[-8000:])
            raise RuntimeError("Device test runner did not report success; see device-tests.log")
        summary["device"] = output.strip()
    (ARTIFACTS / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
