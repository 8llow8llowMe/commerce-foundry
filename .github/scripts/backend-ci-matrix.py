"""backend-ci: settings.gradle 의 모듈을 병렬 job 그룹으로 나눈 matrix 를 만든다.

사용: python .github/scripts/backend-ci-matrix.py <backend 디렉터리> [--changed-files <목록 파일 | ->]

- job 하나에서 `./gradlew check` 를 돌리면 러너 한 대(4 vCPU)의 워커 4개가 모든 모듈의 컴파일 · 테스트를 나눠 써서
  벽시계 시간이 테스트 총량에 비례해 늘어난다. 테스트가 오래 걸리는 서비스만 이름으로 떼어 따로 job 을 준다.
- 그룹 표에 없는 모듈(core · cloud · 앞으로 생길 모듈)은 전부 `rest` 그룹이다. 새 모듈을 추가하고 이 표를 잊어도
  어느 job 에서든 반드시 테스트된다. 새 서비스의 테스트가 길어지면 표에 그룹을 더한다.
- **include 집합과 `*/*/build.gradle` 디렉터리 집합이 같아야 한다.** 다르면 실패시킨다 — settings 에 include 를 잊은 모듈이나
  이 파서가 못 읽은 include 형식 때문에 그 모듈의 테스트가 어느 job 에서도 돌지 않는 일을 막는다
  (job 마다 자기 그룹만 세므로 backend-test-count.py 로는 못 잡는다). 모듈 깊이를 바꾸면 backend-test-count.py 와 같이 바꾼다.
- 표에 적은 모듈이 settings.gradle 에 없으면(이름 변경 · 오타) 실패시킨다.
- **PR 은 바뀐 모듈과 그 모듈에 의존하는 모듈만 고른다**(#179). `--changed-files` 에 저장소 루트 기준 경로를 한 줄에 하나씩 준다
  (`-` 면 stdin). 옵션이 없거나 첫 줄이 `__ALL__` 이면 전체다(develop push). 라벨이 아니라 경로로 정한다 — 라벨은 별도 workflow 라
  순서가 보장되지 않고 손으로 바뀐다. **판단이 서지 않으면 덜 돌지 않고 전체로 간다**(#211).
  목록은 `git -c core.quotePath=false diff --name-only --no-renames` 로 만든다. git 기본값(`core.quotePath=true`)은 한글 경로를
  `"backend/...\\355..."` 처럼 따옴표로 감싸 출력해, 감싼 채로 읽으면 backend 밖 파일이 되어 테스트가 0개가 된다.
  1. 그대로 믿을 수 없는 경로면 전체 — 따옴표로 시작하는 경로(`"` · `\\` · 제어문자가 든 경로는 quotePath=false 여도 git 이 감싼다),
     제어 · 서식 문자가 든 경로, UTF-8 로 읽지 못한 경로, 앞뒤 공백이 있는 경로. 목록이 비어 있어도 전체.
  2. 빌드 설정 · CI 파일(`FULL_RUN_PATHS`)이면 전체.
  3. 모듈 `build.gradle` 이 `rootProject.file('...')` 로 읽는 파일이거나 그 디렉터리 아래 파일이면 그 모듈(reader), `<그룹>/<모듈>/`
     아래 파일이면 그 모듈(owner) — 둘 다면 합친다. 모듈 밖 파일을 읽는 테스트는 이렇게 선언해야 한다(예: api-gateway 가 대조하는
     `docs/api-design-guide.md`) — 선언이 없으면 그 파일만 바뀐 PR 에서 그 테스트가 돌지 않는다.
     루트 · 그룹 `build.gradle` 이 읽는 파일은 어느 모듈이 읽는지 몰라 전체.
  4. 테스트가 읽지 않는 파일(`NO_TEST_PATHS` — 문서 · 로컬 인프라)이면 없음. backend 밖 파일(frontend 등)도 없음.
  5. 그 밖에 판단하지 못한 backend 파일이면 전체(안전한 쪽).
  그다음 각 모듈 `build.gradle` 의 `project(':...')` 의존을 거꾸로 따라가 바뀐 모듈에 직 · 간접으로 의존하는 모듈을 더한다
  (`implementation` · `compileOnly` · `testFixtures` 모두 — 어느 쪽이든 깨질 수 있다).
  **build.gradle 을 다 읽었다고 믿을 수 없으면 PR 도 전체다**(`BuildGraph.problems`): 모듈 파일의 project 참조(`project(` · `projects.` ·
  `findProject(` 등) 수와 읽어 낸 의존 수가 다를 때, 루트 · 그룹 `build.gradle` 에 project 참조가 있을 때, `apply from` 으로 다른 스크립트를
  읽을 때, `rootDir` · `layout.` · `'../'` · 변수 인자 `rootProject.file(x)` 처럼 이 스크립트가 경로를 풀 수 없게 파일을 읽을 때,
  주석 · 문자열이 닫히지 않았을 때.
- 결과는 GITHUB_OUTPUT 에 `matrix=` · `run_tests=` 로 쓰고, 고른 이유를 stdout 과 GITHUB_STEP_SUMMARY 에 남긴다.
  고를 모듈이 없으면(문서만 바꾼 PR) `run_tests=false` 이고 test job 을 건너뛴다. 로컬에서도 그대로 돌려 볼 수 있다.
"""
import argparse
import fnmatch
import json
import os
import posixpath
import re
import subprocess
import sys
import unicodedata
from collections import deque
from pathlib import Path

