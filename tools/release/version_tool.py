#!/usr/bin/env python3
"""Validate or prepare the single version source: ``version.properties``.

The file is the only version source for both Gradle (root build.gradle ->
app/build.gradle) and the release tooling. Version names use the
``MAJOR.MINOR.PATCH`` form that matches the ``v<versionName>`` release tag
(e.g. 0.3.0 <-> v0.3.0). The two-part historical tag ``v0.2`` is not a template.

Examples:
  python tools/release/version_tool.py check --published-max 2 --tag v0.3.0
  python tools/release/version_tool.py set --code 4 --name 0.3.1 --published-max 3
  python tools/release/version_tool.py get --field versionName
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROPERTIES = ROOT / "version.properties"
NAME_PATTERN = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
CODE_PATTERN = re.compile(r"^[1-9]\d*$")
PUBLISHED_NAME_PATTERN = re.compile(r"^(0|[1-9]\d*)(\.(0|[1-9]\d*)){1,2}$")
KEYS = ("versionCode", "versionName")


class VersionError(Exception):
    """A user-facing version validation failure."""


def read_version_properties(path: Path = DEFAULT_PROPERTIES) -> dict:
    path = Path(path)
    if not path.is_file():
        raise VersionError(f"version properties file not found: {path}")
    values = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise VersionError(f"{path}:{line_number}: expected 'key=value', got {raw_line!r}")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def normalize_name(name: str) -> tuple:
    """Return a tuple of ints for an X.Y[.Z] name, rejecting anything else."""
    if not PUBLISHED_NAME_PATTERN.fullmatch(name):
        raise VersionError(f"invalid version name {name!r}; expected MAJOR.MINOR[.PATCH] (e.g. 0.3.0)")
    parts = [int(part) for part in name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def validate(values: dict, published_max: Optional[int] = None,
             published_name: Optional[str] = None, tag: Optional[str] = None) -> tuple:
    """Validate version properties and optional monotonicity/tag constraints."""
    code_text = values.get("versionCode")
    name_text = values.get("versionName")
    problems = []
    if code_text is None:
        problems.append("missing versionCode")
    elif not CODE_PATTERN.fullmatch(code_text):
        problems.append(
            f"versionCode must be a positive integer without leading zeros, got {code_text!r}"
        )
    if name_text is None:
        problems.append("missing versionName")
    elif not NAME_PATTERN.fullmatch(name_text):
        problems.append(
            f"versionName must be MAJOR.MINOR.PATCH (e.g. 0.3.0) without a 'v' prefix, got {name_text!r}"
        )
    if problems:
        raise VersionError("; ".join(problems))

    code = int(code_text)
    if published_max is not None:
        if code <= int(published_max):
            problems.append(
                f"versionCode {code} must be greater than the highest published versionCode "
                f"{published_max}"
            )
    if published_name is not None:
        if normalize_name(name_text) <= normalize_name(published_name):
            problems.append(
                f"versionName {name_text} must be greater than the published versionName "
                f"{published_name}"
            )
    if tag is not None:
        expected_tag = f"v{name_text}"
        if tag != expected_tag:
            problems.append(f"release tag {tag!r} must equal v<versionName> ({expected_tag!r})")
    if problems:
        raise VersionError("; ".join(problems))
    return code, name_text


def _set_value(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^(?P<indent>\s*){re.escape(key)}(?P<sep>\s*)=.*$", re.MULTILINE)
    replacement = f"{key}={value}"
    if pattern.search(text):
        return pattern.sub(lambda match: f"{match.group('indent')}{replacement}", text, count=1)
    separator = "" if text.endswith("\n") or not text else "\n"
    return f"{text}{separator}{replacement}\n"


def command_check(args: argparse.Namespace) -> int:
    values = read_version_properties(Path(args.properties))
    published_max = args.published_max
    if published_max is not None and published_max < 0:
        raise VersionError(f"--published-max must be >= 0, got {published_max}")
    code, name = validate(values, published_max, args.published_name, args.tag)
    print(f"OK versionCode={code} versionName={name}" + (f" tag={args.tag}" if args.tag else ""))
    return 0


def command_set(args: argparse.Namespace) -> int:
    path = Path(args.properties)
    values = read_version_properties(path)
    if not CODE_PATTERN.fullmatch(args.code):
        raise VersionError(
            f"--code must be a positive integer without leading zeros, got {args.code!r}"
        )
    if not NAME_PATTERN.fullmatch(args.name):
        raise VersionError(
            f"--name must be MAJOR.MINOR.PATCH without a 'v' prefix (e.g. 0.3.0), got {args.name!r}"
        )
    candidate = dict(values)
    candidate["versionCode"] = args.code
    candidate["versionName"] = args.name
    code, name = validate(candidate, args.published_max, args.published_name)
    text = path.read_text(encoding="utf-8")
    text = _set_value(text, "versionCode", str(code))
    text = _set_value(text, "versionName", name)
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"WROTE {path}: versionCode={code} versionName={name}")
    return 0


def command_get(args: argparse.Namespace) -> int:
    values = read_version_properties(Path(args.properties))
    validate(values)
    field = args.field
    if field not in KEYS:
        raise VersionError(f"--field must be one of {', '.join(KEYS)}, got {field!r}")
    print(values[field], end="")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--properties", default=str(DEFAULT_PROPERTIES),
                        help="path to version.properties (default: repo root copy)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser("check", help="validate the version file (no writes)")
    check.add_argument("--published-max", type=int, default=None,
                       help="highest already-published versionCode; new code must exceed it")
    check.add_argument("--published-name", default=None,
                       help="published versionName; new name must be greater (semver order)")
    check.add_argument("--tag", default=None, help="release tag; must equal v<versionName>")
    check.set_defaults(handler=command_check)

    set_command = subparsers.add_parser("set", help="write a new validated version")
    set_command.add_argument("--code", required=True, help="new versionCode, e.g. 3")
    set_command.add_argument("--name", required=True, help="new versionName, e.g. 0.3.0")
    set_command.add_argument("--published-max", type=int, default=None,
                             help="highest already-published versionCode; new code must exceed it")
    set_command.add_argument("--published-name", default=None,
                             help="published versionName; new name must be greater (semver order)")
    set_command.set_defaults(handler=command_set)

    get = subparsers.add_parser("get", help="print one raw field (no newline)")
    get.add_argument("--field", required=True, choices=KEYS)
    get.set_defaults(handler=command_get)
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except VersionError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
