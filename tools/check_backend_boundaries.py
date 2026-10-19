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


def compatible_v1(actual, contract):
    # Approved nullable/default-only additions; all original declarations still compare exactly.
    normalized = declarations(actual)
    for addition in ("valaiFailure:ConversationFailure?=null,",
                     "valbaseline:CallerDraft?=null,", "valdirtyFields:Set<String>?=null,"):
        normalized = normalized.replace(addition.rstrip(','), '').replace(',,', ',')
    normalized = re.sub(r',(?=\))', '', normalized)
    return normalized == declarations(contract)


def has_presentation_literal(path, source):
    text = without_comments(source)
    metadata_start = text.find("private val purposes=listOf(") if path.name == "CapabilityCatalog.kt" else -1
    metadata_end = text.find("fun toolPurpose", metadata_start) if metadata_start >= 0 else -1
    for literal in re.finditer(r'"(?:\\.|[^"\\])*"', text):
        if not re.search(r'[\u3400-\u9fff]', literal.group()):
            continue
        if metadata_start <= literal.start() < metadata_end and metadata_start >= 0:
            continue
        line = text[text.rfind('\n', 0, literal.start()) + 1:text.find('\n', literal.end()) if '\n' in text[literal.end():] else len(text)]
        if path.name == "ToolRegistry.kt" and re.match(r'\s*ToolDefinition\(\d+,', line):
            continue  # Model-facing schema metadata, not a UI message.
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
