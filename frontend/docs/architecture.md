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
  - X-Forwarded-For 는 브라우저 값을 버리고 클라이언트 IP 한 값으로 덮어쓴다(아래 클라이언트 IP)
  - access 쿠키 → Authorization: Bearer (클라이언트가 보낸 Authorization 은 버린다)
  - POST · PUT · PATCH · DELETE 는 같은 origin 요청만 받는다(아래 CSRF), 아니면 BFF_003 403
  - access 쿠키 없음 + refresh 쿠키 → 보내기 전에 재발급 → 새 토큰으로 1회 (토큰 없는 첫 요청을 만들지 않는다)
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
- **access 쿠키가 없고 refresh 만 있으면(access 쿠키 만료) 보내기 전에 재발급한다(#134).** 토큰 없이 보내 401 을 받고 재발급하면
  그 첫 요청이 게이트웨이에서 비로그인(IP 단위) 요청 제한에 세이고, 버킷이 비면 401 대신 429 를 받아 재발급 경로를 못 탄다.
  재발급 결과별로: 성공 → 새 토큰으로 1회 보내고 새 쿠키(그 응답이 401 이어도 다시 재발급하지 않는다), 거절 → 쿠키를 지우고 토큰 없이 보낸다
  (공개 조회는 그대로 되고 회원 API 는 401), 일시 장애 → 보내지 않고 BFF_002 503. 동시 요청은 위의 재발급 합치기를 그대로 탄다.

### SSE 중계 (#214)

서버가 밀어 주는 스트림(`text/event-stream`)은 일반 프록시가 아니라 **경로마다 전용 route handler**(`createEventStreamProxyHandler`)로 중계한다.
지금은 어드민 알림 스트림(`app/api/v1/admin/notifications/stream`) 하나다.

- 경로는 handler 에 고정한다(요청 주소로 고르지 않는다 — 열린 중계 금지). GET 만, 쿠키 → Bearer · 클라이언트 IP 규칙은 일반 프록시와 같다(넘기는 요청 헤더는 `Accept: text/event-stream` 하나다).
- **본문을 모으지 않는다** — 게이트웨이 본문을 그대로 흘리고 `Cache-Control: no-cache, no-transform`(Next 의 gzip 이 건너뛴다) ·
  `X-Accel-Buffering: no`(nginx 버퍼링 끔)를 붙인다. 앞단 nginx 를 둘 때 SSE 경로에 `proxy_read_timeout` 을 heartbeat(5초)보다 길게 둔다.
- **연결 직후 SSE 주석 `: connected` 한 줄을 먼저 보낸다** — Next 는 본문 첫 바이트가 나올 때까지 응답 헤더를 보내지 않아, 그대로 두면
  게이트웨이 첫 heartbeat(5초)까지 브라우저가 연결됐는지 모른다.
- **브라우저가 끊으면 게이트웨이 연결도 끊는다** — 요청 `signal` 을 게이트웨이 요청에 넘긴다(BE 구독이 남지 않게).
- 재발급 · 재시도는 하지 않는다. 401 · 403 · 503 은 봉투 그대로이고, 다시 열지 · 따라잡을지는 화면이 정한다.
- 브라우저는 `EventSource` 대신 `readEventStream`(`@cf/api-client`, `fetch` + SSE 해석)으로 연다 — `EventSource` 는 401 · 403 상태를 알려 주지 않고 스스로 다시 연결해서다.

### 클라이언트 IP (#134)

게이트웨이는 토큰 없는 쓰기 요청을 클라이언트 IP 로 센다(`backend/docs/security-guide.md` §7-3, 켜는 일은 #135). BFF 뒤에서는
게이트웨이가 보는 원격 주소가 늘 BFF 라서, BFF 가 클라이언트 IP 를 `X-Forwarded-For` 로 넘긴다.

- **게이트웨이를 부르는 BFF 의 모든 경로**(중계 · 로그인 · 회원가입 · 재발급 · 로그아웃)가 같은 규칙을 쓴다 — `resolveClientIp` · `setClientIp`(`@cf/api-client/server`).
- 브라우저가 보낸 `X-Forwarded-For` 는 **버린다**(누구나 정할 수 있다). 앞단(호스팅 플랫폼 · LB)이 정한 헤더 하나에서 IP 를 읽어
  게이트웨이행 `X-Forwarded-For` 를 **그 한 값으로 덮어쓴다**. 읽을 헤더는 `CLIENT_IP_HEADER`(기본 `x-real-ip`, `none` 이면 끔)다.
- 그 헤더 값에 쉼표가 있으면 맨 오른쪽(바로 앞 프록시가 붙인 값)만 쓴다. 주소가 아닌 값이거나 헤더가 없으면 `X-Forwarded-For` 를 보내지 않는다
  — 게이트웨이는 BFF 주소로 센다(그 BFF 뒤가 한 버킷, 닫힌 쪽).
- **읽는 헤더는 앞단이 반드시 덮어써야 한다.** 그렇지 않으면 브라우저가 그 헤더로 버킷을 고른다.
  Vercel 은 `x-real-ip` 를 플랫폼이 덮어쓴다. nginx 는 `proxy_set_header X-Real-IP $remote_addr;` 를 둔다.
  `X-Forwarded-For` 를 덧붙이는(append) L7 LB 한 단 뒤라면 `CLIENT_IP_HEADER=x-forwarded-for` 로 맨 오른쪽을 쓴다. 앞단 없이 BFF 가 직접 받는 구성이면 `none` 으로 끈다.
- 게이트웨이는 **BFF 송신 주소가 신뢰 프록시(`GATEWAY_TRUSTED_PROXIES`)일 때만** 이 값을 읽는다. 배포 구성별 `CLIENT_IP_HEADER` 와
  BFF 송신 주소(= 그 환경 docker 네트워크 서브넷)는 `docs/deploy.md` §8 이다.
- `serverFetch`(Server Component)는 넘기지 않는다. 조회(GET)만 하고 게이트웨이는 조회를 세지 않는다. 요청 헤더를 읽으면 그 렌더가 동적이 되는 것도 이유다.
  RSC 에서 쓰기를 부르게 되면 이 규칙을 같이 넣는다.

### CSRF

BFF 가 브라우저가 자동으로 싣는 쿠키를 Bearer 로 바꿔 주므로, 게이트웨이·BE 가 아니라 **BFF 가 CSRF 경계**다.

- 안전하지 않은 메서드(GET · HEAD · OPTIONS 외)는 `Sec-Fetch-Site: same-origin` 일 때만 통과한다.
- `Sec-Fetch-Site` 가 없으면(구형 브라우저) `Origin` 이 요청 origin 과 같아야 한다. 둘 다 없으면 브라우저 요청이 아니므로 통과한다.
- SameSite=Lax 쿠키는 이 검사의 보조 수단이다. GET 은 부수 효과가 없어야 한다(BE 규칙).

### 클릭재킹 (iframe 허용 범위, #345)

다른 사이트가 화면을 iframe 으로 감싸 누르게 만드는 것을 막는다. 정본은 CSP `frame-ancestors` 고,
`X-Frame-Options` 는 그걸 모르는 구형 브라우저용으로 같이 싣는다.

| 앱 | 범위 | 값 | 붙이는 곳 |
|----|------|----|-----------|
| 스토어프론트 | 모든 페이지(proxy matcher 안) | `frame-ancestors 'self'` + `X-Frame-Options: SAMEORIGIN` | `src/proxy.ts` → `setFramePolicy`(`lib/frame-policy.ts`) |
| 스토어프론트 | 메인 전시 미리보기(#71) | `frame-ancestors <ADMIN_ORIGIN>`, XFO 없음 | 같은 함수에 `previewFramePolicy` 결과를 넘긴다 |
| 어드민 | 전 경로(`/login` · `/api` 포함) | `frame-ancestors 'none'` + `X-Frame-Options: DENY` | `next.config.ts` `headers()` |

- 스토어프론트를 proxy 에서 붙이는 이유: 미리보기 경로만 다른 값이어야 하는데, 그 경로는 언어 접두어 · 언어 호스트를 푼 뒤의
  앱 경로(`/[locale]/preview/*`)로만 알 수 있다. `next.config` `headers()` 는 바깥 경로로 맞추고 proxy 보다 먼저 돌아 덮어쓰기가 꼬인다.
- XFO 는 특정 출처를 허용할 수 없다. 기본값이 아닌 정책에는 XFO 를 싣지 않는다 — 실으면 CSP 를 모르는 브라우저가 그 출처까지 막는다.
- 스토어프론트 proxy matcher 밖(`/api` · `/_next` · 확장자 있는 정적 파일)은 붙이지 않는다. JSON · JS · CSS · 이미지는 감싸도 누를 화면이 없다.
  matcher 밖에서 HTML 화면을 내게 되면(예: `/api` 아래 HTML 응답) 그 경로에 같은 값을 붙인다.
- 어드민은 자기 화면을 iframe 으로 감싸지 않는다(미리보기는 스토어프론트를 감싼다). proxy matcher 가 `/login` 을 건너뛰므로 `headers()` 로 둔다.
- 전체 CSP(script-src 등)는 아직 없다. 넣을 때 `frame-ancestors` 를 같은 헤더에 합친다 — `Content-Security-Policy` 를 두 번 보내면 둘 다 적용돼 더 좁은 쪽이 된다.

## 3. 쿠키

- `HttpOnly; SameSite=Lax; Path=/`, 운영은 `Secure`.
- 고객 `cf_at` / `cf_rt`, 어드민 `cf_admin_at`(refresh 없음, §9) — 세션을 섞지 않는다.
- 쿠키를 지우는 조건은 **재발급 명시적 거절**(4xx 또는 `success=false`)뿐이다. 재발급 5xx · 연결 실패 · 봉투 아님 ·
  토큰 누락은 일시 장애로 보고 쿠키를 그대로 둔 채 BFF_002 503 을 준다(일시 장애로 세션을 날리지 않는다).
- **로그인**은 전용 route handler(`app/api/v1/auth/login`, `createLoginHandler`)가 받는다 — 일반 프록시보다 구체적인 경로라 먼저 잡힌다.
  BE 는 토큰을 응답 본문으로 주므로(Set-Cookie 없음) BFF 가 쿠키로 심고, 브라우저에는 토큰을 뺀 성공 봉투(`dataBody: null`)만 준다.
  쿠키 수명은 응답의 `accessTokenExpiresIn` · `refreshTokenExpiresIn`(초)이고, 없으면 앱 설정값이다. 같은 origin 요청만 받는다(login CSRF).
  실패는 게이트웨이 봉투 · 상태를 그대로 중계한다 — 로그인 실패는 401 이 아니라 **400 `AUTH_001`** 이라 화면은 kind 가 아니라 code 로 본다.
  봉투를 다시 만들어 돌려주므로 **429 의 `Retry-After` 는 따로 옮겨 싣는다**(`relayFailure`, 일반 프록시 `relay()` 와 같은 결과).
  시도 제한 **429 `AUTH_040`**(이메일 단위, 비밀번호가 맞아도 · 계정이 없어도 같은 응답. 한도는 BE 문서가 정본, #66)은 화면이 남은 시간 안내와 쿨다운으로 받는다(`api-client.md` §3-1).
- **회원가입**도 전용 route handler(`app/api/v1/auth/sign-up`, `createSignUpHandler`)다. BE 가입(201 `{ memberId }`)은 토큰을 주지 않으므로
  BFF 가 **같은 요청 안에서 같은 이메일 · 비밀번호로 로그인**해 쿠키를 심는다(가입 후 자동 로그인). 로그인 부분은 `attemptLogin` 을 로그인 핸들러와 같이 쓴다.
  가입이 되면 로그인 결과와 무관하게 201 `{ signedIn }` 이다 — 자동 로그인이 실패해도 계정은 만들어졌으니 가입 실패로 보이지 않게 하고,
  화면이 `/login?signedUp=1` 로 보내 가입 완료 안내를 띄운다. 가입 실패(검증 fieldErrors · 409 `AUTH_002` 이메일 중복 · 429 `AUTH_041` 가입 시도 제한)는
  `Retry-After` 까지 그대로 중계한다. 자동 로그인이 429 면 `{ signedIn: false, loginRateLimit: { code, retryAfterSec } }` 로 알리고,
  화면이 `/login?signedUp=1&limited=<초>&reason=<코드>` 로 보내 요청 제한 안내를 덧붙이고 로그인 버튼 쿨다운을 처음부터 건다(#184).
  주소 값은 `readLoginRateLimit` 이 거른다 — 초는 1 ~ 3600 정수, 코드는 사전에 있는 것만(아니면 "잠시 후" · 일반 문구).
  fieldErrors 의 동의 칸은 `agreements.termsOfService` 처럼 중첩 경로다. 비밀번호 확인 칸은 BE 에 없어 화면에서만 본다.
- **소셜 로그인**(카카오 · 네이버, #25 · BE #5)도 전용 route handler(`app/api/v1/auth/oauth/[provider]/login`, `createSocialLoginHandler`)다.
  응답이 이메일 로그인과 같은 토큰 쌍이라 로그인 핸들러와 같은 틀(`loginHandler` · `requestLogin`)로 쿠키를 심고 토큰 없는 성공 봉투만 준다.
  같은 origin 요청만 받고(login CSRF), 제공자는 `kakao` · `naver` 만(허용 목록은 `@cf/api-client` 루트의 `SOCIAL_PROVIDERS` · `isSocialProvider` 하나를 화면과 같이 쓴다) — 그 밖은 게이트웨이를 부르지 않고 404 `BFF_001` 이다. 실패는 봉투 · 상태 · `Retry-After` 그대로 중계한다.
  인가 URL 발급(`GET /api/v1/auth/oauth/{provider}/authorize-url`)은 토큰이 없는 응답이라 일반 프록시가 중계한다. 화면 흐름은 §3-1.
- 고객 쿠키 이름 `cf_at` / `cf_rt` 와 대체 수명(30분 / 14일, BE 설정과 같다)은 `apps/storefront/src/lib/auth-cookies.ts`.
  어드민은 `apps/admin/src/lib/auth-cookies.ts` — `cf_admin_at` 하나, 대체 수명 30분이다(#204, §9).
- **토큰을 주는 인증 경로는 전용 route handler 만 받는다** — 로그인 · 관리자 로그인 · 재발급 · 로그아웃 · 가입(`DEDICATED_AUTH_PATHS`), 소셜 로그인(`/api/v1/auth/oauth/*/login`, 제공자 값 무관)은 일반 프록시가 404 `BFF_001` 로 막는다
  (대소문자 · 끝 슬래시 · 퍼센트 인코딩 · 마디마다 붙은 경로 매개변수 `;…` 변형 포함). 전용 핸들러가 없는 앱(스토어프론트의 관리자 로그인, 어드민의 고객 로그인 · 재발급)에서 토큰이 JS 로 새지 않게 한다.
- **refresh 쿠키가 없는 설정**(`AuthCookieConfig.refreshName: null`)이면 프록시 · 로그아웃은 refresh 쿠키를 읽지 않고(같은 이름 쿠키가 와도 무시) 재발급하지 않는다
  — 401 은 그대로 중계하고, 로그아웃은 Bearer + 본문 `{}` 로 access 만 막는다(BE `LogoutRequest.refreshToken` 은 선택). 쿠키 정리도 access 하나다.
- 인증 계약은 BE PR #99 기준이다(머지 전): 재발급 `{ refreshToken }` → 로그인과 같은 모양. 로그인 · 재발급 모두 응답의 `*ExpiresIn` 을
  `TokenPair` 에 실어(`readTokenPair`) 쿠키 수명으로 쓴다(`issueCookies`). 없으면 앱 설정값이다.
- **로그아웃**은 전용 route handler(`app/api/v1/auth/logout`, `createLogoutHandler`)다 — refresh 는 httpOnly 쿠키라 일반 프록시로는 본문에 실을 수 없다.
  Bearer(access 쿠키) + `{ refreshToken }` 으로 부르고, **401 이거나 access 쿠키가 없으면 재발급한 뒤 새 토큰 쌍으로 한 번 다시 부른다**
  (그러지 않으면 서버의 refresh family 가 남는다). 서버 결과(503 `AUTH_013` · 연결 실패 · 재발급 거절)와 무관하게 쿠키를 지우고 200 이다.
  같은 origin 요청만 받는다(남이 몰래 로그아웃시키지 못하게).
- **로그인 상태 표시**는 레이아웃이 `hasSession()`(`lib/auth/session.ts`, 인증 쿠키 존재 여부)으로 정해 헤더에 넘긴다. 토큰 유효성은 보지 않는다 —
  진입점(로그인 ↔ 마이페이지 · 로그아웃)만 바꾸고, 권한이 걸린 화면은 API 응답으로 판단한다. `[locale]` 화면은 이미 요청마다 렌더링되므로
  쿠키를 읽어도 정적 생성을 잃지 않는다. RSC 는 쿠키를 쓰지 못하므로 여기서 지우거나 재발급하지 않는다.

### 3-1. 소셜 로그인 화면 (#25 · BE #5)

| 단계 | 어디서 | 하는 일 |
|------|--------|---------|
| 시작 | 로그인 · 가입 화면 `SocialLoginButtons` | authorize-url 을 받아 흐름을 **같은 탭 sessionStorage** 키 `cf-social-login` 에 두고 `authorizeUrl` 로 이동 |
| 콜백 | `/login/oauth/{kakao\|naver}` `SocialCallback` | 쿼리 state 를 흐름과 비교 → 맞으면 BFF 소셜 로그인 1회 → 돌아갈 경로로 `router.replace` + `refresh` |

- **redirect URI = `<스토어프론트 origin>/login/oauth/{provider}`** (기본 언어 경로). BE `KAKAO_REDIRECT_URI` · `NAVER_REDIRECT_URI` 와 제공자 콘솔에 같은 값을 넣는다(`local-run.md` §4).
- 흐름 값 `{ provider, state, verifier, returnPath, localePrefix, agreements }` — verifier 는 브라우저 바인딩 비밀이라 URL · 로그에 넣지 않는다(BE security-guide §13).
  `returnPath` 는 `safeReturnPath`(로그인 · 가입 화면과 그 아래 경로 — 콜백 `/login/oauth/*` 포함 — 는 메인으로 바꾼다), `localePrefix` 는 `''` · `/en` 모양만 받는다(읽을 때 다시 거른다). 새로 시작하면 덮어쓴다.
- **수명** — 콜백이 읽자마자(보내기 전에) 지운다. BE 는 요청마다 state 를 먼저 소비하므로 그 뒤의 실패는 모두 인가 URL 부터 다시 시작한다. 콜백까지 가지 않은 흐름은 탭을 닫을 때 사라지고, BE state TTL(기본 5분)이 지나면 AUTH_052 다.
- **콜백 처리** — 서버는 쿼리를 읽지 않는다. 브라우저가 먼저 `history.replaceState` 로 쿼리(code · state)를 주소에서 지워 기록 · 새로고침에 남지 않게 하고,
  StrictMode effect 이중 실행은 ref 로 막아 한 번만 보낸다. 순서(언어 넘기기 → 쿼리 지우기 → 흐름 지우기 → 보내기 → 이동)는 `runSocialCallback`(`lib/auth/social.ts`, 의존성 주입)이 정하고 컴포넌트는 부르기만 한다. 흐름이 없거나 state · 제공자가 다르거나 code 가 없으면 BE 를 부르지 않고 다시 시작 안내다.
  제공자 `error` 쿼리(사용자 취소 `access_denied` 등)도 BE 를 부르지 않는다.
- **언어** — redirect URI 가 하나라 영문 스토어에서 시작해도 기본 언어 콜백으로 돌아온다. 흐름의 `localePrefix` 가 지금 언어와 다르면 같은 쿼리로 그 언어의 콜백으로 `location.replace` 한다(기록을 늘리지 않는다).
  호스트 방식 언어(`LOCALE_HOSTS`)는 origin 이 달라 sessionStorage 가 갈리므로 그 호스트에서 시작한 소셜 로그인은 콜백에서 흐름을 찾지 못한다 — 호스트 방식을 켜면 언어 호스트별 redirect URI 가 필요하다(BE 과제).
- **신규 가입 약관 동의** — 로그인 화면은 동의 없이 시작한다. AUTH_056 이면 콜백이 가입 폼과 같은 동의 칸(`AgreementsFieldset`)을 보이고, 필수 두 칸에 동의하면 동의를 흐름에 담아
  **인가부터 다시 시작**한다(두 번째 콜백이 `agreements` 를 함께 보낸다). 가입 화면의 간편 가입 버튼은 그 화면의 필수 동의를 먼저 요구하고 동의를 담아 시작한다(기존 회원이면 BE 가 무시).
- **오류** — 코드별 사전 문구 `dict.auth.social.errors`(AUTH_050 ~ 057, AUTH_150 ~ 152 검증, 같은 이메일의 기존 계정 409 AUTH_002), 429 는 `rateLimitTextOf`, 그 밖은 한 문구.
  콜백 오류 화면의 "로그인 화면으로" 는 돌아갈 경로를 이은 `/login?next=` 다. AUTH_055 는 제공자 화면에서 이메일 제공에 동의하라는 안내다.
- **버튼 색** — 제공자 브랜드 색 토큰이 없어 중립 보조 버튼(`secondary`)이다. 제공자 디자인 가이드(색 · 로고)를 맞추려면 `tokens.css` 에 제공자 토큰을 더하는 결정이 먼저다(`styling-theme.md` §1).
- 비로그인 장바구니 병합은 로그인 뒤 레이아웃의 `CartMerge` 가 그대로 맡는다(§6). 마이페이지 계정 연결은 §3-2.

### 3-2. 간편 로그인 계정 연결 (`/mypage/social`, #356 · BE #355)

일반(이메일) 계정에 카카오 · 네이버 로그인을 더한다. 이메일이 같아도 자동으로 연결하지 않고, 현재 비밀번호 재인증 → 제공자 인가를 거친다.
제공자별 1개 연결이며 연결 해제 · 회원 병합 · 소셜 전용 회원의 비밀번호 설정은 범위 밖이다(소셜 전용 회원은 재인증이 `AUTH_058` 로 막힌다 — 화면은 안내 문구만).

| 단계 | 어디서 | 하는 일 |
|------|--------|---------|
| 목록 | `SocialAccounts` | `GET /api/v1/members/me/social-accounts` → `{ providers }` 의 아는 코드만(`readLinkedProviders`). 이름은 사전 `dict.auth.socialLink.providers` |
| 시작 | 같은 화면의 비밀번호 확인 폼 | `POST …/{provider}/authorize-url` `{ currentPassword }` → 흐름을 sessionStorage 키 **`cf-social-link`** 에 두고 `authorizeUrl` 로 이동(`startSocialLink`) |
| 콜백 | `/login/oauth/{provider}` `SocialCallback` | 목적 판정(`callbackPurpose`) → 연결이면 state 대조 후 `POST …/{provider}` `{ code, state, verifier }` 1회 → `/mypage/social?linked=<provider>` |

- **세 API 모두 고객 access JWT 가 필요하고 응답에 토큰이 없다** — 전용 핸들러 없이 일반 BFF 프록시가 access 쿠키를 Bearer 로 붙여 중계한다(만료면 재발급 · 재시도도 프록시가 한다).
  새 로그인 토큰을 발급하지 않으므로 쿠키는 바뀌지 않는다. 일반 프록시의 토큰 경로 차단(§3 끝)은 그대로다.
- **비밀번호** — 컴포넌트 상태에만 잠깐 두고 요청 본문으로만 보낸다. 보내는 순간 상태를 비운다(실패해도 다시 입력). 입력 칸에 `name` 을 주지 않고 폼은 `method="post"` 라
  스크립트 전에 제출돼도 주소에 실리지 않는다. 흐름 · 로그에 담지 않는다.
- **흐름 값** `{ purpose: 'LINK', provider, state, verifier, localePrefix }` — 로그인 흐름(`cf-social-login`)과 키가 달라 서로 덮어쓰지 않는다. `parseSocialLinkFlow` 는 `purpose` 가 `LINK` 가 아니면 버린다.
- **콜백 목적 판정** — BE 는 로그인과 같은 redirect URI 를 쓴다(제공자 콘솔에 주소를 더 등록하지 않는다). 연결 흐름이 없으면 기존 로그인 콜백 그대로이고,
  있으면 쿼리 state 가 로그인 흐름의 state 와 맞을 때만 로그인, 그 밖은 연결이다. 연결은 저장한 연결 흐름의 state · 제공자와 맞아야만 보낸다 —
  다른 탭(흐름 없음) · 다른 흐름의 state · verifier 없는 콜백은 BE 를 부르지 않는다. 순서(언어 넘기기 → 쿼리 지우기 → 연결 흐름 지우기 → 보내기 → 이동)는
  로그인과 같고 `runSocialLinkCallback`(`lib/auth/social-link.ts`)이 정한다. effect 안에서 판정과 실행을 같이 해(set-state-in-effect 금지) 처리 중 안내는 둘에 맞는 문구다.
- **결과** — 성공은 연결 화면이 목록을 다시 읽고(즉시 반영) `?linked=` 로 완료 안내를 띄운다(목록에 그 제공자가 있을 때만). 실패는 콜백 화면에 안내 + "간편 로그인 연결로 돌아가기"
  (state 는 이미 소비돼 모두 비밀번호 확인부터 다시). 401 은 `SignedOutNotice`(다시 로그인 뒤 `/mypage/social`).
- **오류** — 코드별 사전 `dict.auth.socialLink.errors`: 재인증 실패 `AUTH_058`(원인 구분 없음), 연결 꺼짐 `AUTH_060` · 저장소 장애 `AUTH_013`, 충돌 `AUTH_057`(다른 회원 · 같은 제공자의 다른 계정),
  경합 `AUTH_004`, state 만료 · 재사용 `AUTH_052`, 제공자 `AUTH_050 ~ 054`, 검증 `AUTH_104 · 150 ~ 152`. 시도 제한 429 `AUTH_059` 는 `Retry-After` 가 없어
  "잠시 후" 문구 + 버튼 기본 쿨다운이다(`rateLimitTextOf`). 사용자 취소(`error=access_denied`)는 BE 를 부르지 않는다.

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

## 6. 장바구니 (#26)

BE 장바구니(`/api/v1/carts/me/**`, `backend/docs/order-flow-guide.md` §2-1)는 **회원 전용**이다. 비회원 줄은 브라우저에 두고 로그인 직후 병합한다.

| | 회원 | 비회원 |
|---|------|--------|
| 저장 | 서버(order-service) | `localStorage` `cf_cart` — SKU · 수량 · 맞춤 제작 입력 · 담은 시각만(`lib/cart/local-cart.ts`) |
| 담기 | `POST /items` 한 줄씩 | 로컬에 더한다. 같은 줄(SKU + 정규화한 입력, BE 와 같은 판정)은 수량을 더하고 상한(1회 최대 수량 · 99)에서 자른다. 50줄 초과는 담지 않는다 |
| 보기 | `GET` — 이름 · 옵션명 · 금액 · 상태 · 합계 모두 서버 값 | 상품마다 상세 API 로 현재 이름 · 옵션 · 단가 · 상태를 읽는다. **줄 금액 · 합계는 보이지 않는다**(클라이언트 금액 계산 금지) — 로그인 안내로 대신한다 |
| 수량 · 삭제 | `PATCH` · `DELETE` — 갱신된 장바구니 전체로 바꿔 끼운다 | 로컬에서 |

- 로그인 여부는 레이아웃의 `hasSession()` 을 `SessionProvider` 로 내려 `useSignedIn()` 으로 읽는다. 쿠키가 있어도 세션이 끝났을 수 있어
  회원 API 가 401 이면 화면을 다시 그리고(router.refresh) 담기는 남은 줄을 로컬에 담는다 — 로그인하면 병합된다.
  장바구니 화면은 401 이면 다시 로그인 안내로 멈춘다(refresh 쿠키 없이 access 만 남으면 쿠키가 지워지지 않아 다시 그려도 회원 화면일 수 있다).
- **병합**은 레이아웃의 `CartMerge` 가 맡는다. 로그인 상태이고 로컬 줄이 있으면 화면마다 한 번 `POST /merge`(오래 담은 것부터 50줄)를 보낸다.
  병합은 **멱등하지 않다**(BE 에 멱등 키가 없어 다시 보내면 또 더해진다). 결과별 처리는 `mergeLocalCart`(`lib/cart/merge.ts`)가 정한다.

  | 결과 | 로컬 | 알림 |
  |------|------|------|
  | 성공 | 보낸 만큼만 지운다(보내는 동안 담은 줄 · 늘린 수량은 남긴다) | `skippedItems` 가 있으면 수 |
  | 502 · 504(결과 모름) | **지운다** — 서버가 반영했을 수 있어 다시 보내지 않는다 | 장바구니를 확인하라고 |
  | 400(형식 오류) | 400 이고 `fieldErrors` 의 `items[i]` 가 줄을 가리키면 그 줄을 빼고 나머지를 **한 번** 다시 보낸다(요청 전체가 거절돼 아무것도 반영되지 않았다). 뺀 줄은 다시 보낸 결과가 성공 · 다시 400 일 때만 지우고, 그 밖(401 · 429 · 503 · 연결 실패)이면 남겨 다음 병합에서 다시 판정한다 — 502 · 504 는 다시 보낸 줄만 지운다. 항목을 가릴 수 없거나 다시 보낸 것도 400 이면 보낸 줄을 지운다 | 옮기지 못한 수 |
  | 401 | 둔다, 화면을 다시 그린다 | 없음 |
  | 429 · 503 · 500 · 연결 실패 | 둔다, 다음 화면에서 다시 보낸다 | 없음 |

  연결 실패는 대개 요청이 나가지 않은 경우(오프라인)라 다시 보낸다 — 드물게 두 번 더해질 수 있지만 상한에서 잘린다.
  두 탭이 같은 로컬 줄을 동시에 보내지 않게 **Web Locks**(`cf-cart-merge`, 잡지 못하면 건너뜀)로 한 번만 돌리고 잠금 안에서 로컬을 다시 읽는다.
  로컬 줄은 BE 제약(맞춤 제작 키 형식 · 값 1~500자 · 20개)을 어기면 읽을 때 버린다 — 한 줄 때문에 병합 전체가 400 이 되지 않게.
  맞춤 제작 TEXT 입력은 상품 정의에 상한이 없어도 500자에서 막는다.
- **맞춤 입력 문자(#263)** — BE 는 value 에 제어 문자(`\p{Cc}`, 탭 · 개행 · NUL 포함)나 짝 없는 서로게이트가 있으면 400 `CART_115` 다(`order-flow-guide.md` §2-1).
  같은 판정(`lib/cart/custom-input.ts`)으로 탭 · 개행은 공백 하나로, 그 밖은 지운다. 적용하는 곳은 TEXT 입력 단계(붙여넣기 포함, `CustomOrderFields`),
  줄 정규화(`normalizeOptions` — 로컬 담기 · 줄 판정 · 회원 담기 · 병합 요청), 로컬 장바구니 읽기(규칙 전에 저장된 줄 — 지워서 같은 줄이 되면 합친다)다.
  서식 문자(이모지 ZWJ 등)는 막지 않는다.
- **담기 버튼**(상세 구매 패널 · Shop the Look)은 `useCartAction()` 하나로 진행 중 · 429 쿨다운 · 결과 문구를 다룬다. 담은 뒤에는 버튼 아래에
  결과와 장바구니 링크를 보이고 머무른다. Shop the Look 은 줄마다 차례로 담고, 한 줄이 거절돼도(상한 · 품절) 나머지는 담아 담긴 수와 이유를 함께 알린다.
  429 · 연결 실패면 멈춘다(나머지도 같다).
  429 쿨다운 중에는 선택(옵션 · 수량 · 맞춤 입력)을 바꿔도 요청 제한 안내를 지우지 않는다 — 막힌 버튼의 이유가 보이게(#91).
- 오류 문구는 `CART_*` 코드로 사전(`dict.cart.errors`)에서 고른다. 서버 메시지는 그대로 보이지 않는다(영문 스토어).
- 맞춤 제작 입력은 필수 칸을 채워야 담을 수 있다. 입력값이 상품 정의에 맞는지는 주문 생성(#13)이 본다. 회원 줄 응답에는 입력 항목 이름(label)이
  없어 키(`fieldKey`)를 그대로 보인다 — BE 응답에 생기면 바꾼다. 회원 줄에는 썸네일도 없어 양쪽 모두 이미지 없이 그린다.
- **프리오더 안내(#60)** — 줄의 옵션 아래에 `프리오더 · ○월 ○일부터 순차 발송` 을 보인다. 비회원 줄은 이미 읽는 상품 상세의 `preorder` 로 안다.
  회원 줄은 장바구니 응답(`CartLine`)에 프리오더 정보가 없어 보이지 않는다 — BE 장바구니 응답에 생기면 `memberLineView` 에서 채운다. 주문서도 같다.
- 주문하기는 구매 가능한 줄이 있을 때 주문서(`/checkout`, §6-1)로 가는 링크다. 없으면 막아 둔다. 비회원 장바구니에는 주문하기가 없다(로그인 링크).

### 6-1. 주문서 (`/checkout`, #26 · BE #13 B2 · C3)

- **진입** — 견적 API 가 로그인 회원만 받으므로 `requireSession('/checkout')`. 장바구니 · 배송지는 이 화면이 브라우저에서 직접 읽는다(장바구니 화면에서 넘겨받지 않는다 — 새로 고쳐도 같은 주문서다).
  401(어느 API 든)이면 다시 로그인 안내로 멈춘다.
- **주문할 줄** — 장바구니에서 구매 가능하고(ON_SALE) 수량이 상한 안인 줄만(`orderLines`, 장바구니 합계와 같은 기준). 판매 불가 줄을 하나라도 보내면 견적 전체가 409 `ORDER_003` 이라 미리 빼고,
  뺀 줄 수를 장바구니 링크와 함께 알린다. 구매 가능한 줄이 없으면 견적하지 않는다.
- **배송지** — 저장된 배송지에서 고르거나(기본 배송지 → 첫 배송지 순으로 먼저 선택), 이 주문에만 쓰는 새 배송지를 입력한다(`AddressForm`, 배송지 목록에 저장하지 않는다).
  저장된 배송지가 없으면 새 배송지 폼이 바로 열리고 취소가 없다(돌아갈 곳이 없다). 새 배송지 입력을 취소하면 직전에 고른 배송지로 돌아간다.
  견적에는 국가 · 우편번호만 간다(`quoteDestination`, 빈 우편번호는 null). 주소 표시는 배송지 관리와 같은 `AddressDetails` 다.
  포커스는 폼이 열리면 첫 칸으로, 닫히면 새 배송지 요약의 "수정" · 돌아간 배송지 라디오로 옮긴다.
- **견적** — `POST /api/v1/orders/quotes` 를 배송지 · 줄이 바뀔 때마다 부른다(본문을 직렬화한 값이 key — 늦게 온 이전 견적은 버린다). 멱등 키는 없다(계산만 하고 저장하지 않는다).
  **금액은 응답 값만 보인다** — 상품 합계 · 배송비 · 총액과 줄 금액(요청 순서). 배송비 근거(무료배송 · 기본 배송비 · 도서산간 추가금 · 해외 무게 요금 · 아직 못 넘은 무료배송 기준)도 서버 값을 옮겨 적을 뿐 계산하지 않는다(`shippingNotes`).
  미리보기는 재고를 보지 않는다 — 재고 부족은 주문 생성에서 난다.
- **실패 자리**(`quoteFailure`) — 배송지 자리: 정책 없음 · 배송 불가 국가 · 통화(`ORDER_012~014`) · 배송지 칸 검증(`destination*`, `ORDER_116` · `121~124`).
  배송지 실패는 새 배송지를 고치는 동안(견적이 멈춰 있어도) 그대로 보이고, 배송지를 바꾸거나 견적이 되면 지운다. 금액 칸은 "배송지를 바꾸거나 고치면 다시 계산합니다" 다.
  장바구니 안내(장바구니 링크): 판매 불가 · 맞춤 입력 · 수량 상한 · 통화 혼합 · 해외 배송 정보 · 중복(`ORDER_003~008`), 무게 초과(`ORDER_015` — 고칠 곳은 수량)와 그 밖의 줄 검증.
  다시 계산 버튼: 503(`ORDER_002` · `ORDER_011`) · 429(`Retry-After` 동안 막음) · 500 · 502/504 · 연결 실패 — 미리보기는 저장하지 않아 다시 보내도 된다.
  429 쿨다운 중에도 배송지를 바꾸면 견적을 보낸다 — 막는 것은 같은 버튼(다시 계산)이고, 배송지 변경은 사용자의 새 요청이라 자동 재시도가 아니다(#91).
  문구는 `checkout.errors` 코드별 문구다. 사전에 없는 코드는 국문 스토어에서 서버 문구, 영문 스토어에서 묶음별 범용 문구다(서버 국문을 영문 화면에 보이지 않는다).
  상품 이름 목록이 붙는 코드(003 · 004 · 005 · 007 · 008)는 국문 사전에서 비워 서버 문구(이름 포함)를 쓰고, 영문 사전은 같은 뜻의 영문 문구를 둔다.
  금액이 바뀌면 스크린리더에는 총액 한 줄만 읽힌다(`role="status"`), 실패는 `role="alert"` 다.
- **주문하기**(`POST /api/v1/orders`, BE #13 C3) — 지금 보이는 견적이 있을 때만 누를 수 있다. 본문은 견적과 같은 줄 · 배송지 전체(`savedShippingAddress` · `draftShippingAddress`) ·
  `expectedTotalAmount`(보이는 견적 총액, `orderRequest`)다. 서버가 다시 계산해 다르면 재고를 잡기 전에 409 `ORDER_010` 이다 — 화면은 금액을 계산하지 않는다.
  - **멱등 키** — 누름 한 번이 시도 하나이고 `createIdempotencyKey()` 로 키를 하나 만든다. 같은 키로 다시 보내도 되는 실패(5xx · 502/504 · 503 · 연결 실패 · 봉투 아님 · 429 · 409 `ORDER_018` 처리 중)로 끝나면
    시도를 남겨 두고, 다음 누름의 본문이 같으면 같은 키를 쓴다(`nextAttempt`). 그 밖의 4xx 는 시도가 끝난 것이라 다음은 새 키다. 본문이 바뀌면(배송지 · 새 견적) 언제나 새 키다 — 같은 키 다른 본문은 409 `ORDER_019`.
    새로 고치면 남겨 둔 시도는 사라진다(미결제 주문은 결제 기한이 지나면 만료된다 — 만료 스케줄러는 BE #96).
  - **더블 클릭** — 요청 중에는 버튼과 배송지 선택을 막는다. 상태 반영 전 두 번째 클릭은 ref 로 막는다. 429 면 주문 쓰기 전용 `useRetryCooldown` 으로 `Retry-After` 동안 막는다(견적 버튼과 따로, #91).
  - **실패**(`orderFailure`) — 금액 변경(`ORDER_010`): 안내 후 견적을 다시 받는다(새 총액 확인 뒤 다시 누름). 재고 부족(`ORDER_016`): 장바구니 링크와 함께 안내.
    장바구니 · 배송지 코드는 견적과 같은 자리로 가르고, 배송지 칸 검증(`shippingAddress.*`, `ORDER_117~128` · `138~140`)은 서버 문구에 필드 이름이 섞여 있어 `checkout.orderErrors` 사전 문구만 쓴다.
    실패 안내는 같은 배송지 · 줄에서만 보이고, 배송지를 바꾸면 사라진다.
  - **주문 뒤** — 결제위젯(#27, BE #14 뒤) 전까지 결제 단계 자리 표시(`OrderPlaced`)로 바뀐다: 주문 번호 · 주문 응답의 금액 · 결제 기한(`paymentDueAt`, 브라우저 시간대).
    BE 가 장바구니를 비우지 않으므로 장바구니는 그대로다 — 비우는 시점은 결제 연동에서 정한다.

## 7. 마이페이지 (#28)

| 경로 | 내용 | API |
|------|------|-----|
| `/mypage` | 하위 화면 메뉴. 주문 내역은 주문 조회 API(BE #16) 전까지 "준비 중" | — |
| `/mypage/profile` | 회원 정보 수정 — 이메일은 읽기만 | `GET` · `PATCH /api/v1/members/me` |
| `/mypage/addresses` | 배송지 목록 · 추가 · 수정 · 삭제 · 기본 지정 | `/api/v1/members/me/shipping-addresses/**` (BE #7) |
| `/mypage/social` | 카카오 · 네이버 간편 로그인 연결 — 연결 여부 · 비밀번호 재인증 후 연결(§3-2) | `/api/v1/members/me/social-accounts/**` (BE #355) |

- **진입** — 인증 쿠키가 없으면 서버에서 `/login?next=<경로>` 로 보낸다(`requireSession`, `lib/auth/require-session.ts`).
  쿠키가 있어도 세션이 끝났을 수 있어, 화면의 API 가 401 이면 다시 로그인 안내(`SignedOutNotice`)로 멈춘다.
- **읽기 · 쓰기는 브라우저에서 BFF 로** 한다(`apiFetch`). RSC(`serverFetch`)는 재발급을 못 해 만료된 access 쿠키로는 401 이 난다.
  국가 선택지만 서버에서 읽는다(해외 배송 정책 — 공개 API).
- **회원 정보** — 바뀐 칸만 `PATCH` 하고, 바뀐 것이 없으면 보내지 않는다(보내면 400 `AUTH_115`). 이름은 앞뒤 공백, 연락처는 하이픈 · 공백을 빼고 비교한다.
  연락처를 비우면 `""`(BE 의 "지우기")를 보낸다. 동시 수정(409 `AUTH_004`)이면 다시 읽어 와 확인 후 다시 저장하게 한다(`lib/account/profile-form.ts`).
- **배송지** — 쓰기가 끝나면 목록을 다시 읽는다. 기본 승격(기본을 지우면 최근 주소) · 정렬(기본 먼저, 최신순)을 화면에서 흉내 내지 않는다.
  - 전체 교체(`PUT`)는 보내지 않은 선택 칸을 지우므로 언제나 모든 칸을 싣는다. 빈 선택 칸은 null 이다(`toAddressBody`). 기본 여부는 추가에서만 보낸다.
  - 다른 요청이 먼저 바꿨거나(409 `AUTH_022`) 이미 없는 주소(404 `AUTH_020`)면 목록을 다시 읽고 알린다. 상한(409 `AUTH_021`) · 회원 잠금 대기(409 `AUTH_023`)는 문구만이다.
    추가 버튼은 목록의 `maxCount` 에 닿으면 막는다.
  - 결과를 모르는 실패(502 · 504 · 연결 끊김)면 폼을 닫고 목록을 다시 읽어 반영 여부를 확인하게 한다. 배송지 쓰기에는 멱등 키가 없어
    다시 저장하면 같은 주소가 하나 더 생길 수 있다(`isUnknownOutcome`, `api-client.md` §3).
  - 국내 주소는 도시 · 주 칸을 보이지 않고 null 로 보낸다 — 해외에서 국내로 바꾼 뒤 숨은 값이 저장되지 않게.
  - 오류 문구는 `AUTH_*` 코드로 사전(`dict.account.errors`)에서 고른다. 칸 오류는 `fieldErrors[].field` 로 칸에 붙인다.
  - 형식 검증(연락처 · 우편번호 · 국가별 필수 칸)은 BE 가 정본이다. 화면은 필수 표시(국내 우편번호 · 해외 도시)와 길이만 브라우저에 맡긴다.
- **국가** — 국내(KR)가 맨 앞이고, 나머지는 해외 배송 정책(`/api/v1/shipping-fees/international-policy`)의 `zones[].countryCodes` 다.
  해외 배송을 끄거나 정책을 읽지 못하면 국내만이다. 정책에서 빠진 국가의 옛 주소도 고칠 수 있게 저장된 주소의 국가를 더한다.
  이름은 `Intl.DisplayNames` 로 그 언어의 국가명이다. 새 배송지의 기본 국가는 국문 KR, 영문 US(선택지에 없으면 KR)다(`lib/account/countries.ts`).
- **주소 검색** — 국내 주소는 카카오(다음) 우편번호 서비스로 우편번호 · 도로명 주소를 채운다(`lib/account/postcode.ts`). 키가 필요 없고
  국내 주소 폼이 뜰 때 스크립트(`t1.daumcdn.net`)를 미리 불러 둔다 — 첫 클릭에 스크립트를 기다리면 클릭 흐름이 끊겨 Safari 등이 팝업을 막을 수 있다.
  불러오지 못하면 클릭 때 다시 시도하고, 그래도 안 되면 직접 입력을 안내한다. CSP 를 도입하면 이 출처를 허용한다.
- 연락처는 저장값(숫자만)을 국내만 하이픈을 넣어 보인다(`formatPhone`).

## 8. 배송비 안내 (#171)

배송비 정책은 delivery 의 공개 API 로 읽어 **표시만** 한다. 실제 배송비는 주문서 견적(order-service → delivery internal 견적, #13)이 정한다.

| 스토어 | 정책 | 안내 |
|--------|------|------|
| 국문 | `GET /api/v1/shipping-fees/policy` | 기본 배송비(0 이면 "무료배송") · 무료배송 기준 · 도서산간 추가 배송비(무료배송이어도 붙는다) |
| 영문 | `GET /api/v1/shipping-fees/international-policy` | 기본료 "부터"(무게 요금이 더해진다) · 무료배송 기준 · 관세 안내 문구(`customsNotice`) |

- 서버(`loadShippingNotice`, `lib/catalog/load-shipping-notice.ts`)에서 요청마다 한 번 읽는다. 정책 미설정 · 해외 배송 꺼짐(`dataBody: null`) · 조회 실패 · 모양 불일치면
  안내를 그리지 않는다(`readShippingPolicy` — 틀린 금액을 보이지 않게). 상품 상세는 상품 조회와 함께 읽어 기다림을 늘리지 않는다.
- **상품 상세** — "배송 · 교환" 아코디언 맨 위에 배송비 줄, 그 아래 브랜드 설정의 기간 · 교환 문단(고객사 값, 없으면 숨김).
- **장바구니** — 무료배송 기준이 있으면 그 문구만 더한다(회원 · 비회원). **합계와 비교하거나 남은 금액을 계산하지 않는다** — 클라이언트 금액 계산 금지,
  무료배송 판정 기준(할인 후 상품 합계)도 주문서에서 정해진다.
- 금액은 `formatMoney(money, locale)` 로 정책 통화 그대로 보인다(환산하지 않는다).

### 8-1. 상품 사이즈표 (#218 · BE #216)

- 상품 상세는 `GET /api/v1/products/{id}/size-chart` 를 상품 · 배송비 안내와 **함께** 읽는다(`loadSizeChart`). 표가 없거나(null) 실패하면 null 이고 상품 화면을 막지 않는다.
- 표가 있으면 구매 정보 아래 아코디언의 **사이즈 가이드 항목을 이 표로 그린다**(브랜드 공통 가이드 대신). 없으면 기존 표시 그대로다 —
  브랜드 공통 가이드(`content[locale].productInfo.sizeGuide`)와 상세 설명 속 사이즈 이미지(이미지 사이즈표만 있는 상품 · 이관 전).
- 표는 진짜 `<table>` 이고 첫 칸이 사이즈 열이다. 좁은 화면은 표 상자만 가로로 스크롤하고(페이지는 넘치지 않는다) **첫 열은 `sticky left-0 bg-bg`** 로 남는다.
  값은 서버 숫자를 그 언어 숫자 표기로만 바꾼다(`Intl.NumberFormat`, 소수 둘째 자리까지). 단위는 표 아래 안내 첫머리(`단위 cm`), 항목 설명은 "재는 방법" 목록이다.
- **단위 전환은 서버가 한다** — `?unit=CM|INCH` 이면 서버가 바꾼 값과 `displayUnit` 을 준다. 화면은 계산하지 않는다. 언어별 단위는 `sizeChartUnit`(`lib/catalog/size-chart.ts`) 한 곳이고,
  영문 스토어(#31)를 열기 전이라 지금은 모든 언어가 저장 단위다. 영문 inch 기본 · cm/inch 전환 버튼은 #31 에서 이 함수와 같은 API 로 붙인다.

## 9. 어드민 콘솔 (#187)

- **화면 틀** — 콘솔 화면은 `app/(console)/` 아래에 두고 `AdminShell`(상단 바 · 왼쪽 메뉴 · 본문)을 같이 쓴다. 메뉴는 `AdminShell` 의 `NAV` 한 곳에서 더한다
  (화면이 아직 없으면 `href` 없이 "준비 중"). 로그인 화면(`app/login`)은 이 그룹 밖이다. 상단 바에 작업 진행 · 알림(아래 #214) · 도움말 · 계정 메뉴(내 비밀번호 변경 · 로그아웃, #352)가 있다. 어드민은 국문만이고 i18n 사전을 두지 않는다.
- **읽기 · 쓰기는 클라이언트에서 BFF 로** — 편집 화면은 `apiFetch` 로 같은 origin BFF(`app/api/[...path]`, 어드민 쿠키 `cf_admin_at`)를 부른다.
  CSRF 규칙은 스토어프론트와 같고(§2), 재발급은 하지 않는다(§3 refresh 쿠키가 없는 설정).
- **401 · 403 은 오류가 아니라 상태다** — `accessDeniedOf` 로 가르고 `AccessNotice` 를 그린다. 401(세션 끝 — 만료 · 로그아웃 · 차단)이면
  지금 경로를 `next` 로 붙인 로그인 화면으로 바로 보낸다(이동이 늦어도 누를 수 있게 로그인 링크를 같이 둔다). 403(ADMIN 아닌 토큰)은 "관리자 권한이 없습니다" 안내와 로그인 링크만 둔다.
  **저장(쓰기) 중 401 은 예외다** — 세션이 30분뿐이라 폼을 오래 쓰면 생기는데, 로그인 화면으로 보내면 입력이 사라진다. 폼을 그대로 두고
  `SESSION_EXPIRED_ON_SAVE`("새 탭에서 다시 로그인한 뒤 저장해 주세요")를 폼 전체 오류로 둔다. 401 은 서버가 처리하지 않은 요청이라 다시 저장해도 안전하다.

### 도움말 · 온보딩 투어 (#228)

- **상단 바 도움말(?)** — 지금 화면의 짧은 설명 · 자주 묻는 질문(`<details>`)과 "이 화면 안내 다시 보기" · "처음 안내 다시 보기" · "모든 화면 안내 다시 켜기". 내용은 `lib/tour/tours.ts` 의 경로 → 도움말 표 한 곳이다.
- **투어** — 처음 콘솔에 들어오면 콘솔 투어(메뉴 · 알림 · 도움말), 그다음 화면 투어(재고 · 옵션별 재고 · 사이즈표 고르기 · 사이즈표 입력 · 사이즈표 템플릿)를 한 번씩 자동으로 보인다.
  끝내거나(마치기) 닫으면(×, Esc, 바깥 누르기) 본 것으로 남기고 다시 띄우지 않는다. 화면을 옮겨 끊긴 투어는 본 것으로 남기지 않는다. 실행은 콘솔 레이아웃의 `TourRunner` 하나다.
- **가리키는 요소는 `data-tour="<이름>"` 속성**이다 — 클래스 · 문구가 바뀌어도 투어가 깨지지 않게. 목록 · 표는 API 응답 뒤에 그려져 모두 나타날 때까지(최대 4초) 기다리고,
  빈 목록 · 템플릿을 고르기 전의 표처럼 없을 수 있는 요소는 `optional` 단계라 없으면 그 단계만 뺀다. 필수 요소가 없으면(권한 없음 안내 · 불러오기 실패)
  시작하지 않고 본 것으로도 남기지 않는다. **기다리는 동안 운영자가 누르거나 입력하면 이번 방문에는 자동으로 띄우지 않는다**(조작 중에 덮지 않게, 다음에 다시 뜬다).
  같은 투어라도 경로가 바뀌면 닫고 새 화면에서 다시 고른다. 끝나면 투어 전의 포커스로 돌린다.
- **라이브러리** — `driver.js`(MIT, 의존성 없음). React Joyride 는 의존성이 많고 Shepherd.js 는 AGPL 이라 고르지 않았다. 팝오버가 `role=dialog` · Tab 가둠 · Esc · 화살표 키를 지원한다.
  **제목 · 설명을 innerHTML 로 넣으므로 투어 문구는 평문 상수만 쓰고 이스케이프한다**(`driveSteps`). 운영 데이터를 넣지 않는다. 투어 중에는 가리킨 요소를 누를 수 없다(누르면 화면이 바뀌어 끊긴다).
  색은 `globals.css` 의 `.driver-popover.cf-tour` 에서 중립 토큰으로 덮는다(driver.css 가 뒤에 붙어 우선순위를 한 단계 높이고 :hover · :focus 도 덮는다).
  화살표(흰색)와 오버레이(검정 50%)는 라이브러리 기본이다 — 중립 토큰 배경이 흰색이라 맞고, 토큰을 바꾸면 함께 본다. 움직임 줄이기 설정이면 애니메이션 · 부드러운 스크롤을 끈다.
- **진행 상태는 운영자별 서버 기록이 정본이다**(BE #357 `GET /api/v1/admin/onboarding/me` · `PUT .../me/tours/{guideKey}`, 계약은 `backend/docs/api-design-guide.md`
  "운영자 온보딩 계약"). `lib/tour/progress.ts` 의 `tourProgress` 가 들고, `TourRunner` 가 붙을 때(로그인 직후) 불러온다. BFF 는 공용 프록시(`app/api/[...path]`) 그대로다.
  - 서버 안내 키는 투어 아이디에서 `-v<숫자>` 꼬리를 뗀 것이다(`console-v1` → `console`, 버전 1). 투어 내용을 크게 바꾸면 아이디 버전(`stocks-v1` → `v2`)을 올리고
    서버 허용 목록(`operations.onboarding.guides`)의 버전도 같이 올린다. 투어를 더하면 허용 목록에도 더한다 — 없으면 그 투어는 이 브라우저 기록으로만 간다.
  - 시작하면 `IN_PROGRESS`(단계 0), 마지막 단계에서 닫으면(마치기) `COMPLETED`, 그 전에 닫으면 `SKIPPED`(본 단계)다. "다시 보기" 는 `NOT_STARTED`(재실행)를 먼저 보내고,
    "모든 화면 안내 다시 켜기" 는 서버가 아는 투어마다 재실행을 보낸다. 투어마다 기록을 차례로 보내 재실행이 그 뒤 진행보다 먼저 닿게 하고, 아직 못 보낸 진행 기록은 나중 것만 보낸다.
  - 화면에는 먼저 반영하고(낙관적) 보낸 응답으로 상태를 바꾸지 않는다 — 서버가 단조 병합하므로 다음 불러오기에서 맞춰지고, 돌고 있는 투어가 응답 때문에 닫히지 않는다.
  - **불러오기 전 · 불러오지 못하면(5xx · 네트워크 · 401) 자동 시작하지 않는다** — 운영자 입력을 막지 않게. 다시 보기는 된다. 서버 기록이 늦게 와 시작할 때 입력 칸에 포커스가 있어도 띄우지 않는다.
  - 오류 대응 — GET 404(온보딩 API 가 없는 서버)는 예전처럼 이 브라우저 기록으로 간다. PUT 404 `ONBOARDING_001` · 400 은 그 투어만 이 브라우저 기록으로 돌린다.
    409 `ONBOARDING_020`(옛 번들)은 GET 을 한 번만 다시 불러오고, 서버 버전이 더 새로우면 도움말에 새로 고침을 안내한다(그 세션은 이 브라우저 기록).
    409 `ONBOARDING_021` · 5xx · 네트워크는 화면 기록을 둔 채 30초 뒤 다시 보낸다(연달아 3번 실패하면 다음 기록 때까지 쉰다). 401 · 403 은 못 보낸 기록을 버린다.
- **이 브라우저 기록(localStorage `cf_admin_tour_seen`, 예전 저장 방식)은 힌트다** — 서버 완료로 올리지 않고, 서버가 정하지 못하는 투어(위 폴백)에만 쓴다.
  서버 기록이 있는 투어는 이 브라우저 기록을 보지 않는다. 여러 탭은 저장 직전에 저장소를 다시 읽어 합친다.
- **다른 운영자 기록이 보이지 않게** — 로그인 화면에 오면(로그아웃 · 세션 만료 · 계정 전환, `LoginForm`) 그리고 로그아웃 버튼에서 `signOut` 으로 메모리 기록 · 못 보낸 기록 ·
  이 브라우저 기록을 지운다. 콘솔에 다시 들어오면 새 운영자 기록을 불러온다. 운영자가 바뀌었을 수 있는 때(`refresh` · `signOut`)는 진행 중인 보내기 · 불러오기 결과를 버린다.
- 실제 업무 완료(`tasks`)와 시작 체크리스트는 #358 이다 — 투어 완료는 안내를 본 기록일 뿐 업무 완료가 아니다. 상품 등록 · 주문 · 송장 · 리포트 화면이 생기면 같은 표에 투어를 더한다.

### 로그인 · 세션 (`/login`, #204)

- **세션은 access 토큰 하나다** — 관리자 로그인 `POST /api/v1/auth/admin/login`(BE #191)은 `{ accessToken, accessTokenExpiresIn }` 만 주고 refresh 를 주지 않는다.
  BFF 전용 route handler(`app/api/v1/auth/admin/login`, `createAdminLoginHandler`)가 `cf_admin_at` 하나를 응답 수명으로 심고, 브라우저에는 토큰 없는 성공 봉투만 준다.
  만료되면(기본 30분) 다시 로그인한다 — 프록시는 재발급하지 않는다(§3 refresh 쿠키가 없는 설정).
- **진입 확인** — `src/proxy.ts` 가 `cf_admin_at` 쿠키가 없으면 `/login?next=<경로>` 로 보낸다(`/api` · `/_next` · 정적 파일 · `/login` 은 제외).
  쿠키 수명이 토큰 수명이라 만료된 세션은 다음 화면 이동에서 여기 걸린다. 쿠키가 있어도 토큰이 무효일 수 있어(로그아웃 · 차단) 그때는 API 401 → `AccessNotice` 가 보낸다.
  토큰 유효성 · 권한의 정본은 게이트웨이 · BE 다.
- **`next`** 는 `safeReturnPath`(`lib/auth/return-path.ts`)로 거른다 — 같은 사이트 경로만, `//` · 역슬래시 · 제어 문자 · `/login` · `/api/` 는 콘솔 홈으로.
  검사는 `resolveSitePath` 로 `.` · `..` · `%2e%2e` 마디를 브라우저처럼 풀어 둔 값으로 한다(`/x/../login` 이 비켜 가지 않게, #347). 이동은 원래 값으로 한다 —
  브라우저가 같은 규칙으로 풀어 도착지가 검사한 경로다. 스토어프론트 `safeReturnPath`(로그인 · 가입 화면 → 메인)도 같은 방식이다.
- **로그인 실패** — 400 `AUTH_030`(이메일 없음 · 비밀번호 틀림 · 고객 계정 모두)은 칸을 짚지 않는 서버 문구 하나, 필드 검증(`AUTH_130~132`)은 그 칸에,
  429 는 남은 시간 안내, 그 밖은 한 문구다(`loginErrors`). 어드민은 국문만이라 서버 문구를 그대로 쓴다.
- **로그아웃** — 상단 바 계정 메뉴의 버튼이 `POST /api/v1/auth/logout`(`createLogoutHandler`, Bearer + `{}`)을 부르고 `/login` 으로 간다.
  BFF 는 서버 결과와 무관하게 쿠키를 지우고 200 이다. 요청 자체가 실패하면(연결 끊김) 쿠키가 남으므로 이동하지 않고 버튼 옆에 알린다.
- **오류를 칸에 붙인다** — 서버 fieldErrors 의 `field`(요청 본문 경로)를 `fieldKey` 로 폼 칸 키로 바꾼다.
  `remoteAreas[0].surcharge.amount` → `remoteAreas.0.surcharge`(금액은 amount · currency 를 한 칸으로), `zones[0].countryCodes[2]` → `zones.0.countryCodes`(목록 원소는 목록 칸으로).
  줄을 알려 주지 않는 도메인 오류는 그 묶음 칸에 붙이고, 문구는 서버 문구를 쓴다. 폼이 그리지 않는 키(`request` 등)와 모르는 오류 · 연결 실패는
  저장 버튼 위 폼 전체 오류 한 문구다. 칸 오류만 있으면 그 자리에 "입력값을 확인해 주세요" 요약을 둔다(오류 칸이 화면 밖일 수 있다).
  오류 키가 줄 순번이라 줄을 더하거나 지우면 오류를 지운다. 저장 중에는 폼을 잠근다(`fieldset disabled`).
- **금액 입력** — 칸은 정책 통화의 주 단위 글자로 받고, 보낼 때 `parseMoneyInput`(`@cf/api-client`)으로 최소 단위 정수로 바꾼다(표기 변환일 뿐 계산이 아니다).
  형식이 틀린 글자(음수 · 소수 자릿수 초과 등)는 보내지 않고 그 칸에 오류를 붙인다. 빈 필수 칸은 amount null 로 보내 서버 검증(정본)에 맡긴다.

### 관리자 계정 · 내 비밀번호 (`/admins` · `/account/password`, #352 · BE #193)

- **목록은 회원 목록의 `role=ADMIN`** 이다(`GET /api/v1/admin/members`, 마스킹 값 · 최신순 · 주소의 `page` 가 정본). 전용 목록 API 가 없고
  **응답에 활성 여부가 없어 비활성화한 계정도 목록에 남는다** — 이번 방문에서 끈 계정만 "비활성화함" 표시를 붙이고 버튼을 거둔다. 다시 끄면 404 `AUTH_033` 이다.
- **추가** — `POST /api/v1/admin/admins` `{email, password, name}`. 폼 칸 이름은 `staffEmail` · `initialPassword` 이고 보낼 때 BE 이름으로 바꾼다
  (브라우저가 남의 처음 비밀번호를 내 로그인 자격 증명으로 저장하자고 하는 일을 줄인다). fieldErrors(`AUTH_130~132` · `140~144`)는 칸에, 409 `AUTH_002` 는 이메일 칸에 서버 문구로.
- **비활성화** — 줄 안 확인("비활성화할까요?") 뒤 `PATCH /api/v1/admin/admins/{memberId}`(본문 없음). 409 `AUTH_031`(마지막 관리자) · 400 `AUTH_146` 은 줄에 서버 문구,
  404 `AUTH_033` 은 "이미 꺼진 계정" 으로 표시한다. **503 `AUTH_013` 은 실패가 아니다** — BE 는 계정을 끈 뒤(커밋) 옛 토큰 컷오프(Redis)를 하므로 꺼진 것으로 표시하고
  "기존 로그인을 끊지 못해 최대 30분 쓸 수 있다" 고 알린다. 연결 실패 · 결과를 모르는 실패는 줄에 알리고 목록을 다시 읽는다. 추가 폼이 열린 동안에는 줄 버튼을 막는다(비활성화 401 이 입력을 지우지 않게).
  자기 계정도 끌 수 있다(BE 허용) — 끄면 토큰이 막혀 다음 조회에서 로그인 화면으로 간다(도움말에 적는다).
  포커스는 카테고리와 같은 규칙이다 — 확인이 열리면 "취소" 로, 취소하면 그 줄 "비활성화" 로, 폼이 열리면 이메일 칸으로, 닫히거나 끄고 나면 "관리자 추가" 로.
- **내 비밀번호 변경** — 상단 바 계정 메뉴에서 연다. `PATCH /api/v1/admin/me/password` `{currentPassword, newPassword}` 뒤 BE 가 지금 토큰을 끊으므로
  로그아웃(쿠키 삭제)하고 `/login?reason=password-changed` 로 간다. 로그아웃 요청이 실패해도 토큰은 이미 막혔으니 이동한다. `reason` 은 허용한 이름만 문구로 바꾼다(`loginNotice`).
  400 `AUTH_032`(현재 비밀번호 틀림)는 현재 비밀번호 칸에, `AUTH_141` · `142` · `145` 는 그 칸에. 새 비밀번호 확인 칸은 화면에서만 맞춰 본다.
  503 `AUTH_013` 은 새 비밀번호가 이미 저장된 뒤라 성공과 같이 로그아웃 · 로그인 화면으로 보낸다(실패로 두면 같은 값을 다시 보내 `AUTH_032` 를 받는다).
- **비밀번호는 React 상태 · 주소 · 저장소 · 로그에 두지 않는다** — 두 폼 모두 제어하지 않는 칸이고 제출할 때만 `FormData` 로 읽는다. 보내기 전 규칙 확인(`passwordProblem`, 12~64자 ·
  영문과 숫자 · 공백 없는 ASCII — BE `AdminRequestPatterns` 와 같다)은 고정 문구만 돌려준다. 결과를 모르는 실패(`unknownOutcome`)는 다시 보내기 전에 결과를 확인하게 안내한다.

### 배송비 정책 (`/shipping-policies/domestic` · `/international`)

- 국내 `GET/PUT /api/v1/admin/shipping-policies/domestic`(기본 배송비 · 무료배송 기준 · 도서산간 우편번호 구간),
  해외 `.../international`(기본료 · 무료배송 기준 · 관세 안내 · 국가 그룹별 무게 구간 요금). 둘 다 **전체 교체 PUT + `version`** 이다(`backend/docs/api-design-guide.md` §6-1).
- GET 이 `null` 이면 처음 만들기 폼(version null)이다. 통화는 처음 만들 때만 고르고, 정책 안의 금액은 모두 그 통화로 보낸다
  (다르면 `DELIVERY_003`, 저장된 통화를 바꾸면 `DELIVERY_012`).
- **409 `DELIVERY_006`(다른 곳에서 먼저 저장)** 이면 덮어쓰지 않는다 — 최신 값을 다시 읽어 폼을 바꾸고 "다시 불러왔습니다" 를 안내한다. 고치던 값은 버린다.
- **502 · 504(`unknownOutcome`)** 는 저장됐을 수도 있어 실패로 단정하지 않는다 — 다시 읽어 폼을 바꾸고 "저장됐는지 확인해 주세요" 를 안내한다(자동 재시도 없음).
- 해외 정책에는 `remoteAreas` 를 보내지 않는다(보내면 `DELIVERY_136`). 국가 코드는 쉼표 · 공백으로 나눠 대문자로 보낸다 — 형식은 서버가 본다.
- 화면은 배송비를 계산하지 않는다. 실제 배송비는 주문서 견적(#13)이 정하고, 고객 화면의 안내는 공개 정책 API 를 쓴다(§8).
- **국내 변경 이력(#319)** — 국내 화면 폼 아래 `GET .../domestic/histories?page=&size=20`(BE #69, 최신순, size 상한 20)을 페이지로 보인다. 해외 이력 API 는 없다.
  한 건은 교체 시각 · 교체한 사람 · 칸(기본 배송비 · 무료배송 기준 · 도서산간 구간)별 이전 → 이후다. 바뀐 칸을 강조하고 구간은 반대쪽에 없는 줄만 굵게 한다(`compareHistory`).
  `before` 가 null 이면 "처음 설정"(설정 값만), 값이 같고 version 만 오른 교체는 "값 변경 없음" 이다. 금액은 `formatMoney` 로 옮겨 적기만 한다.
  교체한 사람은 응답에 내부 회원 아이디(`adminMemberId`)뿐이라 원문 대신 "관리자" 로 보인다(#305) — 아이디로 이름을 찾는 API 가 없다.
  저장 성공 · 409 · 결과 모름으로 다시 읽은 뒤에는 `usePolicyEditor` 의 `revision` 을 `key` 로 써서 이력을 첫 페이지부터 다시 읽는다.

### 상품 목록 (`/products`, #208)

- 어드민 상품 목록 `GET /api/v1/admin/products`(#9) — 숨김 · 판매 종료까지 전체, 페이지 20개. 등록 · 수정 · 이미지는 BE #9 PR-D/E 뒤에 붙인다(지금은 목록만).
- **조건은 주소(query string)가 정본이다** — 새로고침 · 뒤로 가기 · 링크 공유에도 같은 목록이다. 페이지(`page.tsx`)가 `searchParams` 를 `parseProductQuery` 로 읽어 넘긴다.
  허용 값만 받고 나머지는 "전체" 로 본다(손으로 고친 주소가 400 이 되지 않게 — 카테고리는 Long 범위 양의 정수만). 검색어는 앞뒤 공백을 빼고 100자에서 자른다. 기본값은 주소에서 뺀다(`productsHref`).
  페이지가 범위를 넘으면(상품은 있는데 이 페이지가 빔) "이 페이지에는 상품이 없습니다" 와 첫 페이지 링크를 보인다.
- 필터는 상품명 · 카테고리(하위 포함, 어드민 카테고리 트리에서 숨김 포함으로 고른다) · 판매 상태(저장된 값) · 노출 · 판매 가능(재고 반영) · 정렬(최신순 · 상품명순)이다.
  고친 조건은 "검색" 을 눌러야 주소로 가고 페이지는 처음으로 돌아간다. 폼은 주소가 바뀌면(뒤로 가기 · 페이지 이동) `key` 로 새로 시작한다.
  카테고리 트리는 화면에 한 번만 읽어 폼에 넘긴다(폼이 새로 시작해도 다시 받지 않는다). 못 읽어도 상품 목록은 쓴다(카테고리 칸에 안내). 주소의 카테고리가 트리에 없으면 그 값을 옵션으로 남겨 실제 조건을 보인다.
- 표는 서버 값을 표시만 한다 — 판매가 · 정가(`formatMoney`, 저장된 기본가 그대로), 판매 상태 · 판매 가능은 서버 `name`(설명은 `title`). 재고 · 원가 링크는 노출 상품만이다(§9 재고와 같은 이유). 사이즈표 링크는 숨긴 상품에도 있다(§9 사이즈표).
  표는 `relative overflow-x-auto` 상자 안에서만 가로로 스크롤된다.

### 회원 목록 (`/members`, #241)

- 어드민 회원 목록 `GET /api/v1/admin/members`(BE #6 · #191) — 최신 가입순, 페이지 20개. 조회는 서버 감사 로그에 남는다.
- **개인정보는 서버가 마스킹한 값을 그대로 보인다** — 이메일 · 이름 · 휴대폰(`backend/docs/api-design-guide.md` §2-4). 화면은 원문을 받지도, 볼 길을 두지도 않는다. 휴대폰이 없으면 `—`.
- **조건은 주소가 정본이다**(상품 목록과 같은 규칙) — `parseMemberQuery` 가 역할(`CUSTOMER` · `ADMIN`, 대소문자까지 같을 때만)과 페이지(숫자 글자 6자리까지)를 읽고
  나머지는 "전체" 로 본다(손으로 고친 주소가 400 `AUTH_198` 이 되지 않게). 기본값은 주소에서 뺀다(`membersHref`).
- 조건이 역할 하나라 폼 없이 링크(전체 · 고객 · 관리자)로 고르고, 고르면 페이지는 처음으로 간다. 페이지가 범위를 넘으면 첫 페이지 링크를 보인다.
- 역할은 국문 이름으로 보이고(`roleLabel`), 모르는 값이 오면 이름 그대로 보인다. 역할 링크의 선택 표시는 `aria-current="true"` 다(2페이지 이상이면 링크가 지금 주소와 다르다).

### 카테고리 (`/categories`, #206)

- 어드민 카테고리 API(#9, `backend/docs/api-design-guide.md` §2-5) — `GET /api/v1/admin/categories`(숨김 포함 트리), `POST`(만들기), `PUT /{categoryId}`(**전체 교체** — 이름 · 상위 · 노출 순서 · 노출 여부를 다 보낸다), `DELETE /{categoryId}`(행 삭제).
- **version 이 없어 마지막 쓰기가 이긴다.** 그래서 만들기 · 고치기 · 지우기 뒤에는 늘 트리를 다시 읽어 지금 상태를 보인다. 다시 읽는 동안에는 이전 트리를 그대로 둔다.
- **한 번에 한 가지만 한다** — 폼이 열려 있거나 삭제 중이면 다른 줄의 버튼 · "최상위 추가" 를 막는다. 그래서 폼이 열린 동안에는 트리를 다시 읽지 않고
  (상위 후보 · 편집 대상이 바뀌지 않는다), 삭제 401 로 로그인 화면에 갈 때 잃을 입력도 없다.
- **포커스** — 폼이 열리면 폼 제목으로, 닫히면(저장 · 취소 · 다시 읽기) 연 버튼으로 돌려준다(그 줄이 없어졌으면 화면 제목으로).
  삭제 확인이 열리면 "취소" 로, 확인을 취소하면 그 줄의 "삭제" 로 간다. 삭제 중 확인 버튼은 `disabled` 대신 `aria-disabled` 다(포커스가 body 로 빠지지 않게).
  결과 안내 live region 은 비어도 렌더 트리에 남긴다(`empty:hidden` 을 쓰지 않는다).
- 트리는 깊이 우선으로 펴서(`flattenTree`) 들여쓰기로 보인다. 숨김은 자신(`숨김`)과 숨긴 상위(`상위가 숨김`)를 가른다 — 숨기면 하위까지 고객 메뉴에서 빠지고 상품 노출은 바뀌지 않는다.
- **상위 고르기**는 3단계를 넘지 않고(옮길 카테고리의 하위 높이까지 더한다) 자기 자신 · 하위가 아닌 것만 보인다(`parentCandidates`). 고르기 쉽게 거를 뿐 정본은 서버다 —
  깊이 초과 `PRODUCT_040` · 순환 `PRODUCT_041` · 없는 상위 `PRODUCT_044` 는 상위 칸에 서버 문구로 붙인다. 3단계 카테고리에는 "하위 추가" 가 없다.
- **노출 순서**는 0 이상 정수 글자(Integer 최대값까지)만 숫자로 바꿔 보낸다(형식이 틀리면 보내지 않고 칸 오류, 빈 칸은 null 로 보내 서버 검증). 새로 만들 때 기본값은 형제 중 가장 큰 값 다음이다(맨 뒤).
  이름은 그대로 보낸다 — 앞뒤 공백 정리 · 50자(코드 포인트) · 금지 문자는 서버가 정본이라 `maxLength` 로 자르지 않는다. 같은 이름 · 같은 순서는 서버가 허용한다.
- **오류** — fieldErrors 는 칸에(`fieldKey`), 404 `PRODUCT_004`(다른 곳에서 지움) · 502/504 는 폼을 닫고 다시 읽어 안내, 409 `PRODUCT_045`(다른 변경 처리 중) 등 그 밖의 4xx 는 폼 전체 서버 문구다.
  저장 401 은 폼을 지킨다(위 공통 규칙). 403 은 `AccessNotice`.
- **삭제**는 그 줄에서 한 번 더 확인한다. 하위 있음 409 `PRODUCT_042` · 연결 상품 있음 409 `PRODUCT_043` 은 그 줄 아래 서버 문구다 — 자동으로 풀어 주지 않는다.
  삭제 401 은 잃을 입력이 없어 읽기처럼 로그인 화면으로 보낸다.

### 재고 (`/stocks` · `/stocks/[productId]`, #188)

- **상품 고르기** — 어드민 상품 목록 `GET /api/v1/admin/products`(#9 — 상품명 검색 · 숨김 포함 · 페이지). 옵션 · SKU 는 어드민 상품 상세 API 가 아직 없어
  고객 상품 상세(`/api/v1/products/{id}`)에서 읽는다. 고객 상세는 숨긴 상품에 404 라 **숨긴 상품은 목록에 보이되 고를 수 없게** 둔다(어드민 상세가 생기면 바꾼다).
- **재고 표** — `GET /api/v1/admin/stocks?skuIds=`(한 번에 100개, 넘으면 나눠 부른다 — `fetchStocks`). 재고 행이 없는 SKU 는 서버가 관리 · 0 · `updatedAt: null`(등록 전)로 준다.
  판매 가능 수량은 결제 전 선점분이 빠진 값이라 선점 수량을 옆에 함께 보인다.
- **조정** — `PATCH /api/v1/admin/stocks/{skuId}`. 수량은 **결과 수량(절대값)** 을 넣고, 화면에서 본 판매 가능 수량을 `expectedQuantity` 로 함께 보낸다(CAS).
  화면은 수량을 계산하지 않는다. 바뀐 것만 보내고(수량 · 관리 여부), 아무것도 안 바꿨으면 보내지 않는다. 사유가 비면 null 로 보내 서버 검증(`PRODUCT_108`)에 맡긴다.
  실사 수량을 넣을 때는 선점분을 뺀 값을 넣으라고 안내한다(초과 판매 방지, `backend/docs/order-flow-guide.md` §3).
- **409 `PRODUCT_020`(그사이 주문 등으로 수량이 바뀜)** 이면 덮어쓰지 않는다 — 그 SKU 만 다시 읽어 표와 폼을 바꾸고 안내한다. 502 · 504 도 다시 읽어 확인을 권한다.
  클래스 수준 검증(`targetPresent` · `expectedQuantityPresent`)처럼 칸을 특정하지 않는 오류는 폼 전체 오류다.
- 조정 · 이력 패널은 열리거나 다시 시작되면(조정 · 충돌 뒤) 제목으로 포커스를 옮긴다. 결과 안내는 늘 있는 live region 에 그 SKU 패널이 열려 있을 때만 쓴다.
  폼이 작아 "입력값을 확인해 주세요" 요약은 두지 않는다(오류 칸이 버튼 바로 위다). 무제한 SKU 를 관리로 켤 때는 수량 칸이 비어 있다(숨은 옛 수량을 보이지 않는다).
- **이력** — `GET /api/v1/admin/stocks/{skuId}/adjustments` 최신순 페이지(20개). 사유는 서버가 준 이름(`reason.name`)을 그대로 쓴다. 조정하면 첫 페이지부터 다시 읽는다.
- 표 머리의 `sr-only` 처럼 absolute 요소가 가로 스크롤 상자(`overflow-x-auto`) 안에 있으면 상자에 `relative` 를 준다 — 없으면 스크롤 상자 밖으로 나가 화면이 가로로 넓어진다.

### 원가 (`/sku-costs` · `/sku-costs/[productId]`, #395)

메뉴 `상품 > 원가`, 상품 목록 줄의 `원가` 링크(노출 상품만). 계약 정본은 `backend/docs/api-design-guide.md` §2-12(BE #361) — 모두 ADMIN 이다.
코드는 `lib/sku-cost/`(계약 타입 `types.ts` · 폼 · 검증 `form.ts` · 실패 처리 `errors.ts`), 화면은 `components/sku-cost/` 다.
**원가는 어드민 전용이다** — 스토어프론트 코드에서 이 API · 타입을 쓰지 않는다(BE 도 고객 응답에 넣지 않는다).

- **상품 · SKU 고르기** — 재고와 같다(`ProductPicker` → 고객 상품 상세로 옵션 · SKU). 숨긴 상품은 고를 수 없다.
  현재 원가 API 가 SKU 단건이라 표에 다 펼치지 않고(SKU 수만큼 부른다) 줄의 `원가` 버튼으로 SKU 하나의 패널을 연다. 패널이 열리면 제목으로 포커스를 옮긴다.
- **현재 원가** — `GET /api/v1/admin/skus/{skuId}/costs`. 유형은 `GOODS`(매입 · 제작 원가) · `PACKAGING`(포장 변동비)다.
  **응답에 없는 유형은 "미입력"** 으로 보이고 0원(입력한 값)과 다르게 그린다(`currentCostRows`). 금액은 `formatMoney` 로 옮겨 적기만 한다.
- **이력** — `GET .../costs/histories?costType=&page=&size=20`, 적용 시작 내림차순 페이지. 유형으로 거른다(바꾸면 첫 페이지).
  기록한 사람은 내부 회원 아이디뿐이라 "관리자" 로 보인다(배송비 이력과 같은 규칙).
- **추가** — `POST .../costs` `{costType, amount, effectiveFrom?, reason}`. 수정 · 삭제는 없다(append-only). 멱등 키가 없다.
  - **"지금부터" 는 `effectiveFrom` 을 빼고 보낸다** — 화면 시계로 채우면 서버 시각보다 앞서 `SKU_COST_006` 이 날 수 있다.
  - 원가가 이미 있는 유형은 통화를 그 통화로 고정하고(고르는 칸을 막는다) 시작은 "지금부터" 만 둔다. 미입력 유형(첫 원가)만 `datetime-local` 로 지난 시각을 고른다.
    미리 보는 판정(`costRequest`)은 BE 와 같다 — 금액 `parseMoneyInput`(빈 값 · 음수 · 소수 자릿수 초과 거절, 0 은 0원), 시작 1970 이후 · 지금 + 5분 이내,
    사유는 앞뒤 공백을 뺀 코드 포인트 1~200 · 제어 · 서식 문자 금지. 정본은 서버다.
  - 성공하면 현재 원가를 다시 읽고 폼 · 이력을 새로 시작한다.
- **오류는 운영자 문구다**(코드별, `costFailure`) — 검증 `SKU_COST_101~109` 는 그 칸, `110` · `PRODUCT_002`(없는 SKU) · `PRODUCT_100` 은 폼 전체.
  `001`(5분 넘는 미래) 은 시작 칸, `005`(잠금 대기) 는 다시 시도 안내다. 그사이 타임라인이 바뀐 `002` · `003` · `004` · `006` 은 원가 · 이력을 다시 읽고 **폼 입력은 둔다**.
  502 · 504 는 실패로 단정하지 않고 다시 읽어 확인하게 한다 — "지금부터" 로 다시 보내면 행이 하나 더 생긴다. 저장 401 은 폼을 지킨다(위 공통 규칙).

### 사이즈표 (`/size-chart-templates` · `/size-charts` · `/size-charts/[productId]`, #218)

메뉴 `상품 > 사이즈표` · `상품 > 사이즈표 템플릿`, 상품 목록 줄의 `사이즈표` 링크. 계약 정본은 `backend/docs/api-design-guide.md` §2-10(BE #216) — 모두 ADMIN 이다.
코드는 `lib/size-chart/`(계약 타입 `types.ts` · 폼 · 검증 · 붙여넣기 `form.ts` · 실패 처리 `errors.ts`), 화면은 `components/size-chart/` 다.

- **템플릿** — 목록 `GET /api/v1/admin/size-chart-templates`(최신순 · 20개 페이지) · 만들기 `POST` · 고치기 `PUT`(전체 교체) · 삭제 `DELETE`(줄에서 한 번 더 확인).
  고칠 때는 상세를 다시 읽는다(요약에는 항목이 없다). 항목 순서는 화면 순서(위 → 아래 = 표 왼쪽 → 오른쪽, 0부터)이고 위 · 아래 버튼으로 옮긴다.
  **기존 항목은 `columnId` 를 늘 싣는다** — 쓰는 중인 템플릿은 그래야 이름 · 설명 · 순서만 바뀌고, 안 쓰는 템플릿은 서버가 무시하고 새로 만든다.
  사용 여부는 서버만 알아서, 고치기 화면에서 단위를 바꾸거나 항목을 더하고 빼면 "쓰는 사이즈표가 있으면 저장되지 않는다"고 미리 알린다(409 `PRODUCT_062` 문구도 같다).
  쓰는 중 삭제는 409 `PRODUCT_063` 안내다. 폼은 한 번에 하나만 열고, 열려 있으면 줄의 고치기 · 삭제를 막는다.
- **상품 사이즈표** — 상품 고르기는 재고와 같은 `ProductPicker`(어드민 상품 목록, `components/product/`)다. 어드민 사이즈표 API 는 숨긴 상품도 받아 **숨긴 상품도 고른다**.
  `GET .../products/{id}/size-chart` 가 null 이면 새 표다. 템플릿(최신 100개)을 고르면 그 항목이 칸이 된다. **다른 템플릿을 고르면 치수는 비우고 사이즈 이름만 남긴다**(항목 아이디가 다르다).
  저장은 `PUT`(전체 교체, 없으면 생성) 하나다. 줄 순서가 `displayOrder`(0부터), 칸은 항목 순서로 보낸다. 삭제 API 는 없다(BE #216 범위 밖).
  상품 이름은 제목에만 쓰고 고객 상세에서 읽는다 — 숨긴 상품은 404 라 이름 없이 둔다.
- **붙여넣기** — 엑셀 · 구글 시트에서 복사한 표(칸은 탭, 줄은 개행 · 탭이 없으면 쉼표)를 아무 칸에 붙여넣으면 그 칸부터 오른쪽 · 아래로 채운다(`pasteIntoChart`).
  사이즈 칸이면 첫 칸이 사이즈 이름이다. 첫 줄에 항목 이름과 같은 칸이 있으면 머리줄로 보고 건너뛴다. 줄이 모자라면 더하고(최대 30), 넘치는 줄 · 칸은 버리고 알린다.
  탭 · 개행이 없는 한 칸 값은 입력칸 기본 붙여넣기다. 붙인 값은 저장 때 같은 규칙으로 판정한다(단위 꼬리 `84cm` 는 칸 오류).
- **입력 제약은 BE 와 같다**(`chartRequest` · `templateRequest`, 어기면 보내지 않고 칸에 오류) — 앞뒤 공백을 뺀 코드 포인트 수로 템플릿 이름 50 · 항목 이름 30 ·
  설명 200 · 사이즈 이름 20 · 안내 500. 제어 · 서식 문자(개행 · 탭 · 제로폭 공백 포함) 금지. 항목 1~20, 사이즈 1~30, 템플릿 안 항목 이름 · 상품 표 안 사이즈 이름 중복 금지.
  치수는 0 초과 9999.99 이하 · 소수 둘째 자리까지 숫자이고 JSON number 로 보낸다(금액이 아니다). 빈 칸은 보내지 않는다 — 행마다 모든 항목이 필요하다(`PRODUCT_067`).
- **오류는 운영자 문구다**(메인 전시와 같은 규칙) — `fieldErrors` 는 경로로 칸을 찾는다(`rows[i].cells[j]` 는 보낸 항목 순서의 j 번째 칸). 칸 오류는 표 아래에
  "사이즈 항목: 문구" 로 모아 보이고 그 칸 테두리를 붉게 한다. 항목 불일치(`PRODUCT_066~068` — 그사이 템플릿이 바뀜)는 같은 템플릿을 다시 읽어 아이디가 바뀐 칸은 항목 이름이 같으면 옮겨 남기고, 없어진 항목의 치수가 지워졌으면 그렇다고 안내한다.
  `PRODUCT_073`(다른 템플릿으로 먼저 저장됨) · 결과 모름(502 · 504)은 표를 다시 읽고 안내한다. 잠금 대기 `PRODUCT_072` 는 다시 시도 안내다. 저장 401 은 폼을 지킨다(위 공통 규칙). 편집 중 템플릿 읽기 401 도 같은 이유로 로그인 화면으로 보내지 않고 템플릿 칸 오류로 둔다.
  저장 · 템플릿 읽기 중에는 입력을 막고(`fieldset disabled`), 저장 뒤 줄 key 를 유지해 포커스를 잃지 않는다. 숫자가 아닌 상품 id 는 페이지가 먼저 not-found 다.
- **아직 없는 것** — AI 초안 생성 · 이미지 사이즈표 이관(BE #217)은 API 가 없어 버튼 · 진행 표시를 두지 않는다. 생기면 같은 편집기에 초안을 채우는 길로 붙인다.

### 메인 전시 (`/displays/main`, #71)

메뉴 `전시 관리 > 메인`. 어드민 메인 전시 API(BE #63, 계약 정본 `backend/docs/api-design-guide.md` §2-9) — 모두 ADMIN 이다.
코드는 `lib/display/`(계약 타입 `types.ts` · 폼 · 검증 `form.ts` · `text.ts` · 순서 `order.ts` · 상품 `products.ts` · 실패 처리 `errors.ts`), 화면은 `components/display/` 다.

| 일 | API | 화면 |
|----|-----|------|
| 목록 | `GET /api/v1/admin/displays/sections` — 숨김 · 기간 밖 포함 전체(최대 50), 배열 순서 = 노출 순서 | 유형 · 제목 · 노출 상태(`liveStatus.name`) · 기간 · 아이템 수 |
| 순서 | `PATCH .../sections/order` `{ sectionIds }` — 지금 있는 아이디 전체를 새 순서대로(일괄) | 화면에서 옮긴 뒤 "순서 저장" 한 번 |
| 노출 켜기 · 끄기 | `PUT .../sections/{id}` — 전체 교체라 응답의 칸 · 아이템을 그대로 싣고 `visible` 만 바꾼다(`unchangedRequest`) | 줄의 스위치, 응답으로 그 줄만 바꾼다 |
| 만들기 · 고치기 | `POST .../sections` · `PUT .../sections/{id}` — 본문이 같다(`sectionRequest`) | 폼 |
| 삭제 | `DELETE .../sections/{id}` | 그 줄에서 한 번 더 확인 |

- **버전이 없어 마지막 쓰기가 이긴다**(BE 계약). 그래서 만들기 · 고치기 · 삭제 뒤에는 목록을 다시 읽고, "다시 불러오기" 를 늘 둔다.
  409 는 버전 충돌이 아니라 잠금 대기(`PRODUCT_058`, 다시 시도 안내) · 섹션 상한(`PRODUCT_056`)뿐이다.
- **한 번에 한 가지만 한다** — 폼이 열려 있거나 요청 중이면 다른 줄을 막는다. 순서를 바꾼 동안에는 노출 · 수정 · 삭제 · 추가를 막고 "순서 저장 / 되돌리기" 만 둔다
  (순서 저장은 아이디 전체 집합을 보내므로 그사이 다른 쓰기가 끼면 `PRODUCT_057` 이 된다).
- **순서 바꾸기** — `@dnd-kit/core` · `@dnd-kit/sortable`(#71 에서 추가). 끌기(마우스 · 터치)와 키보드 정렬(손잡이에서 스페이스 · 화살표 · 스페이스, Esc 취소)과
  스크린리더 안내(한국어 `announcements`)를 함께 준다. 직접 만들면 이 셋(특히 키보드 이동 중 안내 · 포커스 복원)을 다시 구현해야 해서 검증된 라이브러리를 쓴다.
  드래그를 모르는 사람 · 보조기기를 위해 줄마다 위 · 아래 버튼도 둔다(옮긴 뒤 같은 버튼으로, 끝에 닿으면 반대 버튼으로 포커스).
  상품 픽의 상품 순서(최대 20개, 폼 안)는 위 · 아래 버튼만 둔다.
- **편집할 수 있는 칸은 메인이 그리는 칸뿐이다**(§10, `FIELDS_BY_TYPE`) — 히어로: 제목 · 부제 · 링크, 상품 픽: 제목 · 링크, 스토리: 제목 · 본문 · 링크, 디테일: 제목 · 본문.
  폼에 없는 칸의 저장된 값은 그대로 다시 보낸다(전체 교체라 빼면 지워진다). LOOK 은 목록에서 순서 · 노출만 바꾸고 내용은 "룩 기능 준비 중"(#62)이다. 모르는 유형도 순서 · 노출만이다.
- **입력 제약은 BE 와 같다**(`sectionRequest`, 어기면 보내지 않고 칸에 오류) — 앞뒤 공백을 뺀 UTF-16 길이로 제목 · 부제 200, 본문 2000, 링크 주소 500, 링크 문구 50, 대체 텍스트 200.
  제어 문자 · 짝 없는 서로게이트 금지, 본문만 개행 · 탭 허용. 링크는 `/` 로 시작하는 상대 경로(`//` 제외) 또는 https(호스트 필수, 사용자 정보 금지), 문구만 있는 링크 금지.
  빈 칸은 null 로 보낸다. 제목 칸에 2줄 규칙(#57 §4 — 어떤 폭에서도 2줄 이내, 넘으면 단어를 줄인다) 안내와 글자 수를 둔다. 정본은 서버다.
- **노출 기간** — `datetime-local` 두 칸(이 컴퓨터 시간대), 비우면 제한 없음. 보낼 때 UTC ISO 로 바꾸고, 칸을 고치지 않았으면 불러온 값을 그대로 보낸다(초 아래를 잃지 않게).
  0001 ~ 9999년 · 종료가 시작보다 뒤인지 미리 본다. 시작은 포함, 종료는 제외다.
- **새 섹션** — 업로드(#9) 없이 만들 수 있는 유형(미디어 하한 0, LOOK 제외 — 상품 픽 · 스토리)만 추가 버튼을 둔다(`CREATABLE_TYPES`). 처음에는 숨김으로 만든다(내용 확인 뒤 켠다).
  섹션 50개면 추가를 막는다. 서버는 맨 뒤에 둔다.
- **상품 픽** — 어드민 상품 목록(§9 상품 목록과 같은 `GET /api/v1/admin/products`, 숨김 포함)에서 상품명으로 찾아 넣고 · 빼고 · 옮긴다. 1 ~ 20개, 같은 상품은 한 번만.
  숨긴 상품은 넣을 수 있지만 메인에서 빠진다고, 판매중이 아닌 상품(품절 · 판매 종료)은 메인에 보이지만 살 수 없다고 그 줄에 알린다.
  섹션 응답의 상품에는 노출 여부만 있어 판매 상태는 이번에 검색해 넣은 상품만 안다. 상품 아이디는 문자열로 보낸다.
- **기존 미디어** — 썸네일(영상은 대표 사진)과 대체 텍스트를 보이고 **대체 텍스트만 고친다**(같은 PUT 에 주소를 그대로 싣는다). 교체 · 추가 · 삭제는 업로드 기능(#9) 뒤라고 안내한다.
  미디어가 있는 섹션의 저장은 미디어 주소를 다시 판정받으므로, 저장소 공개 주소가 없거나 바뀌면 503 `PRODUCT_055` · 400 `PRODUCT_054` 다 — 개발 담당자 문의 안내다.
  대체 텍스트는 BE 처럼 선택이다. 비어 있으면 화면 낭독기가 건너뛴다고 안내한다.
- **메인과 어긋나지 않게** — 저장 결과가 메인에서 빠지는 경우를 저장 전에 알린다: 상품이 모두 숨김인 상품 픽, 제목 · 본문 · 미디어가 모두 없는 스토리(§10 "건너뜀").
  여러 장인 히어로는 첫 장만 보인다고 알린다. 메인 반영은 공개 조회 캐시 때문에 최대 약 1분이라 성공 안내에 함께 적는다.
- **오류는 운영자 문구다**(#305) — 서버 검증 문구에는 칸 이름 · 유형 코드가 영문으로 들어 있어 그대로 보이지 않는다. `fieldErrors` 는 코드 · 경로로 칸을 찾아 업무 문구를 붙이고
  (`items[i].media.altText` → 그 미디어의 대체 텍스트 칸, 그 밖의 아이템 오류 → 상품 · 미디어 묶음), 섹션 규칙 오류(`PRODUCT_047~056`)도 코드별 문구다.
  없는 섹션(404 `PRODUCT_046`) · 결과 모름(502 · 504)은 폼을 닫고 다시 읽어 안내한다. 저장 401 은 폼을 지킨다(위 공통 규칙). 순서 저장 401 도 바꾼 순서를 둔 채 안내한다.
  줄의 노출 바꾸기 · 삭제 401 은 지킬 입력이 없어 읽기처럼 로그인 화면으로 보낸다. 순서 저장이 `PRODUCT_057`(그사이 섹션이 추가 · 삭제됨)이면 다시 읽고 순서를 다시 정하라고 안내한다.
- 화면에 섹션 아이디 · 유형 코드 · 상태 코드를 보이지 않는다. 유형 · 상태는 서버 `name` 이다(새로 만들 때의 유형 이름만 `TYPE_LABELS` 사본).
- **미리보기** — "미리보기" 를 누르면 스토어프론트 `/preview/main` 을 iframe 으로 연다(`PreviewPanel`, 넓은 화면에서는 오른쪽에 붙는다). 모바일 390 · PC 1440 폭을 바꿔 보고,
  PC 폭은 칸에 맞춰 줄여 보인다. 저장하지 않은 순서 변경 · 열린 폼 값까지 그대로 BE 미리보기(#340)에 보낸다(`previewSections` — 저장된 섹션은 받은 값, 열린 폼은 검증 없이 `draftRequest`,
  새 섹션은 맨 뒤). 저장하지 않으므로 실제 메인은 바뀌지 않는다. 호출 · 전달 · 오류 · 보안은 §10 미리보기다. `STOREFRONT_ORIGIN` 이 없으면(운영) 버튼을 두지 않는다.

### 룩 (`/displays/looks`, #406)

- 어드민 룩 API(product-service, BE #62 · #370, 계약 정본 `backend/docs/api-design-guide.md` §2-11) — 모두 ADMIN, BFF catch-all 경유(`apiFetch`).
  목록 `GET /api/v1/admin/looks`(숨김 포함 전체, 최대 100, 배열 순서 = 노출 순서), 만들기 `POST`, 고치기 `PUT /{lookId}`(**전체 교체**), 삭제 `DELETE /{lookId}`(구성 상품도 지움),
  순서 `PATCH /order` `{ lookIds }`(지금 있는 아이디 전체). 기능 플래그(`store.features.looks`)와 무관하게 동작한다 — 고객 노출은 플래그가 켜진 쇼핑몰에서만이라고 폼에 안내한다.
- **화면 규칙은 메인 전시와 같다**(`LookManager` · `LookRow` · `LookEditor`) — 순서는 dnd-kit 끌기 · 키보드 · 위 · 아래 버튼으로 바꾼 뒤 "순서 저장" 한 번, 바꾼 순서가 있는 동안 다른 일을 막는다.
  노출 스위치는 응답의 칸 · 상품을 그대로 싣고 `visible` 만 바꾼 PUT(`unchangedLookRequest`)이다. 버전이 없어 마지막 쓰기가 이기므로 만들기 · 고치기 · 삭제 뒤 목록을 다시 읽는다.
  새 룩은 숨김으로 시작한다. 반영은 고객 조회 캐시 때문에 최대 약 1분이라 성공 안내에 적는다.
- **입력 제약은 BE 와 같다**(`lookRequest`) — 제목 필수 · 100, 설명 1000(줄바꿈 · 탭 허용), 이미지 주소 필수 · 500(공백 금지), 대체 텍스트 200, 구성 상품 1 ~ 20개 · 중복 금지.
  선택 칸(설명 · 대체 텍스트)은 비면 null 이다. 제목 · 설명 · 대체 텍스트는 평문이고 텍스트 노드로만 그린다.
- **대표 이미지** — 업로드(#9)가 없어 **쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소**를 넣게 하고 그렇게 안내한다(룩은 이미지가 필수라 주소 칸 없이는 만들 수 없다).
  넣은 주소의 이미지를 작게 보이고, 불러오면 원본 크기(`naturalWidth/Height`)를 그 주소와 함께 기억해 보낸다(주소를 바꾸면 버린다). 저장소 주소인지는 서버가 본다 —
  400 `LOOK_005` 는 이미지 주소 칸에 "저장소에 올린 주소만" 안내, 503 `LOOK_006` 은 개발 담당자 문의 안내다. 업로드가 생기면 주소 칸을 업로드로 바꾼다.
- **구성 상품** — 메인 전시 상품 픽과 같은 `ProductPickField`(`max` · `surface` 를 받는다)로 찾아 넣고 · 빼고 · 옮긴다. 응답의 `saleStatus`(판매중 · 판매 종료)를 판매 상태로 써서
  숨긴 상품은 룩에서 빠진다고, 판매 종료 상품은 보이지만 살 수 없다고 그 줄에 알린다. 상품이 모두 숨김이면 저장 전에 "함께 담기" 가 없다고 알린다.
- **오류는 운영자 문구다**(#305, `lib/look/errors.ts`) — 검증 코드(`LOOK_101~117`)는 코드로 칸 · 문구를 찾고(모르는 코드는 경로로 칸), 룩 규칙 오류(`LOOK_002~008`)도 코드별 문구다.
  `LOOK_004` 서버 문구에 실린 상품 아이디는 보이지 않는다. 없는 룩(404 `LOOK_001`) · 결과 모름은 다시 읽어 안내, 순서 저장 `LOOK_007` 은 다시 읽고 순서를 다시 정하라고 안내한다.
  401 · 403 처리는 메인 전시와 같다(저장 · 순서 저장 401 은 입력을 지킨다). 룩 아이디 · 상품 아이디는 화면에 보이지 않는다.
- 메인 전시의 `LOOK` 섹션(§10)은 전시 API 에 룩 연결이 없어 이 화면과 별개다(범위 밖).

### 백그라운드 작업 (`/jobs` · `/jobs/[jobId]`, #272)

- 어드민 작업 API(operations-service, BE #212 PR-C · #305, 계약 정본 `backend/docs/api-design-guide.md` §2-8 · §7-1) — 모두 ADMIN 이다.
  - 목록 `GET /api/v1/admin/jobs?status=&type=&page=&size=` — `PageResponse`, 등록 최신순 고정, 페이지 20개
  - 상세 `GET /api/v1/admin/jobs/{jobId}` — **작업 실패도 200** 이고 `status=FAILED` + 서버 안내 문구(`notice`)다
  - 취소 `POST /api/v1/admin/jobs/{jobId}/cancel` — 본문 없음, 응답은 상세와 같다. QUEUED 는 바로 `CANCELLED`,
    RUNNING 은 상태 그대로 `notice`("취소를 요청했습니다") · `cancellable=false` 로 온다(멱등)
- **어드민 사용자는 비개발자 쇼핑몰 운영자다** — 응답 · 화면에 기술 정보(시도 횟수 · 오류 코드 · 오류 원문 · 입력/결과 JSON · 우선순위 · 멱등 키 · 회원 아이디)를 두지 않는다.
  목록 항목 · 상세 · 취소 응답은 같은 칸이다(`JobItem`, `lib/job/types.ts`): `jobId` · `type`(종류 코드, 거르기에만) · `typeName`(화면에 보이는 작업 이름) ·
  `status` · `progressPercent` · `progressMessage` · `notice` · `cancellable` · `requestedAt` · `finishedAt`.
- 각 기능 API 가 `202 + jobId` 로 답하면 그 기능 화면이 `/jobs/{jobId}` 로 보낸다. 범용 등록 화면은 없다. 결과 파일 링크는 #212 PR-E 에서 붙인다(자리를 미리 두지 않는다).
- **작업은 `typeName`, 상태는 서버 `status.name` 을 보인다**(설명은 `title` · 상세 본문). **실패 이유 · 재시도 대기 · 취소 요청됨은 서버 `notice` 문장을 그대로 보인다**(목록 상태 아래 · 상세 진행 아래) —
  화면이 상태 · 칸을 조합해 문구를 만들지 않는다. 필터 선택지 이름만 목록을 읽기 전에 그려야 해서
  서버 `JobStatus` 의 이름을 옮긴 사본(`JOB_STATUS_LABELS`, `lib/job/types.ts`)이다 — BE 가 이름을 바꾸면 같이 고친다.
- **조건은 주소가 정본이다**(상품 목록과 같은 규칙) — `parseJobQuery` 가 상태(enum 이름 그대로)·종류(`[A-Z0-9_]{1,64}`)·페이지(숫자 6자리)만 받고
  나머지는 "전체" 다(손으로 고친 주소가 400 `JOB_103` · `198` 이 되지 않게). 종류 칸은 대문자로 바꿔 보내고, 패턴에 안 맞으면 보내지 않고 칸 오류다.
  작업 아이디가 Long 범위 양의 정수가 아니면 요청 없이 "찾을 수 없음" 이다.
- **상세 주기 갱신** — 읽기 · 갱신 · 취소는 `createJobDetailController`(`lib/job/detail-controller.ts`)가 하고 화면은 그 상태를 그린다.
  - QUEUED · RUNNING 이면 3초 뒤 다시 읽고, 끝나면(SUCCEEDED · FAILED · CANCELLED, 모르는 상태 포함) 멈춘다. 화면을 떠나면 예약을 지우고 진행 중 응답을 버린다.
  - 이미 작업을 보인 뒤 읽기가 일시 오류면 **마지막 상태를 둔 채** 안내하고 계속 읽는다. 연속 실패마다 간격을 두 배로 늘리고 최대 30초다(성공하면 3초로 돌아간다).
    처음 읽기 실패는 "다시 불러오기" 다. 401 · 403 · 404 는 화면을 바꾸고 멈춘다.
  - **숨은 탭**(`document.visibilityState === 'hidden'`)에서는 예약을 지우고, 다시 보이면 곧바로 한 번 읽은 뒤 이어 간다.
  - 읽기마다 세대 번호를 붙여 늦게 온 이전 응답을 버린다. 취소를 보내는 순간 세대를 올리고 취소 중에는 새 읽기를 시작하지 않는다 —
    그 전에 나간 읽기가 취소 결과(CANCELLED)를 예전 상태(QUEUED)로 덮지 못한다.
- **취소** — 버튼은 서버 `cancellable` 하나로 보인다(상태로 추론하지 않는다). 한 번 더 확인한다(`JobCancelControl`).
  성공하면 **응답 본문으로 바로 갱신**한다 — 상태 · `notice` · `cancellable` 이 곧 결과라 화면이 따로 성공 문구를 만들지 않는다. 실행 중이면 주기 갱신이 이어져 최종 상태 · 안내도 서버 값으로 바뀐다.
  아래 실패만 화면 안내다.

| 취소 실패 | 화면 처리 |
|-----------|-----------|
| 409 `JOB_022`(이미 끝남) | 안내 + 곧바로 다시 읽어 최종 상태를 보인다 |
| 409 `JOB_021`(워커와 계속 겹침 — 서버가 3번 시도) | 다시 시도하라는 안내만, 주기 갱신은 이어 간다 |
| 404 `JOB_001` | "작업을 찾을 수 없습니다" 로 바꾸고 멈춘다 |
| 502 · 504(`unknownOutcome`) | **다시 보내지 않고** 다시 읽어 반영됐는지 보인다(취소는 멱등이라 사용자가 다시 눌러도 된다) |
| 401 · 403 | `AccessNotice`(입력할 폼이 없어 401 도 읽기처럼 로그인 화면으로 보낸다) |
| 그 밖 4xx · 5xx · 연결 실패 | 4xx 는 서버 문구, 나머지는 일시 오류 문구 |

- 취소가 401 · 403 · 404 로 끝날 때도 그 전에 나간 읽기는 버린다(세대 번호).

### 알림 센터 · 작업 진행 표시 (상단 바, #214)

콘솔 레이아웃(`AdminShell`) 상단 바에 붙어 화면을 옮겨도 이어진다(App Router 레이아웃은 화면 이동에 다시 그려지지 않는다).
계약 정본은 `backend/docs/api-design-guide.md` §7-2(알림, BE #213) · §7-1(작업).

- **알림 센터**(`NotificationCenter`, 상태는 `createNotificationCenterController`) — 종 아이콘 · 읽지 않은 수(99+) · 패널(최신 20건, 더 보기, 읽음, 모두 읽음) · 토스트.
  - 읽지 않은 수 `GET /unread-count`, 목록 `GET ?page=&size=20`, 읽음 `POST /{id}/read`(응답으로 그 행을 바꾸고 수를 하나 내린 뒤 서버 수로 맞춘다),
    모두 읽음 `POST /read-all`(응답 `{count}` 로). 읽음 404(`NOTIFICATION_001`, 보존 기간이 지나 지워짐)는 목록에서 빼고, 409(`NOTIFICATION_020`, 그 사이 다른 곳에서 읽음)는 안내 없이 수 · 첫 쪽을 다시 읽고, 그 밖 실패는 패널 안내다.
    모두 읽음은 보내기 전에 나간 수 읽기를 버리고, 보낼 때까지 본 알림만 읽음으로 바꾼다(그 사이 스트림으로 온 알림은 읽지 않음으로 남고 수를 다시 읽는다).
  - 종류 · 심각도는 서버 `name` 을 그대로 보인다. 코드는 꾸밈에만 쓴다 — 안내(INFO)는 심각도를 따로 적지 않고, 긴급(CRITICAL)은 강조색 · 토스트가 저절로 닫히지 않는다.
  - **알림 링크** — BE `linkPath` 는 `/admin/jobs/{jobId}` 처럼 `/admin` 아래로 온다. 콘솔은 자기 origin 루트라 맨 앞 `/admin` 한 마디를 떼어 간다(`consolePathOf`).
    `.` · `..` 마디를 풀어 둔 뒤(`resolveSitePath`) 같은 사이트 경로만 받고(`safeReturnPath` 규칙) 아니면 링크 없이 보인다. 링크를 누르면 읽음으로 바꾸고 패널을 닫는다.
- **실시간 수신**(SSE, 위 §2 SSE 중계) — 이벤트 `notification` 이 오면 목록 맨 위에 넣고, 읽지 않은 알림이면 토스트(8초, 최대 3장)를 띄우고 수를 하나 올린 뒤
  0.5초 뒤 서버 수로 맞춘다(몰려오는 알림은 한 번에). 같은 아이디는 다시 세지 않는다. data 모양이 다르면 버리고 목록으로 맞춘다.
- **다시 연결 · 따라잡기** — BE 는 스트림을 5분마다 닫는다(정상). 정상 종료면 1초 뒤(열린 지 10초가 안 돼 닫혔으면 실패로 센다 — 열리자마자 닫히는 설정 오류에서 1초마다 다시 열지 않게), 연결 실패 · 5xx 면 2초부터 두 배씩 최대 30초 뒤(429 는 `Retry-After` 이상) 다시 연다.
  **끊겼다가 다시 열리면 읽지 않은 수와 첫 쪽을 다시 읽어 놓친 알림을 따라잡는다** — 마지막으로 본 알림 아이디보다 새 읽지 않은 알림은 토스트로 알린다
  (3건까지 한 장씩, 넘치면 "새 알림 N건" 한 장 + 알림 보기). 기준은 따라잡기 요청을 보낼 때 잡고, 그 사이 스트림으로 와 이미 띄운 알림은 뺀다. 처음 읽기는 기준만 잡고 토스트를 띄우지 않는다.
- **토스트 접근성** — 읽어 주는 곳(live region, 긴급은 `alert` · 그 밖은 `status`)에는 "종류: 제목" 문장만 두고 링크 · 닫기는 그 밖에 둔다.
  마우스 · 초점이 토스트 위에 있는 동안은 저절로 닫히지 않고, 떠나면 8초를 다시 센다(WCAG 2.2.1).
- **숨은 탭에서는 스트림을 끊는다** — 탭마다 연결을 붙잡으면 HTTP/1.1 의 origin 당 연결 수 한도(6)를 스트림이 다 쓴다. 다시 보이면 열고 따라잡는다.
- **401 · 403 이면 알림만 멈춘다**(패널에 안내). 로그인 화면으로 보내지 않는다 — 상단 바는 모든 화면에 있고 쓰던 폼을 지켜야 한다(§9 저장 중 401). 화면 자신의 API 가 401 을 처리한다.
- **작업 진행 표시**(`JobTray`) — 이 탭에서 연 실행 중 작업의 진행률 · 진행 문구 · 서버 안내 · 취소 · 결과 이동. 작업마다 상세의 `createJobDetailController` 와
  `JobCancelControl` · `JobProgressBar` 를 그대로 쓴다(3초 갱신, 끝나면 멈춤, 숨은 탭에서 쉼, 취소 처리 표 위와 같다). 패널이 닫혀도 행은 DOM 에 남아 계속 읽는다.
  - **무엇을 지켜보나** — 작업 목록 API 는 요청자로 거르지 못해(모든 관리자 작업) 서버에서 "내 작업" 을 고르지 않는다. 작업 상세 화면이 실행 중 작업을 열면
    `trackedJobs`(`lib/job/tracked-jobs.ts`)에 더한다. 기능 화면은 `202 + jobId` 를 받으면 상세(`/jobs/{jobId}`)로 보내므로 그 흐름이 곧 등록이다.
    탭 세션 저장소(`sessionStorage`, 작업 아이디만, 최근 5건)라 새로 고침해도 남고 다른 탭과 섞이지 않는다(탭 복제는 브라우저가 저장소를 복사해 그 시점 목록을 이어 간다). 저장소를 못 쓰면 메모리에만 둔다.
  - 끝나면 상태와 "결과 보기"(누르면 목록에서 뺀다) · "목록에서 빼기" 를 둔다. 없는 작업(404)은 저절로 뺀다. 화면이 저절로 결과로 이동하지는 않는다 —
    다른 일을 하던 중이라 끼어들지 않고, 요청자에게는 BE 완료 알림이 토스트로 온다(보기 → 결과 화면).

### 관리자 감사 로그 (`/audit-logs`, #320 · #329)

메뉴 `운영 > 관리자 감사 로그`. auth-service 감사 로그 조회 `GET /api/v1/admin/audit-logs?action=&result=&page=&size=`(BE #192 · #286, ADMIN,
계약 정본 `backend/docs/api-design-guide.md` §2-4 · 기록 방식 `security-guide.md` §11-4) — 최신순 `PageResponse`, 페이지 20개, 읽기만 있다.
코드는 `lib/audit/`(계약 `types.ts` · 조건 `query.ts` · 문구 `view.ts` · 읽기 `load.ts`), 화면은 `components/audit/` 다.

- **응답 칸** — `auditLogId` · `action`(`ADMIN_LOGIN` · `MEMBER_SEARCH`) · `actorMemberId`(로그인 실패는 null) · `target`(원문, null 가능) · `result`(`SUCCESS` · `FAILURE`) · `createdAt`.
  기록하는 행위는 관리자 로그인 성공 · 실패와 어드민 회원 목록 조회다. 이 조회 자체는 기록되지 않는다.
- **운영자 문구만 보인다(#305)** — 행위 · 결과 코드, `target` 원문, 회원 · 감사 로그 아이디를 화면에 내지 않는다(`describeAuditLog`).
  - 행위는 업무 이름(`AUDIT_ACTION_LABELS` — 서버가 이름을 주지 않아 화면 사본이다. BE 가 행위를 더하면 같이 더한다), 모르는 행위는 "기타" 다. 결과는 성공 · 실패(실패는 `text-danger`), 모르는 값은 "알 수 없음".
  - 대상은 BE `AdminAuditRecord` 가 만드는 두 형식만 문장으로 바꾼다(`targetText`). 그 밖의 형식 · 값(모르는 역할 · 남는 칸 · 겹친 칸)은 원문 대신 "기타", 없으면 `—`.

    | 원문 | 화면 |
    |------|------|
    | `members?role=<ALL\|CUSTOMER\|ADMIN>&page=<0부터>&size=<n>` | `역할: 전체 · 1쪽` (역할은 `roleLabel`, 쪽은 +1) |
    | `member:<id>` (관리자 로그인 — 성공은 자기 계정, 실패는 노린 관리자 계정) | `관리자 계정` |

  - 관리자 칸은 **"관리자" 로만 보인다**. `actorMemberId` 가 null 이면 "확인 안 됨"(로그인 실패는 누가 시도했는지 모른다, 설명은 `title`).
- **관리자 이름 표시 · 관리자 필터는 BE #322 이후에 한다** — 감사 로그 응답에 이름이 없어서다. BE #322 가 항목에 표시 이름(`actorDisplayName` · `targetDisplayName`, 마스킹)을
  더하면 이름을 보이고 관리자 필터(`actorMemberId`)를 붙인다. 그때까지 관리자 필터는 없고 관리자 조건을 보내지 않는다.
- **조건은 행위 · 결과 두 가지다**(#329) — 행위(전체 · 업무 이름)와 결과(전체 · 성공 · 실패, `AUDIT_RESULT_LABELS`)를 함께 쓸 수 있다. 실패한 관리자 로그인만 보려면 행위 "관리자 로그인" · 결과 "실패" 다.
  - **대상 조건(`target`, BE #286)은 두지 않는다** — 완전 일치 원문(`member:<id>`)을 입력 · 표시해야 해서 #305(아이디 · 코드 원문을 보이지 않는다)와 맞지 않는다. 보내지도 않는다.
- **조건은 주소가 정본이다**(상품 목록과 같은 규칙) — 화면 주소는 `?action=&result=&page=`, `parseAuditLogQuery` 가 행위 · 결과(enum 이름 그대로, 대소문자까지) · 페이지(숫자 6자리)만 받고
  나머지는 "전체" 다(손으로 고친 주소가 400 `AUTH_198` 이 되지 않게). 모르는 파라미터(옛 주소의 `actor`, `target` 등)는 무시한다.
  조건을 고르고 "검색" 을 눌러야 주소로 가고 1쪽부터 다시 본다. 페이지 이동은 조건을 그대로 둔다.
- **실패** — 401 · 403 은 `AccessNotice`, 그 밖(400 · 5xx · 연결)은 "다시 불러오기" 다(`loadAuditLogs`). 400 서버 문구는 영문 파라미터 이름이 들어 있어 보이지 않는다.
  화면이 `target` 을 보내지 않아 `AUTH_136`(대상 형식)은 서버 계약이 바뀐 경우에만 생기고, 다른 400 과 같이 다룬다.

## 10. 메인 전시 (#59 · BE #63)

스토어프론트 메인은 product-service 고객 메인 전시 `GET /api/v1/displays/main`(공개, 계약 정본 `backend/docs/api-design-guide.md` §2-9)을 서버에서 읽어 그린다.
읽기 · 바꾸기는 `lib/display/`(`load-home.ts` · `home-sections.ts`), 타입은 BE DTO 그대로 `lib/display/types.ts` 다.

- **노출 · 순서는 응답이 정본이다.** 응답 순서대로 블록을 그리고, 노출 판정(노출 여부 · 기간 · 숨긴 상품)은 BE 가 한다. 화면은 다시 거르지 않는다.
- **유형별 그리기** — 섹션 유형 code 로 블록을 고른다(`toHomeBlocks`). 아래 "건너뜀" 이면 그 섹션만 그리지 않는다. 모르는 유형도 건너뛴다.

  | 유형 | 그리는 것 | 건너뜀 |
  |------|-----------|--------|
  | `HERO` | 첫 미디어만(여러 장 넘기기는 화보 촬영 후). IMAGE 는 PC · 모바일 사진(`<picture>`), VIDEO 는 PC 주소 재생 + 포스터. 제목은 한 줄 값이라 폭에 맞춰 줄바꿈하고, 없으면 h1 을 그리지 않는다. 링크가 주 버튼, `/shop` 이 아니면 SHOP 보조 버튼을 함께 둔다. 링크가 없으면 SHOP 이 주 버튼이다(모바일 첫 화면 버튼, #59 완료 기준) | 그릴 미디어 없음 |
  | `PRODUCT_PICK` | 응답의 상품 카드를 `ProductCard` 로 그대로(목록 API 를 다시 부르지 않는다 — 상품 카드는 목록 항목과 같은 모양이라 hover 용 `hoverImage` 도 함께 온다). 링크는 VIEW ALL | 상품 0개(숨긴 상품이 빠져 빈 배열로 올 수 있다) |
  | `STORY` | 레이아웃은 브랜드 설정의 STORY 순서를 따른다 — 응답의 n 번째 STORY 가 설정의 n 번째 STORY 레이아웃(curtain · panel …), 넘치면 asymmetric. 사진이 없으면 패널 외에는 centered | 제목 · 본문 · 미디어 모두 없음 |
  | `DETAIL` | 확대 지점(focus)이 API 에 없어 확대 무대 없이 클로즈업 + 캡션을 세로로(`frame: null`) | 미디어 없음 |
  | `LOOK` | 룩 API(#62) 전까지 응답 자리에 **브랜드 설정의 LOOK** 을 한 번 그린다(운영 값은 아직 쓰지 않는다) | 설정에 LOOK 없음 · 두 번째 LOOK |

- **문구는 평문이다** — 텍스트 노드로만 그리고 본문 개행은 `whitespace-pre-line` 이다. 제목이 없으면 제목 요소와 `aria-labelledby` 를 함께 뺀다(`sectionLabel`).
  부제는 히어로만 쓴다(다른 유형의 부제는 지금 그리지 않는다). 링크 문구가 없으면 사전 `dict.home`(`more` · `viewAll` · `shop`)이다.
- **링크** — 스토어 안 상대 경로 또는 https 만 그린다(`safeHref`, BE 와 같은 규칙을 한 번 더 본다). https 링크에는 `rel="noopener noreferrer"` 를 붙인다(`linkRel`).
- **미디어** — 사진 칸은 기존 고정 비율 그대로다(CLS). 모바일 주소가 따로 있으면 `HomeImage.mobileSrc` 로 1024px 기준 `<picture>` 를 쓴다. 히어로 외 영상은 재생하지 않고 포스터를 보인다.
  대체 텍스트가 없으면 `alt=""`(장식)다.
- **실패 · 빈 응답 — 브랜드 설정(`content.home`)이 안전한 기본이다.** 조회 실패(장애 · 연결 실패 · 봉투 아님) · 모양 불일치 · 그릴 섹션이 하나도 없음이면
  응답 대신 설정 메인을 그린다(`resolveHomeBlocks`). 운영 데이터가 아직 없는 처음 배포 · 장애에도 메인이 비지 않는다. 그릴 섹션이 하나라도 있으면 응답만 그리고 설정 섹션을 섞지 않는다.
  모양이 다른 섹션 · 아이템 하나는 그것만 버린다(`readDisplaySections`).
- **캐시** — 화면은 요청마다 그리고(레이아웃이 세션 쿠키를 읽는다) 전시 응답만 서버 데이터 캐시에 **60초**(`DISPLAY_MAIN_REVALIDATE_SEC`, BE `Cache-Control: max-age=60` 과 같다) 둔다.
  `serverFetch(path, { cache: 'force-cache', next: { revalidate: 60 } }, { anonymous: true })` — 공개 · 사용자 무관 응답이라 **토큰을 싣지 않는다**(데이터 캐시 키에 헤더가 들어가 토큰을 실으면 사용자마다 갈린다).
  `Accept-Language` 는 싣는다(언어별 키 — BE 가 언어별 응답을 내게 되어도 섞이지 않는다). 200 만 캐시되므로 실패는 다음 요청에서 다시 부른다.
  어드민 변경이 메인에 보이기까지 최대 약 1분(만료 뒤 첫 요청은 이전 값을 받고 뒤에서 새로 받는다)이다. 메인 페이지에 `dynamic = 'force-dynamic'` 을 두지 않는다 — 두면 이 캐시까지 꺼진다.
  설정 기본의 대표 상품 · 룩 구성품 조회는 지금처럼 캐시하지 않는다.
- 어드민 편집 화면은 §9 메인 전시(#71)다 — 순서 · 노출 · 문구 · 상품 픽. 편집 칸은 이 절이 그리는 칸에 맞춘다.
- 남은 것: 히어로 여러 장 넘기기 · 모바일 영상, LOOK 의 운영 값(#62), 06 인스타그램 줄, 어드민 미디어 업로드(#9). 미리보기는 BE 미리보기 API(#340) 응답을 그린다(아래 미리보기).

### 미리보기 (`/preview/main`, #71)

어드민 메인 전시(§9)가 저장 전 편집 상태를 메인과 같은 블록으로 그려 본다. 어드민이 BE 미리보기(`POST /api/v1/admin/displays/preview`, #340,
계약 정본 `backend/docs/api-design-guide.md` §2-9)를 부르고, 스토어프론트는 받은 응답을 그리기만 한다.
코드는 어드민 `lib/display/preview.ts`(요청 · 순서 보장) · `lib/display/errors.ts`(`previewFailure`) · `components/display/PreviewPanel.tsx`,
스토어프론트 `lib/display/preview.ts`(메시지 모양 정본 · 응답 읽기) · `app/[locale]/preview/main/`(페이지 · 서버 액션) · `components/home/MainPreview.tsx` 다.

- **어드민이 부른다** — 관리자 토큰 쿠키는 어드민 origin 에만 있어 스토어프론트(서버 액션 포함)는 이 API 를 부를 수 없다. 스토어프론트 공개 액션으로 관리자 자격을 넘기지 않는다.
  - 편집 상태가 바뀔 때마다(300ms 묶음) 어드민 BFF `apiFetch` 로 `{sections}` 를 보낸다(배열 순서 = 노출 순서, 화면 아이디는 뺀다 — `previewRequest`).
  - **순서 보장**(`createPreviewLoader`) — 새 요청을 보내면 앞 요청을 취소(`AbortSignal`)하고, 요청 번호가 마지막이 아닌 응답(성공 · 실패 모두)은 버린다. 패널을 닫으면 남은 응답도 버린다.
  - 응답의 `sectionId`(`preview-section-<i>`) · `itemId` 는 임시 값이다. 어드민은 응답을 읽지 않고 그대로 넘기며 편집 상태 · 저장 요청에 섞지 않는다.
- **전달은 postMessage 다** — iframe 이 준비됐다고(`cf:main-preview:ready`) 알리면 어드민은 마지막으로 받은 응답을 `cf:main-preview:display`(`{ type, display }`)로 보내고,
  그다음 새 응답이 올 때마다 보낸다. 준비 알림이 다시 오면(새로 고침 · iframe 이 다시 열림) 곧바로 다시 보낸다. 양쪽 모두 상대 origin(`event.origin`)과 창(`event.source` — 부모 · 그 iframe)을 확인하고,
  보낼 때 `targetOrigin` 을 고정한다. 쿠키 · 주소에 싣지 않는다.
- **Next draft mode 는 쓰지 않는다** — draft mode 는 캐시를 건너뛰고 원천에서 초안을 읽는 장치인데 BE 에 초안 저장소가 없다(저장 전 상태는 어드민 화면에만 있다).
- **그리기는 서버 액션이다** — iframe 이 받은 응답을 `renderMainPreview` 로 보내면 서버가 메인과 같은 경로(`resolveHomeBlocks` → `<HomeSections>`, `loadHomeBlocks` 와 같다)로 그려 돌려준다.
  - 노출 판정(노출 여부 · 기간 · 숨긴 상품)과 상품 카드 값은 BE 가 메인과 같게 낸다 — 화면은 다시 거르거나 카드를 만들지 않는다. 그릴 섹션이 없으면 메인처럼 브랜드 설정 메인이다.
  - 액션 자체는 게이트웨이를 부르지 않는다. 다만 응답이 브랜드 설정 메인으로 떨어지면 설정 상품 픽(목록 API)을, LOOK 이 있으면 설정 LOOK 의 구성품(상품 상세)을 메인처럼 따로 받는다 —
    서버 액션은 root params(`locale()`)를 읽을 수 없어 언어를 인자로 받고 토큰 없이 부른다(`HomeSections` 의 `fetchLocale`). 이 조회 수는 브랜드 설정이 정하고 입력과 무관하다.
  - 액션은 페이지(서버)가 prop 으로 넘긴다. 액션이 돌려주는 블록의 클라이언트 컴포넌트(`Reveal` · `ProductCard` …)가 이 페이지의 클라이언트 매니페스트에 들어가려면
    액션 모듈이 페이지의 서버 그래프에 있어야 한다(클라이언트가 직접 import 하면 "Could not find the module … in the React Client Manifest").
  - 누구나 부를 수 있는 POST 라 `readPreviewDisplay` 가 응답 모양을 다시 본다 — BE 상한(섹션 50 · 아이템 30)까지만 읽고, 섹션 아이디는 `[\w-]` 64자 · 상품 아이디는 숫자만,
    미디어 · 상품 사진 주소는 http(s) 만(`<img>` 로만 그리고 서버가 가져가지 않는다), 금액은 정수 · KRW/USD 만, 모양이 다른 섹션 · 아이템 · 상품은 버린다.
    문구는 평문 · 링크는 `safeHref` 그대로라 결과는 부른 브라우저에만 돌아가고 저장 · 캐시되지 않는다.
    다른 사이트의 브라우저 호출은 Next 의 서버 액션 Origin 검사가 막지만 직접 호출은 막지 못한다 — 인증 · 속도 제한이 없다. 앞단(Nginx)의 `/preview/*` POST 속도 제한은 배포 구성(#197) 후속이다.
  - 서버 액션 인자가 BE 응답 전체라서 BE 상한을 꽉 채우면(섹션 50, LOOK 상품 · 미디어) Next 기본 `serverActions.bodySizeLimit` 1MB 에 가까워질 수 있다.
    실제 운영 규모에서는 문제없고, 넘으면 iframe 이 실패 문구를 보인다(설정은 바꾸지 않았다).
- **배포는 두 앱을 함께 한다** — 미리보기 메시지가 `cf:main-preview:draft`(편집 상태)에서 `cf:main-preview:display`(BE 응답)로 바뀌었다(#71 · #340 전환).
  어드민과 스토어프론트를 함께 배포해야 하고, 한쪽만 배포하면 받는 쪽이 모르는 메시지를 버려 미리보기가 "기다리는 중" 문구에 오류 없이 멈춘다.
- **오류**(`previewFailure`) — 어떤 실패든 마지막으로 그린 미리보기를 그대로 두고 패널에 안내한다. 열린 폼 값은 검증 없이 보내므로 400 이 자주 난다.
  - 400 — "입력값을 확인해 주세요" + `fieldErrors` 의 `sections[i]` 로 섹션 위치 · 제목과 칸 문구(저장과 같은 코드별 문구)를 줄마다 보인다. 섹션 규칙 오류(`PRODUCT_047~053`)는 어느 섹션인지 응답에 없어 코드별 문구만 보인다. 다시 시도 버튼은 없다(칸을 고치면 다시 부른다).
  - 401 — 저장 401 처럼 폼을 지키고 새 탭에서 다시 로그인하라고 안내한다(로그인 화면으로 보내면 열린 폼 값이 사라진다). 403 — 권한 안내.
  - 503 `PRODUCT_055` · 400 `PRODUCT_054` — 미리보기용 미디어 저장소 안내(저장은 하지 않았다고 알린다, 저장용 문구와 따로다). 055 는 설정을 고친 뒤 입력을 바꾸지 않고 다시 그릴 수 있게 "다시 시도" 를 두고, 054 는 그 미디어 주소가 바뀌어야 풀려 두지 않는다.
    미디어 공개 주소(`STORAGE_PUBLIC_BASE_URL`)가 없는 환경이나 공개 주소를 바꾼 뒤에는 미디어 섹션이 하나라도 있으면 BE 가 숨김 섹션까지 다시 검사해 미리보기가 그려지지 않는다.
  - 그 밖(503 · 연결 실패 · 5xx · 모양이 다른 응답)과 429 는 "다시 시도" 버튼을 둔다. 429 는 `Retry-After`(없으면 3초, 1 ~ 60초) 동안 그 버튼을 막는다.
  - 오류 안내의 섹션 제목은 20자에서 줄이고 말줄임표를 붙인다.
- **패널 연결은 `createPreviewSession` 이다** — 로더 · 실패 안내 · iframe 전송을 묶은 작은 컨트롤러라 DOM 없이 테스트한다(준비 전에는 보내지 않음 · 실패하면 마지막 성공 응답을 두고 다시 보내지 않음 · 다시 시도 · 닫으면 취소). `PreviewPanel` 은 이벤트를 넘기기만 한다.
- **캐시** — 초안은 공개 전시 데이터 캐시(60초)를 지나지 않는다. BE 응답은 `no-store` 다. 저장하지 않으면 실제 메인은 그대로다.
- **iframe 허용** — proxy 가 `/[locale]/preview/*` 에만 `Content-Security-Policy: frame-ancestors <ADMIN_ORIGIN>` 을 붙인다(`previewFramePolicy`).
  `ADMIN_ORIGIN` 이 없으면 개발은 `http://localhost:3001`, 운영은 `'none'` 이다. 페이지는 `noindex` 다. 다른 경로는 같은 출처만 감쌀 수 있다(§2 클릭재킹, #345).
- 어드민 iframe 은 `sandbox="allow-scripts allow-same-origin"` 이라 미리보기 안 링크가 어드민 창을 옮기지 못한다. 미리보기 블록 안의 링크 · 버튼은 누르지 않는다
  (`MainPreview` 가 클릭을 막는다 — 실제 페이지 이동 · 장바구니 담기 방지). 머리글 · 바닥글(레이아웃)의 링크는 iframe 안에서 그 페이지로 옮겨 간다.
- 어드민은 기본 언어(국문) 미리보기만 연다. 영문 메인 미리보기는 `/en/preview/main` 이 있지만 어드민에 고르는 칸이 없다.
