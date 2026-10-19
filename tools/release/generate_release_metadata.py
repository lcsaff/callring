#!/usr/bin/env python3
"""Generate update.json and SHA256SUMS.txt from one signed release APK.

The APK is staged in --out-dir under the canonical asset name
``callring-<versionName>.apk``; every value in the metadata is then computed from
that staged copy, so the APK, update.json and SHA256SUMS.txt share one source of
truth. Debug builds, backendQa artifacts and APKs whose package/version do not
match version.properties are rejected.

Examples:
  python tools/release/generate_release_metadata.py \\
      --apk app/build/outputs/apk/release/app-release.apk --out-dir dist
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

from apk_info import ApkInfoError, ToolNotFoundError, read_apk_info
from version_tool import VersionError, read_version_properties, validate

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROPERTIES = ROOT / "version.properties"
DEFAULT_APPLICATION_ID = "com.greatwall.callring"


class MetadataError(Exception):
    """A user-facing refusal to generate release metadata."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MetadataError(message)


def build_update_json(info, version_code: int, version_name: str,
                      application_id: str) -> dict:
    return {
        "schemaVersion": 1,
        "applicationId": application_id,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": info.min_sdk,
        "assetName": info.path.name,
        "sizeBytes": info.size_bytes,
        "sha256": info.sha256,
    }


def generate(apk: Path, out_dir: Path, properties_path: Path,
             application_id: str, aapt2: str | None) -> dict:
    require(apk.is_file(), f"APK does not exist: {apk}")
    values = read_version_properties(properties_path)
    version_code, version_name = validate(values)

    info = read_apk_info(apk, aapt2)
    require(info.package == application_id,
            f"APK package {info.package!r} does not match release applicationId {application_id!r}")
    require(not info.package.endswith(".backendqa"),
            f"refusing backendQa APK (package {info.package!r})")
    require(not info.version_name.endswith("-backend-qa"),
            f"refusing backendQa APK (versionName {info.version_name!r})")
    require(not info.debuggable,
            f"refusing debuggable APK {apk} as a release artifact")
    require(info.version_code == version_code,
            f"APK versionCode {info.version_code} does not match version.properties ({version_code})")
    require(info.version_name == version_name,
            f"APK versionName {info.version_name!r} does not match version.properties ({version_name!r})")
    require(info.min_sdk is not None, "APK declares no minSdkVersion")

    out_dir.mkdir(parents=True, exist_ok=True)
    asset_name = f"callring-{version_name}.apk"
    staged_apk = out_dir / asset_name
    if apk.resolve() != staged_apk.resolve():
        shutil.copy2(apk, staged_apk)

    staged_info = read_apk_info(staged_apk, aapt2)
    update = build_update_json(staged_info, version_code, version_name, application_id)
    (out_dir / "update.json").write_text(
        json.dumps(update, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n"
    )
    (out_dir / "SHA256SUMS.txt").write_text(
        f"{staged_info.sha256}  {asset_name}\n", encoding="utf-8", newline="\n"
    )
    return update


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True, help="signed release APK to describe")
    parser.add_argument("--out-dir", required=True, help="directory that receives the three release artifacts")
    parser.add_argument("--version-properties", default=str(DEFAULT_PROPERTIES),
                        help="path to version.properties (default: repo root copy)")
    parser.add_argument("--application-id", default=DEFAULT_APPLICATION_ID,
                        help=f"expected Android applicationId (default: {DEFAULT_APPLICATION_ID})")
    parser.add_argument("--aapt2", default=None, help="path to aapt2 (default: auto-discover)")
    args = parser.parse_args(argv)
    try:
        update = generate(Path(args.apk), Path(args.out_dir),
                          Path(args.version_properties), args.application_id, args.aapt2)
    except (MetadataError, VersionError, ApkInfoError, ToolNotFoundError, OSError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"WROTE {Path(args.out_dir) / 'update.json'}")
    print(f"WROTE {Path(args.out_dir) / 'SHA256SUMS.txt'}")
    print(
        f"asset={update['assetName']} versionCode={update['versionCode']} "
        f"versionName={update['versionName']} sizeBytes={update['sizeBytes']} sha256={update['sha256']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
