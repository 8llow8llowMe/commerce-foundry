# Frontend Architecture

## 1. 모노레포

pnpm workspace + Turborepo. 앱 둘과 패키지 셋이다.

| 패키지 | 의존해도 되는 것 | 의존하면 안 되는 것 |
|--------|------------------|---------------------|
| `@cf/api-client` | 표준 Web API(`fetch`, `Request`, `Response`) | React, Next, 다른 `@cf/*` |
| `@cf/ui` | React | Next, `@cf/api-client`, 브랜드 |
| `@cf/theme-<brand>` | `@cf/ui`(타입) | 앱 |
| `apps/*` | 위 전부 | 다른 앱 |

- 패키지는 빌드하지 않는다. TS 소스를 `exports` 로 내보내고 앱이 `transpilePackages` 로 소비한다.
- 공유 의존성 버전은 `pnpm-workspace.yaml` 의 `catalog:` 에만 적는다.
- lint·format·test 는 루트에서 한 번에, typecheck·build·dev 는 turbo 로 패키지별로 돈다.
- TypeScript 는 6.0.x, ESLint 는 9.x 로 고정한다. typescript-eslint 가 TS 7 을, eslint-config-next 플러그인이 ESLint 10 을 아직 지원하지 않는다.

## 2. 요청 흐름 (BFF)

```text
[Client Component] apiFetch('/api/v1/...')        같은 origin
      ↓
[apps/*/src/app/api/[...path]/route.ts]            createProxyHandler (@cf/api-client/server)
  - /api/v1/** 만 전달, 그 외 BFF_001 404
  - Content-Type · Accept · Accept-Language · Idempotency-Key 만 전달
  - access 쿠키 → Authorization: Bearer (클라이언트가 보낸 Authorization 은 버린다)
  - POST · PUT · PATCH · DELETE 는 같은 origin 요청만 받는다(아래 CSRF), 아니면 BFF_003 403
  - 401 + refresh 쿠키 → 재발급 1회 → 새 쿠키 → 원 요청 1회 재시도 (재발급 결과별 처리는 §3)
  - 게이트웨이 Set-Cookie 는 버린다. 연결 실패는 BFF_002 503
  - 그 밖의 상태(429 + Retry-After 포함)는 재발급 · 재시도 없이 그대로 중계한다
      ↓
[API Gateway :8000] → 서비스

[Server Component] serverFetch(...)                게이트웨이 직접 호출. 재발급하지 않는다(401 → 로그인으로)
```

