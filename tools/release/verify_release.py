#!/usr/bin/env python3
"""Verify one staged release directory; fail fast with a non-zero exit code.

A staged release must contain exactly three files:
  callring-<versionName>.apk, update.json, SHA256SUMS.txt

Checks performed:
  * version.properties is valid and (when given) versionCode > published maximum
  * tag == "v<versionName>"
  * required attachments are present and no unexpected files/APKs are staged
  * update.json: schemaVersion/applicationId/versionCode/versionName/minSdk and
    assetName/sizeBytes/sha256 agree with version.properties and the APK
  * SHA256SUMS.txt: exactly one entry for the APK with the real digest
  * APK (aapt2): package/version/minSdk match, not debuggable, not backendQa
  * optional: signer certificate SHA-256, zipalign -c, /releases/latest tag

Examples:
  python tools/release/verify_release.py --release-dir dist --tag v0.3.0 \\
      --published-max 2 --expected-cert-sha256 5a901cad...24b4 --check-alignment
  python tools/release/verify_release.py --release-dir dist --tag v0.3.0 --latest-tag v0.3.0
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

from apk_info import ApkInfoError, ToolNotFoundError, find_tool, read_apk_info, sha256_file
from version_tool import VersionError, read_version_properties, validate

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROPERTIES = ROOT / "version.properties"
DEFAULT_APPLICATION_ID = "com.greatwall.callring"
UPDATE_JSON_NAME = "update.json"
SHA256SUMS_NAME = "SHA256SUMS.txt"
UPDATE_JSON_KEYS = {
    "schemaVersion",
    "applicationId",
    "versionCode",
    "versionName",
    "minSdk",
    "assetName",
    "sizeBytes",
    "sha256",
}
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
SUMS_LINE_PATTERN = re.compile(r"^(?P<sha>[0-9a-fA-F]{64})[ \t]+(?:\*)?(?P<name>\S.*?)\s*$")
SIGNER_DIGEST_PATTERN = re.compile(
    r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", re.MULTILINE
)


class ReleaseCheckError(Exception):
    """A user-facing release verification failure."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseCheckError(message)


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def verify_update_json(update_path: Path, version_code: int, version_name: str,
                       application_id: str, apk_name: str, apk_size: int,
                       apk_digest: str) -> dict:
    require(update_path.is_file(), f"missing required attachment {UPDATE_JSON_NAME}")
    try:
        update = json.loads(update_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseCheckError(f"{UPDATE_JSON_NAME} is not valid JSON: {error}") from error
    require(isinstance(update, dict), f"{UPDATE_JSON_NAME} must be a JSON object")
    missing = UPDATE_JSON_KEYS - set(update)
    unexpected = set(update) - UPDATE_JSON_KEYS
    require(not missing, f"{UPDATE_JSON_NAME} is missing fields: {', '.join(sorted(missing))}")
    require(not unexpected,
            f"{UPDATE_JSON_NAME} has unexpected fields for schemaVersion 1: {', '.join(sorted(unexpected))}")

    require(_is_int(update["schemaVersion"]) and update["schemaVersion"] == 1,
            f"{UPDATE_JSON_NAME} schemaVersion must be the integer 1")
    require(update["applicationId"] == application_id,
            f"{UPDATE_JSON_NAME} applicationId {update['applicationId']!r} != {application_id!r}")
    require(not str(update["applicationId"]).endswith(".backendqa"),
            f"{UPDATE_JSON_NAME} points at a backendQa applicationId: {update['applicationId']!r}")
    require(_is_int(update["versionCode"]) and update["versionCode"] == version_code,
            f"{UPDATE_JSON_NAME} versionCode {update['versionCode']!r} != version.properties ({version_code})")
    require(not str(update["versionName"]).endswith("-backend-qa"),
            f"{UPDATE_JSON_NAME} versionName is a backendQa suffix variant: {update['versionName']!r}")
    require(update["versionName"] == version_name,
            f"{UPDATE_JSON_NAME} versionName {update['versionName']!r} != version.properties ({version_name!r})")
    require(_is_int(update["minSdk"]) and update["minSdk"] > 0,
            f"{UPDATE_JSON_NAME} minSdk must be a positive integer")
    require(update["assetName"] == apk_name,
            f"{UPDATE_JSON_NAME} assetName {update['assetName']!r} != staged APK name {apk_name!r}")
    require(_is_int(update["sizeBytes"]) and update["sizeBytes"] == apk_size,
            f"{UPDATE_JSON_NAME} sizeBytes {update['sizeBytes']!r} != actual APK size {apk_size}")
    require(isinstance(update["sha256"], str) and SHA256_PATTERN.fullmatch(update["sha256"]) is not None,
            f"{UPDATE_JSON_NAME} sha256 is not a 64-character hex digest")
    require(update["sha256"].lower() == apk_digest,
            f"{UPDATE_JSON_NAME} sha256 {update['sha256'].lower()} != actual APK digest {apk_digest}")
    return update


def verify_sha256sums(sums_path: Path, apk_name: str, apk_digest: str) -> None:
    require(sums_path.is_file(), f"missing required attachment {SHA256SUMS_NAME}")
    entries = {}
    for line_number, raw_line in enumerate(sums_path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue
        match = SUMS_LINE_PATTERN.match(line)
        require(match is not None, f"{SHA256SUMS_NAME}:{line_number}: malformed line {raw_line!r}")
        name = match.group("name")
        require(name not in entries, f"{SHA256SUMS_NAME}:{line_number}: duplicate entry for {name!r}")
        entries[name] = match.group("sha").lower()
    require(set(entries) == {apk_name},
            f"{SHA256SUMS_NAME} entries {sorted(entries)} != expected [{apk_name!r}]")
    require(entries[apk_name] == apk_digest,
            f"{SHA256SUMS_NAME} digest {entries[apk_name]} != actual APK digest {apk_digest}")


def verify_apk(apk: Path, application_id: str, version_code: int, version_name: str,
               expected_min_sdk: int, aapt2: str | None):
    info = read_apk_info(apk, aapt2)
    require(info.package == application_id,
            f"APK package {info.package!r} != expected applicationId {application_id!r}")
    require(not info.package.endswith(".backendqa"),
            f"APK is a backendQa build (package {info.package!r}); QA builds must not be released")
    require(not info.version_name.endswith("-backend-qa"),
            f"APK is a backendQa build (versionName {info.version_name!r})")
    require(info.version_code == version_code,
            f"APK versionCode {info.version_code} != version.properties ({version_code})")
    require(info.version_name == version_name,
            f"APK versionName {info.version_name!r} != version.properties ({version_name!r})")
    require(info.min_sdk == expected_min_sdk,
            f"APK minSdk {info.min_sdk} != {UPDATE_JSON_NAME} minSdk {expected_min_sdk}")
    require(not info.debuggable,
            "APK is a debuggable (debug) build; refusing to publish it as a release artifact")
    return info


def verify_signature(apk: Path, expected_cert_sha256: str, apksigner: str | None) -> None:
    expected = expected_cert_sha256.replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None,
            f"--expected-cert-sha256 must be a 64-character hex digest, got {expected_cert_sha256!r}")
    tool = find_tool("apksigner", apksigner)
    completed = subprocess.run(
        [str(tool), "verify", "--print-certs", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    detail = (completed.stdout or "") + (completed.stderr or "")
    require(completed.returncode == 0,
            f"apksigner verify failed for {apk} (exit {completed.returncode}): {detail.strip()}")
    digests = {digest.lower() for digest in SIGNER_DIGEST_PATTERN.findall(completed.stdout or "")}
    require(digests, "apksigner verify printed no certificate SHA-256 digest")
    require(digests == {expected},
            f"APK signer certificate SHA-256 {sorted(digests)} != expected {expected}")


def verify_alignment(apk: Path, zipalign: str | None) -> None:
    tool = find_tool("zipalign", zipalign)
    completed = subprocess.run(
        [str(tool), "-c", "-v", "4", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if completed.returncode != 0:
        tail = "\n".join((completed.stdout or "").strip().splitlines()[-5:])
        raise ReleaseCheckError(f"zipalign -c -v 4 failed for {apk} (exit {completed.returncode}):\n{tail}")


def verify(args: argparse.Namespace) -> dict:
    release_dir = Path(args.release_dir)
    require(release_dir.is_dir(), f"release directory does not exist: {release_dir}")

    values = read_version_properties(Path(args.version_properties))
    version_code, version_name = validate(values, args.published_max)
    apk_name = f"callring-{version_name}.apk"
    require(args.tag == f"v{version_name}",
            f"release tag {args.tag!r} != v<versionName> ({'v' + version_name!r})")

    entries = sorted(release_dir.iterdir())
    directories = [entry.name for entry in entries if entry.is_dir()]
    require(not directories,
            f"release directory must contain only files, found directories: {', '.join(directories)}")
    staged = {entry.name: entry for entry in entries if entry.is_file()}
    expected = {apk_name, UPDATE_JSON_NAME, SHA256SUMS_NAME}
    missing = expected - set(staged)
    unexpected = set(staged) - expected
    require(not missing, f"missing required attachments: {', '.join(sorted(missing))}")
    require(not unexpected, f"unexpected files in release directory: {', '.join(sorted(unexpected))}")

    apk = staged[apk_name]
    apk_size = apk.stat().st_size
    apk_digest = sha256_file(apk)

    update = verify_update_json(
        staged[UPDATE_JSON_NAME], version_code, version_name, args.application_id,
        apk_name, apk_size, apk_digest,
    )
    verify_sha256sums(staged[SHA256SUMS_NAME], apk_name, apk_digest)
    info = verify_apk(apk, args.application_id, version_code, version_name,
                      update["minSdk"], args.aapt2)

    if args.expected_cert_sha256:
        verify_signature(apk, args.expected_cert_sha256, args.apksigner)
    if args.check_alignment:
        verify_alignment(apk, args.zipalign)
    if args.latest_tag is not None:
        require(args.latest_tag == args.tag,
                f"/releases/latest tag {args.latest_tag!r} != released tag {args.tag!r}")
    return {
        "apk_name": apk_name,
        "version_code": version_code,
        "version_name": version_name,
        "tag": args.tag,
        "size_bytes": apk_size,
        "sha256": apk_digest,
        "min_sdk": info.min_sdk,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-dir", required=True, help="directory with the three release artifacts")
    parser.add_argument("--tag", required=True, help="release tag; must equal v<versionName>")
    parser.add_argument("--version-properties", default=str(DEFAULT_PROPERTIES),
                        help="path to version.properties (default: repo root copy)")
    parser.add_argument("--published-max", type=int, default=None,
                        help="highest already-published versionCode; this release must exceed it")
    parser.add_argument("--application-id", default=DEFAULT_APPLICATION_ID,
                        help=f"expected Android applicationId (default: {DEFAULT_APPLICATION_ID})")
    parser.add_argument("--aapt2", default=None, help="path to aapt2 (default: auto-discover)")
    parser.add_argument("--expected-cert-sha256", default=None,
                        help="require this signer certificate SHA-256 digest")
    parser.add_argument("--apksigner", default=None, help="path to apksigner (default: auto-discover)")
    parser.add_argument("--check-alignment", action="store_true",
                        help="also require zipalign -c -v 4 to pass")
    parser.add_argument("--zipalign", default=None, help="path to zipalign (default: auto-discover)")
    parser.add_argument("--latest-tag", default=None,
                        help="post-publish check: /releases/latest tag_name must equal --tag")
    args = parser.parse_args(argv)
    try:
        result = verify(args)
    except (ReleaseCheckError, VersionError, ApkInfoError, ToolNotFoundError, OSError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(
        f"[OK] release verified: {result['apk_name']} versionCode={result['version_code']} "
        f"versionName={result['version_name']} tag={result['tag']} "
        f"minSdk={result['min_sdk']} sizeBytes={result['size_bytes']} sha256={result['sha256']}"
    )
    print("[OK] update.json and SHA256SUMS.txt agree with the APK")
    if args.expected_cert_sha256:
        print(f"[OK] signer certificate SHA-256 = {args.expected_cert_sha256}")
    if args.check_alignment:
        print("[OK] zipalign -c -v 4 passed")
    if args.latest_tag is not None:
        print(f"[OK] /releases/latest tag = {args.latest_tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
