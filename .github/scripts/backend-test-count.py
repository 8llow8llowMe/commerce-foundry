"""backend-ci: 실제로 실행된 테스트 건수를 모듈별로 세고, 테스트가 있어야 하는데 0건인 모듈이 있으면 실패시킨다.

사용: python3 .github/scripts/backend-test-count.py <backend 디렉터리>

- 테스트가 있어야 하는 모듈: 실행 모듈(cloud/*, service/*) 전부 + src/test/java 가 있는 모듈.
  합계만 보면 core 테스트 덕에 서비스 하나가 0건이어도(캐시로 건너뜀, 테스트 누락) 통과한다.
- 환경변수 GRADLE_OUTCOME 이 success 일 때만 0건을 실패로 본다. check 가 이미 실패했다면 원인은 그쪽이다.
- GITHUB_STEP_SUMMARY 가 있으면 요약 표를 거기에 쓴다. 로컬에서도 그대로 돌려 볼 수 있다.
"""
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXECUTABLE_GROUPS = ('cloud', 'service')


def count_tests(backend: Path):
    counts, totals = {}, {'failures': 0, 'errors': 0, 'skipped': 0}
    for path in backend.glob('**/build/test-results/test/*.xml'):
        root = ET.parse(path).getroot()
        module = path.relative_to(backend).as_posix().split('/build/test-results')[0]
        counts[module] = counts.get(module, 0) + int(root.get('tests', 0))
        for key in totals:
            totals[key] += int(root.get(key, 0))
    return counts, totals


def required_modules(backend: Path):
    required = set()
    for build_file in backend.glob('*/*/build.gradle'):
        module_dir = build_file.parent
        module = module_dir.relative_to(backend).as_posix()
        if module.split('/')[0] in EXECUTABLE_GROUPS or (module_dir / 'src' / 'test' / 'java').is_dir():
            required.add(module)
    return required


def main():
    backend = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
    counts, totals = count_tests(backend)
    required = required_modules(backend)
    empty = sorted(module for module in required if counts.get(module, 0) == 0)
    total = sum(counts.values())

    lines = ['| 모듈 | 실행 건수 |', '|------|----------:|']
    for module in sorted(set(counts) | required):
        mark = ' ⚠️ 0건' if module in empty else ''
        lines.append('| `%s` | %d%s |' % (module, counts.get(module, 0), mark))
    lines.append('| **합계** | **%d** |' % total)
    lines.append('')
    lines.append('실패 %d · 오류 %d · 건너뜀 %d' % (totals['failures'], totals['errors'], totals['skipped']))
    summary = '\n'.join(lines)
    print(summary)

    summary_file = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_file:
        with open(summary_file, 'a', encoding='utf-8') as fp:
            fp.write('## 백엔드 테스트 실행 결과\n\n' + summary + '\n')

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
