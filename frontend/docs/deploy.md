# Frontend Deploy Guide

스토어프론트 · 어드민을 컨테이너로 배포하는 규칙이다. PR 게이트는 GitHub Actions(`frontend-ci`), 머지 후 빌드 · 배포는
Jenkins 가 맡는다(#199). 백엔드와 같은 방식이다 — 레지스트리 없이 배포 agent 가 호스트에서 `docker compose up -d --build` 로
이미지를 만든다.

> **아직 정해지지 않은 값**은 `⏳` 로 표시했다. 백엔드 compose(#196) · Infra 등록(#197) · 백엔드 Jenkins(#198)에서 정해지면
> 이 문서와 compose 를 같이 고친다. 값을 미리 지어 넣지 않는다.

## 1. 산출물

| 앱 | Dockerfile | compose | 이미지 | 컨테이너 / compose project |
|----|-----------|---------|--------|---------------------------|
| storefront | `apps/storefront/storefront.Dockerfile` | `apps/storefront/docker-compose-storefront.yml` | `commerce-storefront` | `commerce-storefront-{dev\|prod}` |
| admin | `apps/admin/admin.Dockerfile` | `apps/admin/docker-compose-admin.yml` | `commerce-admin` | `commerce-admin-{dev\|prod}` |

- 두 앱 모두 `next.config.ts` 에 `output: 'standalone'` 이고, 추적 루트(`outputFileTracingRoot`)는 `frontend/` 다.
  번들 안 서버 진입점은 `apps/<app>/server.js` 다.
- **이미지 안에서 빌드하지 않는다.** 빌드 agent 가 만든 아래 세 경로만 담는다. 빌드 컨텍스트는 `frontend/` 이고,
  `<app>.Dockerfile.dockerignore` 가 이 세 경로 말고는 아무것도 보내지 않는다(소스 · `node_modules` · `.env*` 차단).
  - `apps/<app>/.next/standalone` → `/app`
  - `apps/<app>/.next/static` → `/app/apps/<app>/.next/static`
  - `apps/<app>/public` → `/app/apps/<app>/public` — **디렉터리가 없으면 COPY 가 실패한다.** 지금은 두 앱 모두 `public/` 이
    없으므로 빌드 단계에서 `mkdir -p apps/<app>/public` 을 먼저 한다.
- 실행 사용자는 공식 node 이미지의 `node`(uid 1000)다. 코드는 root 소유(읽기 전용)이고 `.next/cache` 만 쓸 수 있다.
- 컨테이너 포트는 두 앱 모두 `3000`(`PORT`), `HOSTNAME=0.0.0.0` 이다. 호스트 포트는 compose 가 env 로 받는다.

### 빌드 agent 와 배포 호스트의 조건

standalone 의 `node_modules` 에는 `sharp`(이미지 최적화) 네이티브 바이너리가 **빌드 머신의 OS · CPU 로** 들어간다.

- 런타임 베이스는 glibc 계열 `node:24-bookworm-slim` 이다. 빌드 agent 도 glibc 리눅스여야 한다(alpine · macOS 빌드 산출물 금지).
- 빌드 agent 와 배포 호스트의 CPU 아키텍처(amd64 / arm64)가 같아야 한다. ⏳ `builder-frontend` agent 의 아키텍처 확인(#197)

## 2. 환경변수 — 빌드 시점 vs 런타임

키 목록은 `frontend/.env.example` 이다. 실값은 Vault(`kv/<slug>/frontend/{dev,prod}/env` ⏳ #197)에 있고 저장소에는 이름만 둔다.

| 키 | 앱 | 언제 쓰이나 | 비고 |
|----|----|-------------|------|
| `NEXT_PUBLIC_SITE_URL` | storefront | **빌드 시점에 번들에 박힌다** | 환경(dev/prod)마다 따로 빌드해야 한다. 같은 이미지를 dev → prod 로 옮겨 쓰지 않는다 |
| `GATEWAY_URL` | 둘 다 | 런타임 | 같은 외부 네트워크 안의 게이트웨이 주소 ⏳ 게이트웨이 컨테이너 이름(#196) |
| `CLIENT_IP_HEADER` | 둘 다 | 런타임 | 앞단 Nginx 구성에 맞춘다(`docs/architecture.md` §2). ⏳ Nginx 구성(#197) |
| `LOCALE_HOSTS` | storefront | 런타임(proxy 가 읽음) | 규칙상 빌드 · 실행 모두 같은 값을 준다 |
| `BUSINESS_*` | storefront | 런타임(푸터) | 정적으로 미리 그리는 페이지에 들어갈 수 있어 빌드 · 실행 모두 같은 값을 준다 |
| `NODE_ENV` | 둘 다 | 이미지가 `production` 으로 고정 | 어드민 쿠키가 `Secure` 가 된다 — **HTTPS 앞단 없이 http 로 열면 어드민 로그인이 안 된다** |

- 빌드는 `turbo run build` 를 거친다. turbo 는 strict env 모드라 `turbo.json` 의 `build.env` 에 없는 변수는 `next build` 에
  전달되지 않는다. 빌드 시점 키를 추가하면 `turbo.json` 에도 넣는다.
- 런타임 env 는 배포 agent 가 Vault 에서 읽어 compose 옆 `.env.runtime`(권한 600, 커밋 금지 — `.gitignore` 의 `.env.*`)으로
  만들고, compose 가 `env_file` 로 읽는다. 파일 경로는 `RUNTIME_ENV_FILE` 로 바꿀 수 있다.
- 파이프라인과 로그에 값을 찍지 않는다. 필수 키 검사는 **이름만** 출력한다.

## 3. compose 변수

| 변수 | 의미 | 값 |
|------|------|----|
| `STOREFRONT_DEV_PORT` · `STOREFRONT_PROD_PORT` · `ADMIN_DEV_PORT` · `ADMIN_PROD_PORT` | 호스트 포트 | ⏳ 포트 대역(#197) |
| `STOREFRONT_*_MEM_LIMIT` · `ADMIN_*_MEM_LIMIT` | 메모리 상한 | 기본 `512m` (로컬 측정 유휴 약 50MiB) |
| `COMMERCE_DOCKER_NETWORK` | 게이트웨이와 같은 외부 네트워크 | ⏳ 네트워크 이름(#196 · #197) |
| `IMAGE_TAG` | 이미지 태그 | 기본 `latest` ⏳ 빌드 번호 · 커밋 태그 규칙(#198) |
| `RUNTIME_ENV_FILE` | 런타임 env 파일 | 기본 `.env.runtime` |

- 한 compose 에 `<app>-dev` · `<app>-prod` 두 서비스가 있다. 띄울 서비스 이름을 명시한다. 쓰지 않는 쪽 포트 변수가 비어 있으면
  `docker compose config` 가 경고만 낸다.
- healthcheck 는 프로세스가 HTTP 를 받는지만 본다. `/api/health` 는 게이트웨이가 죽으면 503 이라 상태 코드는 보지 않는다.

## 4. 로컬에서 이미지 확인

macOS 에서 바로 빌드한 standalone 은 리눅스 컨테이너에서 쓸 수 없다(§1). 리눅스 컨테이너에서 빌드한다. 저장소 밖 복사본에서 한다
(호스트의 `node_modules` 를 리눅스용으로 덮어쓰지 않게).

```bash
# frontend/ 복사본(node_modules · .next 제외)에서
docker run --rm -v "$PWD":/w -w /w node:24-bookworm-slim \
  sh -c 'corepack enable && pnpm install --frozen-lockfile && pnpm build'
mkdir -p apps/storefront/public apps/admin/public
printf 'GATEWAY_URL=http://localhost:8000\n' > apps/storefront/.env.runtime   # admin 도 같다
docker network create cf-local
COMMERCE_DOCKER_NETWORK=cf-local STOREFRONT_DEV_PORT=13000 \
  docker compose -f apps/storefront/docker-compose-storefront.yml -p commerce-storefront-dev \
  up -d --build storefront-dev
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:13000/   # 200 (게이트웨이 없이도 SSR 첫 화면)
```

## 5. Jenkins 파이프라인 (⏳ #198 규약 확정 후)

`Jenkinsfile-storefront` · `Jenkinsfile-admin` 이 `Jenkinsfile.frontend-common.groovy` 의 `run(config)` 를 부른다. 백엔드 공통
규약이 정해지면 같은 구조로 쓴다.

1. 배포 문맥 — 브랜치 → 환경(`develop` → dev, `main` → prod), 변경 경로 계산, 배포 라벨 게이트 ⏳
2. 빌드(agent `builder-frontend`) — `pnpm install --frozen-lockfile` → `format:check` · `lint` · `typecheck` · `test` →
   Vault 의 빌드 시점 키로 `pnpm --filter @cf/<app>... build` → `server.js` · `static` 존재 확인 → `mkdir -p public`
3. 배포(배포 lock ⏳) — Vault 로 `.env.runtime` 생성 · 필수 키 검사 → `docker compose config -q` →
   `up -d --build --remove-orphans <app>-<env>` → 재시작 횟수 · SSR 첫 화면(`/` 200, admin 은 `/login` 200) 확인

변경 경로: `apps/<app>/**` 는 그 앱만, `packages/**` · 루트 설정(`package.json` · `pnpm-lock.yaml` · `turbo.json` 등)은 두 앱 모두
⏳ 라벨 체계(`.github/labeler.yml` 의 `frontend-web` → `frontend-<app>` 전환 여부)는 #198 라벨 게이트와 같이 정한다.
