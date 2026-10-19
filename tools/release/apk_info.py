"""APK introspection and digest helpers shared by the release tooling.

Package facts are read with ``aapt2 dump badging``; file size and SHA-256 are
computed locally with hashlib. Nothing here downloads, executes or modifies an
APK, and no network access is performed.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

BUILD_TOOLS_BINARIES = {
    "aapt2": ("aapt2.exe", "aapt2"),
    "apksigner": ("apksigner.bat", "apksigner"),
    "zipalign": ("zipalign.exe", "zipalign"),
}

_PACKAGE_LINE = re.compile(
    r"^package: name='(?P<package>[^']*)' versionCode='(?P<code>[^']*)'"
    r"(?: versionName='(?P<name>[^']*)')?",
    re.MULTILINE,
)
_MIN_SDK_LINE = re.compile(r"^(?:minSdkVersion|sdkVersion):'(?P<sdk>\d+)'", re.MULTILINE)
_DEBUGGABLE_LINE = re.compile(r"^application-debuggable\s*$", re.MULTILINE)


class ToolNotFoundError(RuntimeError):
    pass


class ApkInfoError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApkInfo:
    path: Path
    package: str
    version_code: int
    version_name: str
    min_sdk: Optional[int]
    debuggable: bool
    size_bytes: int
    sha256: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _version_sort_key(directory: Path) -> tuple:
    key = []
    for part in directory.name.split("."):
        key.append(int(part) if part.isdigit() else 0)
    return tuple(key)


def _build_tools_directories():
    roots = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value))
    for root in roots:
        build_tools = root / "build-tools"
        if not build_tools.is_dir():
            continue
        try:
            version_directories = [item for item in build_tools.iterdir() if item.is_dir()]
        except OSError:
            continue
        for version_directory in sorted(version_directories, key=_version_sort_key, reverse=True):
            yield version_directory


def find_tool(kind: str, explicit: Optional[str] = None) -> Path:
    """Locate an Android build-tools binary (aapt2/apksigner/zipalign)."""
    if kind not in BUILD_TOOLS_BINARIES:
        raise ValueError(f"unknown build-tools binary {kind!r}")
    names = BUILD_TOOLS_BINARIES[kind]
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    environment_value = os.environ.get(kind.upper())
    if environment_value:
        candidates.append(Path(environment_value))
    for version_directory in _build_tools_directories():
        candidates.extend(version_directory / name for name in names)
    for name in names:
        located = shutil.which(name)
        if located:
            candidates.append(Path(located))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise ToolNotFoundError(
        f"could not find {kind}; pass it explicitly or set ANDROID_HOME to an Android SDK "
        f"that contains build-tools (tried: {', '.join(names)})"
    )


def parse_badging(apk: Path, output: str) -> ApkInfo:
    package_match = _PACKAGE_LINE.search(output)
    if package_match is None:
        raise ApkInfoError(f"aapt2 badging output for {apk} has no 'package:' line")
    code_text = package_match.group("code").strip()
    if not code_text.isdigit():
        raise ApkInfoError(f"aapt2 badging output for {apk} has a non-numeric versionCode {code_text!r}")
    name_text = (package_match.group("name") or "").strip()
    if not name_text:
        raise ApkInfoError(f"aapt2 badging output for {apk} has an empty versionName")
    sdk_match = _MIN_SDK_LINE.search(output)
    return ApkInfo(
        path=apk,
        package=package_match.group("package"),
        version_code=int(code_text),
        version_name=name_text,
        min_sdk=int(sdk_match.group("sdk")) if sdk_match else None,
        debuggable=bool(_DEBUGGABLE_LINE.search(output)),
        size_bytes=apk.stat().st_size,
        sha256=sha256_file(apk),
    )


def read_apk_info(apk: Path, aapt2: Optional[str] = None) -> ApkInfo:
    apk = Path(apk)
    if not apk.is_file():
        raise ApkInfoError(f"APK does not exist: {apk}")
    tool = find_tool("aapt2", aapt2)
    completed = subprocess.run(
        [str(tool), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise ApkInfoError(f"aapt2 dump badging failed for {apk} (exit {completed.returncode}): {detail}")
    return parse_badging(apk, completed.stdout)
