"""backend-ci: settings.gradle 의 모듈을 병렬 job 그룹으로 나눈 matrix 를 만든다.

사용: python3 .github/scripts/backend-ci-matrix.py <backend 디렉터리>

- job 하나에서 `./gradlew check` 를 돌리면 러너 한 대(4 vCPU)의 워커 4개가 모든 모듈의 컴파일 · 테스트를 나눠 써서
  벽시계 시간이 테스트 총량에 비례해 늘어난다. 테스트가 오래 걸리는 서비스만 이름으로 떼어 따로 job 을 준다.
- 그룹 표에 없는 모듈(core · cloud · 앞으로 생길 모듈)은 전부 `rest` 그룹이다. 새 모듈을 추가하고 이 표를 잊어도
  어느 job 에서든 반드시 테스트된다. 새 서비스의 테스트가 길어지면 표에 그룹을 더한다.
- 표에 적은 모듈이 settings.gradle 에 없으면(이름 변경 · 오타) 실패시킨다. 그 모듈의 테스트가 조용히 빠지는 것을 막는다.
- 결과는 GITHUB_OUTPUT 에 `matrix=` 로 쓰고 stdout 에도 찍는다. 로컬에서도 그대로 돌려 볼 수 있다.
"""
import json
import os
import re
import sys
from pathlib import Path

# 그룹 이름 → 모듈(backend 기준 경로). 시간 근거: #136 측정(auth 100s · delivery 94s · order 60s, product 가 가장 크다)
GROUPS = {
    'auth': ['service/auth-service'],
    'product': ['service/product-service'],
    'order-payment': ['service/order-service', 'service/payment-service'],
    'delivery': ['service/delivery-service'],
}
REST_GROUP = 'rest'

INCLUDE_LINE = re.compile(r'^\s*include\b(.*)$')
QUOTED = re.compile(r"""['"]([^'"]+)['"]""")


def included_modules(settings: Path):
    modules = []
    for line in settings.read_text(encoding='utf-8').splitlines():
        line = line.split('//', 1)[0]
        match = INCLUDE_LINE.match(line)
        if not match:
            continue
        for path in QUOTED.findall(match.group(1)):
            modules.append(path.strip(':').replace(':', '/'))
    return modules


def build_matrix(modules):
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
    return {'include': [
        {
            'group': group,
            'modules': ' '.join(members),
            'tasks': ' '.join(':%s:check' % module.replace('/', ':') for module in members),
        }
        for group, members in groups if members
    ]}


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    backend = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
    modules = included_modules(backend / 'settings.gradle')
    if not modules:
        print('settings.gradle 에서 include 한 모듈을 찾지 못했다.', file=sys.stderr)
        return 1
    try:
        matrix = build_matrix(modules)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1

    print(json.dumps(matrix, ensure_ascii=False, indent=2))
    output_file = os.environ.get('GITHUB_OUTPUT')
    if output_file:
        with open(output_file, 'a', encoding='utf-8') as fp:
            fp.write('matrix=%s\n' % json.dumps(matrix, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
