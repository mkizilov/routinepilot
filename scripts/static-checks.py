#!/usr/bin/env python3
"""Repository checks that need no JDK, Gradle, Android SDK, Docker, or network.

Run from the repository root: python3 scripts/static-checks.py
Standard library only (Python 3.9 or later). Exits with status 1 when a check fails.

These are text and structure checks. They do not compile code and they do not run tests. They catch the mistakes
that are easy to make without a compiler: unbalanced brackets, missing Gradle catalog aliases, DAO methods that do not
exist, Hilt dependencies with no binding, unused imports, missing string resources, and broken documentation links.
"""
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {'.git', '.agent-local', '.opencode', '.cortexkit', '.gradle', '.kotlin', '.idea', '.venv', 'build'}
DOC_ROOTS = ('app/', 'backend/', 'docs/', 'config/', '.github/', 'scripts/')
GENERATED_OR_LOCAL = ('app/build/', 'app/schemas/', 'backend/.env')
OPERATOR_IMPORTS = {'getValue', 'setValue', 'provides'}
FRAMEWORK_TYPES = {'Context', 'Application', 'Clock', 'CoroutineScope', 'DataStore', 'RoutinePilotDatabase',
                   'SavedStateHandle', 'WorkerParameters', 'HiltWorkerFactory'}
failures = []


def report(name, problems, detail=''):
    if problems:
        failures.extend(f'{name}: {p}' for p in problems)
        print(f'FAIL  {name} ({len(problems)} problem(s))')
    else:
        print(f'OK    {name}' + (f' {detail}' if detail else ''))


def files(*suffixes, under=None):
    base = ROOT / under if under else ROOT
    found = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        found += [Path(dirpath) / n for n in filenames if n.endswith(suffixes)]
    return sorted(found)


def rel(path):
    return path.relative_to(ROOT).as_posix()


