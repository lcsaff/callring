#!/usr/bin/env python3
"""Check that CI workflows reference real release tooling and supported flags.

`.github/workflows/*.yml` calls `tools/release/` scripts directly. A rename or a
flag change would otherwise only surface on the first CI run, so this guard
statically verifies:
  * every `tools/release/...` path mentioned in a workflow exists (globs expand),
  * every script call uses a registered argparse subcommand (when one is given),
  * every `--flag` used on a script call appears as a literal in that script.

Exit code 0 means every reference is resolvable; any mismatch prints `[FAIL]`
and exits non-zero.
"""
from __future__ import annotations

import glob
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
SCRIPT_REFERENCE = re.compile(r"tools/release/[A-Za-z0-9_./*-]+")
FLAG = re.compile(r"^--[A-Za-z0-9][A-Za-z0-9-]*$")
FLAG_IN_SOURCE = re.compile(r"""["'](--[A-Za-z0-9][A-Za-z0-9-]*)["']""")
SUBCOMMAND_IN_SOURCE = re.compile(r"""add_parser\(\s*["']([A-Za-z0-9_-]+)["']""")


def logical_commands(workflow_text: str):
    """Yield shell commands, joining YAML/shell backslash continuations."""
    current = ""
    for raw_line in workflow_text.splitlines():
        line = raw_line.rstrip()
        if line.endswith("\\"):
            current += line[:-1] + " "
            continue
        current += line
        if current.strip():
            yield current
        current = ""
    if current.strip():
        yield current


def resolve_reference(reference: str):
    """Return (paths, error). Globs must match at least one path."""
    if "*" in reference:
        matches = [Path(match) for match in glob.glob(str(ROOT / reference))]
        if not matches:
            return [], f"glob {reference!r} matches no files"
        missing = [str(path) for path in matches if not path.exists()]
        return matches, (f"glob {reference!r} matched missing paths: {missing}" if missing else None)
    path = ROOT / reference
    if not path.exists():
        return [], f"referenced path does not exist: {reference}"
    return [path], None


def check_workflow(workflow_path: Path) -> list:
    problems = []
    text = workflow_path.read_text(encoding="utf-8")
    commands = list(logical_commands(text))

    references = sorted(set(SCRIPT_REFERENCE.findall(text)))
    if not references:
        problems.append(f"{workflow_path.name}: no tools/release references found (stale workflow?)")
    for reference in references:
        _, error = resolve_reference(reference)
        if error:
            problems.append(f"{workflow_path.name}: {error}")

    for command in commands:
        for match in SCRIPT_REFERENCE.finditer(command):
            reference = match.group(0)
            if "*" in reference:
                continue
            script = ROOT / reference
            if not script.is_file():
                continue  # already reported above
            tokens = command[match.end():].split()
            flags = [token for token in tokens if FLAG.match(token)]
            source = script.read_text(encoding="utf-8")
            supported_flags = set(FLAG_IN_SOURCE.findall(source))
            for flag in flags:
                if flag not in supported_flags:
                    problems.append(
                        f"{workflow_path.name}: {reference} does not support flag {flag!r}"
                    )
            if tokens and not tokens[0].startswith(("-", "'", '"')):
                subcommand = tokens[0].strip("'\"")
                if subcommand.isidentifier() or "-" in subcommand:
                    supported_subcommands = set(SUBCOMMAND_IN_SOURCE.findall(source))
                    if supported_subcommands and subcommand not in supported_subcommands:
                        problems.append(
                            f"{workflow_path.name}: {reference} has no subcommand {subcommand!r} "
                            f"(has: {', '.join(sorted(supported_subcommands))})"
                        )
    return problems


def main() -> int:
    workflows = sorted(WORKFLOW_DIR.glob("*.yml")) + sorted(WORKFLOW_DIR.glob("*.yaml"))
    if not workflows:
        print(f"[FAIL] no workflow files under {WORKFLOW_DIR}", file=sys.stderr)
        return 1
    problems = []
    for workflow in workflows:
        problems.extend(check_workflow(workflow))
    if problems:
        for problem in problems:
            print(f"[FAIL] {problem}", file=sys.stderr)
        return 1
    for workflow in workflows:
        references = sorted(set(SCRIPT_REFERENCE.findall(workflow.read_text(encoding="utf-8"))))
        print(f"[OK] {workflow.name}: {len(references)} tools/release references resolvable")
    return 0


if __name__ == "__main__":
    sys.exit(main())