# 그룹 이름 → 모듈(backend 기준 경로). 시간 근거: #136 측정(auth 100s · delivery 94s · order 60s, product 가 가장 크다)
# 그룹은 job 하나다. 무료 Organization 은 동시 실행 job 이 20개라 그룹을 늘릴수록 PR 이 몰릴 때 Queued 대기가 길어진다(#194).
# 그래서 서비스를 묶는다 — job 안에서는 `org.gradle.parallel=true` 로 모듈이 병렬로 돈다.
GROUPS = {
    'product-delivery': ['service/product-service', 'service/delivery-service'],
    'auth-order': ['service/auth-service', 'service/order-service', 'service/payment-service'],
}
REST_GROUP = 'rest'

ALL_MARKER = '__ALL__'
# 바뀌면 전 모듈을 돌리는 경로. `{backend}` 는 저장소 루트 기준 backend 디렉터리다. fnmatch 의 `*` 는 `/` 도 넘는다
FULL_RUN_PATHS = [
    '{backend}/build.gradle',
    '{backend}/settings.gradle',
    '{backend}/gradle.properties',
    '{backend}/gradle/*',
    '{backend}/gradlew',
    '{backend}/gradlew.bat',
    '.github/workflows/backend-ci.yml',
    '.github/scripts/backend-*',
    # workflow 가 쓰는 로컬 composite action. backend-ci.yml 의 `paths` 에도 있다
    '.github/actions/*',
]
# 테스트가 읽지 않는 backend 파일. 테스트가 읽게 되면 그 모듈 build.gradle 에 `inputs.file(rootProject.file(...))` 로 선언한다(3단계가 먼저 본다).
# backend-ci.yml 의 `paths` 제외 목록과 같게 둔다 — 이 파일들만 바꾼 PR 은 workflow 자체가 뜨지 않는다(#194). 선언 파일을 늘리면 거기서 다시 넣는다
NO_TEST_PATHS = [
    '{backend}/docs/*',
    '{backend}/*.md',
    '{backend}/.env.example',
    '{backend}/docker-compose-local.yml',
    '{backend}/docker/*',
]

# `include 'a', 'b'` · 여러 줄에 걸친 `include 'a',\n 'b'` · `include('a', 'b')` 를 읽는다. 인자에 변수 · 표현식을 쓰면 못 읽는데,
# 그때는 아래 교차 검사가 실패시킨다
INCLUDE = re.compile(r"""\binclude\b\s*\(?\s*((?:['"][^'"\n]+['"]\s*,?\s*)+)\)?""")
QUOTED = re.compile(r"""['"]([^'"\n]+)['"]""")
# 읽어 내는 의존 형식: project(':a') · project(":a") · project('a') · project(path: ':a', ...). 여러 줄에 걸쳐도 된다
PROJECT_DEPENDENCY = re.compile(r"""\bproject\s*\(\s*(?:path\s*:\s*)?['"]:?([^'"\n]+)['"]\s*[,)]""")
# 다른 모듈을 가리킬 수 있는 참조 전부. 모듈 파일에서 이 수가 위에서 읽어 낸 수와 다르면(`projects.x` 타입 세이프 접근 ·
# 괄호 없는 `project ':a'` · 변수 인자 · findProject 등) 의존 그래프를 믿지 않는다
PROJECT_REFERENCE = re.compile(r"""\b(?:project|findProject|evaluationDependsOn)\s*\(|\bproject\s+['"]|\bprojects\s*\.""")
# 다른 스크립트를 끌어오면 그 안의 의존 · 파일 입력을 읽지 못한다
SCRIPT_APPLY = re.compile(r"""\bapply\s*\(?\s*from\b""")
ROOT_FILE_CALL = re.compile(r"""\brootProject\s*\.\s*file\s*\(""")
ROOT_FILE_INPUT = re.compile(r"""\brootProject\s*\.\s*file\s*\(\s*['"]([^'"\n]+)['"]\s*\)""")
# 이 스크립트가 경로를 풀 수 없는 모듈 밖 파일 참조. `layout.buildDirectory` 는 모듈 자기 출력이라 뺀다
OPAQUE_FILE_REFERENCE = re.compile(
    r"""\b(?:rootDir|settingsDir|getRootDir|getSettingsDir)\b"""
    r"""|\brootProject\s*\.\s*(?:projectDir|getProjectDir|layout|getLayout|files|fileTree)\b"""
    r"""|\blayout\s*\.(?!\s*buildDirectory\b)"""
    r"""|\bparent\s*\.\s*(?:projectDir|layout|file|files|fileTree)\b"""
    r"""|['"][^'"\n]*\.\.[/\\]|['"]\.\.['"]""")
