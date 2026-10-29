# API Client Guide

백엔드 계약 정본은 `backend/docs/api-design-guide.md` §2 · §4 · §6 이다. 이 문서는 프론트에서 그것을 다루는 법이다.

## 1. 호출

| 어디서 | 무엇으로 |
|--------|----------|
| Client Component | `apiFetch<T>('/api/v1/...', { method, json, idempotencyKey })` (`@cf/api-client`) |
| Client Component(SSE) | `readEventStream('/api/v1/...', { signal, onOpen, onEvent })` (`@cf/api-client`) — 실패는 `ApiError`, 끊기면 호출자가 다시 연다(`architecture.md` §2 SSE 중계) |
| Server Component | `serverFetch<T>('/api/v1/...')` (앱의 `src/lib/server-api.ts`) |
| route handler | `@cf/api-client/server` 의 함수 |

`@cf/api-client/server` 는 클라이언트 컴포넌트에서 import 하지 않는다.

- `serverFetch` 는 기본으로 access 쿠키를 Bearer 로 싣고 캐시하지 않는다(`cache: 'no-store'`).
- **공개 · 사용자 무관 조회를 캐시할 때만** 셋째 인자 `{ anonymous: true }` 로 토큰을 빼고 `init` 에 `cache: 'force-cache'` + `next.revalidate` 를 준다.
  Next 데이터 캐시는 헤더까지 키로 써서 토큰을 실으면 사용자마다 갈린다. 지금 쓰는 곳은 메인 전시(60초, `architecture.md` §10)뿐이다.
  재검증 시간은 BE 응답의 `Cache-Control: max-age` 와 맞춘다. 회원 API 에는 쓰지 않는다.

## 2. 성공

- `dataBody` 를 그대로 돌려준다. 선택적 하위 리소스 부재는 `null` 이 정상이다.
- `204 No Content` 는 `null` 이다.
- 목록 타입: 스토어프론트 `SliceResponse<T>`, 어드민 `PageResponse<T>`(page 는 0 부터, 화면은 +1).

## 3. 실패 — `ApiError`

`kind` · `status` · `code`(resultCode) · `message` · `fieldErrors` 를 갖는다. 판정은 위에서부터 먼저 맞는 것이다.

| kind | 조건 | 화면 기본 처리 |
|------|------|----------------|
| `network` | fetch 자체 실패 | 일시 오류 안내 |
| `invalidResponse` | 봉투가 아닌 응답 | 일시 오류 안내 |
| `validation` | `fieldErrors` 가 비어 있지 않다 | `messagesFor(error, field)` 로 필드별 표시 |
| `unauthorized` | 401 (BFF 재발급까지 실패) | 로그인 이동 |
| `forbidden` | 403 | 권한 없음 안내 |
| `notFound` | 404 | not-found |
| `conflict` | 409 (같은 멱등 키 처리 중) | 잠시 후 결과 조회 |
| `unknownOutcome` | 502 · 504 | **쓰기는 자동 재시도 금지.** 같은 키로 재시도하거나 조회 |
| `unavailable` | 503 | 잠시 후 재시도 안내 |
| `rateLimited` | 429 (`GATEWAY_006` 등 요청 제한) | 안내 + 같은 버튼을 `retryAfterSec` 동안 막는다 (§3-1). 자동 재시도 금지 |
| `server` | 그 밖 | `message` 표시 |

- `message` 는 서버 `resultMessage` 가 있으면 그것, 없으면 kind 별 기본 문구다.
- 요청을 취소하면(`signal` abort) `ApiError` 가 아니라 취소 reason(기본 `AbortError`)이 그대로 던져진다.
  응답 본문을 읽는 중에 취소돼도 같다. 오류 안내를 띄우지 않는다.
- `retryAfterSec` 은 429 의 `Retry-After`(초)다. 헤더가 없거나 읽을 수 없으면 `null`, 429 가 아니면 항상 `null` 이다.
- BFF 자체 코드: `BFF_001`(404, `/api/v1` 밖 경로), `BFF_002`(503, 게이트웨이 연결 실패·재발급 일시 장애),
  `BFF_003`(403, 다른 origin 에서 온 쓰기 요청 — CSRF).

### 3-1. 요청 제한 (429)

