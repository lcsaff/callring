"""Enforce the JVM business/UI boundary and the shared public contract."""
from pathlib import Path
import re
import sys
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
BASE = Path("app/src/main/java/com/greatwall/callring")
LEGACY = (
    "CallerRepository.java", "IncomingCallManager.java", "ManagementEvents.java",
    "MainActivity.java", "ManagementActivity.java", "ManagementSettingsActivity.java",
    "CallerEditActivity.java", "CallerListActivity.java", "CallerReportActivity.java",
    "CallerWidgetConfigActivity.java", "CallerOverlayService.java",
    "CallerWidgetProvider.java", "Ui.java",
    "AppSettings.java", "CallLogSynchronizer.java", "EventLog.java", "CsvReport.java",
    "CallerScore.java", "WorkSchedulePolicy.java", "QuietHoursPolicy.java",
    "PositionRelationPolicy.java", "NumberNormalizer.java", "RulePolicy.java", "WorkConfigCodec.java",
)


def without_comments(source):
    # Preserve quoted URIs and comment markers inside strings when checking Kotlin/Java.
    return re.sub(r'(""".*?"""|"(?:\\.|[^"\\])*")|/\*.*?\*/|//[^\n]*',
                  lambda match: match.group(1) or '', source, flags=re.S)


def declarations(source):
    # Whitespace inside wire strings is significant; normalize only outside literals.
    return re.sub(r'(""".*?"""|"(?:\\.|[^"\\])*")|\s+',
                  lambda match: match.group(1) or '', without_comments(source), flags=re.S)


STRING = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"', re.S)


def arguments(text, opening):
    """Return exact top-level argument spans, excluding strings and nested expressions."""
    masked = STRING.sub(lambda m: ' ' * len(m.group()), text)
    stack = ['(']
    start = opening + 1
    spans = []
    pairs = {')': '(', ']': '[', '}': '{', '>': '<'}
    for i in range(start, len(text)):
        c = masked[i]
        if c in '([{':
            stack.append(c)
        elif c in ')]}':
            if not stack or stack[-1] != pairs[c]:
                return None
            stack.pop()
            if not stack:
                if text[start:i].strip():
                    spans.append((start, i))
                return spans, i
        elif c == ',' and len(stack) == 1:
            spans.append((start, i))
            start = i + 1
    return None


def contract_inventory(files):
    return json.dumps({name: {"sha256": hashlib.sha256(declarations(source).encode("utf-8")).hexdigest(),
                             "declarations": [name for row in re.findall(r"^(?:(?:data|sealed|enum)\s+)?(?:class|interface|object)\s+(\w+)|^fun\s+(?:[\w<>?]+\.)?(\w+)\s*\(|^const val (\w+)", source, re.M) for name in row if name]}
                       for name, source in sorted(files.items())}, ensure_ascii=False, indent=2)

def compatible_inventory(files, inventory):
    return contract_inventory(files) == inventory.strip()


def has_presentation_literal(path, source):
    text = without_comments(source)
    allowed = []
    # Preset names are shared catalog data, not UI messages. Only the literal
    # name argument of a static id/name/RGB row in this contract is permitted.
    if path.name == 'ThemePresets.kt' and re.search(r'^package com\.greatwall\.callring\.contracts\.settings\s*$', text, re.M):
        for match in re.finditer(r'\bThemePreset\s*\(', text):
            parts = arguments(text, match.end()-1)
            if not parts or len(parts[0]) != 3:
                continue
            spans = parts[0]
            values = [text[a:b] for a, b in spans]
            if not re.fullmatch(r'\s*"[a-z][a-z0-9_]*"\s*', values[0]):
                continue
            if not re.fullmatch(r'\s*(?:0x[0-9a-fA-F]{1,6}|[0-9]+)\s*', values[2]):
                continue
            field = re.fullmatch(r'\s*("(?:\\.|[^"\\])*")\s*', values[1], re.S)
            if field:
                allowed.append((spans[1][0] + field.start(1), spans[1][0] + field.end(1)))
    if path.name == 'OperationCatalog.kt':
        for match in re.finditer(r'\bOperationDefinition\s*\(', text):
            parts = arguments(text, match.end()-1)
            if not parts:
                continue
            spans = parts[0]
            if not any(re.fullmatch(r'\s*id\s*=\s*OperationId\.\w+\s*', text[a:b]) for a, b in spans):
                continue
            for start, end in spans:
                field = re.fullmatch(r'\s*description\s*=\s*("(?:\\.|[^"\\])*")\s*', text[start:end], re.S)
                if field:
                    allowed.append((start + field.start(1), start + field.end(1)))
    for literal in STRING.finditer(text):
        if re.search(r'[\u3400-\u9fff]', literal.group()) and not any(a <= literal.start() and literal.end() <= b for a, b in allowed):
            return True
    return False


