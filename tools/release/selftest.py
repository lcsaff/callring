#!/usr/bin/env python3
"""Failure-sample self test for the release tooling (no network, no upload).

Builds a valid staged release from the local release APK with
generate_release_metadata.py, then mutates copies of it to prove that
verify_release.py / version_tool.py return a non-zero exit code for every
rejection the design requires: wrong tag, missing attachment, digest mismatch,
size mismatch, package mismatch, non-increasing versionCode, backendQa mix-in
and a debuggable (debug) APK.

Requires the release APK from ``./gradlew.bat :app:assembleRelease``.

Example:
  python tools/release/selftest.py
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Optional

from apk_info import sha256_file
from version_tool import read_version_properties

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
GENERATE = HERE / "generate_release_metadata.py"
VERIFY = HERE / "verify_release.py"
VERSION_TOOL = HERE / "version_tool.py"
DEFAULT_PROPERTIES = ROOT / "version.properties"
DEFAULT_RELEASE_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DEFAULT_DEBUG_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


@dataclass
class Case:
    name: str
    mutate: Callable[[Path], None] = lambda directory: None
    tag: Optional[str] = None
    published_max: Optional[int] = None
    expected_text: Optional[str] = None
    expect_ok: bool = False


def run(script: Path, arguments: List[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(script), *arguments],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )


def combined_output(completed: subprocess.CompletedProcess) -> str:
    return (completed.stdout or "") + (completed.stderr or "")


def write_json(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8", newline="\n")


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-apk", default=str(DEFAULT_RELEASE_APK),
                        help="signed, non-debuggable release APK (default: Gradle release output)")
    parser.add_argument("--debug-apk", default=str(DEFAULT_DEBUG_APK),
                        help="debug APK used for the debuggable-artifact sample")
    parser.add_argument("--version-properties", default=str(DEFAULT_PROPERTIES))
    parser.add_argument("--published-max", type=int, default=None,
                        help="published versionCode ceiling (default: current versionCode - 1)")
    parser.add_argument("--keep", action="store_true", help="keep the temporary fixture directories")
    args = parser.parse_args(argv)

    release_apk = Path(args.release_apk)
    debug_apk = Path(args.debug_apk)
    properties = Path(args.version_properties)
    if not release_apk.is_file():
        print(f"[FAIL] release APK not found: {release_apk}\n"
              "       run './gradlew.bat :app:assembleRelease' first", file=sys.stderr)
        return 1

    values = read_version_properties(properties)
    version_code = int(values["versionCode"])
    version_name = values["versionName"]
    published_max = args.published_max if args.published_max is not None else version_code - 1
    apk_name = f"callring-{version_name}.apk"

    workspace = Path(tempfile.mkdtemp(prefix="callring-release-selftest-"))
    base = workspace / "base"
    completed = run(GENERATE, ["--apk", str(release_apk), "--out-dir", str(base),
                               "--version-properties", str(properties)])
    if completed.returncode != 0:
        print(f"[FAIL] could not build the good fixture: {combined_output(completed)}", file=sys.stderr)
        return 1

    def good_fixture() -> Path:
        return base

    def staged_apk(directory: Path) -> Path:
        return directory / apk_name

    def verify_args(directory: Path, tag: Optional[str], published_max_value: Optional[int]) -> List[str]:
        return [
            "--release-dir", str(directory),
            "--tag", tag if tag is not None else f"v{version_name}",
            "--version-properties", str(properties),
            "--published-max", str(published_max_value if published_max_value is not None else published_max),
        ]

    cases: List[Case] = [
        Case("good", expected_text="release verified", expect_ok=True),
        Case("wrong_tag", tag="v0.3", expected_text="release tag"),
        Case("missing_apk", lambda directory: staged_apk(directory).unlink(),
             expected_text="missing required attachments"),
        Case("missing_update_json", lambda directory: (directory / "update.json").unlink(),
             expected_text="missing required attachments"),
        Case("digest_mismatch_update_json", lambda directory: write_json(
            directory / "update.json", {**load_json(directory / "update.json"), "sha256": "0" * 64}),
             expected_text="sha256"),
        Case("digest_mismatch_sha256sums", lambda directory: (directory / "SHA256SUMS.txt").write_text(
            f"{'0' * 64}  {apk_name}\n", encoding="utf-8", newline="\n"),
             expected_text="SHA256SUMS"),
        Case("size_mismatch", lambda directory: write_json(
            directory / "update.json",
            {**load_json(directory / "update.json"),
             "sizeBytes": load_json(directory / "update.json")["sizeBytes"] + 1}),
             expected_text="sizeBytes"),
        Case("package_mismatch", lambda directory: write_json(
            directory / "update.json",
            {**load_json(directory / "update.json"), "applicationId": "com.greatwall.callring.fake"}),
             expected_text="applicationId"),
        Case("qa_version_name", lambda directory: write_json(
            directory / "update.json",
            {**load_json(directory / "update.json"), "versionName": f"{version_name}-backend-qa"}),
             expected_text="backendQa"),
        Case("unexpected_file",
             lambda directory: (directory / "stray.txt").write_text("unexpected\n", encoding="utf-8"),
             expected_text="unexpected files"),
        Case("version_not_increasing", published_max=version_code, expected_text="versionCode"),
        Case("debug_apk_mixed", lambda directory: _stage_debug_apk(
            directory, debug_apk, apk_name), expected_text="debuggable"),
    ]

    results = []
    for case in cases:
        case_directory = workspace / f"case-{case.name}"
        shutil.copytree(good_fixture(), case_directory)
        case.mutate(case_directory)
        completed = run(VERIFY, verify_args(case_directory, case.tag, case.published_max))
        output = combined_output(completed)
        passed = completed.returncode == 0 if case.expect_ok else completed.returncode != 0
        if passed and case.expected_text is not None and case.expected_text not in output:
            passed = False
        results.append((case.name, case.expect_ok, completed.returncode, output.strip(), passed))

    version_cases = _version_tool_cases(workspace, properties, values)
    for name, arguments, expect_ok, expected_text in version_cases:
        completed = run(VERSION_TOOL, arguments)
        output = combined_output(completed)
        passed = completed.returncode == 0 if expect_ok else completed.returncode != 0
        if passed and expected_text is not None and expected_text not in output:
            passed = False
        results.append((f"version_tool:{name}", expect_ok, completed.returncode, output.strip(), passed))

    print("release tooling failure-sample self test")
    print(f"fixture: {apk_name} versionCode={version_code} published-max={published_max}")
    print("-" * 96)
    failures = 0
    for name, expect_ok, returncode, output, passed in results:
        status = "PASS" if passed else "FAIL"
        if not passed:
            failures += 1
        expectation = "exit 0" if expect_ok else "non-zero exit"
        print(f"{status}  {name:<32} expected {expectation:<12} observed exit {returncode}")
        if not passed:
            print(f"      output: {output.replace(chr(10), ' | ')[:400]}")
    print("-" * 96)
    print(f"{len(results) - failures}/{len(results)} samples behaved as required")
    if args.keep:
        print(f"kept fixtures: {workspace}")
    else:
        shutil.rmtree(workspace, ignore_errors=True)
    return 1 if failures else 0


def _stage_debug_apk(directory: Path, debug_apk: Path, apk_name: str) -> None:
    """Replace the staged APK with a debug build and keep metadata self-consistent.

    The debug APK has the same package/version/minSdk, so only the debuggable flag
    distinguishes it; metadata is rewritten so the debuggable check is what fires.
    """
    if not debug_apk.is_file():
        raise FileNotFoundError(f"debug APK not found: {debug_apk}")
    staged = directory / apk_name
    shutil.copy2(debug_apk, staged)
    update_path = directory / "update.json"
    update = load_json(update_path)
    update["sizeBytes"] = staged.stat().st_size
    update["sha256"] = sha256_file(staged)
    write_json(update_path, update)
    (directory / "SHA256SUMS.txt").write_text(
        f"{update['sha256']}  {apk_name}\n", encoding="utf-8", newline="\n"
    )


def _version_tool_cases(workspace: Path, properties: Path, values: dict):
    """(name, arguments, expect_ok, expected_text) samples for version_tool.py."""
    cases = []
    good = workspace / "version-good.properties"
    good.write_text(properties.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    cases.append(("good", ["--properties", str(good), "check"], True, "OK versionCode"))

    code = values["versionCode"]
    name = values["versionName"]
    published_max = int(code) - 1
    samples = [
        ("two_part_name", f"versionCode={code}\nversionName=0.3\n", "MAJOR.MINOR.PATCH"),
        ("tag_prefix_name", f"versionCode={code}\nversionName=v{name}\n", "MAJOR.MINOR.PATCH"),
        ("downgrade_name", f"versionCode={code}\nversionName=0.2.9\n", "must be greater"),
        ("non_increasing_code", f"versionCode={published_max}\nversionName=0.4.0\n", "greater than"),
        ("leading_zero_code", f"versionCode=0{code}\nversionName=0.4.0\n", "positive integer"),
        ("misspelled_key", f"versionCodes={code}\nversionName=0.4.0\n", "missing versionCode"),
    ]
    for sample_name, content, expected_text in samples:
        path = workspace / f"version-{sample_name}.properties"
        path.write_text(content, encoding="utf-8", newline="\n")
        arguments = ["--properties", str(path), "check", "--published-max", str(published_max)]
        if sample_name == "downgrade_name":
            arguments.extend(["--published-name", "0.3.0"])
        cases.append((sample_name, arguments, False, expected_text))
    return cases


if __name__ == "__main__":
    sys.exit(main())
