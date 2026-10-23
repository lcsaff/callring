"""Compile/module directions and pure domain boundaries across all source sets."""
from pathlib import Path
import re
import sys
from check_backend_boundaries import without_comments, has_presentation_literal
ROOT = Path(__file__).resolve().parents[1]

def numeric_operation_dispatch(path, source):
    """Catch explicit metadata selectors and local aliases; not interprocedural analysis."""
    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', without_comments(source))
    def typed_names(type_name):
        pairs = re.findall(r'\b(\w+)\s*:\s*' + type_name + r'\b|\b' + type_name + r'\s+(\w+)\b', code)
        return {name for pair in pairs for name in pair if name}

    assignments = re.findall(r'\b(?:val|var|int|long|Integer|Long|String|OperationId|OperationDefinition)\s+(\w+)(?:\s*:\s*\w+)?\s*=\s*([^;\n]+)', code)
    definitions = typed_names('OperationDefinition')
    definitions.update(re.findall(r'\b(?:val|var)\s+(\w+)\s*=\s*OperationDefinition\s*\(', code))
    while True:
        added = {name for name, value in assignments if value.strip() in definitions} - definitions
        if not added: break
        definitions.update(added)
    definition_receiver = '(?:' + '|'.join(re.escape(n) for n in definitions) + ')' if definitions else r'(?!)'
    ids = typed_names('OperationId')
    id_sources = [r'\bOperationId\s*\.\s*\w+',
                  r'\b' + definition_receiver + r'\s*\.\s*(?:id\b|getId\s*\(\s*\))']
    while True:
        id_value = '(?:' + '|'.join(id_sources + [r'\b' + re.escape(n) + r'\b' for n in ids]) + ')'
        added = {name for name, value in assignments if re.fullmatch(r'\s*' + id_value + r'\s*', value)} - ids
        if not added: break
        ids.update(added)
    sources = [r'\b(?:legacyIndex|displayOrder|operationCode)\b',
               r'\b' + definition_receiver + r'\s*\.\s*(?:getLegacyIndex|getDisplayOrder|getOperationCode)\s*\(\s*\)',
               id_value + r'\s*\.\s*ordinal\b']
    taint = '(?:' + '|'.join(sources) + ')'
    aliases = set()
    while True:
        added = {name for name, value in assignments if re.search(taint, value)} - aliases
        if not added:
            break
        aliases.update(added)
        taint = '(?:' + '|'.join(sources + [r'\b' + re.escape(n) + r'\b' for n in aliases]) + ')'
    if re.search(r'\b(?:when|if|switch)\s*\([^;{}\n]*' + taint, code):
        return True
    if re.search(taint + r'[^;{}\n]*(?:\.\s*(?:toInt|toLong|substring|removePrefix|drop|take)|(?:parseInt|parseLong))\s*\(', code):
        return True
    if re.search(r'\b(?:parseInt|parseLong)\s*\([^;{}\n]*' + taint, code):
        return True
    return False


def concrete_resource_cast(source):
    target = 'com.greatwall.callring.data.database.SqliteCoreStorageResource'
    aliases = {'SqliteCoreStorageResource', target}
    for name in re.findall(r'\bimport\s+' + re.escape(target) + r'\s+as\s+(\w+)', source):
        aliases.add(name)
    declarations = re.findall(r'\btypealias\s+(\w+)\s*=\s*([\w.]+)', source)
    while True:
        added = {name for name, value in declarations if value in aliases} - aliases
        if not added:
            break
        aliases.update(added)
    names = '(?:' + '|'.join(re.escape(n) for n in aliases) + ')'
    return bool(re.search(r'\bas\s*\??\s*' + names + r'\b|\basSqliteCoreStorageResource\s*\(|\(\s*' + names + r'\s*\)', source))