def check():
    errors = []
    sources = list((ROOT / "backend/src/main").rglob("*.kt"))
    sources += list((ROOT / "backend/src/main").rglob("*.java"))
    if not sources:
        errors.append("No pure JVM backend sources")
    build = (ROOT / "backend/build.gradle").read_text(encoding="utf-8")
    if re.search(r'com\.android|androidx|project\([\'\"]:(?:app|ui)', build):
        errors.append("JVM backend build depends on Android or a presentation module")
    for path in sources:
        text = without_comments(path.read_text(encoding="utf-8"))
        if "/domain/" in path.as_posix() and re.search(r'\bimport\s+com\.greatwall\.callring\.application\.(?!api\.)', text):
            errors.append(f"{path.relative_to(ROOT)}: domain algorithm depends on application implementation/ports")
        for pattern in (r'\bandroid(?:x)?\.', r'\bcom\.greatwall\.callring\.ui\.',
                        r'\b(?:Toast|WindowManager|RemoteViews|CallerRepository|ManagementEvents)\b'):
            if re.search(pattern, text):
                errors.append(f"{path.relative_to(ROOT)}: forbidden business dependency {pattern}")
        if has_presentation_literal(path, text):
            errors.append(f"{path.relative_to(ROOT)}: localized presentation text in JVM backend")
    # The v1 snapshot is historical. Current source is reviewed against the T03 inventory.
    contract_root = ROOT / "contracts/src/main/kotlin/com/greatwall/callring/contracts"
    files = {p.relative_to(contract_root).as_posix(): p.read_text(encoding="utf-8") for p in contract_root.rglob("*.kt")}
    current = ROOT / "docs/contracts/current-api.md"
    document = current.read_text(encoding="utf-8") if current.exists() else ""
    inventory = re.search(r"<!-- contract-inventory -->\s*```json\n(.*?)\n```", document, re.S)
    if not inventory or not compatible_inventory(files, inventory.group(1)):
        errors.append("Current contracts differ from reviewed docs/contracts/current-api.md inventory")
    for approved in ("RecordingApi.getSettings/saveSettings/testConnection", "RecordingApi.setSystemFolder", "AiConnectionView.copyToAnalysisProfile", "CallringUiApi abstract capabilities", "ConversationBudget.validate"):
        if approved not in document: errors.append("Missing approved internal signature change: " + approved)
    golden = ROOT / "docs/contracts/ai-operation-compatibility.json"
    if not golden.exists() or hashlib.sha256(golden.read_text(encoding="utf-8").encode("utf-8")).hexdigest() != "2e4924263530ec1d4cc06c7d371f1acc4ed2df873fd113073ee33955e1329e29":
        errors.append("AI operation compatibility golden changed; preserve the reviewed external schema")
    compatibility_test = ROOT / "backend/src/test/kotlin/com/greatwall/callring/ToolCompatibilityBaselineTest.kt"
    if not compatibility_test.exists() or 'ai-operation-compatibility.json' not in compatibility_test.read_text(encoding="utf-8"):
        errors.append("Missing external AI compatibility test against baseline golden")
    from check_architecture import check as check_architecture
    errors.extend(check_architecture(ROOT))
    for directory in (ROOT / "app/src/main/kotlin/com/greatwall/callring/data",
                      ROOT / "app/src/main/kotlin/com/greatwall/callring/platform",
                      ROOT / "app/src/main/java/com/greatwall/callring/data"):
        for path in directory.rglob("*"):
            if path.suffix not in (".kt", ".java"):
                continue
            text = without_comments(path.read_text(encoding="utf-8"))
            if re.search(r'\b(?:CallerRepository|ManagementEvents|CallerWidgetProvider|CallerOverlayService|MainActivity)\b|\bcom\.greatwall\.callring\.ui\.', text):
                errors.append(f"{path.relative_to(ROOT)}: concrete UI/legacy dependency")
    for name in ("RuleScreeningService", "RingPlaybackService", "SyncJobService", "RuntimeReceiver"):
        for directory in (ROOT / "app/src/main/java/com/greatwall/callring", ROOT / "app/src/main/kotlin/com/greatwall/callring"):
            for suffix in (".java", ".kt"):
                path = directory / (name + suffix)
                if path.exists() and re.search(r'\b(?:CallerRepository|ManagementEvents|AppSettings|EventLog|CallerWidgetProvider|CallerOverlayService|MainActivity)\b|\bcom\.greatwall\.callring\.ui\.',
                                              without_comments(path.read_text(encoding="utf-8"))):
                    errors.append(f"{path.relative_to(ROOT)}: framework adapter depends on concrete UI/legacy service")
    errors.extend(f"Legacy production source remains: {name}" for name in LEGACY if (ROOT / BASE / name).exists())
    for directory in (ROOT / "app/src/main/kotlin/com/greatwall/callring/ui", ROOT / "ui/src/main"):
        for path in directory.rglob("*"):
            if path.suffix not in (".kt", ".java"):
                continue
            if re.search(r'\b(?:CallerRepository|ManagementEvents|SQLiteDatabase|SQLiteOpenHelper|CallringBackend|BackendStore)\b|com\.greatwall\.callring\.(?:data|domain)\.|com\.greatwall\.callring\.platform\.(?!UiNotificationEntryPoints\b)|com\.greatwall\.callring\.application\.(?!api\.)', without_comments(path.read_text(encoding="utf-8"))):
                errors.append(f"{path.relative_to(ROOT)}: UI depends on a business/storage implementation")
    return sources, errors


if __name__ == "__main__":
    sources, errors = check()
    if errors:
        print("\n".join(errors))
        sys.exit(1)
    print(f"PASS: {len(sources)} JVM sources; no Android/UI/presentation dependencies; current contract inventory and AI compatibility golden preserved")