- 브라우저는 게이트웨이를 직접 부르지 않으므로 운영 CORS 가 필요 없다.
- 401 은 인증 단계 거절이라 서버가 처리하지 않았다. 쓰기 요청도 재발급 후 1회 재시도가 안전하다.
- 같은 refresh 토큰의 동시 재발급은 인스턴스 안에서 한 번으로 합친다. 성공 결과는 30초(`REISSUE_GRACE_MS`) 동안
  같은 옛 refresh 토큰에 다시 내준다 — 브라우저가 새 쿠키를 반영하기 전에 옛 쿠키로 온 요청(병렬 요청·다른 탭)이
  회전된 refresh 로 재발급을 시도해 로그아웃되는 것을 막는다. 다른 인스턴스로 간 같은 refresh 는 BE 가 30초 유예 안에 3회까지 새 토큰을 내준다(PR #99, 넷째는 AUTH_012 로 family 폐기).
- 재발급이 성공한 뒤 재시도가 연결 실패해도 새 쿠키는 심는다(회전된 refresh 토큰을 잃지 않는다).

### CSRF

BFF 가 브라우저가 자동으로 싣는 쿠키를 Bearer 로 바꿔 주므로, 게이트웨이·BE 가 아니라 **BFF 가 CSRF 경계**다.

- 안전하지 않은 메서드(GET · HEAD · OPTIONS 외)는 `Sec-Fetch-Site: same-origin` 일 때만 통과한다.
- `Sec-Fetch-Site` 가 없으면(구형 브라우저) `Origin` 이 요청 origin 과 같아야 한다. 둘 다 없으면 브라우저 요청이 아니므로 통과한다.
- SameSite=Lax 쿠키는 이 검사의 보조 수단이다. GET 은 부수 효과가 없어야 한다(BE 규칙).

## 3. 쿠키

- `HttpOnly; SameSite=Lax; Path=/`, 운영은 `Secure`.
- 고객 `cf_at` / `cf_rt`, 어드민 `cf_admin_at` / `cf_admin_rt` — 세션을 섞지 않는다.
- 쿠키를 지우는 조건은 **재발급 명시적 거절**(4xx 또는 `success=false`)뿐이다. 재발급 5xx · 연결 실패 · 봉투 아님 ·
  토큰 누락은 일시 장애로 보고 쿠키를 그대로 둔 채 BFF_002 503 을 준다(일시 장애로 세션을 날리지 않는다).
- **로그인**은 전용 route handler(`app/api/v1/auth/login`, `createLoginHandler`)가 받는다 — 일반 프록시보다 구체적인 경로라 먼저 잡힌다.
  BE 는 토큰을 응답 본문으로 주므로(Set-Cookie 없음) BFF 가 쿠키로 심고, 브라우저에는 토큰을 뺀 성공 봉투(`dataBody: null`)만 준다.
  쿠키 수명은 응답의 `accessTokenExpiresIn` · `refreshTokenExpiresIn`(초)이고, 없으면 앱 설정값이다. 같은 origin 요청만 받는다(login CSRF).
  실패는 게이트웨이 봉투 · 상태를 그대로 중계한다 — 로그인 실패는 401 이 아니라 **400 `AUTH_001`** 이라 화면은 kind 가 아니라 code 로 본다.
- **회원가입**도 전용 route handler(`app/api/v1/auth/sign-up`, `createSignUpHandler`)다. BE 가입(201 `{ memberId }`)은 토큰을 주지 않으므로
  BFF 가 **같은 요청 안에서 같은 이메일 · 비밀번호로 로그인**해 쿠키를 심는다(가입 후 자동 로그인). 로그인 부분은 `attemptLogin` 을 로그인 핸들러와 같이 쓴다.
  가입이 되면 로그인 결과와 무관하게 201 `{ signedIn }` 이다 — 자동 로그인이 실패해도 계정은 만들어졌으니 가입 실패로 보이지 않게 하고,
  화면이 `/login?signedUp=1` 로 보내 가입 완료 안내를 띄운다. 가입 실패(검증 fieldErrors · 409 `AUTH_002` 이메일 중복)는 그대로 중계한다.
  fieldErrors 의 동의 칸은 `agreements.termsOfService` 처럼 중첩 경로다. 비밀번호 확인 칸은 BE 에 없어 화면에서만 본다.
- 고객 쿠키 이름 `cf_at` / `cf_rt` 와 대체 수명(30분 / 14일, BE 설정과 같다)은 `apps/storefront/src/lib/auth-cookies.ts`. 어드민은 #29 에서 정한다.
- 인증 계약은 BE PR #99 기준이다(머지 전): 재발급 `{ refreshToken }` → 로그인과 같은 모양. 재발급 쿠키도 응답 수명을 쓰게 하는 것과
  로그아웃(Bearer + `{ refreshToken }`, 401 이면 재발급 후 1회 재시도)은 #25 다음 단계에서 붙인다.

## 4. 헬스체크

각 앱 `GET /api/health` → 게이트웨이 `/actuator/health` 결과로 `{ app, gateway }`. 게이트웨이 DOWN 이면 503.

## 5. 언어 (i18n, #31)

스토어프론트 화면은 모두 `app/[locale]/...` 아래 하나다. 바깥 URL 은 `src/proxy.ts` 가 `/[locale]/...` 로 바꿔 넣는다(규칙 정본 `src/lib/i18n/routing.ts`).

| 방식 | 국문 | 영문 | 켜는 법 |
|------|------|------|---------|
| 경로 (기본) | `/shop` | `/en/shop` | 그대로 |
| 호스트 | `example.com/shop` | `en.example.com/shop` | env `LOCALE_HOSTS=en=en.example.com` |

- 정식 주소가 아닌 요청은 308 로 보낸다: `/ko/...` → `/...`, 언어 호스트의 `/en/...` → `/...`, 기본 호스트의 `/en/...`(호스트 방식일 때) → 언어 호스트.
- 브라우저 언어(Accept-Language)로 자동으로 보내지 않는다. 같은 주소는 누구에게나 같은 언어다(검색 노출 · 공유 링크).
- 언어 목록 · 기본 언어는 브랜드 설정(`brand.locales` · `defaultLocale`), 전용 호스트는 배포 env 다(환경마다 도메인이 다르다).
- 서버는 `getI18n()`(`lib/i18n/server.ts`, `next/root-params`)으로 언어 · 사전 · 브랜드 문구를 읽는다. 클라이언트는 `LocaleProvider` 의 `useLocale()` · `useLocalizedHref()` 를 쓴다.
- 링크는 `next/link` 대신 `@/components/i18n/Link` — 앱 경로('/shop')에 언어 접두어를 붙인다. 언어 전환만 `<a>`(루트 레이아웃이 달라 문서를 새로 받는다).
- 언어 전환(`LanguageLinks`)은 **같은 화면 · 같은 쿼리**의 다른 언어로 간다. 서버는 각 언어 메인 주소로 그리고(JS 전 기본값), 링크를 가리키거나 누를 때 `window.location` 으로 주소를 만든다(`switchLocaleHref`). 렌더 중에 `usePathname()` · `window` 를 읽지 않는다 — rewrite 뒤 브라우저 주소와 다를 수 있고 hydration 이 어긋난다.
- 게이트웨이 요청에 스토어 언어를 `Accept-Language` 로 싣는다(`serverFetch`, 클라이언트 `apiFetch` 호출부). BFF 는 이 헤더를 넘긴다.
- 페이지는 `generateMetadata` 에서 `localeAlternates(path)` 로 canonical · hreflang(+ `x-default`)을 낸다. 절대 주소는 `NEXT_PUBLIC_SITE_URL` 이 기준이다.
- UI 문구는 `lib/i18n/dictionaries/{ko,en}.ts`. `en` 은 `Dictionary`(= ko 의 모양) 타입이라 키가 빠지면 typecheck 가 잡는다.
  - 서버 컴포넌트는 `getI18n().dict`, 클라이언트 컴포넌트는 `useDictionary()`(사전이 몇 KB 라 `LocaleProvider` 에 통째로 싣는다). 서버 · 클라이언트 양쪽에서 쓰는 화면 조각(`ProductCard`)은 클라이언트 컴포넌트로 둔다.
  - 값이 들어가는 문구는 함수 대신 `{name}` 자리표시자 + `fill(template, values)` — 사전이 서버 → 클라이언트 props 로 넘어가 직렬화돼야 한다.
  - 상품명 · 카테고리 · 옵션 · 판매 상태 이름 등 **운영 데이터는 사전에 두지 않는다**. BE 가 `Accept-Language` 로 고른 값을 그대로 보인다(#20).
- 금액은 클라이언트에서 `useFormatMoney()`, 서버에서 `formatMoney(money, locale)` — 영문 스토어의 KRW 는 `₩129,000`.