def removed_protocol_errors(path, module, package, source):
    errors = []
    if re.search(r'\b(?:BackendContext|coreScopeValues|LazyBackendStore)\b|\b\w*[Rr]esource\w*\s*\.\s*legacy\b', source):
        errors.append('removed full-state context or resource projection')
    production = path.parts[path.parts.index('src') + 1] == 'main'
    if production and re.search(r'\b(?:CapabilityFallbacks|aiUnavailable|UnavailableCoreUnitOfWork|UnavailableQuietTransactions|Unavailable\w+Api)\b', source):
        errors.append('removed unavailable capability assembly')
    # Root assemblies may wire each other; consumers must receive narrow capabilities.
    root_wiring = module == 'app' and package == 'com.greatwall.callring.bootstrap' and path.name in ('BackendGraph.kt', 'CoreAssembly.kt', 'RecordingAssembly.kt', 'ConversationAssembly.kt', 'ScopedQueryAssembly.kt', 'CompleteSnapshotAssembly.kt')
    if not root_wiring and re.search(r':\s*(?:\(\s*\)\s*->\s*)?(?:CoreAssembly|RecordingAssembly|ConversationAssembly)\b', source):
        errors.append('service consumes complete assembly context')
    for declaration in re.finditer(r'\b(?:class|interface|object)\s+(\w+)([^{}]*)(?:\{([^{}]*)\})?', source):
        if root_wiring and declaration.group(1) in ('BackendGraph', 'CoreAssembly', 'RecordingAssembly', 'ConversationAssembly'):
            continue
        body = declaration.group(2) + (declaration.group(3) or '')
        if len(set(re.findall(r'\b(?:CoreAssembly|RecordingAssembly|ConversationAssembly)\b', body))) >= 2:
            errors.append('replacement universal assembly context')
    resources = set(re.findall(r'\b(\w+)\s*:\s*(?:CoreStorageResource|StorageResources)\b', source))
    if any(re.search(r'\b' + re.escape(name) + r'\s*\.\s*legacy\b', source) for name in resources):
        errors.append('removed typed resource legacy projection')
    if production and re.search(r'\bfun\s+commandApi\s*\([^)]*\)\s*:[^=\n{]+(?:=\s*this\b|\{\s*return\s+this\b)', source):
        errors.append('removed commandApi identity fallback')
    if re.search(r'\bfun\s+load\s*\([^)]*\)\s*:\s*FullSnapshotFacts\b', source) and re.search(r'\bfun\s+save\s*\([^)]*:\s*FullSnapshotFacts\b', source):
        errors.append('removed full-state load/save session')
    if re.search(r'\bclass\s+BusinessRevisionCoordinator\b', source) and re.search(r'\bMutableSharedFlow\b', source) and re.search(r'\bMutableStateFlow\b', source):
        errors.append('removed coordinator dual publisher')
    if re.search(r'\bclass\s+SnapshotViewModel\b', source) and re.search(r'\bRequestLoader\s*[<(]', source) and re.search(r'\bRefreshCoordinator\s*[<(]', source):
        errors.append('removed dual query loader')
    for match in re.finditer(r'\b(?:class|interface)\s+(\w+)([^{}]*)(?:\{([^{}]*)\})?', source):
        if match.group(1) in ('CallringBackend', 'CallringUiApi'):
            continue
        body = match.group(2) + (match.group(3) or '')
        core = bool(re.search(r'\b(?:CallersApi|SettingsApi|CoreAssembly|CoreUnitOfWork)\b', body))
        recording = bool(re.search(r'\b(?:RecordingApi|RecordingAssembly|RecordingService)\b', body))
        conversation = bool(re.search(r'\b(?:ConversationApi|ConversationAssembly|ConversationService)\b', body))
        if core and recording and conversation and not root_wiring:
            errors.append('replacement full-capability context')
    if module == 'contracts' and re.search(r'\binterface\s+CallringUiApi\b', source):
        # Required capabilities have no property getter/default implementation.
        if re.search(r'\bval\s+\w+\s*:[^;\n{}]+(?:\bget\s*\(|=)', source):
            errors.append('CallringUiApi capabilities must be abstract')
        if re.search(r'\bfun\s+commandApi\s*\([^)]*\)\s*:\s*[\w.]+\s*(?:=|\{)', source):
            errors.append('CallringUiApi commandApi must be abstract')
    return errors

SHELL_WORKFLOW = {
    'RuntimeReceiver.kt': r'\bworker\s*\.\s*execute\s*[{(]',
    'CalendarSyncJobService.kt': r'\bCalendarRefreshPolicy\b|\bfor\s*\(\s*year\b',
    'SyncJobService.kt': r'\bvar\s+retry\b|\bInterruptedIOException\b|\bruns\s*\.\s*(?:start|complete|stop)\s*\(',
    'RecordingJobService.kt': r'\bAtomicBoolean\b|\bworker\s*\.\s*execute\s*[{(]',
    'CallCaptureService.kt': r'\bsynchronized\s*\(|\bworker\s*\.\s*execute\s*[{(]|\bprivate\s+var\s+(?:call|recorder|destroyed)\b',
    'RingPlaybackService.java': r'\b(?:PreparationGate|SessionGate)\b|\b(?:reportReady|finish|releasePreparationLease)\s*\(',
}

