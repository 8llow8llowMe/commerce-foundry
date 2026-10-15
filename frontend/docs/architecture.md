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
  - 401 + refresh 쿠키 → 재발급 1회 → 새 쿠키 → 원 요청 1회 재시도. 재발급 실패면 쿠키 삭제 + 401
  - 게이트웨이 Set-Cookie 는 버린다. 연결 실패는 BFF_002 503
      ↓
[API Gateway :8000] → 서비스

[Server Component] serverFetch(...)                게이트웨이 직접 호출. 재발급하지 않는다(401 → 로그인으로)
```

- 브라우저는 게이트웨이를 직접 부르지 않으므로 운영 CORS 가 필요 없다.
- 401 은 인증 단계 거절이라 서버가 처리하지 않았다. 쓰기 요청도 재발급 후 1회 재시도가 안전하다.
- 같은 refresh 토큰의 동시 재발급은 인스턴스 안에서 한 번으로 합친다. 다중 인스턴스 경합은 #25 에서 BE 와 정한다.
- 재발급이 성공한 뒤 재시도가 연결 실패해도 새 쿠키는 심는다(회전된 refresh 토큰을 잃지 않는다).

## 3. 쿠키

- `HttpOnly; SameSite=Lax; Path=/`, 운영은 `Secure`.
- 고객 `cf_at` / `cf_rt`, 어드민 `cf_admin_at` / `cf_admin_rt` — 세션을 섞지 않는다.
- 이름·수명은 임시다. 고객은 #25, 어드민은 #29 에서 BE 토큰 만료와 맞춰 확정한다.
- 재발급 요청·응답 모양(`{ refreshToken }` → `{ accessToken, refreshToken }`)은 #4 기준 가정이다. #4 머지 후 `packages/api-client/src/server/reissue.ts` 를 맞춘다.

## 4. 헬스체크

각 앱 `GET /api/health` → 게이트웨이 `/actuator/health` 결과로 `{ app, gateway }`. 게이트웨이 DOWN 이면 503.