def strip_kotlin(src):
    """Blanks out strings and comments so brackets and keywords inside them are ignored."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        elif src[i] == '"' or src[i] == "'":
            quote, j = src[i], i + 1
            while j < n and src[j] != quote and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(' ' * (j + 1 - i))
            i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        else:
            out.append(src[i])
            i += 1
    return ''.join(out)


def check_brackets():
    problems, targets = [], files('.kt', '.kts')
    pairs = {')': '(', ']': '[', '}': '{'}
    for path in targets:
        stack = []
        for ln, line in enumerate(strip_kotlin(path.read_text()).split('\n'), 1):
            for ch in line:
                if ch in '([{':
                    stack.append((ch, ln))
                elif ch in pairs:
                    if not stack or stack[-1][0] != pairs[ch]:
                        problems.append(f'{rel(path)}:{ln} unexpected {ch}')
                        stack = None
                        break
                    stack.pop()
            if stack is None:
                break
        else:
            if stack:
                problems.append(f'{rel(path)} has an unclosed {stack[-1][0]} from line {stack[-1][1]}')
    report('Kotlin and Gradle brackets balance', problems, f'({len(targets)} files)')


def check_xml():
    problems, xmls = [], files('.xml', under='app/src') + files('.xml', under='backend/src')
    for path in xmls:
        try:
            ET.parse(path)
        except ET.ParseError as error:
            problems.append(f'{rel(path)}: {error}')
    report('XML files are well formed', problems, f'({len(xmls)} files)')


def catalog_entries(text):
    sections = {'versions': set(), 'libraries': set(), 'plugins': set()}
    version_refs, current = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('['):
            current = line.strip('[]')
            continue
        key = re.match(r'([\w.-]+)\s*=', line)
        if key and current in sections:
            sections[current].add(key.group(1))
        version_refs += re.findall(r'version\.ref\s*=\s*"([\w.-]+)"', line)
    return sections, version_refs


def accessor(name):
    return tuple(re.split(r'[-_.]', name))


def check_catalogs():
    problems, checked = [], 0
    catalogs = [('gradle/libs.versions.toml', None), ('backend/gradle/libs.versions.toml', 'backend')]
    for catalog_path, scope in catalogs:
        catalog = ROOT / catalog_path
        if not catalog.exists():
            problems.append(f'{catalog_path} is missing')
            continue
        sections, version_refs = catalog_entries(catalog.read_text())
        problems += [f'{catalog_path}: version.ref "{r}" is not defined' for r in version_refs
                     if r not in sections['versions']]
        tables = {key: {accessor(n) for n in sections[key]} for key in sections}
        scripts = files('.kts', under=scope) if scope else [p for p in files('.kts') if not rel(p).startswith('backend/')]
        for script in scripts:
            for name in re.findall(r'\blibs\.([\w.]+)', script.read_text()):
                checked += 1
                parts = name.split('.')
                if parts[-1] == 'get':
                    parts = parts[:-1]
                table = 'libraries'
                if parts[0] in ('plugins', 'versions'):
                    table, parts = parts[0], parts[1:]
                if accessor('.'.join(parts)) not in tables[table]:
                    problems.append(f'{rel(script)} uses libs.{name}, which {catalog_path} does not define')
    report('Version catalog aliases match the build scripts', problems, f'({checked} references)')


def dao_declarations():
    declared, accessors = {}, {}
    for path in files('.kt', under='app/src/main'):
        text = path.read_text()
        for match in re.finditer(r'interface (\w+Dao)\b[^{]*\{', text):
            end = text.find('\n}', match.end())
            body = text[match.end(): end if end != -1 else len(text)]
            declared[match.group(1)] = set(re.findall(r'\bfun (\w+)\(', body))
        if path.name == 'RoutinePilotDatabase.kt':
            accessors = dict(re.findall(r'abstract fun (\w+)\(\):\s*(\w+)', text))
    return declared, accessors


def check_dao_calls():
    declared, accessors = dao_declarations()
    problems, checked = [], 0
    for path in files('.kt', under='app/src'):
        text = path.read_text()
        class_level = {}
        for var, accessor_name in re.findall(r'^\s{4}(?:private |internal )?val (\w+)\s*=\s*(?:[\w.]+\.)?(\w+)\(\)',
                                             text, re.M):
            if accessor_name in accessors:
                class_level[var] = accessors[accessor_name]
        for var, dao in re.findall(r'(?:private |internal )?val (\w+): (\w+Dao)\b', text):
            class_level[var] = dao
        chunks = re.split(r'(?m)^(?=\s*(?:(?:private|internal|override|public|suspend|inline)\s+)*fun\s)', text)
        for chunk in chunks:
            local = dict(class_level)
            for var, accessor_name in re.findall(r'\b(?:val|var) (\w+)\s*=\s*(?:[\w.]+\.)?(\w+)\(\)', chunk):
                if accessor_name in accessors:
                    local[var] = accessors[accessor_name]
            for var, dao in re.findall(r'\b(\w+): (\w+Dao)\b', chunk):
                local[var] = dao
            for var, method in re.findall(r'\b(\w+)\.(\w+)\(', chunk):
                if var in local and local[var] in declared:
                    checked += 1
                    if method not in declared[local[var]]:
                        problems.append(f'{rel(path)}: {var}.{method}() is not declared on {local[var]}')
            for accessor_name, method in re.findall(r'\b(\w+)\(\)\.(\w+)\(', chunk):
                if accessor_name in accessors and accessors[accessor_name] in declared:
                    checked += 1
                    if method not in declared[accessors[accessor_name]]:
                        problems.append(f'{rel(path)}: {accessor_name}().{method}() is not declared on '
                                        f'{accessors[accessor_name]}')
    report('Every DAO call names a declared DAO method', sorted(set(problems)), f'({checked} call sites)')


def split_params(params):
    pieces, depth, current = [], 0, ''
    for ch in params:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            pieces.append(current)
            current = ''
        else:
            current += ch
    if current.strip():
        pieces.append(current)
    types = []
    for raw in pieces:
        if '@Assisted' in raw:
            continue
        declaration = re.sub(r'@\w+(\([^)]*\))?\s*', '', raw)
        if ':' not in declaration:
            continue
        type_text = declaration.split(':', 1)[1].split('=')[0].strip()
        types.append(re.sub(r'<.*', '', type_text).rstrip('?').strip().split('.')[-1])
    return types


def check_hilt():
    bindings, placement_problems, injectable = set(), [], {}
    for path in files('.kt', under='app/src/main'):
        code = strip_kotlin(path.read_text())
        declarations = [(m.start(), m.group(1)) for m in
                        re.finditer(r'\b(abstract class|companion object|object|interface|class)\b', code)]
        for match in re.finditer(r'@Binds\s+(?:@\w+\s+)*(?:internal\s+)?abstract fun \w+\(\s*\w+:\s*[\w.]+\s*\):\s*'
                                 r'([\w.]+)', code):
            bindings.add(match.group(1).split('.')[-1])
            owner = [kind for pos, kind in declarations if pos < match.start()]
            if owner and owner[-1] not in ('abstract class', 'interface'):
                placement_problems.append(f'{rel(path)}: @Binds is declared inside {owner[-1]}')
        provided = set()
        for match in re.finditer(r'@Provides\s+(?:@\w+\s+)*(?:internal\s+)?fun \w+\([^)]*\)\s*:\s*([\w.]+)', code):
            provided.add(match.group(1).split('.')[-1])
            owner = [kind for pos, kind in declarations if pos < match.start()]
            if owner and owner[-1] not in ('object', 'companion object'):
                placement_problems.append(f'{rel(path)}: @Provides is declared inside {owner[-1]}')
        bindings |= provided
        for match in re.finditer(r'\b(?:class|object)\s+(\w+)\b((?:(?!\b(?:class|object)\b)[^{])*?)'
                                 r'@(?:Assisted)?Inject\s+constructor\(', code, re.S):
            start, depth, j = match.end(), 1, match.end()
            while depth and j < len(code):
                if code[j] == '(':
                    depth += 1
                elif code[j] == ')':
                    depth -= 1
                j += 1
            injectable[match.group(1)] = split_params(code[start:j - 1])
    unbound = []
    for cls, dependencies in injectable.items():
        for base in dependencies:
            if base not in FRAMEWORK_TYPES and base not in injectable and base not in bindings:
                unbound.append(f'{cls} needs {base}, which has no binding')
    report('Hilt modules place @Binds and @Provides correctly', placement_problems)
    report('Every injected constructor dependency has a binding', unbound, f'({len(injectable)} classes)')


def check_lint():
    kotlin = files('.kt', under='app/src') + files('.kt', under='backend/src')
    unused, long_lines = [], []
    for path in kotlin:
        lines = path.read_text().splitlines()
        body = '\n'.join(line for line in lines if not line.startswith('import '))
        for line in lines:
            match = re.match(r'import ([\w.]+)(?: as (\w+))?$', line)
            if not match:
                continue
            name = match.group(2) or match.group(1).split('.')[-1]
            if name not in OPERATOR_IMPORTS and not re.search(r'\b' + re.escape(name) + r'\b', body):
                unused.append(f'{rel(path)}: {line}')
        long_lines += [f'{rel(path)}:{i}' for i, line in enumerate(lines, 1) if len(line) > 120]
    report('No unused imports', unused, f'({len(kotlin)} files)')
    report('No line is over 120 characters', long_lines)


def check_resources():
    strings_root = ET.parse(ROOT / 'app/src/main/res/values/strings.xml').getroot()
    names = [element.get('name') for element in strings_root.iter('string')]
    problems = [f'duplicate string name {n}' for n in sorted({n for n in names if names.count(n) > 1})]
    referenced = set()
    for path in files('.kt', under='app/src'):
        referenced |= set(re.findall(r'R\.string\.(\w+)', path.read_text()))
    problems += [f'R.string.{r} is not defined' for r in sorted(referenced - set(names))]
    styles = set()
    for path in files('.xml', under='app/src/main/res'):
        styles |= {element.get('name') for element in ET.parse(path).getroot().iter('style')}
    xml_names = {p.stem for p in (ROOT / 'app/src/main/res/xml').glob('*.xml')}
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    for element in manifest.iter():
        for value in element.attrib.values():
            if not value.startswith('@') or '/' not in value:
                continue
            kind, name = value[1:].split('/', 1)
            defined = {'string': names, 'style': styles, 'xml': xml_names}.get(kind)
            if defined is not None and name not in defined:
                problems.append(f'AndroidManifest.xml refers to {value}, which is not defined')
    report('String, style, and XML resources resolve', problems)


def check_doc_paths():
    docs = [ROOT / 'README.md', ROOT / 'AGENTS.md', ROOT / 'ROADMAP.md'] + files('.md', under='docs') + \
        files('.md', under='backend')
    problems = []
    for doc in docs:
        if not doc.exists():
            continue
        text = doc.read_text()
        for token in sorted(set(re.findall(r'`([^`\s]+)`', text))):
            if not token.startswith(DOC_ROOTS) or token.startswith(GENERATED_OR_LOCAL):
                continue
            if '...' in token or any(c in token for c in '*$<>{}'):
                continue
            if not (token.endswith('/') or re.search(r'\.\w+$', token)):
                continue
            if not (ROOT / token.rstrip('/')).exists():
                problems.append(f'{rel(doc)} refers to {token}, which does not exist')
        for link in re.findall(r'\]\(((?!https?:)[\w./-]+\.md)\)', text):
            if not (doc.parent / link).exists():
                problems.append(f'{rel(doc)} links to {link}, which does not exist')
    report('Documentation paths and links exist', problems, f'({len(docs)} documents)')


def check_gradle_properties():
    props = ROOT / 'gradle.properties'
    text = props.read_text() if props.exists() else ''
    report('Gradle is limited to two workers (AGENTS.md)', [] if 'org.gradle.workers.max=2' in text
           else ['gradle.properties does not set org.gradle.workers.max=2'])


def check_workflows():
    ruby = shutil.which('ruby')
    flows = files('.yml', under='.github/workflows')
    if not ruby:
        print('SKIP  Workflow YAML parses (ruby is not installed)')
        return
    result = subprocess.run([ruby, '-ryaml', '-e', 'ARGV.each { |f| YAML.load_file(f) }'] + [str(f) for f in flows],
                            capture_output=True, text=True)
    report('Workflow YAML parses', [result.stderr.strip()] if result.returncode else [], f'({len(flows)} files)')


def main():
    for check in (check_brackets, check_xml, check_catalogs, check_dao_calls, check_hilt, check_lint,
                  check_resources, check_doc_paths, check_gradle_properties, check_workflows):
        check()
    if failures:
        print(f'\n{len(failures)} problem(s):')
        for failure in failures:
            print(f' - {failure}')
        return 1
    print('\nAll checks passed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
