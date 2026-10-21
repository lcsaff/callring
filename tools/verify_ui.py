"""Build isolated UI checks; optionally install and run only the UI test package."""
from pathlib import Path
import argparse
import json
import re
import sys
import time
import xml.etree.ElementTree as ET
import verify_common as common

ROOT = Path(__file__).absolute().parents[1]
ARTIFACTS = ROOT / "ui/build/qa"
TEST_PACKAGE = "com.greatwall.callring.ui.test"
RUNNER = "com.greatwall.callring.ui.testing.UiTestRunner"
DEVICE_TIMEOUT = common.DEVICE_TIMEOUT
SESSION = None


def run(arguments, log_name, timeout=DEVICE_TIMEOUT):
    return SESSION.run(arguments, log_name, timeout)


def verified_apk():
    apk = common.single_apk(ROOT / "ui/build/outputs/apk/androidTest/debug/output-metadata.json", TEST_PACKAGE)
    badging = run([common.find_android_tool("aapt"), "dump", "badging", str(apk)], "apk-badging.log", timeout=30)
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package or package.group(1) != TEST_PACKAGE:
        raise RuntimeError("Refusing APK whose actual package differs from the isolated UI test package")
    return apk


def appop_mode(output, operation):
    if "No operations" in output:
        return "default"
    label = (r"(?:MIUIOP\(" + re.escape(operation) + r"\)|" + re.escape(operation) + r")"
             if operation.isdigit() else re.escape(operation))
    match = re.search(r"^\s*" + label +
                      r":\s*(allow|ignore|deny|default|foreground|errored)\b", output, re.MULTILINE)
    if not match:
        raise RuntimeError(f"Cannot safely read original app-op mode for {operation}: {output.strip()}")
    return match.group(1)


def check_unlocked(adb, suffix):
    power = run([*adb, "shell", "dumpsys", "power"], f"device-power-{suffix}.log", timeout=30)
    policy = run([*adb, "shell", "dumpsys", "window", "policy"], f"device-keyguard-{suffix}.log", timeout=30)
    states = re.findall(r"\b(?:mShowingLockscreen|mKeyguardShowing|isStatusBarKeyguard|"
                        r"showing|showingAndNotOccluded)=(true|false)\b", policy)
    if "mWakefulness=Awake" not in power or not states or "true" in states:
        raise RuntimeError("Device must be awake and unlocked before UI tests; unlock it manually and rerun. "
                           "No lock timeout or lock settings were changed.")


def device_checks(serial, summary):
    adb = [common.find_android_tool("adb"), "-s", serial]
    if run([*adb, "get-state"], "device-state.log", timeout=30).strip() != "device":
        raise RuntimeError(f"Device {serial} is not online")
    check_unlocked(adb, "before-install")
    apk = verified_apk()
    operations = ["SYSTEM_ALERT_WINDOW"]
    miui = run([*adb, "shell", "getprop", "ro.miui.ui.version.name"], "device-miui.log", timeout=30).strip()
    hyperos = run([*adb, "shell", "getprop", "ro.mi.os.version.name"], "device-hyperos.log", timeout=30).strip()
    if miui or hyperos:
        operations.append("10021")
    original_modes = {}
    cleanup_errors = []
    try:
        installed = run([*adb, "shell", "pm", "list", "packages", TEST_PACKAGE],
                        "device-packages.log", timeout=30).splitlines()
        existed = f"package:{TEST_PACKAGE}" in installed
        # Preserve the pre-install modes: the package scan itself may reset them.
        for operation in operations:
            if existed:
                output = run([*adb, "shell", "appops", "get", TEST_PACKAGE, operation],
                             f"appops-{operation}-before.log", timeout=30)
                original_modes[operation] = appop_mode(output, operation)
            else:
                original_modes[operation] = "default"
        summary["original_appops"] = original_modes.copy()
        install = run([*adb, "install", "-r", "-t", str(apk)], "device-install.log")
        if not re.search(r"^Success\s*$", install, re.MULTILINE):
            raise RuntimeError("UI test APK installation did not report Success")
        # HyperOS's package safety scan may reset app-ops shortly after installation.
        print("Waiting 12s for the device package safety scan...", flush=True)
        time.sleep(12)
        check_unlocked(adb, "after-install")
        registry = run([*adb, "shell", "pm", "list", "instrumentation"], "device-runners.log", timeout=30)
        expected = f"instrumentation:{TEST_PACKAGE}/{RUNNER} (target={TEST_PACKAGE})"
        if expected not in registry:
            raise RuntimeError("Installed runner/target differs from the isolated UI test package")
        for operation in operations:
            run([*adb, "shell", "appops", "set", TEST_PACKAGE, operation, "allow"],
                f"appops-{operation}-grant.log", timeout=30)
            output = run([*adb, "shell", "appops", "get", TEST_PACKAGE, operation],
                         f"appops-{operation}-active.log", timeout=30)
            if appop_mode(output, operation) != "allow":
                raise RuntimeError(f"Device did not grant test-only {operation}")
        output = run([*adb, "shell", "am", "instrument", "-w", "-e", "package",
                      "com.greatwall.callring.ui", f"{TEST_PACKAGE}/{RUNNER}"], "device-tests.log")
        summary["device"] = {"serial": serial, "package": TEST_PACKAGE, "tests": common.instrumentation_count(output)}
    finally:
        # Also stops remote instrumentation if adb timed out; never stop another package.
        try:
            run([*adb, "shell", "am", "force-stop", TEST_PACKAGE], "device-cleanup.log", timeout=30)
        except (OSError, RuntimeError) as error:
            cleanup_errors.append(str(error))
        for operation, mode in original_modes.items():
            try:
                run([*adb, "shell", "appops", "set", TEST_PACKAGE, operation, mode],
                    f"appops-{operation}-restore.log", timeout=30)
                restored = run([*adb, "shell", "appops", "get", TEST_PACKAGE, operation],
                               f"appops-{operation}-restored.log", timeout=30)
                if appop_mode(restored, operation) != mode:
                    raise RuntimeError(f"Failed to restore {operation} to {mode}")
            except (OSError, RuntimeError) as error:
                cleanup_errors.append(str(error))
        summary["cleanup"] = {"appops_restored": not cleanup_errors, "errors": cleanup_errors}
        if cleanup_errors:
            raise RuntimeError("UI test cleanup incomplete: " + "; ".join(cleanup_errors))


def main(argv=None):
    global SESSION
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", metavar="ADB_SERIAL", help="Run on an awake, manually unlocked device")
    args = parser.parse_args(argv)
    summary = common.new_summary(ROOT, args.device)
    SESSION = common.VerificationRun(ROOT, ARTIFACTS, summary)
    try:
        common.execute_gate(SESSION)
        if args.device:
            device_checks(args.device, summary)
        summary["status"] = "passed"
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        summary["status"] = "failed"
        summary["error"] = str(error)
        raise
    finally:
        SESSION.write_summary()
        print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
