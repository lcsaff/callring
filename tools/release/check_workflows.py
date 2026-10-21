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

import ast
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


def run_commands(text):
    """Read actual YAML run values, excluding comments and non-run metadata."""
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        match = re.match(r'^( *)(?:run):[ \t]*(.*)$', lines[i])
        if not match:
            i += 1; continue
        indent, value = len(match.group(1)), match.group(2)
        i += 1
        if value in ('|', '>', '|-', '>-'):
            block = []
            while i < len(lines) and (not lines[i].strip() or len(lines[i])-len(lines[i].lstrip()) > indent):
                if lines[i].strip() and not lines[i].lstrip().startswith('#'): block.append(lines[i])
                i += 1
            yield from logical_commands('\n'.join(block))
        elif value and not value.startswith('#'):
            yield value


def gate_problems(name, workflow, shared, entry, classpath=None):
    problems = []
    commands = list(run_commands(workflow))
    if not any(re.fullmatch(r'[ \t]*python3[ \t]+tools/verify_backend\.py[ \t]*', c) for c in commands):
        problems.append(f'{name}: missing actual unified verification command')
    if name == 'build.yml':
        code = '\n'.join(line for line in workflow.splitlines() if not line.lstrip().startswith('#'))
        if not re.search(r'^[ \t]*-[ \t]*[\'"]codex/\*\*[\'"][ \t]*$', code, re.M): problems.append('build.yml: missing codex/** push branch')
        if not re.search(r'^  pull_request:[ \t]*$', code, re.M): problems.append('build.yml: missing pull_request')
    try:
        tree = ast.parse(shared)
        constants = {node.targets[0].id: ast.literal_eval(node.value) for node in tree.body
                     if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id in ('GATE_TASKS', 'PYTHON_CHECKS')}
        required = {':contracts:test', ':backend:test', ':ui:testDebugUnitTest', ':app:testDebugUnitTest',
                    ':ui:checkDebugCompileClasspath', ':ui:assembleDebug', ':app:assembleDebug',
                    ':app:assembleRelease', ':app:assembleBackendQa', ':ui:assembleDebugAndroidTest',
                    ':app:assembleDebugAndroidTest', ':ui:lintDebug', ':app:lintDebug'}
        if required - set(constants.get('GATE_TASKS', ())): problems.append(f'{name}: shared gate missing required Gradle tasks')
        checks = set(tuple(c) for c in constants.get('PYTHON_CHECKS', ()))
        expected = {('tools/check_architecture.py',), ('tools/check_backend_boundaries.py',)}
        expected.update(('-m', 'unittest', 'discover', '-s', 'tools', '-p', file) for file in ('test_architecture.py', 'test_backend_boundaries.py', 'test_verification.py'))
        if expected - checks: problems.append(f'{name}: shared gate missing Python checks')
        gate = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'execute_gate')
        if not any(isinstance(n, ast.For) and any(isinstance(v, ast.Name) and v.id == 'PYTHON_CHECKS' for v in ast.walk(n.iter))
                   and any(isinstance(c, ast.Call) and isinstance(c.func, ast.Attribute) and c.func.attr == 'run' for c in ast.walk(n)) for n in ast.walk(gate)):
            problems.append(f'{name}: Python checks are not executed')
        if not any(isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == 'run'
                   and any(isinstance(c, ast.Starred) and isinstance(c.value, ast.Name) and c.value.id == 'GATE_TASKS' for c in ast.walk(n)) for n in ast.walk(gate)):
            problems.append(f'{name}: Gradle task table is not executed')
        main = next(n for n in ast.parse(entry).body if isinstance(n, ast.FunctionDef) and n.name == 'main')
        if not any(isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and isinstance(n.func.value, ast.Name)
                   and n.func.value.id == 'common' and n.func.attr == 'execute_gate' for n in ast.walk(main)):
            problems.append(f'{name}: verification entry does not execute shared gate')
    except (SyntaxError, ValueError, TypeError, StopIteration) as error:
        problems.append(f'{name}: invalid gate source: {error}')
    if classpath is None:
        classpath = (ROOT/'ui/build.gradle').read_text(encoding='utf-8')
    classpath = re.sub(r'/\*.*?\*/|//[^\n]*', '', classpath, flags=re.S)
    for pattern in (r"tasks\.register\(['\"]checkDebugCompileClasspath['\"]\)",
                    r'\bdoLast\s*\{', r'\bdebugCompileClasspath\.incoming\.resolutionResult\.allComponents\b',
                    r'\bProjectComponentIdentifier\b', r"\.projectPath\s*==\s*['\"]:backend['\"]", r'\bthrow\s+new\s+GradleException\b'):
        if not re.search(pattern, classpath):
            problems.append(f'{name}: missing actual resolved UI classpath guard'); break
    return problems


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
    commands = list(run_commands(text))

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
    if workflow_path.name in ('build.yml', 'release.yml'):
        problems.extend(gate_problems(workflow_path.name, text,
            (ROOT/'tools/verify_common.py').read_text(encoding='utf-8'),
            (ROOT/'tools/verify_backend.py').read_text(encoding='utf-8')))
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