# 경로에 이 범주의 문자가 있으면 그대로 믿지 않는다: 제어 · 서식(BOM · 방향 제어 등) · 서로게이트(UTF-8 로 못 읽은 바이트) ·
# 사용자 정의 · 미할당 · 줄 · 문단 구분
UNSAFE_CATEGORIES = {'Cc', 'Cf', 'Cs', 'Co', 'Cn', 'Zl', 'Zp'}


def scan_code(text):
    """`//` · `/* */` 주석을 지운 코드와, 닫히지 않은 것(없으면 None)을 돌려준다.

    문자열('…' · "…" · '''…''' · \"\"\"…\"\"\") 안의 `/*` · `//`(예: `'**/*.yml'` · `'https://…'`)는 주석으로 보지 않는다.
    정규식으로 주석을 먼저 지우면 문자열 속 `/*` 부터 다음 `*/` 까지가 통째로 사라져 그 사이의 project(...) 를 놓친다.
    """
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('//', i):
            end = text.find('\n', i)
            i = n if end < 0 else end
            continue
        if text.startswith('/*', i):
            end = text.find('*/', i + 2)
            if end < 0:
                return ''.join(out), '/* 주석'
            out.append(' ')
            i = end + 2
            continue
        if text[i] in '\'"':
            quote = text[i:i + 3] if text[i:i + 3] in ("'''", '"""') else text[i]
            j = i + len(quote)
            while j < n and not text.startswith(quote, j):
                j += 2 if text[j] == '\\' else 1
            if j >= n:
                return ''.join(out) + text[i:], '문자열'
            out.append(text[i:j + len(quote)])
            i = j + len(quote)
            continue
        out.append(text[i])
        i += 1
    return ''.join(out), None


def strip_comments(text):
    return scan_code(text)[0]


def included_modules(settings: Path):
    # BOM 이 붙은 파일이면 첫 줄 include 를 놓치지 않게 utf-8-sig 로 읽는다
    text = strip_comments(settings.read_text(encoding='utf-8-sig'))
    modules = []
    for match in INCLUDE.finditer(text):
        for path in QUOTED.findall(match.group(1)):
            modules.append(path.strip().strip(':').replace(':', '/'))
    return modules


def module_dirs(backend: Path):
    return {build_file.parent.relative_to(backend).as_posix() for build_file in backend.glob('*/*/build.gradle')}


def check_modules(modules, dirs):
    errors = []
    included = set(modules)
    duplicated = sorted({module for module in modules if modules.count(module) > 1})
    if duplicated:
        errors.append('settings.gradle 에 두 번 include 한 모듈: %s' % ', '.join(duplicated))
    not_included = sorted(dirs - included)
    if not_included:
        errors.append('build.gradle 이 있는데 settings.gradle 에서 읽지 못한 모듈(include 누락 · 읽지 못한 include 형식): %s'
                      % ', '.join(not_included))
    no_build_file = sorted(included - dirs)
    if no_build_file:
        errors.append('include 했는데 <그룹>/<모듈>/build.gradle 이 없는 모듈: %s' % ', '.join(no_build_file))
    return errors


