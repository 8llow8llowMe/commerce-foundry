"""backend-ci: 실제로 실행된 테스트 건수를 모듈별로 세고, 테스트가 있어야 하는데 0건인 모듈이 있으면 실패시킨다.

사용: python3 .github/scripts/backend-test-count.py <backend 디렉터리> [--modules "service/a service/b"] [--group 이름]

- 테스트가 있어야 하는 모듈: 실행 모듈(core 가 아닌 그룹 전부 — backend/build.gradle 의 library 판정과 같다) + src/test/java 가 있는 모듈.
  합계만 보면 core 테스트 덕에 서비스 하나가 0건이어도(캐시로 건너뜀, 테스트 누락) 통과한다.
- 건수는 '실제로 실행된' 수다(tests - skipped). @Disabled · disabledWithoutDocker 로만 채운 모듈도 0건으로 본다.
- 환경변수 GRADLE_OUTCOME 이 success 일 때만 0건을 실패로 본다. check 가 이미 실패했다면 원인은 그쪽이다.
- --modules 를 주면 그 모듈만 센다. CI 는 모듈 그룹별 병렬 job(backend-ci-matrix.py)이라 job 마다 자기 그룹만 본다.
  없는 모듈 이름이면 실패시킨다 — 그룹 표 오타로 검사가 빈 채 통과하지 않게. 주지 않으면 전체 모듈이다(로컬 사용법).
- GITHUB_STEP_SUMMARY 가 있으면 요약 표를 거기에 쓴다(--group 이 제목에 붙는다). 로컬에서도 그대로 돌려 볼 수 있다.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# backend/build.gradle 의 `boolean library = project.parent.name == 'core'` 와 같은 규칙이다. 한쪽을 바꾸면 둘 다 바꾼다
LIBRARY_GROUP = 'core'


def count_tests(backend: Path, modules):
    counts, totals = {}, {'failures': 0, 'errors': 0, 'skipped': 0}
    for path in backend.glob('**/build/test-results/test/*.xml'):
        module = path.relative_to(backend).as_posix().split('/build/test-results')[0]
        if modules is not None and module not in modules:
            continue
        root = ET.parse(path).getroot()
        executed = int(root.get('tests', 0)) - int(root.get('skipped', 0))
        counts[module] = counts.get(module, 0) + executed
        for key in totals:
            totals[key] += int(root.get(key, 0))
    return counts, totals


def required_modules(backend: Path, modules):
    required = set()
    for build_file in backend.glob('*/*/build.gradle'):
        module_dir = build_file.parent
        module = module_dir.relative_to(backend).as_posix()
        if modules is not None and module not in modules:
            continue
        if module.split('/')[0] != LIBRARY_GROUP or (module_dir / 'src' / 'test' / 'java').is_dir():
            required.add(module)
    return required


def main():
    # Windows 에서 파이프·IDE 로 돌리면 stdout 이 cp949 라 한글 외 문자에서 깨진다. CI(ubuntu)는 원래 UTF-8 이다
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('backend', nargs='?', default='.')
    parser.add_argument('--modules', help='공백으로 구분한 모듈 경로 (backend 기준, 예: service/auth-service)')
    parser.add_argument('--group', help='요약 제목에 붙일 CI 그룹 이름')
    args = parser.parse_args()
    backend = Path(args.backend).resolve()
    modules = set(args.modules.split()) if args.modules is not None else None
    if modules is not None:
        unknown = sorted(module for module in modules if not (backend / module / 'build.gradle').is_file())
        if unknown or not modules:
            print('없는 모듈이다: %s — 그룹 표(backend-ci-matrix.py)를 확인한다.' % (', '.join(unknown) or '(빈 목록)'), file=sys.stderr)
            return 1

    counts, totals = count_tests(backend, modules)
    required = required_modules(backend, modules)
    empty = sorted(module for module in required if counts.get(module, 0) == 0)
    total = sum(counts.values())

    lines = ['| 모듈 | 실행 건수 (건너뜀 제외) |', '|------|----------:|']
    for module in sorted(set(counts) | required):
        mark = ' <- 0건' if module in empty else ''
        lines.append('| `%s` | %d%s |' % (module, counts.get(module, 0), mark))
    lines.append('| **합계** | **%d** |' % total)
    lines.append('')
    lines.append('실패 %d · 오류 %d · 건너뜀 %d' % (totals['failures'], totals['errors'], totals['skipped']))
    summary = '\n'.join(lines)
    print(summary)

    summary_file = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_file:
        with open(summary_file, 'a', encoding='utf-8') as fp:
            title = '백엔드 테스트 실행 결과' + (' — `%s`' % args.group if args.group else '')
            fp.write('## %s\n\n%s\n' % (title, summary))

    if os.environ.get('GRADLE_OUTCOME', 'success') != 'success':
        return 0
    if total == 0:
        print('테스트가 한 건도 실행되지 않았는데 check 가 통과했다 — 이 초록불은 믿을 수 없다.', file=sys.stderr)
        return 1
    if empty:
        print('테스트가 있어야 하는데 0건인 모듈: %s — 캐시로 건너뛰었거나 테스트가 빠졌다.' % ', '.join(empty), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
