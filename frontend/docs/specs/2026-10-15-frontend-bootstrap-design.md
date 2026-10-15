# 프론트엔드 초기 구성 설계 (#23)

- 이슈: #23 `[FE] chore: Next.js 프론트엔드 초기 구성 (스토어프론트·어드민)`
- 작성: 2026-10-15, `seonghoho`
- 상태: 설계 확정 → 구현 계획 대기

## 1. 목표와 범위

고객 스토어프론트와 어드민 콘솔이 올라갈 프론트엔드 기반을 만든다. 이후 FE 이슈(#24~#31)가 이 위에서
화면만 추가하면 되도록 구조·도구·API 클라이언트·테마·CI·문서를 정한다.

### 완료 기준 (이슈 원문)

- dev 서버가 뜨고 frontend-ci 가 통과한다.
- 백엔드 게이트웨이 헬스체크 호출이 성공한다.

### 이번 범위가 아닌 것

| 항목 | 정하는 곳 |
|------|-----------|
| 쿠키 이름·수명, 다중 인스턴스 재발급 경합, 로그인 화면 | #25 (BE #4 · #38 과 합의) |
| 데이터 페칭 라이브러리(TanStack Query 등) | #24 |
| 어드민 표·폼 라이브러리 | #29 |
| i18n · USD 스토어프론트 | #31 |
| 실제 브랜드 색·폰트·로고 | 브랜드 가이드 확보 후 (지금은 임시 팔레트) |
| 다크 모드, 멀티 테넌트 런타임 | 필요해질 때 |

### 따르는 백엔드 계약

정본은 `backend/docs/api-design-guide.md` 다. 바뀌면 이 문서가 아니라 그쪽을 따른다.

- 응답 봉투 `{dataHeader:{success, resultCode, resultMessage, fieldErrors}, dataBody}` — 게이트웨이 거절도 같은 봉투(§2-1)
- `401` 은 재발급 신호, `403` 은 권한 부족. 게이트웨이 코드 `GATEWAY_000~005`(§2-1)
- 금액 `{amount, currency}`, amount 는 최소 화폐 단위 정수(§2-3)
- 목록: 스토어프론트 `SliceResponse`, 어드민 `PageResponse`(page 0 부터)(§4)
- 쓰기 멱등성 `Idempotency-Key`, 처리 중 같은 키는 `409`(§6)
- 게이트웨이 로컬 `:8000`, 헬스체크 `/actuator/health`

## 2. 결정 요약

| 결정 | 선택 | 버린 대안과 이유 |
|------|------|------------------|
| 앱 구조 | pnpm 모노레포 `apps/storefront` · `apps/admin` + `packages/*` | 단일 앱 라우트 그룹 — 배포·인증 경계와 i18n middleware 가 뒤섞이고 나중에 분리 비용이 크다 |
| 도구 | pnpm workspace + Turborepo, ESLint(flat) + Prettier, Vitest, Tailwind v4 | Biome — Next·React Hooks 규칙 커버리지가 약하다. Turbo 없이 — CI 캐시·필터링을 따로 만들어야 한다 |
| API 경로·토큰 | Next BFF 프록시 + httpOnly 쿠키 | 직접 호출 + BE refresh 쿠키 — BE 쿠키·CORS 작업 필요. localStorage — XSS 에 토큰 노출 |
| 브랜드 테마 | pnpm 별칭 `@cf/brand` → `theme-<brand>` 패키지 | 빌드 env 분기 — 타입 추적 약함. 멀티 테넌트 — 고객사 하나라 과함 |
| PR | #23 하나의 PR, 30파일 초과 사유 기재 | 초기 골격은 서로 맞물려 따로 동작 확인이 어렵다. 커밋을 단위별로 나눠 리뷰 가능하게 한다 |

## 3. 디렉터리 구조와 도구

```text
frontend/
  package.json            루트 스크립트(turbo), packageManager: pnpm@<정확한 버전>
  pnpm-workspace.yaml     apps/*, packages/*
  turbo.json              lint · typecheck · test · build · dev
  .nvmrc                  Node 24 LTS
  tsconfig.base.json      strict, noUncheckedIndexedAccess
  eslint.config.mjs
  .prettierrc
  .env.example            키 이름만
  apps/
    storefront/           :3000, Next App Router, src/app
    admin/                :3001, 같은 구조, 중립 테마
  packages/
    api-client/           봉투 타입·파싱, ApiError, 금액 유틸, 멱등 키, BFF 프록시 핵심 로직
    ui/                   브랜드 무관 컴포넌트 + 중립 토큰
    theme-mokoyuri/       브랜드 토큰 값 + brand config
  docs/                   규칙 정본
  CLAUDE.md               엔트리
```

- 패키지 이름 스코프는 `@cf/` 다.
- 패키지는 빌드하지 않는다. TS 소스를 그대로 export 하고 앱이 `transpilePackages` 로 소비한다.
- 의존성 버전은 설치 시점 최신 안정판을 `^` 없이 고정하고 `pnpm-lock.yaml` 을 커밋한다.
- 명령: 루트에서 `pnpm dev` · `pnpm lint` · `pnpm typecheck` · `pnpm test` · `pnpm build`.
  앱 하나만: `pnpm --filter @cf/storefront dev`.

### 환경변수

| 키 | 범위 | 기본값 | 쓰임 |
|----|------|--------|------|
| `GATEWAY_URL` | 서버 전용 | `http://localhost:8000` | BFF · Server Component 가 게이트웨이를 부를 주소 |
| `NEXT_PUBLIC_SITE_URL` | 공개 | `http://localhost:3000` | 절대 URL(SEO 등) |
| 사업자 정보 키(`BUSINESS_*`) | 서버 전용 | 없음 | 푸터 표시. 실값은 Vault, 저장소에는 이름만 |

브라우저는 게이트웨이를 직접 부르지 않으므로 운영 CORS 설정이 필요 없다.

## 4. API 클라이언트와 BFF

### 4-1. 흐름

```text
[Client Component] apiFetch('/api/v1/...')          같은 origin
      ↓
[apps/*/src/app/api/[...path]/route.ts]              BFF. 로직은 @cf/api-client/server
  1. 경로 허용: /api/v1/** 만 전달, 그 외 404
  2. 헤더: Content-Type · Accept · Accept-Language · Idempotency-Key 만 전달.
     Cookie 는 전달하지 않고 access 쿠키 → Authorization: Bearer 로 바꾼다
  3. GATEWAY_URL 로 호출 (메서드 · 쿼리 · 본문 유지)
  4. 401 이고 refresh 쿠키가 있으면 reissue 1회 → 쿠키 갱신 → 원 요청 1회 재시도.
     재발급 실패면 인증 쿠키를 지우고 401 을 그대로 돌려준다
  5. 응답 상태 · 봉투를 그대로 중계. 백엔드 Set-Cookie 는 버린다
      ↓
[API Gateway :8000] → 서비스

[Server Component] serverFetch(...)                  자기 BFF 를 거치지 않고 cookies() 로 게이트웨이 직접 호출
```

- 401 은 인증 단계 거절이라 서버가 처리하지 않았다. 따라서 쓰기 요청도 재발급 후 1회 재시도가 안전하다.
- 같은 refresh 토큰으로 진행 중인 재발급이 있으면 그 Promise 를 공유한다(인스턴스 내부 한정).
  다중 인스턴스 경합은 BE refresh 회전 정책과 함께 #25 에서 확정한다.
- 고객용·어드민 쿠키 이름을 다르게 둔다. 속성은 `HttpOnly; Secure; SameSite=Lax; Path=/`
  (로컬 http 에서는 `Secure` 생략). 정확한 이름·수명은 #25 · #29 에서 정한다.
- 인증 API(#4)가 아직 없으므로 #23 은 재발급 로직을 목 게이트웨이로 단위 테스트하는 데까지 한다.
  reissue 경로는 `POST /api/v1/auth/token/reissue`(#4 기준)이고, 요청·응답 모양은 #4 머지 후 맞춘다.

### 4-2. 오류 모델

봉투가 실패면 `ApiError` 를 던진다. `status` · `code`(`resultCode`) · `message`(`resultMessage`) ·
`fieldErrors` · `kind` 를 갖는다.

| kind | 조건 (위에서부터 먼저 맞는 것) | 화면 기본 처리 |
|------|------|-----------------|
| `network` | fetch 자체 실패 | 일시 오류 안내 |
| `invalidResponse` | 봉투가 아닌 응답(예: origin 거절 403 무봉투) | 일시 오류 안내 |
| `validation` | `fieldErrors` 가 비어 있지 않다 | 필드별 메시지 |
| `unauthorized` | 401 (재발급까지 실패) | 로그인 이동 |
| `forbidden` | 403 | 권한 없음 안내 |
| `notFound` | 404 | not-found |
| `conflict` | 409 | 잠시 후 결과 조회 |
| `unknownOutcome` | 502 · 504 | 쓰기는 자동 재시도 금지. 같은 키로 재시도하거나 조회 |
| `unavailable` | 503 | 잠시 후 재시도 안내 |
| `server` | 그 밖의 실패 | `resultMessage` 표시 |

- 사용자 문구는 서버 `resultMessage` 를 우선한다. 없을 때만 kind 별 기본 문구를 쓴다.
- 성공 응답은 `dataBody` 를 그대로 돌려준다. 선택적 하위 리소스 부재는 `null` 이 정상이다(§2-2).

### 4-3. 멱등 키

- `createIdempotencyKey()` 는 `crypto.randomUUID()` 를 쓴다.
- 키는 "사용자 의도 한 번"(주문하기 버튼 한 번)에 하나다. 재시도는 같은 키를 쓴다. 실제 사용은 #26 부터다.

### 4-4. 헬스체크

- 각 앱에 `GET /api/health` 를 둔다. 서버가 `GATEWAY_URL/actuator/health` 를 부르고
  `{ app: "UP", gateway: "UP" | "DOWN" }` 을 돌려준다. 게이트웨이가 DOWN 이면 HTTP 503.
- actuator 는 `/api/v1` 밖이므로 프록시와 별개 라우트다.
- 루트 페이지에 게이트웨이 연결 상태를 임시로 표시한다. #24 에서 메인 화면으로 바꾼다.

## 5. 금액 표시

```ts
type Currency = 'KRW' | 'USD';
type Money = { amount: number; currency: Currency };

formatMoney({ amount: 129000, currency: 'KRW' }); // "129,000원"
formatMoney({ amount: 8950, currency: 'USD' });   // "$89.50"
```

- 소수 자릿수는 `Intl.NumberFormat(...).resolvedOptions().maximumFractionDigits` 로 구한다(KRW 0, USD 2).
- KRW 는 국내 관례 `129,000원`, USD 는 `en-US` 통화 표기.
- `amount` 가 안전한 정수가 아니면 개발 모드에서 throw 한다.
- 클라이언트 금액 계산 헬퍼(합계·할인)는 두지 않는다. 금액은 서버 값만 표시한다.

## 6. 공통 UI와 테마

### 6-1. 토큰 두 층

```text
packages/ui/src/tokens.css        :root 에 중립 기본값 (--color-primary, --color-bg, --color-fg,
                                  --color-muted, --color-border, --color-danger, --radius-md, --font-sans …)
                                  + Tailwind v4 @theme inline 로 클래스(bg-primary 등)에 연결
packages/theme-mokoyuri/theme.css 같은 변수의 값만 덮어쓴다 (임시 팔레트)
packages/theme-mokoyuri/brand.ts  { name, logoSrc, seo: { titleTemplate, description }, defaultLocale }
```

- 스토어프론트 `globals.css` 는 `tailwindcss` → `@cf/ui/tokens.css` → `@cf/brand/theme.css` 순서로 import 하고
  `@source` 로 `packages/ui/src` 를 스캔한다. 어드민은 `@cf/brand/theme.css` 를 import 하지 않는다.
- 앱 `package.json` 의 `"@cf/brand": "workspace:@cf/theme-mokoyuri@*"` 한 줄이 브랜드 선택이다.
  앱 코드에 브랜드 이름이 나오지 않는다.
- `ui` 컴포넌트는 토큰 클래스만 쓴다. 임의 색 값(`bg-[#…]`, `text-[#…]`)을 lint 로 막는다.
- 폰트는 테마가 `--font-sans` 이름을 정하고 실제 로딩은 앱 `layout.tsx` 의 `next/font` 가 한다.
- 고객사 실정보(사업자등록번호 등)는 테마에 넣지 않는다. 런타임 env 로 받는다.
- 새 브랜드 추가: `packages/theme-<brand>` 를 만들고 앱의 별칭 한 줄을 바꾼다.

### 6-2. 컴포넌트

`Button`, `TextField`(label + input + 오류), `FieldError` 만 만든다. 나머지는 화면 이슈에서 필요할 때 더한다.

## 7. CI

`.github/workflows/frontend-ci.yml` 은 `backend-ci.yml` 규칙을 따른다.

- 트리거: `pull_request`(`frontend/**`, 워크플로 파일) + `develop` push(캐시를 PR 이 상속하도록)
- `concurrency` 로 같은 ref 의 이전 실행 취소, `permissions: contents: read`
- `pnpm/action-setup` → `actions/setup-node`(`.nvmrc`, pnpm 캐시) → `pnpm install --frozen-lockfile`
  → `format:check` → `lint` → `typecheck` → `test:ci` → 테스트 건수 검사 → `build`
- lint · format · test 는 루트에서 한 번에 돈다(설정 하나로 전 패키지). typecheck · build 만 turbo 로 패키지별로 돈다.
- 테스트 건수 검사: Vitest JSON 리포트를 읽어 테스트가 있어야 하는 패키지(`api-client`)가 0 건이면 실패한다.
  "초록불인데 테스트가 하나도 안 돌았다" 를 막는다.
- Turbo 원격 캐시는 쓰지 않는다(공개 저장소에 토큰을 두지 않는다).
- `labeler.yml` 의 `frontend-web` 라벨은 이미 있다.

## 8. 테스트

| 대상 | 내용 |
|------|------|
| 봉투 파싱 | 성공 → dataBody, 실패 → kind 매핑 표 전부, 봉투 아님 → invalidResponse |
| BFF 프록시 | 경로 허용/거부, 헤더 화이트리스트, Cookie → Authorization 변환, Set-Cookie 제거 |
| 재발급 | 401 → reissue → 재시도 성공, reissue 실패 → 쿠키 삭제 + 401, 동시 401 은 reissue 1회 |
| 금액 | KRW · USD 표기, 비정수 amount throw |
| 헬스체크 | 게이트웨이 UP → 200, 연결 실패 → 503 + DOWN |

게이트웨이는 `fetch` 주입으로 목 처리한다. 테스트 위치는 대상 옆 `*.test.ts`.

## 9. 문서와 Claude 설정

| 파일 | 내용 |
|------|------|
| `frontend/CLAUDE.md` | 엔트리. 구조 지도, 명령, docs 링크 |
| `frontend/docs/architecture.md` | 모노레포 구조, 패키지 경계, BFF 흐름, 쿠키·토큰 원칙 |
| `frontend/docs/api-client.md` | 봉투, ApiError kind 처리, 멱등 키, 금액 표시 |
| `frontend/docs/styling-theme.md` | 토큰 두 층, 임의 색 값 금지, 새 브랜드 추가 절차 |
| `frontend/docs/local-run.md` | Node · pnpm 버전, env, 게이트웨이와 함께 띄우기 |
| `frontend/docs/conventions.md` | 파일 · 네이밍 · import 규칙, 테스트 위치 |
| `.claude/agents/fe-implementer.md` | 구현 + lint · typecheck · test 통과 |
| `.claude/agents/fe-reviewer.md` | 읽기 전용 frontend 규칙 검토 |

루트 `CLAUDE.md` 워크스페이스 표와 `docs/claude-agents.md` 프론트엔드 칸도 갱신한다.

## 10. 완료 검증

1. `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build` 통과
2. `pnpm dev` 로 storefront(:3000) · admin(:3001) 기동
3. 게이트웨이 기동(`./gradlew :cloud:api-gateway:bootRun`) 후 `curl localhost:3000/api/health` → `gateway: "UP"`,
   게이트웨이 정지 시 503 + `DOWN`
4. PR 에서 frontend-ci 통과와 테스트 건수 확인