class BuildGraph:
    """build.gradle 에서 읽은 모듈 의존 · 모듈 밖 파일 입력. `problems` 가 하나라도 있으면 PR 도 전체로 간다."""

    def __init__(self):
        self.dependencies = {}      # 모듈 → 의존 모듈 집합. 주석 안의 project(...) 는 세지 않는다
        self.inputs = {}            # 모듈 → rootProject.file 로 읽는 저장소 루트 기준 경로 집합(파일 · 디렉터리)
        self.shared_inputs = set()  # 루트 · 그룹 build.gradle 이 rootProject.file 로 읽는 경로 — 어느 모듈이 읽는지 모른다
        self.problems = []          # 다 읽었다고 믿을 수 없는 이유


def read_code(build_file: Path, name, problems):
    code, unclosed = scan_code(build_file.read_text(encoding='utf-8-sig'))
    if unclosed:
        problems.append('%s 의 %s 이 닫히지 않음' % (name, unclosed))
    if SCRIPT_APPLY.search(code):
        problems.append('%s 가 apply from 으로 다른 스크립트를 읽음' % name)
    opaque = OPAQUE_FILE_REFERENCE.search(code)
    if opaque:
        problems.append('%s 가 경로를 풀 수 없게 파일을 읽음(%s)' % (name, opaque.group(0).strip()))
    return code


def read_file_inputs(code, name, backend_rel, problems):
    """rootProject.file('<backend 기준 경로>') → 저장소 루트 기준 경로 집합. 풀 수 없는 인자는 problems 에 남긴다."""
    paths = ROOT_FILE_INPUT.findall(code)
    if len(ROOT_FILE_CALL.findall(code)) != len(paths):
        problems.append('%s 의 rootProject.file(...) 인자를 다 읽지 못함(문자열 하나가 아님)' % name)
    inputs = set()
    for path in paths:
        normalized = posixpath.normpath(posixpath.join(backend_rel, path))
        if re.search(r'[$\\:]', path) or path.startswith('/') or normalized in ('.', '..') or normalized.startswith('../'):
            problems.append('%s 의 rootProject.file(%r) 경로를 풀 수 없음' % (name, path))
        else:
            inputs.add(normalized)
    return inputs


def read_build_graph(backend: Path, backend_rel, modules):
    graph = BuildGraph()
    for module in modules:
        name = '%s/build.gradle' % module
        code = read_code(backend / module / 'build.gradle', name, graph.problems)
        found = PROJECT_DEPENDENCY.findall(code)
        references = len(PROJECT_REFERENCE.findall(code))
        if references != len(found):
            graph.problems.append('%s 의 project 참조 %d개 중 %d개만 의존으로 읽음' % (name, references, len(found)))
        graph.dependencies[module] = {path.strip(':').replace(':', '/') for path in found} - {module}
        graph.inputs[module] = read_file_inputs(code, name, backend_rel, graph.problems)
    # 루트 · 그룹 build.gradle 은 subprojects · allprojects 블록으로 여러 모듈에 걸린다 — project 참조가 어느 모듈의 의존인지 모른다
    for build_file in [backend / 'build.gradle'] + sorted(backend.glob('*/build.gradle')):
        if not build_file.is_file():
            continue
        name = build_file.relative_to(backend).as_posix()
        code = read_code(build_file, name, graph.problems)
        if PROJECT_REFERENCE.search(code):
            graph.problems.append('%s 에 project 참조가 있음(어느 모듈의 의존인지 모름)' % name)
        graph.shared_inputs |= read_file_inputs(code, name, backend_rel, graph.problems)
    unknown = sorted('%s → %s' % (module, dep) for module, deps in graph.dependencies.items()
                     for dep in deps if dep not in graph.dependencies)
    if unknown:
        raise ValueError('build.gradle 이 settings.gradle 에 없는 모듈을 의존한다: %s' % ', '.join(unknown))
    return graph


def dependents_closure(seeds, dependencies):
    """seeds 와, seeds 에 직 · 간접으로 의존하는 모듈. 값은 그 모듈을 더하게 만든 의존 대상(seed 면 None)."""
    reverse = {}
    for module, deps in dependencies.items():
        for dep in deps:
            reverse.setdefault(dep, set()).add(module)
    added = {module: None for module in seeds}
    queue = deque(sorted(seeds))
    while queue:
        current = queue.popleft()
        for dependent in sorted(reverse.get(current, ())):
            if dependent not in added:
                added[dependent] = current
                queue.append(dependent)
    return added