게이트웨이는 장바구니 · 주문 쓰기를 회원 단위로 제한하고, 넘으면 **429 `GATEWAY_006`** + `Retry-After`(초)를 준다
(정책·한도 정본은 `backend/docs/security-guide.md` §7-3). 요청은 서비스에 닿지 않았다.
auth 서비스도 로그인 · 회원가입 시도를 이메일 단위로 제한한다 — **429 `AUTH_040`**(로그인) · **`AUTH_041`**(가입) + `Retry-After`(#66 — 한도 · 절 번호는 #66 이 `backend/docs/security-guide.md` 에 적는 것이 정본).

- BFF 는 429 를 그대로 넘긴다. 재발급 · 재시도 · 쿠키 변경을 하지 않는다 — 로그아웃되지 않는다.
  봉투를 다시 만드는 로그인 · 가입 전용 핸들러도 `Retry-After` 를 함께 넘긴다(`relayFailure`, `architecture.md` §3).
- 토큰 없는 요청은 클라이언트 IP 로 센다(게이트웨이에서 켰을 때). 그래서 BFF 는 access 쿠키가 만료된 회원의 요청을 토큰 없이 보내지 않고
  **먼저 재발급**하며, 게이트웨이행 `X-Forwarded-For` 를 클라이언트 IP 한 값으로 덮어쓴다(`architecture.md` §2).
- 화면은 `message`(기본 "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")를 보여 주고, 스토어프론트의
  `useRetryCooldown()`(`apps/storefront/src/lib/use-retry-cooldown.ts`)으로 **같은 버튼**을 `Retry-After` 동안 막는다.
  `Retry-After` 가 없으면 3초, 최소 1초 · 최대 60초다. 한도가 정책(장바구니 쓰기 · 주문 쓰기)마다 따로라서 훅도 버튼마다 하나다.
- **로그인 · 가입 화면**은 범용 실패 문구 대신 이유 + 남은 시간 안내를 보인다(`rateLimitText`, `apps/storefront/src/lib/auth/rate-limit.ts`).
  이유는 코드로 고르고(`AUTH_040` · `AUTH_041`, 그 밖은 일반 요청 제한), 남은 시간은 `Retry-After` 를 1분 이상이면 분으로 올려 보인다.
  버튼 쿨다운은 위 상한(60초)을 그대로 쓴다 — 안내 시간보다 짧을 수 있고, 다시 누르면 서버가 남은 시간을 다시 알려 준다.
  가입 직후 자동 로그인이 429 면 로그인 화면이 `useRetryCooldown({ retryAfterSec })` 로 쿨다운을 이어받는다.
- **자동 재시도하지 않는다.** 쿨다운이 끝나면 사용자가 다시 누른다. 주문하기는 다시 눌러도 같은 의도이므로 같은 멱등 키를 쓴다(§4).
- **장바구니 병합(로그인 직후 1회)이 429 면** 사용자를 막지 않는다. 안내 없이 넘어가고 비회원 장바구니를 지우지 않은 채
  다음 화면 진입 때 다시 시도한다(`CartMerge`, `architecture.md` §6).

## 4. 멱등 키

- 주문 생성·결제 승인·취소·환불은 `idempotencyKey` 를 보낸다.
- `createIdempotencyKey()` 로 "사용자 의도 한 번"(버튼 한 번)에 하나 만들고, 그 의도의 재시도에는 같은 키를 쓴다.
- 버튼은 요청 중 비활성화해 더블 클릭을 막는다.
- 같은 키로 다시 보낼 때는 **본문도 같아야 한다**(다르면 409). 그래서 키는 "본문 + 키" 묶음(시도)으로 들고, 본문이 바뀌면 새 키를 만든다.
  같은 키로 다시 보내는 것은 결과를 모르는 실패(5xx · 502/504 · 연결 실패)와 처리 중(409) · 429 뒤뿐이다. 그 밖의 4xx 뒤에는 새 키다.
  주문서의 예는 `nextAttempt` · `orderFailure`(`apps/storefront/src/lib/checkout/order.ts`, `architecture.md` §6-1)다.

## 5. 금액

- `Money = { amount, currency }`, amount 는 최소 화폐 단위 정수(KRW 원, USD 센트).
- 표시는 `formatMoney(money, locale)` 만 쓴다: 국문 스토어 KRW `129,000원`, 영문 스토어 KRW `₩129,000`, USD `$89.50`. 스토어프론트 클라이언트 컴포넌트는 `useFormatMoney()` 로 지금 언어를 넣는다.
- 클라이언트에서 금액을 계산하지 않는다. 합계·할인·배송비는 서버 값을 표시한다.
