"""Compile/module directions and pure domain boundaries across all source sets."""
from pathlib import Path
import re
import sys
from check_backend_boundaries import without_comments, has_presentation_literal
ROOT = Path(__file__).resolve().parents[1]

OPERATION_FILES = {"ToolExecutor.kt", "ToolBatchEngine.kt", "OperationScopePlanner.kt", "ConfirmationTargets.kt", "ReceiptVerifier.kt", "ScopedToolReader.kt", "OperationCatalog.kt", "TaskPlanner.kt", "ConversationService.kt"}

def numeric_operation_dispatch(path, source):
    if path.name not in OPERATION_FILES:
        return False
    # Strings and comments are data, never executable selectors.
    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', without_comments(source))
    identity = r'(?:legacyIndex|index|ordinal|displayOrder|operationCode)'
    if re.search(r'\bwhen\s*\([^)]*\b' + identity + r'\b', code):
        return True
    if re.search(r'\b' + identity + r'\b\s*(?:==|!=|<=|>=|<|>|!?in\b)', code):
        return True
    if re.search(r'\boperationCode\b[^;\n]*(?:toInt|toLong|substring|removePrefix|drop|take)\s*\(', code):
        return True
    # Aliasing a protocol field before branching does not change its meaning.
    aliases = re.findall(r'\bval\s+(\w+)\s*=\s*[^;\n]*\b' + identity + r'\b[^;\n]*', code)
    for alias in aliases:
        if re.search(r'\b(?:when|if)\s*\([^)]*\b' + re.escape(alias) + r'\b', code):
            return True
    return False

def check(root=ROOT):
    errors=[]
    allowed={'contracts':set(), 'backend':{'contracts'}, 'ui':{'contracts'}, 'app':{'contracts','backend','ui'}}
    required={'contracts':set(), 'backend':{'contracts'}, 'ui':{'contracts'}, 'app':{'contracts','backend','ui'}}
    for module in allowed:
        directory=root/module
        builds=[p for p in (directory/'build.gradle',directory/'build.gradle.kts') if p.exists()]
        if not builds:
            errors.append(f'{module}: missing build file');continue
        text='\n'.join(without_comments(p.read_text(encoding='utf-8')) for p in builds)
        deps=set(re.findall(r'\bproject\s*\(\s*(?:path\s*[:=]\s*)?[\'\"]:([^\'\"]+)[\'\"]',text))
        if deps-allowed[module]: errors.append(f'{module}: forbidden project dependencies {sorted(deps-allowed[module])}')
        if required[module]-deps: errors.append(f'{module}: missing explicit project dependencies {sorted(required[module]-deps)}')
        if module in ('contracts','backend') and re.search(r'com\.android|androidx[.:]',text):errors.append(f'{module}: Android build dependency')
        for path in (directory/'src').rglob('*'):
            if path.suffix not in ('.kt','.java'):continue
            source=without_comments(path.read_text(encoding='utf-8'))
            references=re.sub(r'"(?:\\.|[^"\\])*"', '""', source)
            if re.search(r'\b(?:BackendStore|BackendState|StorageSession|SqliteBackendStore|stateLoadCount|coreStateLoads|fullStateLoads|stateLoads|fullDiff|TableSnapshot|CallStore|ProfileStore|ScheduleStore|SettingsStore)\b|\b(?:resource|coreResource|store|it)\s*\.\s*legacy\b', references):
                errors.append(f'{path.relative_to(root)}: removed full-state storage protocol')
            if numeric_operation_dispatch(path, source):
                errors.append(f'{path.relative_to(root)}: numeric operation dispatch; use OperationId or explicit catalog policy')
            if re.search(r'\bas\s*\??\s*(?:com\.greatwall\.callring\.data\.database\.)?SqliteCoreStorageResource\b|\basSqliteCoreStorageResource\s*\(|\(\s*(?:com\.greatwall\.callring\.data\.database\.)?SqliteCoreStorageResource\s*\)', references):
                errors.append(f'{path.relative_to(root)}: concrete backend store cast; inject explicit resource capabilities')
            if re.search(r'\b(?:data\s+)?class\s+OperationContract\b|\bfun\s+(?:ToolRegistry\s*\.\s*)?operationContracts\s*\(', references):
                errors.append(f'{path.relative_to(root)}: removed operation contract projection; consume OperationDefinition directly')
            if re.search(r'\bDataChange\b|\brefreshFromChanges\b|\bfun\s+observeChanges\b', references):
                errors.append(f'{path.relative_to(root)}: removed legacy change protocol or dual subscription mode')
            if re.search(r'\binterface\s+ChangeFeed\b', references):
                if re.search(r'\bfun\s+observe\s*\(', references) or re.search(r'\bfun\s+snapshots\s*\([^)]*\)\s*:\s*(?:[\w.]+\.)?StateFlow\s*<\s*InvalidationSnapshot\s*>\s*(?:\?|=|\{)', references):
                    errors.append(f'{path.relative_to(root)}: cumulative snapshots must be the required sole feed protocol')
            if path.name == 'BusinessRevisionCoordinator.kt' and re.search(r'\bfun\s+(?:changed|commit|publishScopes|runtimeChanged|observe)\s*\(', references):
                errors.append(f'{path.relative_to(root)}: removed coordinator publisher')
            if module in ('contracts','backend') and re.search(r'\b(?:android|androidx)\.|\bcom\.greatwall\.callring\.ui\.',source):errors.append(f'{path.relative_to(root)}: Android/UI dependency')
            if module=='backend' and re.search(r'\bpackage\s+com\.greatwall\.callring\.domain\b', references):
                if re.search(r'\bcom\.greatwall\.callring\.(?:contracts|application)\b|\b(?:kotlinx\.coroutines|java\.io|java\.nio\.file|java\.sql|javax\.sql|java\.net\.(?!URI\b))', references): errors.append(f'{path.relative_to(root)}: domain implementation/transport/IO dependency')
            if module=='contracts':
                if re.search(r'\bcom\.greatwall\.callring\.(?!contracts\b)',source):errors.append(f'{path.relative_to(root)}: contracts implementation dependency')
                if re.search(r'\b(?:java\.sql|java\.net|javax\.sql)\.|\b(?:AiConnection|RecordingSettings|ThinkingPolicyResolver|Unavailable\w+Api)\b|\bfun\s+validate\s*\(',source):errors.append(f'{path.relative_to(root)}: implementation/policy/secret in contracts')
                if has_presentation_literal(path,source):errors.append(f'{path.relative_to(root)}: localized presentation text in contracts')
            if module=='ui' and re.search(r'\bcom\.greatwall\.callring\.(?!(?:contracts|ui)\b)',references):errors.append(f'{path.relative_to(root)}: UI implementation dependency')
    return errors
if __name__=='__main__':
    errors=check()
    if errors:print('\n'.join(errors));sys.exit(1)
    print('PASS: contracts / backend / UI / app dependency directions (all source sets)')