def untrusted_path(path):
    """경로를 그대로 믿을 수 없으면 이유, 믿을 수 있으면 None."""
    if path.startswith('"'):
        return 'git 이 따옴표로 감싼 경로(core.quotePath 이스케이프)'
    if any(0xDC80 <= ord(ch) <= 0xDCFF for ch in path):
        return 'UTF-8 로 읽지 못한 경로'
    if any(unicodedata.category(ch) in UNSAFE_CATEGORIES for ch in path):
        return '제어 · 서식 문자가 든 경로'
    if path != path.strip():
        return '앞뒤 공백이 있는 경로'
    return None


def covers(declared, path):
    """선언한 입력(파일 또는 디렉터리)이 path 를 덮는가. 디렉터리면 그 아래 파일 전부다."""
    return path == declared or path.startswith(declared + '/')


def classify(path, backend_rel, modules, graph):
    """바뀐 파일 하나 → ('all' | 'module' | 'none', 모듈 목록, 이유)."""
    def matches(patterns):
        return any(fnmatch.fnmatchcase(path, pattern.format(backend=backend_rel)) for pattern in patterns)

    problem = untrusted_path(path)
    if problem:
        return 'all', None, problem
    if matches(FULL_RUN_PATHS):
        return 'all', None, '빌드 설정 · CI 파일'
    if any(covers(item, path) for item in graph.shared_inputs):
        return 'all', None, '루트 · 그룹 build.gradle 이 rootProject.file 로 읽는 파일'
    in_backend = path.startswith(backend_rel + '/')
    # reader(모듈 밖 파일을 읽는 모듈)와 owner(파일이 든 모듈)는 둘 중 하나가 아니라 합집합이다
    readers = {module for module, inputs in graph.inputs.items() if any(covers(item, path) for item in inputs)}
    inside = path[len(backend_rel) + 1:] if in_backend else None
    owner = next((module for module in modules if inside.startswith(module + '/')), None) if in_backend else None
    if readers or owner:
        reasons = (['build.gradle 이 rootProject.file 로 읽는 파일'] if readers else []) + (['모듈 안 파일'] if owner else [])
        return 'module', sorted(readers | ({owner} if owner else set())), ' · '.join(reasons)
    if not in_backend:
        return 'none', None, 'backend 밖 파일'
    if matches(NO_TEST_PATHS):
        return 'none', None, '테스트가 읽지 않는 파일'
    return 'all', None, '판단하지 못한 backend 파일(안전하게 전체)'


def select_modules(changed, backend_rel, modules, graph):
    """반환: (전체 여부, 전체 사유, 고른 모듈 → 더한 이유, 파일별 판정 목록)."""
    if changed is None or (changed and changed[0] == ALL_MARKER):
        reason = '바뀐 파일 목록 없음(develop push 등)' if changed is None else 'PR diff 를 만들지 못함'
        return True, reason, {}, []
    if not changed:
        return True, '바뀐 파일 목록이 비어 있음(판단 못 함)', {}, []
    decisions, seeds, full_reason = [], set(), None
    for path in changed:
        kind, owners, reason = classify(path, backend_rel, modules, graph)
        decisions.append((path, kind, owners, reason))
        if kind == 'all' and full_reason is None:
            full_reason = '%s — `%s`' % (reason, cell(path))
        elif kind == 'module':
            seeds.update(owners)
    if full_reason:
        return True, full_reason, {}, decisions
    if graph.problems:
        return True, 'build.gradle 을 다 읽지 못함 — %s' % '; '.join(graph.problems), {}, decisions
    return False, None, dependents_closure(seeds, graph.dependencies), decisions


def build_matrix(modules, selected=None):
    known = set(modules)
    errors, owner = [], {}
    for group, members in GROUPS.items():
        for module in members:
            if module not in known:
                errors.append('그룹 %s 의 %s 가 settings.gradle 에 없다' % (group, module))
            if module in owner:
                errors.append('%s 가 %s · %s 두 그룹에 있다' % (module, owner[module], group))
            owner[module] = group
    if errors:
        raise ValueError('\n'.join(errors))

    rest = [module for module in modules if module not in owner]
    groups = [(REST_GROUP, rest)] + list(GROUPS.items())
    if selected is not None:
        groups = [(group, [module for module in members if module in selected]) for group, members in groups]
    return {'include': [
        {
            'group': group,
            'modules': ' '.join(members),
            'tasks': ' '.join(':%s:check' % module.replace('/', ':') for module in members),
        }
        for group, members in groups if members
    ]}


