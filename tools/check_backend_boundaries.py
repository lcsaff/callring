"""Enforce the JVM business/UI boundary and the shared public contract."""
from pathlib import Path
import re
import sys

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
    return re.sub(r',(?=\))', '', re.sub(r'\s+', '', without_comments(source)))


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
        if c in '([{<':
            stack.append(c)
        elif c in ')]}>':
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


def constructor(text, name):
    match = re.search(r'\bdata\s+class\s+' + re.escape(name) + r'\s*\(', text)
    if not match:
        return None
    opening = match.end() - 1
    parts = arguments(text, opening)
    return (opening, *parts) if parts else None


def compatible_v1(actual, contract):
    actual, contract = without_comments(actual), without_comments(contract)
    approved = {'ApiError': ['valaiFailure:ConversationFailure?=null'],
                'SaveCallerProfile': ['valbaseline:CallerDraft?=null', 'valdirtyFields:Set<String>?=null']}
    for name, allowed in approved.items():
        found, original = constructor(actual, name), constructor(contract, name)
        if found is None or original is None:
            return False
        opening, spans, closing = found
        old_opening, old_spans, old_closing = original
        old = [declarations(contract[a:b]) for a, b in old_spans]
        fields = [declarations(actual[a:b]) for a, b in spans]
        suffix = fields[len(old):]
        if fields[:len(old)] != old or len(suffix) != len(set(suffix)):
            return False
        if suffix != [value for value in allowed if value in suffix]:
            return False
        actual = actual[:opening+1] + contract[old_opening+1:old_closing] + actual[closing:]
    return declarations(actual) == declarations(contract)


def has_presentation_literal(path, source):
    text = without_comments(source)
    allowed = []
    if path.name == 'CapabilityCatalog.kt':
        match = re.search(r'private\s+val\s+purposes\s*=\s*listOf\s*\(', text)
        parts = arguments(text, match.end()-1) if match else None
        if parts and all(STRING.fullmatch(text[a:b].strip()) for a, b in parts[0]):
            allowed.extend(parts[0])
    if path.name == 'ToolRegistry.kt':
        for match in re.finditer(r'\bToolDefinition\s*\(', text):
            parts = arguments(text, match.end()-1)
            if not parts:
                continue
            spans = parts[0]
            if len(spans) != 5 or not re.fullmatch(r'\d+', text[slice(*spans[0])].strip()):
                continue
            start, end = spans[4]
            if STRING.fullmatch(text[start:end].strip()):
                allowed.append((start, end))
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
    contract = ROOT / "docs/contracts/CallringUiApi.kt"
    actual = ROOT / "backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt"
    if not actual.exists() or not compatible_v1(actual.read_text(encoding="utf-8"), contract.read_text(encoding="utf-8")):
        errors.append("Production API differs from shared v1 contract")
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
    print(f"PASS: {len(sources)} JVM sources; no Android/UI/presentation dependencies; v1 preserved with approved optional additions")
