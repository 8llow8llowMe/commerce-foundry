# Jenkins CI/CD Guide

> 백엔드를 Jenkins 멀티브랜치 파이프라인으로 빌드 · 배포하는 규칙이다(#198). 구현은 저장소 루트의
> `Jenkinsfile.backend-common.groovy` 와 모듈마다 하나인 `Jenkinsfile-<module>` 이다.
> 배포 단위(Dockerfile · compose · 이름 · 포트 키 · env 계약)는 `deploy-guide.md`(#196)가 정본이고, 이 문서는 그것을 언제 · 어떻게 올리는지를 정한다.
> 사내 hondigagae 파이프라인을 옮겨 왔다. 다른 점은 §9.
> **공개 저장소다.** 서버 주소 · 호스트 이름 · 포트 값 · 시크릿을 이 문서와 Jenkinsfile 에 쓰지 않는다. 라벨 · 경로 · 키 이름만 둔다.

## 1. 역할 분담 — Actions 는 게이트, Jenkins 는 배포

| | GitHub Actions `backend-ci` | Jenkins `commerce-foundry-<module>` |
|---|---|---|
| 언제 | PR 열기 · 갱신, develop push | PR 빌드, develop · main 머지 |
| 무엇을 | 바뀐 모듈 + 그 모듈에 의존하는 모듈의 `check`, 실행 건수 집계 | 그 모듈 하나의 `test` + `bootJar`, 머지 빌드면 배포 |
| 머지 조건 | **필수 체크**(`check`) | 아니다 — 배포 경로를 한 번 더 확인할 뿐이다 |
| 시크릿 | 없다 | 배포 단계에서만, 배포 agent 의 sh 하나 안에서 Vault 를 읽는다(§6) |

- 머지를 막는 것은 Actions 다. Jenkins PR 빌드는 배포 agent 와 같은 빌더 · 같은 명령으로 jar 가 만들어지는지를 본다.
  빌더가 바쁘면 멀티브랜치의 PR 발견을 꺼도 된다 — 배포는 브랜치 빌드만 하므로 영향이 없다.
- 의존 방향이 반대다. Actions 는 core 를 바꾸면 **core 를 쓰는 모듈 전부**를 테스트하고(역의존), Jenkins 잡은 **자기가 쓰는 core** 가 바뀌었을 때 돈다(정의존). 결과는 같은 모듈 집합이다.

## 2. 흐름

```text
작업 PR → develop
  Actions backend-ci (필수)    Jenkins PR-N: 영향 있는 모듈 잡만 test + bootJar, 배포 없음
develop 에 Rebase and merge
  Jenkins develop 잡 (모듈마다)
    배포 문맥 확인 (builder-backend)  브랜치 → 환경, 이전 성공 커밋, 변경 경로, 머지된 PR 라벨
    JAR 빌드      (builder-backend)  ./gradlew :<group>:<module>:test :<group>:<module>:bootJar
    dev 환경 배포 (deploy-backend-dev2, lock commerce-foundry-backend-deploy)
      sh 하나: Vault kv/commerce-foundry/backend/dev/env → .env.runtime → compose config -q → up --wait → 재시작 확인 → 정리
릴리스 PR develop → main (merge commit)
  Jenkins main 잡 — 같은 단계로 prod (deploy-backend-prod)
```

`develop` → dev, `main` → prod 다. 그 밖의 브랜치 빌드와 PR 빌드는 배포하지 않는다(브랜치 빌드는 Gradle 도 돌리지 않고 끝난다).

## 3. 잡 · agent · 멀티브랜치 설정

| 모듈 | 잡 이름 | Script Path | 배포 라벨 |
|------|---------|-------------|-----------|
| service-discovery | `commerce-foundry-service-discovery` | `Jenkinsfile-service-discovery` | `backend-service-discovery` |
| api-gateway | `commerce-foundry-api-gateway` | `Jenkinsfile-api-gateway` | `backend-api-gateway` |
| auth-service | `commerce-foundry-auth-service` | `Jenkinsfile-auth-service` | `backend-auth-service` |
| product-service | `commerce-foundry-product-service` | `Jenkinsfile-product-service` | `backend-product-service` |
| order-service | `commerce-foundry-order-service` | `Jenkinsfile-order-service` | `backend-order-service` |
| payment-service | `commerce-foundry-payment-service` | `Jenkinsfile-payment-service` | `backend-payment-service` |
| delivery-service | `commerce-foundry-delivery-service` | `Jenkinsfile-delivery-service` | `backend-delivery-service` |
| operations-service | `commerce-foundry-operations-service` | `Jenkinsfile-operations-service` | `backend-operations-service` |

- **operations-service 는 자리만 있다.** #212 가 모듈 · Dockerfile · compose 를 추가하고 `.github/labeler.yml` 에 라벨을 더한 뒤 잡을 등록한다.
  그 전에 돌리면 첫 단계가 "배포 단위 파일이 없습니다" 로 멈춘다(compose · Dockerfile · `.dockerignore` · `build.gradle` 중 하나라도 없으면 모든 잡이 그렇다).
- 모듈을 추가하면 `Jenkinsfile-<module>` 을 복사해 이름만 바꾼다. config 값이 `deploy-guide.md` §2 이름 규칙과 다르면 첫 단계가 실패한다(`configErrors`).

| 역할 | agent 라벨 | 필요한 것 |
|------|-----------|-----------|
| 빌드 | `builder-backend` | git, JDK 21(Gradle toolchain), **Docker**(Testcontainers — §4), curl |
| 배포 dev | `deploy-backend-dev2` | docker, docker compose v2(`up --wait --wait-timeout` 지원), rsync, curl, **jq 1.6 이상**, bash 4 |
| 배포 prod | `deploy-backend-prod` | 위와 같다 |

멀티브랜치 GitHub 소스 설정(잡 공통):

| 항목 | 값 |
|------|-----|
| Discover branches | **All branches** — "Exclude branches that are also filed as PRs" 로 두면 릴리스 PR 이 열린 동안 `develop` 잡이 꺼진다(hondigagae #1330) |
| Discover pull requests from origin | Merging the pull request with the current target branch revision |
| Discover pull requests from forks | **두지 않는다**(공개 저장소 — 외부인의 Jenkinsfile 이 빌더에서 돈다) |
| Filter by name | Include `main develop PR-*` |
| Property strategy | **Suppress automatic SCM triggering** 을 잡 등록 · 재등록 때 켜 둔다(첫 인덱싱이 브랜치마다 빌드를 띄우지 않게). 첫 배포(§10)를 끝낸 뒤 webhook 트리거만 남긴다 |
| 체크아웃 | 얕은 클론을 쓰지 않는다 — 이전 성공 커밋 diff · `DEPLOY_COMMIT` 조상 확인에 전체 이력이 필요하다 |

### PR 빌드는 PR 의 파이프라인 코드를 그대로 실행한다

origin PR 빌드는 **PR 브랜치의 `Jenkinsfile-*` · `Jenkinsfile.backend-common.groovy` 를 그대로 실행한다.** 저장소 쓰기 권한이 있는 사람은
PR 에서 파이프라인을 바꿔 그 잡이 닿는 credential(Vault AppRole · GitHub App)을 쓰고, 그 잡이 쓰는 노드에서 명령을 돌릴 수 있다.
파이프라인 안의 검사(PR 빌드 배포 금지 · FORCE_DEPLOY 조건)는 이것을 막지 못한다 — 막는 것은 Jenkins 쪽 범위 설정이다. 권장 설정(**사용자 결정 필요**, §10):

1. **환경별 AppRole** — dev · prod 를 다른 AppRole 로 나누고, prod 정책은 `kv/commerce-foundry/backend/prod/*` 읽기만,
   `secret_id_bound_cidrs` · `token_bound_cidrs` 로 prod 배포 agent 에서만 로그인되게 한다.
   파이프라인은 환경별 credential ID 를 지원한다(`vaultCredentialIds` — 지금은 dev · prod 가 같은 ID, prod 를 `commerce-foundry-vault-prod-role-id` 등으로 바꾸면 바로 쓴다).
2. **prod 잡 분리** — prod 배포 잡은 `main` 만 발견하고 PR 발견이 없는 별도 폴더/잡에 두고, prod credential 은 그 폴더 범위(folder-scoped credential)에만 둔다.
3. **prod 노드 제한** — `deploy-backend-prod` 노드는 그 prod 잡만 쓰게 제한한다(Job Restrictions 등).
4. **포크 PR 발견 금지** — 위 표.

## 4. 빌드 범위 — 변경 경로

각 잡은 이번 변경이 자기와 관계있는지 먼저 판단한다. 브랜치 빌드는 **이 잡의 마지막 성공 빌드 커밋**부터, PR 빌드는 대상 브랜치와의 merge-base 부터 본다.

| 바뀐 경로 | 판정 |
|-----------|------|
| `backend/<group>/<module>/**` (자기 모듈 — Dockerfile · compose 포함) | 그 모듈 `own` |
| 자기 `build.gradle` 이 `project(':...')` 로 의존하는 모듈 디렉터리(전이 포함) | `own` |
| `backend/build.gradle` · `settings.gradle` · `gradle.properties` · `gradle/**` · `gradlew` · `gradlew.bat` | 전 모듈 `own` |
| `Jenkinsfile.backend-common.groovy` · 자기 `Jenkinsfile-<module>` | `pipeline` |
| `*.md`(어디에 있든) · `backend/docs/**` · `.env.example` · 로컬 compose · `frontend/**` · `.github/**` · 남의 Jenkinsfile | `none` — 건너뛴다 |

- 의존은 실행할 때 `build.gradle` 을 읽어 계산한다(표로 두지 않는다). `implementation` · `compileOnly` · `testFixtures` 를 가리지 않는다 —
  지금은 security-core 의 `compileOnly project(':core:redis-core')` 때문에 서비스 5종이 redis-core 변경에도 돈다. 읽는 형식은 `backend-ci-matrix.py` 와 같다.
  그 형식 밖의 참조(`projects.x` · `findProject` · `apply from` · 루트 build.gradle 의 project 참조)가 보이면 좁히지 않고 `backend/core/**` 전체를 의존으로 본다.
- **빌드는 fail-open, 배포는 fail-closed 다.** 판단할 수 없으면(따옴표로 감싼 경로 등) `own` 으로 보고 빌드한다. 배포는 §5 가 따로 막는다.
  특히 배포 브랜치에서 **이전 성공 커밋이 없으면**(첫 빌드 · 잡 재등록 · 인덱싱 · 성공 커밋이 저장소에 없음) 무엇이 바뀌었는지 모르므로 자동 배포하지 않는다.
  그 빌드는 "이전 성공 커밋 없음 - 자동 배포 안 함" 으로 성공해 다음 머지부터 기준이 생긴다. 지금 배포하려면 FORCE_DEPLOY(§7).
- `pipeline` 만 바뀐 PR 은 라벨로 한 번 더 거른다 — 다른 배포 단위 라벨만 붙어 있으면 CI 를 건너뛴다.
- 셸에 들어가는 브랜치 이름(`BRANCH_NAME` · `CHANGE_TARGET`)은 `[A-Za-z0-9._/-]` 만 허용하고, 커밋 SHA · PR 번호도 형식을 본 뒤 쓴다.
- 빌드 명령: `CI=true ./gradlew :<group>:<module>:test :<group>:<module>:bootJar --no-daemon --no-build-cache`.
  `CI=true` 라 Docker 가 없는 빌더에서는 Testcontainers 테스트가 건너뛰지 않고 실패한다(`@EnabledWithDockerOrOnCi`, backend-ci 와 같다).
  `--no-build-cache` 는 test 가 FROM-CACHE 로 건너뛰어진 채 초록이 되는 것을 막는다. Gradle 이 성공했는데 테스트 결과가 0건이면 `junit` 단계가 실패시킨다.
- 빌드에는 시크릿을 주지 않는다(이슈 본문의 "Vault 에서 빌드용 env" 와 다르다 — §9). 테스트가 시크릿을 요구하게 되면 테스트가 잘못된 것이다.

## 5. 배포 라벨 게이트 (fail-closed)

머지 빌드는 **이전 성공 빌드 이후 이 잡의 범위(§4 `own` · `pipeline`)에 닿은 커밋마다** 그 브랜치로 머지된 PR 을 찾아 라벨을 합친다
(연달아 머지한 앞 PR 의 라벨을 놓치지 않게 — hondigagae #1269). 그중 `backend-<module>` 이 있어야 배포한다.

| 상황 | 결과 |
|------|------|
| 머지된 PR 에 `backend-<module>` | 배포 |
| 라벨 없음 · 다른 모듈 라벨만 | 생략(SUCCESS, 설명 "배포 라벨 없음") |
| **`backend-core` 만**(core 만 바꾼 PR) | **생략.** `backend-core` 는 배포 라벨이 아니다 |
| PR 없이 직접 push | 생략(라벨 없음과 같다) |
| 이전 성공 커밋 없음(§4) | 생략(라벨을 보지 않는다) — FORCE_DEPLOY 필요 |
| 라벨 조회 실패(credential · API 오류) | 생략 + **UNSTABLE** — 배포가 조용히 멈춘 것을 알아채게 |

- **core 만 바꾼 PR 은 배포할 모듈 라벨을 작성자가 직접 더한다**(`.github/labeler.yml` 의 `backend-core` 주석과 같은 규칙).
  core 변경은 의존 모듈 잡을 모두 돌리지만, 그 모듈들을 실제로 다시 올릴지는 사람이 정한다. 라벨을 빠뜨렸으면 머지 뒤 FORCE_DEPLOY(§7).
- 라벨은 `label.yml` 이 PR 을 **열 때** 경로로 붙인다. 열린 뒤 다른 모듈을 건드렸으면 손으로 더한다.
- **릴리스 PR(develop → main)도 같다.** 릴리스 PR 의 라벨이 prod 배포 대상이다. 릴리스 PR 을 연 뒤 develop 에 들어온 변경은 자동 라벨이 붙지 않으므로,
  머지 전에 라벨이 이번 릴리스의 배포 모듈과 맞는지 본다.
- 라벨 이름은 `.github/labeler.yml` 과 같다. 접두어는 `backend-`, 모듈 이름은 디렉터리 이름이다.
- GitHub API 는 GitHub App credential 로 부른다. 커밋 → PR 조회(`commits/{sha}/pulls`)가 PR 을 돌려주지 않으면 그 브랜치로 닫힌 최근 PR 50건에서
  `merge_commit_sha` 가 같은 것을 찾는다(main 의 릴리스 merge commit 대비).

## 6. 배포 단계

`lock(commerce-foundry-backend-deploy)` 안에서 배포 agent 가 한다. lock 은 백엔드 잡 전부가 공유한다 — 두 모듈이 동시에 같은 호스트 · 네트워크를 만지지 않는다.

**시크릿은 Groovy 를 지나지 않는다.** Groovy 는 compose 를 읽어 키 **이름**(쓸 키 · 필수 키 · 자리값 키)만 정하고, 아래 1~6 은 배포 agent 의
**sh 하나**(`deployShellScript`)가 한다. role_id · secret_id 는 `withCredentials` 가 env 로 넣고, Vault 토큰 · env 값은 그 셸의 변수와
워크스페이스 임시 파일(권한 700 디렉터리)에만 있다. `withEnv` · `writeFile` 같은 단계 인자로 값이 가지 않으므로 Pipeline Steps 화면 · `build.xml` 에 남지 않는다.

1. **Vault 읽기** — `VAULT_ADDR`(Jenkins 전역 env)로 AppRole 로그인(환경별 credential — 지금은 dev · prod 모두 `commerce-foundry-vault-role-id` / `-secret-id`)
   → `kv/commerce-foundry/backend/<env>/env` 전체 → 토큰 폐기. 로그인 본문 · 토큰은 stdin 으로 curl 에 넘긴다(argv 에 올리지 않는다).
   환경당 secret 하나다(모듈별로 쪼개지 않는다). 키 이름은 `backend/.env.example` 과 같다.
2. **검사** (실패하면 키 **이름**만 출력)
   - 필수 키 — compose 의 `:?` 키(= `deploy-guide.md` §4-1 의 "필수" + 네트워크 · 자기 환경 포트)가 없거나 공백뿐이면 실패.
     compose 가 자기 포트 · 반대 포트 · 네트워크를 `:?` 로 보간하지 않으면(§3 · §6 규칙 위반) Groovy 단계에서 실패.
   - compose 가 읽는 키의 값에 작은따옴표 · 줄바꿈이 있으면 실패(`KEY='value'` 로 쓸 수 없다). Vault 키 이름이 env 이름 규칙 밖이면 실패.
   - 자기 환경 포트는 1~65535 정수, `COMMERCE_DOCKER_NETWORK` 는 docker 네트워크 이름 규칙.
3. **`.env.runtime` 쓰기** (`deploy-guide.md` §1) — `$HOME/deploy/commerce-foundry/<group>/<module>` 에 모듈 디렉터리를 rsync 하고 bootJar(정확히 하나)를
   `app.jar` 로 둔 뒤, umask 077 · 권한 600 으로 쓴다.
   - compose 가 보간하는 키(`${KEY...}`)만 쓴다. 다른 모듈의 시크릿(예: auth 의 `JWT_PRIVATE_KEY`)은 그 모듈 배포 경로에 내려가지 않는다.
   - `KEY='value'` · 키 정렬. Vault 의 null 은 빈 값, 숫자 · 불리언은 문자열로.
   - **반대 환경 포트 자리값** — 한 compose 에 dev · prod 서비스가 같이 있고 파일 전체가 보간되므로, 반대 환경의 `<MODULE>_PORT_{DEV|PROD}` 만 `'0'` 으로 채운다.
     그 서비스는 올리지 않아 포트를 잡지 않는다. `_BIND_` · `_MEM_LIMIT_` 은 compose 기본값이 있어 채우지 않는다. Vault 에 반대 환경 포트를 넣을 필요가 없다.
   - `COMMERCE_DOCKER_NETWORK` 는 그 환경 secret 의 값이다. dev · prod 가 같은 이름을 쓰지 않는다.
4. **compose**

   ```bash
   docker compose -p commerce-<module>-<env> --env-file .env.runtime -f docker-compose-<module>.yml config -q
   docker compose -p commerce-<module>-<env> --env-file .env.runtime -f docker-compose-<module>.yml \
     up -d --build --wait --wait-timeout 300 --remove-orphans <module>-<env>
   ```

   - 네트워크가 없으면 만든다. 그 네트워크에 반대 환경 컨테이너(`commerce-*-<반대 env>`)가 붙어 있으면 멈추고, 프로젝트 밖 컨테이너가 있으면 경고한다(`deploy-guide.md` §6).
   - 같은 이름의 컨테이너가 다른 compose 프로젝트에 있으면 지우지 않고 멈춘다.
5. **기동 확인** — `--wait` 가 healthy 를 기다린 뒤 60초 더 두고 상태 · 헬스 · 재시작 횟수를 다시 본다. `running` · `healthy` 여야 하고, 재시작 횟수는
   - compose 가 컨테이너를 **새로 만들었으면**(up 전과 ID 가 다름) 0,
   - **다시 만들지 않았으면**(이미지 · 설정이 같아 그대로 둠) up 전 횟수와 같아야 한다 — 예전 재시작으로 거짓 실패하지 않게.

   `--force-recreate` 는 쓰지 않는다. 바뀐 것이 없으면 컨테이너를 내리지 않는 compose 기본 동작이 맞고, bootJar 는 빌드마다 새로 만들어져 실제 배포에서는 늘 새로 만든다.
   실패하면 컨테이너 로그 마지막 100줄을 **셸 안에서 Vault 값을 가린 뒤** 출력한다(§11 마스킹 규칙 · 한계).
6. **정리** — 셸의 trap 이 Vault 토큰을 폐기하고 `.env.runtime` · 임시 파일을 지운다. trap 이 돌지 않는 경우(SIGKILL)에 대비해 셸 시작에서 남은 파일을 먼저 지우고,
   Groovy `finally` 가 배포 경로의 `.env.runtime` 을 한 번 더 지운 뒤 워크스페이스를 비운다(deleteDir).

## 7. 파라미터 · 강제 배포 · 되돌리기

기본값이면 웹훅 빌드가 정상 동작한다. 수동 실행할 때만 만진다.

| 파라미터 | 기본값 | 용도 |
|----------|--------|------|
| `RUN_TESTS` | `true` | 끄지 않는다. 끄면 로그에 경고를 남긴다 |
| `SKIP_DEPLOY` | `false` | 배포 브랜치에서 빌드만 |
| `FORCE_DEPLOY` | `false` | 변경 경로 · 이전 성공 커밋 · 라벨 게이트를 건너뛰고 이 브랜치 환경으로 배포 |
| `FORCE_DEPLOY_REASON` | (빈 값) | FORCE_DEPLOY 사유. **필수, 200자 이내**. HTML 이스케이프해 빌드 설명에 남는다 |
| `DEPLOY_COMMIT` | (빈 값) | FORCE_DEPLOY 와 함께만. 이 브랜치에 **이미 머지된** 커밋으로 되돌려 배포 |

- **FORCE_DEPLOY 는 develop · main 브랜치 잡에서, 사람이 직접 실행한 빌드에서만** 된다. PR 빌드 · 다른 브랜치 · 사용자 없는 빌드(웹훅 · 스캔)면 실패한다.
  로그와 빌드 설명에 실행 사용자 · UTC 시각 · 환경 · 사유를 남긴다. `SKIP_DEPLOY` 는 그대로 적용된다.
- **main(prod) 의 FORCE_DEPLOY 는 다른 사람의 입력 승인을 받는다.** 빌드 전에 멈추고, 승인자가 모듈 이름을 그대로 입력해야 진행한다(30분 안에 없으면 중단).
  - 승인자는 Jenkins 전역 env `COMMERCE_PROD_DEPLOY_APPROVERS`(쉼표로 구분한 사용자 ID)다. **없으면 실패한다**(fail-closed).
  - **실행자 본인의 승인은 거부한다.** 혼자 운영하는 동안만 전역 env `COMMERCE_PROD_DEPLOY_ALLOW_SELF_APPROVAL=true` 로 허용한다(로그에 "본인 — 허용 설정" 이 남는다).
- 배포 환경은 브랜치 이름으로만 정한다. 다른 브랜치를 prod 로 보내는 파라미터는 없다.
- **되돌리기** — 레지스트리가 없어 이전 이미지 태그가 없다(`deploy-guide.md` §1). 되돌릴 때는 그 브랜치 잡을
  `FORCE_DEPLOY=true` · `DEPLOY_COMMIT=<마지막으로 정상이던 커밋>` · 사유를 주고 실행한다. 그 커밋을 다시 빌드 · 테스트해서 올린다.
  커밋이 그 브랜치의 조상이 아니면(머지되지 않은 커밋) 실패한다. 되돌린 뒤 원인 수정은 평소처럼 PR 로 한다.

## 8. 실패 시 대응

| 증상 | 원인 · 대응 |
|------|-------------|
| `변경 없음 - 생략` | 이번 변경이 그 모듈과 무관하다. 정상이다 |
| `이전 성공 커밋 없음 - 자동 배포 안 함` | 첫 빌드 · 잡 재등록 · 인덱싱 빌드다(§4). 배포가 필요하면 FORCE_DEPLOY, 아니면 다음 머지부터 자동으로 돈다 |
| `배포 라벨 없음 - 빌드 · 배포 생략` | 머지된 PR 에 `backend-<module>` 이 없다(core 만 바꾼 PR 포함, §5). 라벨을 붙이고 FORCE_DEPLOY 로 실행한다 |
| UNSTABLE `라벨 확인 실패(...)` | GitHub App credential 이 없거나 저장소에 App 이 설치되지 않았다. 고친 뒤 FORCE_DEPLOY 로 실행한다 |
| `배포 단위 파일이 없습니다` | 모듈 · Dockerfile · compose · `.dockerignore` 가 그 커밋에 없다. #196 · #212 머지 전에 잡을 돌렸다 |
| `Jenkinsfile config 가 규칙과 다릅니다` | `Jenkinsfile-<module>` 값이 `deploy-guide.md` §2 와 다르다 |
| `브랜치 이름이 허용 문자 ... 밖` | 브랜치 · PR 대상 이름에 셸에 넣을 수 없는 문자가 있다. 이름을 바꾼다 |
| Testcontainers 테스트 실패(Docker 를 못 찾음) | 빌더에 Docker 가 없다. `CI=true` 라 건너뛰지 않는다 — 빌더에 Docker 를 붙인다 |
| `필수 키가 없거나 비어 있습니다: ...` | 그 환경 Vault secret 에 키를 넣는다(`deploy-guide.md` §4-3). 다시 실행한다 |
| `작은따옴표 · 줄바꿈이 있어 ... 쓸 수 없는 키` | 그 값을 작은따옴표 없는 한 줄 값으로 다시 만든다(PEM 은 `security-guide.md` §6 의 한 줄 형식) |
| `반대 환경 컨테이너 ... 가 붙어 있습니다` | dev · prod 가 같은 네트워크를 쓴다. 환경마다 `COMMERCE_DOCKER_NETWORK` 를 다르게 둔다 |
| `--wait` 실패 · `안정되지 않았습니다` | 아래 가린 로그에 원인이 있다. 키 값(DB 주소 · Eureka 호스트 · `EUREKA_PASSWORD` — 7개 모듈 같은 값)을 확인하거나 §7 로 직전 커밋으로 되돌린다 |
| `승인자 ... 가 없어 멈춥니다` · `실행자가 직접 승인` | `COMMERCE_PROD_DEPLOY_APPROVERS` 를 등록하거나 다른 승인자가 승인한다(§7) |
| `jq: command not found` | 배포 agent 에 jq 가 없다(§3) |
| Jenkins 가 노드를 기다리며 멈춤 | 그 라벨의 agent 가 없다(실패가 아니라 대기다) |

배포가 실패하면 이전 성공 커밋이 앞으로 가지 않으므로, 원인을 고친 뒤 다음 머지 빌드(또는 재실행)가 같은 변경을 다시 본다.
재실행이 "변경 없음" 으로 끝나는 경우(같은 커밋 재빌드가 이미 성공한 경우)는 FORCE_DEPLOY 를 쓴다.

## 9. hondigagae 와 다른 점

| 항목 | hondigagae | 여기 | 이유 |
|------|------------|------|------|
| core 변경 | 전 서비스 `own` | `build.gradle` 의존으로 좁힘 | 요구사항. 판단이 안 서면 hondigagae 처럼 core 전체 |
| 문서 | 모듈 안 `*.md` 도 `own` | `*.md` 는 어디든 `none` | 문서만 바뀐 머지는 배포하지 않는다 |
| 이전 성공 커밋 없음 | 전체 빌드 + HEAD PR 라벨로 배포 | 자동 배포 안 함 · FORCE_DEPLOY | 잡 재등록 · 인덱싱이 라벨 붙은 옛 PR 을 다시 배포하는 것을 막는다 |
| 빌드 시 Vault | 빌드 env 를 Vault 에서 받음(이슈 본문도 그렇게 적음) | 받지 않음 · `CI=true` · `--no-build-cache` | 테스트에 시크릿이 필요 없고, 캐시로 test 가 건너뛰어지는 것을 막는다 |
| Vault · `.env.runtime` | Groovy 에서 읽고 `writeFile` | 배포 agent 의 sh 하나 안에서 읽기 · 검사 · 쓰기 · 정리 | 시크릿이 단계 인자 · build.xml 에 남지 않게 |
| `.env.runtime` 형식 | `KEY=value`, secret 전체, 배포 경로에 남김 | `KEY='value'`, compose 가 읽는 키만, 600, 끝나면 삭제 | `deploy-guide.md` §1 |
| 필수 키 | 고정 목록 몇 개 | compose 의 `:?` 키 전부 | compose 가 곧 §4-1 표다 — 두 목록이 갈리지 않게 |
| 기동 확인 | running 폴링 + 45초 | `--wait` healthy + 60초 뒤 재확인, 재시작 횟수는 up 전과 비교 | #196 healthcheck · 거짓 실패 방지 |
| 실패 로그 | 그대로 출력 | 셸 안에서 Vault 값을 가려 출력 | 공개 저장소 · 공유 Jenkins |
| 파라미터 | Vault 주소 · 경로 · credential · lock 을 파라미터로 덮어씀 | 고정. Vault 주소만 전역 env | 덮어쓰기 경로가 곧 다른 secret 을 읽는 경로다. 저장소에 주소를 쓰지 않는다 |
| Vault credential | 하나 | 환경별 ID 맵(지금은 같은 값) | prod AppRole 분리 대비(§3) |
| `TARGET_BRANCH` · `PR_SHA` | 대상 브랜치 · 임의 SHA 를 고를 수 있음 | 없음. `DEPLOY_COMMIT` 은 그 브랜치 조상만 | 아무 커밋을 prod 로 보내는 길을 막는다 |
| prod 강제 배포 | 확인 없음 | 다른 승인자의 입력 승인 + 모듈 이름 재입력, 승인자 미설정이면 실패 | 요구사항 |
| 이전 컨테이너 | 다른 프로젝트면 지움 | 지우지 않고 멈춤 | 새 프로젝트라 옮길 것이 없다 |
| 아티팩트 보관 | jar archive | 하지 않음 | 되돌리기는 커밋 재빌드로 한다(§7). 컨트롤러 디스크를 쓰지 않는다 |
| 외부 호출 | 토큰을 argv 헤더로, 타임아웃 없음 | stdin 헤더 · 타임아웃 · 토큰 폐기 | 프로세스 목록 노출 · 무한 대기를 막는다 |

## 10. 첫 실행 체크리스트 (#197)

Jenkins · Vault 에 등록할 것. 값은 저장소에 쓰지 않는다.

- [ ] Jenkins credential `commerce-foundry-vault-role-id` · `commerce-foundry-vault-secret-id` (Secret text, AppRole) — 정책은 `kv/commerce-foundry/backend/*` 읽기만
- [ ] **(사용자 결정 필요)** §3 권장 설정 ①~④ — 환경별 AppRole(prod 는 `.../prod/*` 만 · bound CIDR), prod 잡 · 폴더 분리와 folder-scoped prod credential,
      `deploy-backend-prod` 노드 제한, 포크 PR 발견 금지. prod 를 나누면 `vaultCredentialIds` 의 prod ID 만 바꾼다
- [ ] Jenkins credential `github-app-followfollowme-jenkins` 를 쓰고, GitHub App 을 `commerce-foundry` 저장소에 설치(PR · 라벨 읽기)
- [ ] Jenkins 전역 env `VAULT_ADDR` (그리고 AppRole mount 가 `approle` 이 아니면 `VAULT_AUTH_PATH`)
- [ ] Jenkins 전역 env `COMMERCE_PROD_DEPLOY_APPROVERS` (운영 강제 배포 승인자 — 없으면 prod FORCE_DEPLOY 가 실패한다).
      혼자 운영하는 동안만 `COMMERCE_PROD_DEPLOY_ALLOW_SELF_APPROVAL=true`
- [ ] Markup Formatter 는 **Plain text**(§11)
- [ ] 플러그인: Pipeline Utility Steps(`readJSON`), Lockable Resources(`lock`), JUnit, GitHub Branch Source
- [ ] agent `builder-backend`(Docker 포함) · `deploy-backend-dev2` · `deploy-backend-prod`(jq 포함) — §3 도구
- [ ] Vault secret `kv/commerce-foundry/backend/dev/env` · `.../prod/env` — `deploy-guide.md` §4-3 의 키, 환경마다 다른 `COMMERCE_DOCKER_NETWORK`.
      #248 부터 `EUREKA_HOST`(`commerce-service-discovery-<env>`) · `EUREKA_PASSWORD`(`openssl rand -hex 32`, 환경마다 다른 값)가 필요하고 `EUREKA_DEFAULT_ZONE` 은 쓰지 않는다
- [ ] 멀티브랜치 잡 7개(§3, operations-service 는 #212 뒤) — 소스 설정은 §3 표, **Suppress automatic SCM triggering** 을 켠 채 등록
- [ ] **첫 배포** — 잡을 등록한 뒤 각 잡의 `develop` 을 **FORCE_DEPLOY** 로 한 번씩, 순서대로 실행한다:
      service-discovery → api-gateway → auth → product · delivery → order · payment (`deploy-guide.md` §5).
      첫 빌드는 이전 성공 커밋이 없어 자동 배포하지 않으므로(§4) 이 절차가 유일한 첫 배포 경로다. 끝나면 트리거 억제를 풀어 webhook 빌드를 받는다
- [ ] 첫 배포 뒤 §11 의 Pipeline Steps · build.xml 확인

## 11. 보안 체크

- [ ] Jenkinsfile · groovy · 이 문서에 주소 · 호스트 · 포트 값 · 시크릿이 없다(`git grep` 으로 확인)
- [ ] **첫 배포 뒤 Pipeline Steps 화면(빌드 → Pipeline Steps)과 컨트롤러의 `build.xml` · `workflow/*.xml` 에 Vault 값 · 토큰이 없는지** 확인한다
      (단계 인자에는 키 이름 · 경로만 있어야 한다 — §6)
- [ ] 빌드 로그에 Vault 값이 없다 — sh 는 `set +x`, role_id · secret_id 는 Jenkins 가 가리고, 값은 셸 안에서만 쓴다
- [ ] 배포 경로 · 워크스페이스에 `.env.runtime` · `.deploy-secrets/` 가 남지 않는다
- [ ] Markup Formatter 가 **Plain text** 다 — 빌드 설명에 사람이 넣은 문자열(FORCE_DEPLOY 사유 · 사용자 ID)이 들어간다. 파이프라인도 HTML 이스케이프한다
- [ ] 배포 호스트의 docker 권한은 시크릿 열람 권한이다(`docker inspect` — `deploy-guide.md` §1)
- [ ] AppRole 정책이 `kv/commerce-foundry/backend/*` 밖을 읽지 않는다(prod 를 나눴으면 prod 는 `.../prod/*` 만)

**실패 로그 마스킹 규칙과 한계** — 컨테이너 로그는 셸 안에서 jq 로 가린 뒤에만 출력한다(가릴 목록이 없거나 jq 가 실패하면 출력하지 않는다).

- 시크릿 이름(`*_PASSWORD` · `*_SECRET*` · `*_KEY` · `*_TOKEN`)의 값은 길이와 무관하게, 그 밖의 값은 8자 이상만 `****` 로 바꾼다.
- 여러 줄 값(PEM)은 값 전체와 줄마다 가린다. 긴 값부터 바꿔 부분 겹침을 막는다.
- **못 가리는 것**: 값의 일부만 찍힌 경우(잘린 키 · 부분 문자열), 인코딩이 바뀐 값(base64 · URL 인코딩 · 이스케이프), 8자 미만의 비시크릿 이름 값,
  한 줄 안에 줄바꿈 없이 이어 붙은 PEM 조각, Vault 에 없는 값(앱이 만든 토큰). 그래서 실패 로그는 원인 파악용으로만 보고, 앱은 설정 값을 로그에 찍지 않는다.
