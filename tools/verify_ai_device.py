"""Run the explicitly authorized real-model acceptance once, preserving formal app data."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).absolute().parents[1]
PACKAGE = "com.greatwall.callring"
ARTIFACTS = ROOT / "docs/plans/ai-rectification-evidence/device"
FILES = ("real-model.json", "persistence.json", "idle-keyboard.png", "create-keyboard.png",
         "theme-keyboard.png", "light-before.png", "ten-created.png", "dark-after.png", "restart-dark.png")


def command(args, *, check=True):
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError(result.stdout[-4000:])
    return result


def instrument(adb, name, log_name, paid=False):
    args = [*adb, "shell", "am", "instrument", "-w", "-e", "class", name]
    if paid:
        args += ["-e", "ai_v2_allow_paid", "true"]
    args += [f"{PACKAGE}/android.test.InstrumentationTestRunner"]
    process = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               encoding="utf-8", errors="replace")
    lines = []
    for line in process.stdout:
        lines.append(line)
        if "AI_V2" in line:
            print(line.rstrip(), flush=True)
    process.wait()
    output = ''.join(lines)
    (ARTIFACTS / log_name).write_text(output, encoding="utf-8")
    print(output[-8000:], flush=True)
    return process.returncode == 0 and "OK (" in output and not any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "shortMsg="))


def collect(adb):
    for name in FILES:
        command([*adb, "pull", f"/sdcard/Android/data/{PACKAGE}/files/ai-v2-proof/{name}", str(ARTIFACTS / name)], check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--allow-paid", action="store_true", help="Only within an explicitly authorized real-model task")
    parser.add_argument("--persistence-only", action="store_true", help="No model calls or business writes")
    args = parser.parse_args()
    if not args.persistence_only and not args.allow_paid:
        parser.error("Real-model execution requires explicit --allow-paid; no task was started")
    adb = ["adb", "-s", args.device]
    if command([*adb, "get-state"]).stdout.strip() != "device":
        raise RuntimeError("Device is not online")
    trust = command([*adb, "shell", "dumpsys", "trust"]).stdout
    if "deviceLocked=1" in trust:
        raise RuntimeError("Unlock the device first; no APK or model task was started")
    metadata_path = ROOT / "app/build/outputs/apk/debug/output-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata["applicationId"] != PACKAGE:
        raise RuntimeError("Refusing an APK outside the formal package")
    apk = metadata_path.parent / metadata["elements"][0]["outputFile"]
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    build = {"apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
             "bytes": apk.stat().st_size, "package": PACKAGE, "device": args.device,
             "git": command(["git", "rev-parse", "HEAD"]).stdout.strip()}
    (ARTIFACTS / "build.json").write_text(json.dumps(build, indent=2), encoding="utf-8")
    # Android verifies the signing certificate atomically; a mismatch stops here.
    # There is deliberately no uninstall, data clear, key read or retry path.
    print(command([*adb, "install", "-r", "-t", str(apk)]).stdout, flush=True)
    if not args.persistence_only:
        if not instrument(adb, "com.greatwall.callring.RealAiConversationDeviceTest", "real-model.log", paid=True):
            collect(adb)
            raise RuntimeError("Real-model acceptance failed; inspect existing receipts/proof before any further action. No batch retry was issued.")
    command([*adb, "shell", "am", "force-stop", PACKAGE])
    success = instrument(adb, "com.greatwall.callring.AiConversationPersistenceDeviceTest", "persistence.log")
    collect(adb)
    if not success:
        raise RuntimeError("Persistence verification failed")
    print(f"PASS: formal model/Activity proof and restart verification saved in {ARTIFACTS}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, KeyError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