def read_changed_files(source):
    if source is None:
        return None
    data = sys.stdin.buffer.read() if source == '-' else Path(source).read_bytes()
    # UTF-8 이 아닌 바이트는 예외로 plan 을 죽이지 않고 서로게이트로 남겨 classify 가 전체로 보낸다.
    # utf-8-sig: Windows 편집기 · PowerShell 이 붙인 BOM 을 뗀다 — 남기면 첫 경로가 backend 밖이 된다
    text = data.decode('utf-8-sig', errors='surrogateescape')
    # splitlines() 는 U+2028 · U+0085 같은 문자에서도 끊어 경로 하나를 둘로 만든다 — 줄바꿈으로만 나눈다. 공백은 지우지 않는다(경로의 일부)
    lines = (line[:-1] if line.endswith('\r') else line for line in text.split('\n'))
    return [line for line in lines if line.strip()]


def repo_relative(backend: Path):
    try:
        top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], cwd=backend, capture_output=True, text=True, check=True)
        return backend.relative_to(Path(top.stdout.strip()).resolve()).as_posix()
    except (OSError, subprocess.CalledProcessError, ValueError):
        return backend.name


def cell(text):
    """요약 표에 쓸 수 있게 바꾼다 — 못 읽은 바이트 · 제어문자는 `\\xNN` 꼴로(그대로 쓰면 출력 인코딩에서 예외가 난다), 표를 깨는 `|` · `` ` `` 도."""
    def safe(ch):
        if 0xDC80 <= ord(ch) <= 0xDCFF:
            return '\\x%02x' % (ord(ch) - 0xDC00)
        if unicodedata.category(ch) in UNSAFE_CATEGORIES:
            return ch.encode('unicode_escape').decode('ascii')
        return ch
    return ''.join(safe(ch) for ch in text).replace('|', '\\|').replace('`', "'")


def report(full, full_reason, selected, decisions, matrix):
    lines = ['## backend-ci 테스트 대상', '']
    if full:
        lines.append('**전 모듈** — %s' % full_reason)
    elif not selected:
        lines.append('**테스트 없음** — 테스트 대상 모듈이 바뀌지 않았다')
    else:
        lines.append('**바뀐 모듈과 의존 모듈만** (%d개)' % len(selected))
    if decisions:
        lines += ['', '| 바뀐 파일 | 판정 | 모듈 |', '|------|------|------|']
        shown = decisions[:60]
        for path, kind, owners, reason in shown:
            lines.append('| `%s` | %s | %s |' % (cell(path), cell(reason), ', '.join('`%s`' % o for o in owners) if owners else '-'))
        if len(decisions) > len(shown):
            lines.append('| … 외 %d개 | | |' % (len(decisions) - len(shown)))
    added = sorted((module, via) for module, via in selected.items() if via)
    if added:
        lines += ['', '의존으로 더한 모듈: ' + ' · '.join('`%s` ← `%s`' % (module, via) for module, via in added)]
    lines += ['', '| 그룹 | 모듈 |', '|------|------|']
    for entry in matrix['include']:
        lines.append('| %s | %s |' % (entry['group'], entry['modules']))
    if not matrix['include']:
        lines.append('| - | - |')
    return '\n'.join(lines)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('backend', nargs='?', default='.')
    parser.add_argument('--changed-files', help='저장소 루트 기준 바뀐 파일 목록(한 줄에 하나, - 면 stdin). 없으면 전체')
    args = parser.parse_args()

    backend = Path(args.backend).resolve()
    modules = included_modules(backend / 'settings.gradle')
    errors = check_modules(modules, module_dirs(backend))
    if not modules:
        errors.insert(0, 'settings.gradle 에서 include 한 모듈을 찾지 못했다.')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    try:
        backend_rel = repo_relative(backend)
        graph = read_build_graph(backend, backend_rel, modules)
        full, full_reason, selected, decisions = select_modules(
            read_changed_files(args.changed_files), backend_rel, modules, graph)
        matrix = build_matrix(modules, None if full else set(selected))
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1

    run_tests = 'true' if matrix['include'] else 'false'
    summary = report(full, full_reason, selected, decisions, matrix)
    print(summary)
    print()
    print(json.dumps(matrix, ensure_ascii=False, indent=2))
    output_file = os.environ.get('GITHUB_OUTPUT')
    if output_file:
        with open(output_file, 'a', encoding='utf-8') as fp:
            fp.write('matrix=%s\n' % json.dumps(matrix, ensure_ascii=False))
            fp.write('run_tests=%s\n' % run_tests)
    summary_file = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_file:
        with open(summary_file, 'a', encoding='utf-8') as fp:
            fp.write(summary + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