def component_boundary_errors(path, package, references):
    errors = []
    if package.startswith('com.greatwall.callring.entrypoint.'):
        # Exact complete-facade/framework types; typed media/results and payloads are allowed.
        if re.search(r'\b(?:BackendGraph|BackendProvider|CallringBackend|CallringUiApi|RecordingAssembly|CoreAssembly|Context|Service|JobService|Application|RuntimeReceiver|RingPlaybackService|CallCaptureService|SyncJobService|RecordingJobService|CalendarSyncJobService)\b|\b[A-Z]\w*Api\b', references):
            errors.append('entrypoint controller depends on complete graph/API or Android component')
    rule = SHELL_WORKFLOW.get(path.name)
    if rule and re.search(rule, references):
        errors.append('registered component contains extracted workflow; keep Android resource wiring only')
    return errors

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
            package_match = re.search(r'\bpackage\s+([\w.]+)', references)
            package = package_match.group(1) if package_match else ''
            if path.relative_to(directory/'src').parts[0] == 'main':
                ai_consumer = package.startswith(('com.greatwall.callring.application.conversation.execution',
                                                  'com.greatwall.callring.application.conversation.query'))
                if (module == 'ui' or ai_consumer) and re.search(r'\bPrefixLengthNumberMatcher\b', references):
                    errors.append(f'{path.relative_to(root)}: number matcher consumer references default implementation')
                matching_consumer = module == 'ui' or package.startswith(tuple('com.greatwall.callring.' + part for part in
                    ('domain.model', 'domain.calling', 'application.calling', 'application.callers',
                     'application.conversation', 'platform.calling', 'data')))
                if matching_consumer and '3[0-9]{4}' in source:
                    errors.append(f'{path.relative_to(root)}: removed fixed number matching rule')
            if module == 'app' and path.relative_to(directory/'src').parts[0] == 'main':
                errors.extend(f'{path.relative_to(root)}: {error}' for error in component_boundary_errors(path, package, references))
            if module == 'ui':
                old_entry = r'\bcom\.greatwall\.callring\.ui\.designsystem\.(?:DefaultAppearance|ThemeRegistry|usesDarkColors|components\.(?:CallerCard|rememberFormBack))\b'
                if re.search(old_entry, references):
                    errors.append(f'{path.relative_to(root)}: removed designsystem business entry')
                if path.relative_to(directory/'src').parts[0] == 'main' and (package == 'com.greatwall.callring.ui.designsystem' or package.startswith('com.greatwall.callring.ui.designsystem.')):
                    if re.search(r'\bcom\.greatwall\.callring\.(?:contracts\b|ui\.(?:state|feature|app)\b)', references):
                        errors.append(f'{path.relative_to(root)}: designsystem business dependency')
                    if re.search(r'\b(?:class|object|val|fun|typealias)\s+(?:(?:[\w.]+)\s*\.\s*)?(?:DefaultAppearance|ThemeRegistry|usesDarkColors|CallerCard|rememberFormBack)\b', references):
                        errors.append(f'{path.relative_to(root)}: removed designsystem business declaration')
                    for signature in re.findall(r'\bfun\s+CallringTheme\s*\((.*?)\)\s*', references, re.S):
                        if not re.match(r'\s*spec\s*:\s*ThemeSpec\s*,', signature) or re.search(r'\b(?:appearance|systemDark|registry)\s*:', signature):
                            errors.append(f'{path.relative_to(root)}: removed preference-aware theme rendering overload')
            if module == 'app' and 'main' in path.relative_to(directory/'src').parts[:1] and package == 'com.greatwall.callring.bootstrap' and re.search(
                    r'\b(?:SourceRow|SnapshotPart|safeValue|aiObject|sourceCount|snapshotSourceCount)\s*\(', references):
                errors.append(f'{path.relative_to(root)}: bootstrap query projection; inject backend projectors')
            if module == 'backend' and package == 'com.greatwall.callring.application.conversation.query' and re.search(
                    r':\s*(?:com\.greatwall\.callring\.[\w.]+\.)?(?:BackendGraph|CallringBackend|CallringUiApi|RecordingAssembly|CoreAssembly)\b', references):
                errors.append(f'{path.relative_to(root)}: query projector depends on a complete facade')
            removed_mixed = {
                ('com.greatwall.callring.application', 'RecordingPorts.kt'),
                ('com.greatwall.callring.application', 'RecordingAssociations.kt'),
                ('com.greatwall.callring.application.ports', 'BackendPorts.kt'),
                ('com.greatwall.callring.platform', 'PlatformSafety.kt'),
            }
            if (package, path.name) in removed_mixed or re.search(
                    r'\bfun\s+(?:scopedBindingRows|playbackSnapshot|activitySnapshot)\s*\(', references):
                errors.append(f'{path.relative_to(root)}: removed mixed responsibility or JSON observation entry')
            if re.search(r'\b(?:BackendStore|BackendState|StorageSession|SqliteBackendStore|stateLoadCount|coreStateLoads|fullStateLoads|stateLoads|fullDiff|TableSnapshot|CallStore|ProfileStore|ScheduleStore|SettingsStore)\b|\b(?:resource|coreResource|store|it)\s*\.\s*legacy\b', references):
                errors.append(f'{path.relative_to(root)}: removed full-state storage protocol')
            errors.extend(f'{path.relative_to(root)}: {error}' for error in removed_protocol_errors(path, module, package, references))
            if numeric_operation_dispatch(path, source):
                errors.append(f'{path.relative_to(root)}: numeric operation dispatch; use OperationId or explicit catalog policy')
            if concrete_resource_cast(references):
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
