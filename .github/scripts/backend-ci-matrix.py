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
  순서가 보장되지 않고 손으로 바뀐다.
  1. 빌드 설정 · CI 파일(`FULL_RUN_PATHS`)이 바뀌면 전체.
  2. 모듈 `build.gradle` 이 `rootProject.file('...')` 로 읽는 파일이면 그 모듈(예: api-gateway 가 대조하는 `docs/api-design-guide.md`).
     모듈 밖 파일을 읽는 테스트는 이렇게 선언해야 한다 — 선언이 없으면 그 파일만 바뀐 PR 에서 그 테스트가 돌지 않는다.
  3. `<그룹>/<모듈>/` 아래 파일이면 그 모듈.
  4. 테스트가 읽지 않는 파일(`NO_TEST_PATHS` — 문서 · 로컬 인프라)이면 없음. backend 밖 파일(frontend 등)도 없음.
  5. 그 밖에 판단하지 못한 backend 파일이면 전체(안전한 쪽).
  그다음 각 모듈 `build.gradle` 의 `project(':...')` 의존을 거꾸로 따라가 바뀐 모듈에 직 · 간접으로 의존하는 모듈을 더한다
  (`implementation` · `compileOnly` · `testFixtures` 모두 — 어느 쪽이든 깨질 수 있다).
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
from collections import deque
from pathlib import Path

# 그룹 이름 → 모듈(backend 기준 경로). 시간 근거: #136 측정(auth 100s · delivery 94s · order 60s, product 가 가장 크다)
GROUPS = {
    'auth': ['service/auth-service'],
    'product': ['service/product-service'],
    'order-payment': ['service/order-service', 'service/payment-service'],
    'delivery': ['service/delivery-service'],
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
]
# 테스트가 읽지 않는 backend 파일. 테스트가 읽게 되면 그 모듈 build.gradle 에 `inputs.file(rootProject.file(...))` 로 선언한다(2단계가 먼저 본다)
NO_TEST_PATHS = [
    '{backend}/docs/*',
    '{backend}/*.md',
    '{backend}/.env.example',
    '{backend}/docker-compose-local.yml',
    '{backend}/docker/*',
]

BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')
# `include 'a', 'b'` · 여러 줄에 걸친 `include 'a',\n 'b'` · `include('a', 'b')` 를 읽는다. 인자에 변수 · 표현식을 쓰면 못 읽는데,
# 그때는 아래 교차 검사가 실패시킨다
INCLUDE = re.compile(r"""\binclude\b\s*\(?\s*((?:['"][^'"\n]+['"]\s*,?\s*)+)\)?""")
QUOTED = re.compile(r"""['"]([^'"\n]+)['"]""")
PROJECT_DEPENDENCY = re.compile(r"""\bproject\(\s*['"]:?([^'"\n]+)['"]\s*\)""")
ROOT_FILE_INPUT = re.compile(r"""\brootProject\.file\(\s*['"]([^'"\n]+)['"]\s*\)""")


def strip_comments(text):
    return LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', text))


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


def read_build_files(backend: Path, modules):
    """모듈 → (의존 모듈 집합, rootProject.file 로 읽는 파일 집합). 주석 안의 project(...) 는 세지 않는다."""
    dependencies, root_inputs = {}, {}
    for module in modules:
        text = strip_comments((backend / module / 'build.gradle').read_text(encoding='utf-8-sig'))
        dependencies[module] = {path.strip(':').replace(':', '/') for path in PROJECT_DEPENDENCY.findall(text)} - {module}
        root_inputs[module] = {posixpath.normpath(path) for path in ROOT_FILE_INPUT.findall(text)}
    unknown = sorted('%s → %s' % (module, dep) for module, deps in dependencies.items() for dep in deps if dep not in dependencies)
    if unknown:
        raise ValueError('build.gradle 이 settings.gradle 에 없는 모듈을 의존한다: %s' % ', '.join(unknown))
    return dependencies, root_inputs


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


def classify(path, backend_rel, modules, root_inputs):
    """바뀐 파일 하나 → ('all' | 'module' | 'none', 모듈, 이유)."""
    def matches(patterns):
        return any(fnmatch.fnmatchcase(path, pattern.format(backend=backend_rel)) for pattern in patterns)

    if matches(FULL_RUN_PATHS):
        return 'all', None, '빌드 설정 · CI 파일'
    if path.startswith(backend_rel + '/'):
        inside = path[len(backend_rel) + 1:]
        readers = sorted(module for module, inputs in root_inputs.items() if inside in inputs)
        if readers:
            return 'module', readers, 'build.gradle 이 rootProject.file 로 읽는 파일'
        owner = next((module for module in modules if inside.startswith(module + '/')), None)
        if owner:
            return 'module', [owner], '모듈 안 파일'
        if matches(NO_TEST_PATHS):
            return 'none', None, '테스트가 읽지 않는 파일'
        return 'all', None, '판단하지 못한 backend 파일(안전하게 전체)'
    return 'none', None, 'backend 밖 파일'


def select_modules(changed, backend_rel, modules, dependencies, root_inputs):
    """반환: (전체 여부, 전체 사유, 고른 모듈 → 더한 이유, 파일별 판정 목록)."""
    if changed is None or (changed and changed[0] == ALL_MARKER):
        reason = '바뀐 파일 목록 없음(develop push 등)' if changed is None else 'PR diff 를 만들지 못함'
        return True, reason, {}, []
    decisions, seeds, full_reason = [], set(), None
    for path in changed:
        kind, owners, reason = classify(path, backend_rel, modules, root_inputs)
        decisions.append((path, kind, owners, reason))
        if kind == 'all' and full_reason is None:
            full_reason = '%s — %s' % (reason, path)
        elif kind == 'module':
            seeds.update(owners)
    if full_reason:
        return True, full_reason, {}, decisions
    return False, None, dependents_closure(seeds, dependencies), decisions


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
    text = sys.stdin.read() if source == '-' else Path(source).read_text(encoding='utf-8')
    return [line.strip() for line in text.splitlines() if line.strip()]


def repo_relative(backend: Path):
    try:
        top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], cwd=backend, capture_output=True, text=True, check=True)
        return backend.relative_to(Path(top.stdout.strip()).resolve()).as_posix()
    except (OSError, subprocess.CalledProcessError, ValueError):
        return backend.name


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
            lines.append('| `%s` | %s | %s |' % (path, reason, ', '.join('`%s`' % o for o in owners) if owners else '-'))
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
        dependencies, root_inputs = read_build_files(backend, modules)
        full, full_reason, selected, decisions = select_modules(
            read_changed_files(args.changed_files), repo_relative(backend), modules, dependencies, root_inputs)
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
