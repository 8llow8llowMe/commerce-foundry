# Frontend Deploy Guide

스토어프론트 · 어드민을 컨테이너로 배포하는 규칙이다. PR 게이트는 GitHub Actions(`frontend-ci`), 머지 후 빌드 · 배포는
Jenkins 멀티브랜치 파이프라인이 맡는다(#199). **백엔드 규약을 그대로 따른다** — 이름 · 포트 키 · `.env.runtime` · compose 명령은
`backend/docs/deploy-guide.md`(#196), 브랜치 → 환경 · 라벨 게이트 · FORCE_DEPLOY · 승인은 `backend/docs/jenkins-cicd-guide.md`(#198)와 같다.
이 문서는 프론트가 다른 점과 프론트에만 있는 것을 적는다.

> **공개 저장소다.** 서버 주소 · 호스트 이름 · 도메인 · 포트 실값 · 서브넷 · 시크릿을 이 문서와 compose · Jenkinsfile 에 쓰지 않는다.
> Infra 등록(#197)에서 정할 값은 `⏳ #197` 로 표시했다. 값을 미리 지어 넣지 않는다.

## 1. 산출물 · 이름

| 항목 | 규칙 | storefront | admin |
|------|------|------------|-------|
| Dockerfile | `apps/<app>/<app>.Dockerfile` | `storefront.Dockerfile` | `admin.Dockerfile` |
| 빌드 컨텍스트 ignore | `apps/<app>/<app>.Dockerfile.dockerignore` | | |
| compose 파일 | `apps/<app>/docker-compose-<app>.yml` | | |
| compose 서비스 | `<app>-{dev\|prod}` | `storefront-dev` | `admin-dev` |
| 이미지 | `commerce-<app>:{dev\|prod}` | `commerce-storefront:dev` | `commerce-admin:dev` |
| 컨테이너 · compose 프로젝트 | `commerce-<app>-{dev\|prod}` (`-p` 로 반드시 준다) | `commerce-storefront-dev` | `commerce-admin-dev` |
| Jenkins 잡 · Script Path | `commerce-foundry-<app>` · `Jenkinsfile-<app>` | | |
| 배포 라벨 | `frontend-<app>` | `frontend-storefront` | `frontend-admin` |
| SSR 확인 경로 | Jenkinsfile 의 `smokePath` | `/` | `/login` |

- 두 앱 모두 `next.config.ts` 에 `output: 'standalone'` 이고, 추적 루트(`outputFileTracingRoot`)는 `frontend/` 다.
  번들 안 서버 진입점은 `apps/<app>/server.js` 다.
- **이미지 안에서 빌드하지 않는다.** 빌드 agent 가 만든 아래 세 경로만 담는다. 빌드 컨텍스트는 `frontend/`(배포 경로에서는 그 배치를 그대로 옮긴 디렉터리)이고,
  `<app>.Dockerfile.dockerignore` 가 이 세 경로 말고는 아무것도 보내지 않는다(소스 · `node_modules` · `.env*` 차단).
  - `apps/<app>/.next/standalone` → `/app`
  - `apps/<app>/.next/static` → `/app/apps/<app>/.next/static`
  - `apps/<app>/public` → `/app/apps/<app>/public` — 디렉터리가 없으면 COPY 가 실패하므로 파이프라인이 `mkdir -p` 한다.
- **이미지 이름은 dev · prod 가 같고 환경은 태그로 가른다.** `NEXT_PUBLIC_*` 가 박힌 번들이라 dev 이미지를 prod 로 쓰면 안 된다.
- 실행 사용자는 공식 node 이미지의 `node`(uid 1000)다. 코드는 root 소유(읽기 전용)이고 `.next/cache` 만 쓸 수 있다.
- 컨테이너 포트는 두 앱 모두 `3000`(`PORT`), `HOSTNAME=0.0.0.0` 이다. 호스트 포트는 compose 가 env 로 받는다(§3).

### 빌드 agent 와 배포 호스트의 조건

standalone 의 `node_modules` 에는 `sharp`(이미지 최적화) 네이티브 바이너리가 **빌드 머신의 OS · CPU 로** 들어간다.

- 런타임 베이스는 glibc 계열 `node:24-bookworm-slim` 이다. 빌드 agent 도 glibc 리눅스여야 한다(alpine · macOS 빌드 산출물 금지).
  파이프라인이 빌드 전에 확인한다(Node 메이저 = `frontend/.nvmrc`, glibc).
- 빌드 agent 와 배포 호스트의 CPU 아키텍처(amd64 / arm64)가 같아야 한다. 빌드가 `uname -m` 을 묶음에 적고, 배포 단계가 배포 호스트와 비교해 다르면 멈춘다.

## 2. 환경변수 — 빌드 시점 vs 런타임

키 이름은 `frontend/.env.example` 이 정본이다. 실값은 Vault `kv/commerce-foundry/frontend/{dev,prod}/env` 에 있다 — **환경당 secret 하나**,
두 앱이 같이 쓴다. 백엔드 secret(`kv/commerce-foundry/backend/...`)과 나눠 프론트 잡이 백엔드 시크릿을 읽지 못하게 한다.
**Vault 키 이름 = 환경변수 이름 = compose 의 키 이름**이다.

| 키 | 앱 | 빌드 | 런타임(compose) | 비고 |
|----|----|:---:|:---:|------|
| `NEXT_PUBLIC_SITE_URL` | storefront | **필수** | - | 번들에 박힌다. 비면 canonical · hreflang 이 localhost 가 되므로 storefront 배포 빌드는 없으면 멈춘다 |
| `LOCALE_HOSTS` | storefront | O | `:-` | proxy 가 읽는다. 빌드 · 실행에 같은 값 |
| `ADMIN_ORIGIN` | storefront | O | `:-` | 메인 전시 미리보기(#71)를 iframe 으로 여는 어드민 origin. proxy · 미리보기 화면이 읽어 빌드 · 실행에 같은 값. 비면 운영에서는 미리보기를 닫는다(`frame-ancestors 'none'`) |
| `STOREFRONT_ORIGIN` | admin | - | `:-` | 미리보기가 여는 스토어프론트 origin(요청 시점에 읽어 빌드에는 쓰지 않는다). 비면 운영에서는 미리보기 버튼이 없다 |
| `BUSINESS_*` (6개) | storefront | O | `:-` | 푸터. 정적으로 미리 그리는 페이지에 들어갈 수 있어 빌드 · 실행에 같은 값. 비면 그 줄을 뺀다 |
| `GATEWAY_URL` | 둘 다 | O | **`:?` 필수** | `http://commerce-api-gateway-<env>:8000` (같은 네트워크의 게이트웨이 컨테이너 이름, §3) |
| `CLIENT_IP_HEADER` | 둘 다 | - | `:-` | 앞단 구성에 맞춘다(§8). 비면 앱 기본값 `x-real-ip` |
| `COMMERCE_DOCKER_NETWORK` · 포트 · 바인드 · 메모리 | 둘 다 | - | 네트워크 · 자기 포트 `:?` | §3 |
| `NODE_ENV` | 둘 다 | - | 이미지가 `production` 으로 고정 | 어드민 쿠키가 `Secure` 가 된다 — **HTTPS 앞단 없이 http 로 열면 어드민 로그인이 안 된다** |

**빌드 시점 키** — `turbo.json` 의 `build.env` 가 목록의 정본이다. turbo 는 strict env 모드라 여기 없는 변수는 `next build` 에 전달되지 않는다.
배포 빌드(develop · main 의 배포 대상 빌드)만 빌드 agent 의 sh 하나에서 Vault 를 읽어 이 목록에 맞는 키를 그 셸의 env 로 넘긴다.
PR · CI 빌드는 Vault 를 읽지 않는다(앱 기본값으로 빌드, 배포하지 않음). 빌드 시점 키를 추가하면 `turbo.json` · `.env.example` · 이 표를 같이 고친다.
storefront 의 필수 빌드 키는 `Jenkinsfile-storefront` 의 `buildRequiredKeys` 다.

**런타임 키** — compose 의 `environment` 가 읽는 키만 `.env.runtime` 에 쓴다(백엔드 `planRuntimeKeys` 와 같다). 그래서 admin 배포에는
`BUSINESS_*` 가 내려가지 않는다. 앱이 새 서버 env 를 읽기 시작하면 그 앱 compose 의 `x-<app>-environment` 에 같이 더한다 —
빠뜨리면 컨테이너에는 그 키가 없다. 보간 규칙은 백엔드와 같다(`deploy-guide.md` §4-2: 필수는 `:?`, 기본값이 빈 값이면 `:-`).

- `.env.runtime` 은 `KEY='value'`(작은따옴표) · 권한 600 이고, 배포 경로가 아니라 **워크스페이스의 700 디렉터리**에 만든다
  (compose 는 `--env-file` 절대 경로로 읽는다). 백엔드와 달리 프론트 배포 경로는 docker 빌드 컨텍스트라서다. 성공 · 실패와 무관하게 지운다.
- 파이프라인과 로그에 값을 찍지 않는다. 필수 키 검사는 **이름만** 출력한다.

## 3. 포트 · 네트워크 · 메모리

| 앱 | 컨테이너 포트 | 호스트 포트 키 (값 ⏳ #197) | 바인드 키 (기본 루프백) | 메모리 상한 키 (기본) |
|----|---------------|-----------------------------|-------------------------|-----------------------|
| storefront | 3000 | `STOREFRONT_PORT_{DEV,PROD}` | `STOREFRONT_BIND_{DEV,PROD}` | `STOREFRONT_MEM_LIMIT_{DEV,PROD}` (512m) |
| admin | 3000 | `ADMIN_PORT_{DEV,PROD}` | `ADMIN_BIND_{DEV,PROD}` | `ADMIN_MEM_LIMIT_{DEV,PROD}` (512m) |

- **키 이름은 백엔드와 같은 `<APP>_PORT_<ENV>` 형식이다.** 한 compose 에 dev · prod 가 같이 있고 파일 전체가 보간되므로, 파이프라인이 반대 환경
  포트만 `'0'` 자리값으로 채운다. **Vault 에는 자기 환경 포트만 넣는다.** `_BIND_` · `_MEM_LIMIT_` 은 compose 기본값이 있어 채우지 않는다.
- **바인드 기본값은 루프백(닫힌 쪽)이다.** 같은 호스트의 Nginx 가 앞단이면 그대로 둔다. 다른 호스트의 LB 가 앞단이면 LB 가 닿는 사설 주소만 넣는다.
  BFF 포트를 외부에 직접 열면 안 된다 — BFF 는 앞단이 덮어쓴 헤더로 클라이언트 IP 를 정하므로(§8), 앞단을 거치지 않고 닿는 요청은 그 헤더를 마음대로 정한다.
  Docker 게시 포트는 ufw · firewalld 를 우회한다(`deploy-guide.md` §6).
- 메모리 기본 512m 은 로컬 측정(유휴 약 50MiB) 기준 출발점이다.
- **네트워크** — 프론트 컨테이너는 **같은 환경의 백엔드 외부 네트워크(`COMMERCE_DOCKER_NETWORK`)** 에 붙어 게이트웨이를 컨테이너 이름으로 부른다
  (`GATEWAY_URL=http://commerce-api-gateway-<env>:8000`). 게이트웨이 호스트 포트를 거치지 않으므로 게이트웨이 바인드는 루프백 그대로 둔다.
  - dev · prod 는 네트워크를 공유하지 않는다. 배포 단계가 반대 환경 컨테이너(`commerce-*-<반대 env>`)가 붙어 있으면 멈춘다.
  - **프론트 파이프라인은 네트워크를 만들지 않는다.** 없으면 게이트웨이도 없다는 뜻이라 "백엔드를 먼저 배포" 로 멈춘다.
    게이트웨이 컨테이너가 네트워크에 없으면 경고만 남기고 배포한다(SSR 첫 화면은 게이트웨이 없이도 뜨고, BFF 요청만 503).
  - 같은 네트워크라 BFF 는 Eureka · 서비스에도 닿는다(백엔드 `deploy-guide.md` §6 은 "백엔드 컨테이너만" 이라고 적고 있다).
    컨테이너 이름이 `commerce-*-<env>` 라 백엔드 배포 검사에도 걸리지 않는다. 더 좁히려면 게이트웨이 · BFF 만 붙는 별도 네트워크가 필요하고
    게이트웨이 compose 변경이 따른다 — #135 코멘트(§8)에 후속으로 적었다.

## 4. 배포 라벨 (`.github/labeler.yml`)

| 라벨 | 붙는 경로 | 배포 라벨인가 |
|------|-----------|:---:|
| `frontend-web` | `frontend/**` | 아니다(영역 라벨 — 이슈 · PR 분류) |
| `frontend-storefront` | `frontend/apps/storefront/**` · `frontend/packages/**` · `frontend/*`(빌드 설정) — `*.md` · `.env.example` 제외 | O |
| `frontend-admin` | `frontend/apps/admin/**` · `frontend/packages/**` · `frontend/*` — 같은 제외 | O |

- 머지 빌드는 **이전 성공 빌드 이후 이 잡의 범위에 닿은 커밋마다** 머지된 PR 의 라벨을 합치고, 그중 `frontend-<app>` 이 있어야 배포한다(fail-closed).
  상황별 결과(라벨 없음 · 직접 push · 조회 실패 UNSTABLE)는 `jenkins-cicd-guide.md` §5 와 같다.
- **공용 패키지 · 빌드 설정 변경은 두 라벨이 모두 붙는다.** 백엔드 `backend-core` 와 다르다 — 공용 패키지는 두 앱 번들에 모두 들어가고,
  다시 올리지 않으면 앱이 옛 패키지로 남는다. 한 앱만 올리려면 PR 에서 다른 라벨을 손으로 뺀다.
- `label.yml` 은 PR 을 **열 때만** 돈다. 연 뒤 다른 앱을 건드렸으면 라벨을 손으로 더한다. 릴리스 PR(develop → main)의 라벨이 prod 배포 대상이다.
- 빌드 범위(변경 경로) 판정도 라벨과 같은 경로 규칙이다 — `frontend/apps/<app>/**` · `frontend/packages/**` · `frontend/` 바로 아래 파일은 `own`,
  `Jenkinsfile.frontend-common.groovy` · 자기 `Jenkinsfile-<app>` 은 `pipeline`, 그 밖(`*.md` · `frontend/docs/**` · `.env.example` · 다른 앱 · `backend/**` · `.github/**`)은 `none`.

## 5. Jenkins 파이프라인

```text
작업 PR → develop
  Actions frontend-ci (필수)   Jenkins PR-N: 영향 있는 앱 잡만 검사 + build, 배포 없음
develop 에 Rebase and merge
  Jenkins develop 잡 (앱마다)
    배포 문맥 확인 (builder-frontend)  브랜치 → 환경, 이전 성공 커밋, 변경 경로, 머지된 PR 라벨, turbo.json 빌드 키 이름
    검사 · 빌드    (builder-frontend)  도구 확인 → pnpm install --frozen-lockfile → format:check · lint · typecheck · test:ci · 테스트 건수
                                        → sh 하나: Vault 빌드 키 → turbo run build --filter=@cf/<app> → standalone 확인 · public · 아키텍처 기록
                                        → 묶음(tar.gz) stash
    dev 환경 배포  (deploy-frontend-dev2, lock commerce-foundry-frontend-deploy)
      sh 하나: 아키텍처 비교 → Vault kv/commerce-foundry/frontend/dev/env → 검사 → .env.runtime → 배포 경로 rsync
               → 네트워크 확인 → compose config -q → up -d --build --wait → 재시작 확인 → SSR 첫 화면 200 → 정리
릴리스 PR develop → main (merge commit)
  Jenkins main 잡 — 같은 단계로 prod (deploy-frontend-prod)
```

| 역할 | agent 라벨 ⏳ #197 | 필요한 것 |
|------|--------------------|-----------|
| 빌드 | `builder-frontend` | glibc 리눅스, git, Node 24(`.nvmrc`) + corepack, curl, jq — **배포 호스트와 같은 CPU 아키텍처** |
| 배포 dev | `deploy-frontend-dev2` | docker, docker compose v2(`--wait` 지원), rsync, tar, curl, jq 1.6+, bash 4 — **같은 환경 게이트웨이와 같은 docker 호스트** |
| 배포 prod | `deploy-frontend-prod` | 위와 같다 |

배포 agent 는 백엔드 배포 노드에 라벨을 더 붙여도 된다(같은 docker 호스트여야 하므로 보통 그렇다).

### 백엔드 파이프라인과 다른 점

| 항목 | 백엔드 | 프론트 | 이유 |
|------|--------|--------|------|
| 빌드 | Gradle test + bootJar | pnpm 검사 4종 + 테스트 건수 + `turbo run build --filter` | frontend-ci 와 같은 검사 |
| 빌드 시 Vault | 읽지 않음 | **배포 빌드만** 빌드 시점 키를 읽음(sh 하나 안) | `NEXT_PUBLIC_*` · `BUSINESS_*` 는 빌드에 박힌다 |
| Vault 경로 · AppRole | `kv/commerce-foundry/backend/*`, `commerce-foundry-vault-*` | `kv/commerce-foundry/frontend/*`, `commerce-foundry-frontend-vault-*` | 프론트 잡이 백엔드 시크릿을 읽지 않게 |
| 배포 lock | `commerce-foundry-backend-deploy` | `commerce-foundry-frontend-deploy` | 프론트는 네트워크를 만들지 않고 백엔드 컨테이너를 건드리지 않는다 |
| 산출물 전달 | jar · Dockerfile · compose stash | tar.gz 하나 stash | pnpm standalone 의 심볼릭 링크를 그대로 옮긴다 |
| 배포 경로 | `$HOME/deploy/commerce-foundry/<group>/<module>` | `$HOME/deploy/commerce-foundry/frontend/<app>` (`frontend/` 배치) | compose 빌드 컨텍스트가 `../..` |
| `.env.runtime` 위치 | 배포 경로 | 워크스페이스 700 디렉터리 | 배포 경로가 docker 빌드 컨텍스트다 |
| 네트워크 | 없으면 만든다 | 없으면 멈춘다 | 게이트웨이가 먼저다 |
| 기동 확인 | `--wait` healthy + 60초 뒤 재확인 | healthy + 30초 뒤 재확인 + **SSR 첫 화면 200**(컨테이너 안에서) | healthcheck 는 프로세스만 본다 |
| 아키텍처 | 확인 없음(jar) | 빌드 · 배포 호스트 `uname -m` 비교 | sharp 네이티브 바이너리 |
| 의존 좁히기 | `build.gradle` 의존 계산 | 공용 패키지 변경은 두 앱 모두 | 두 앱이 패키지를 모두 쓴다 |

파라미터(`RUN_TESTS` · `SKIP_DEPLOY` · `FORCE_DEPLOY` · `FORCE_DEPLOY_REASON` · `DEPLOY_COMMIT`), 강제 배포 조건, prod 강제 배포 승인
(`COMMERCE_PROD_DEPLOY_APPROVERS`, 확인 입력은 앱 이름), 되돌리기, 멀티브랜치 소스 설정, PR 빌드의 credential 범위는 `jenkins-cicd-guide.md` §3 · §7 과 같다.

## 6. 첫 실행 체크리스트 (#197)

값은 저장소에 쓰지 않는다. 백엔드 체크리스트(`jenkins-cicd-guide.md` §10)에 더해 프론트에 필요한 것만 적는다.

- [ ] Jenkins credential `commerce-foundry-frontend-vault-role-id` · `commerce-foundry-frontend-vault-secret-id` (Secret text, AppRole) —
      정책은 `kv/commerce-foundry/frontend/*` 읽기만. 빌드 agent 도 이 credential 을 쓴다(빌드 시점 키). prod 를 나누면 `vaultCredentialIds` 의 prod ID 만 바꾼다
- [ ] Vault secret `kv/commerce-foundry/frontend/dev/env` · `.../prod/env` — §2 · §3 의 키. `COMMERCE_DOCKER_NETWORK` 는 같은 환경 백엔드 secret 과 **같은 값**,
      `GATEWAY_URL` 은 `http://commerce-api-gateway-<env>:8000`, 자기 환경 포트 2개(`STOREFRONT_PORT_<ENV>` · `ADMIN_PORT_<ENV>`) ⏳ 포트 대역
- [ ] agent `builder-frontend` · `deploy-frontend-dev2` · `deploy-frontend-prod` — §5 도구 · 아키텍처 · 같은 docker 호스트 ⏳
- [ ] 앞단(Nginx · LB · TLS) 구성과 `CLIENT_IP_HEADER` — §8 ⏳. 어드민은 HTTPS 필수
- [ ] GitHub 라벨 `frontend-storefront` · `frontend-admin` 생성(설명 · 색) — 없으면 labeler 가 기본값으로 만든다
- [ ] 멀티브랜치 잡 2개(`commerce-foundry-storefront` · `commerce-foundry-admin`) — 소스 설정은 `jenkins-cicd-guide.md` §3 표, **Suppress automatic SCM triggering** 을 켠 채 등록
- [ ] **첫 배포** — 같은 환경 백엔드(최소 service-discovery · api-gateway)를 먼저 올린 뒤, 각 잡의 `develop` 을 **FORCE_DEPLOY** 로 한 번씩 실행한다.
      첫 빌드는 이전 성공 커밋이 없어 자동 배포하지 않는다. 끝나면 트리거 억제를 풀어 webhook 빌드를 받는다
- [ ] 첫 배포 뒤 확인 — SSR 첫 화면 응답(#199 완료 기준), Pipeline Steps · `build.xml` 에 Vault 값 없음, 배포 경로 · 워크스페이스에 `.env.runtime` · `.build-secrets` · `.deploy-secrets` 없음

## 7. 실패 시 대응

백엔드 표(`jenkins-cicd-guide.md` §8)와 같은 메시지는 같은 대응이다. 프론트에만 있는 것:

| 증상 | 원인 · 대응 |
|------|-------------|
| `빌드 agent 의 Node 메이저 ... 가 frontend/.nvmrc ... 와 다릅니다` · `glibc 리눅스여야 합니다` | 빌드 agent 의 도구를 맞춘다(§5) |
| `빌드 필수 키가 없거나 비어 있습니다: NEXT_PUBLIC_SITE_URL` | 그 환경 Vault secret 에 넣고 다시 실행한다 |
| `turbo.json build.env 의 ... 를 해석할 수 없습니다` | `build.env` 는 대문자 키 또는 끝 와일드카드(`BUSINESS_*`)만 쓴다 |
| `빌드 agent 아키텍처 ... 와 배포 호스트 ... 가 다릅니다` | `builder-frontend` 를 배포 호스트와 같은 아키텍처로 둔다(§1) |
| `네트워크 ... 가 없습니다. 같은 환경의 백엔드(api-gateway)를 먼저 배포합니다` | 백엔드를 먼저 배포하거나 `COMMERCE_DOCKER_NETWORK` 값을 백엔드 secret 과 맞춘다 |
| 경고 `commerce-api-gateway-<env> 가 없습니다` | 배포는 됐다. BFF 요청은 게이트웨이가 뜰 때까지 503 이다 |
| `SSR 첫 화면 ... 가 200 이 아닙니다` | 아래 가린 로그를 본다. 307/308 이면 `smokePath` 가 리다이렉트 경로다 |
| 테스트 건수 0 으로 실패 | `frontend-test-count.mjs` 가 필수 패키지 테스트를 찾지 못했다. frontend-ci 와 같은 원인이다 |

## 8. 앞단 · 클라이언트 IP · `GATEWAY_TRUSTED_PROXIES` (#134 → #135)

```text
브라우저 ─TLS─▶ 앞단(Nginx · LB) ─▶ BFF(commerce-<app>-<env>, 호스트 포트) ─docker 네트워크─▶ commerce-api-gateway-<env>:8000
                X-Real-IP 를 덮어씀     X-Forwarded-For 를 그 한 값으로 덮어씀           소켓 원격 주소 = BFF 컨테이너 IP
```

**BFF 가 믿는 헤더 (`CLIENT_IP_HEADER`)** — 앞단이 **반드시 덮어쓰는** 헤더여야 한다(`docs/architecture.md` §2).

| 앞단 구성 | `CLIENT_IP_HEADER` | 앞단 설정 |
|-----------|--------------------|-----------|
| 같은 호스트의 Nginx 가 TLS 를 받는다 (기본 권장) | 비움(= `x-real-ip`) | `proxy_set_header X-Real-IP $remote_addr;` |
| L7 LB → Nginx → BFF | 비움(= `x-real-ip`) | Nginx `set_real_ip_from <LB 대역>; real_ip_header X-Forwarded-For; real_ip_recursive on;` 뒤 위 줄 |
| `X-Forwarded-For` 를 덧붙이는 L7 LB 한 단 → BFF | `x-forwarded-for` (맨 오른쪽을 쓴다) | LB 가 덧붙이게 둔다 |
| 앞단 없이 BFF 가 직접 받는다 | `none` | 쓰지 않는다 — §3 바인드 규칙 위반이다 |

- 어느 구성이든 **BFF 포트는 앞단에서만 닿게 둔다**(§3 바인드). 앞단을 우회해 BFF 에 닿으면 누구나 그 헤더를 정해 버킷을 고른다.

**BFF 송신 주소 → `GATEWAY_TRUSTED_PROXIES`** — BFF 와 게이트웨이는 같은 docker 네트워크라 NAT 없이 붙는다. 게이트웨이가 보는 원격 주소는
**BFF 컨테이너의 그 네트워크 IP** 다. 컨테이너를 다시 만들 때마다 바뀔 수 있으므로 값은 **그 환경 `COMMERCE_DOCKER_NETWORK` 의 서브넷(CIDR)** 이다.

- 네트워크를 **서브넷을 지정해 미리 만든다**(`docker network create --subnet <CIDR> <이름>`). 파이프라인이 자동으로 만들면 docker 가 서브넷을 고르고,
  네트워크를 다시 만들 때 바뀐다. 서브넷 값은 Infra 에서 정한다 ⏳ #197.
- 그 서브넷에는 백엔드 컨테이너도 있다. 백엔드는 게이트웨이를 부르지 않지만, 그 네트워크의 컨테이너는 누구든 `X-Forwarded-For` 로 버킷을 고를 수 있다 —
  네트워크에 이 프로젝트 컨테이너만 둔다는 전제(`deploy-guide.md` §6)가 이 위험을 막는다. 더 좁히려면 BFF 고정 IP(`/32` 두 개)나 게이트웨이 · BFF 전용 네트워크를 쓴다.
- 이 값은 게이트웨이 env 다(#135). 게이트웨이 compose 가 아직 `GATEWAY_CLIENT_IP_ENABLED` 를 넘기지 않아 배포 환경에서 켤 수 없다 — #135 에 함께 넘긴다.

## 9. 로컬에서 이미지 확인

macOS 에서 바로 빌드한 standalone 은 리눅스 컨테이너에서 쓸 수 없다(§1). 리눅스 컨테이너에서 빌드한다. 저장소 밖 복사본에서 한다
(호스트의 `node_modules` 를 리눅스용으로 덮어쓰지 않게).

```bash
# frontend/ 복사본(node_modules · .next 제외)에서
docker run --rm -v "$PWD":/w -w /w node:24-bookworm-slim \
  sh -c 'corepack enable && pnpm install --frozen-lockfile && pnpm build'
mkdir -p apps/storefront/public apps/admin/public
docker network create cf-local
# env 파일은 저장소 밖에 새로 만든다. 값은 작은따옴표로 감싼다
printf "%s\n" "COMMERCE_DOCKER_NETWORK='cf-local'" "GATEWAY_URL='http://commerce-api-gateway-dev:8000'" \
  "STOREFRONT_PORT_DEV='13000'" "STOREFRONT_PORT_PROD='0'" > /tmp/cf-storefront.env
docker compose -p commerce-storefront-dev --env-file /tmp/cf-storefront.env \
  -f apps/storefront/docker-compose-storefront.yml up -d --build --wait storefront-dev
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:13000/   # 200 (게이트웨이 없이도 SSR 첫 화면)
```

- admin 은 `ADMIN_PORT_DEV` · `ADMIN_PORT_PROD` 로 같은 명령이다(확인 경로 `/login`).
- 정리: `docker compose -p commerce-storefront-dev -f apps/storefront/docker-compose-storefront.yml down`, `docker network rm cf-local`.
