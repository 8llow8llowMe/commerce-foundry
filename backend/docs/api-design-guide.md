# Backend API Design Guide

## 1. 경로 체계

| 구분 | 접두어 | 사용자 | 게이트웨이 | 진입 패키지 |
|------|--------|--------|-----------|-------------|
| 고객(스토어프론트) | `/api/v1/**` | 비로그인 · `CUSTOMER` | 라우팅 | `adapter/in/web` |
| 어드민 콘솔 | `/api/v1/admin/**` | `ADMIN` | 라우팅 + **ADMIN 역할 확인** | `adapter/in/admin` |
| 서비스 간 | `/internal/v1/**` | 다른 서비스 (서비스 토큰 · 허용 호출자, `security-guide.md` §9) | **라우팅하지 않는다** | `adapter/in/internal` |
| 외부 콜백 | `/api/v1/payments/webhooks/**` 등 | PG·택배사 | 라우팅 (JWT 없음) | `adapter/in/webhook` |
| 운영(개발용) | `/health/{service-id}`, `/docs/{service-id}/v3/api-docs/{customer\|admin}`, `/swagger-ui.html` | 개발자 | 정해진 경로만 서비스로 라우팅(`/actuator/health` · `/v3/api-docs/{group}` 으로 고정), Swagger UI 는 게이트웨이가 낸다 · **local·dev 만** (`gateway.dev-routes.enabled`) | — (서비스 actuator · springdoc) |

### 1-1. RESTful 원칙

- 컬렉션은 복수형 kebab-case: `products`, `categories`, `carts`, `orders`, `payments`, `deliveries`, `shipping-addresses`
- 상위 → 하위 리소스 순으로 깊이를 표현한다.
  - `GET /api/v1/products/{productId}/skus` (상품의 옵션 조합)
  - `GET /api/v1/members/me/shipping-addresses`
  - `POST /api/v1/orders/{orderId}/cancellation` (주문 취소 요청 — 동사 대신 명사 리소스)
  - `PATCH /api/v1/admin/orders/{orderId}/status`
- 컬렉션에 대한 계산·요약은 컬렉션 바로 아래 리터럴로 둔다: `POST /api/v1/orders/quotes` (주문서 금액 미리 계산).

### 1-2. 서비스별 경로 소유

| 서비스 | 고객 | 어드민 |
|--------|------|--------|
| auth-service | `/api/v1/auth/**`, `/api/v1/members/**` | `/api/v1/admin/members/**` |
| product-service | `/api/v1/categories/**`, `/api/v1/products/**` | `/api/v1/admin/categories/**`, `/api/v1/admin/products/**`, `/api/v1/admin/stocks/**` |
| order-service | `/api/v1/carts/**`, `/api/v1/orders/**` | `/api/v1/admin/orders/**`, `/api/v1/admin/claims/**` |
| payment-service | `/api/v1/payments/**` | `/api/v1/admin/payments/**` |
| delivery-service | `/api/v1/deliveries/**`, `/api/v1/shipping-fees/**` | `/api/v1/admin/deliveries/**`, `/api/v1/admin/shipping-policies/**` |

게이트웨이 라우트는 이 표를 그대로 옮긴다. 새 경로 접두어를 만들면 이 표와 게이트웨이를 함께 고친다.

### 1-3. 서비스 간 벌크 조회 (`/internal/v1/**`)

여러 건을 한 번에 읽는 internal API(예: `GET /internal/v1/skus?ids=1,2,3`)는 아래를 지킨다. 호출하는 쪽이 루프에서 단건 API 를 부르지 않게 하려는 것이다 (`coding-conventions.md` §9-6).

| 규칙 | 내용 |
|------|------|
| 파라미터 | `ids` 하나. `ids=1,2,3` · `ids=1&ids=2` 모두 받는다. 컨트롤러 시그니처는 `@RequestParam @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> ids` |
| 상한 | **100개** — 중복을 포함한 원문 개수로 센다. 넘으면 400 |
| 빈 목록 | `ids=` 는 400 이다. 호출하는 쪽은 조회할 것이 없으면 부르지 않는다 |
| 원소 | 0 · 음수 · 빈 원소(`1,,2`)는 400, 숫자가 아니면 400 (`{DOMAIN}_198`), 파라미터가 없으면 400 (`{DOMAIN}_199`) |
| 중복 | 첫 등장 순서로 한 번만 조회한다 |
| 없는 아이디 | **응답에서 뺀다** — 404 로 전체를 실패시키지 않는다. 누락을 보고 거절할지는 호출한 쪽이 정한다 |
| 순서 | 응답은 요청 순서(중복 제거 후)다. 호출한 쪽이 다시 맞추지 않아도 된다 |
| 쿼리 | 받은 아이디로 `in` 절 벌크 조회만 한다. 첫 조회가 0건이면 이어지는 조회를 보내지 않는다 |

- 검증은 Spring MVC 의 메서드 검증이 한다. 컨트롤러 클래스에 `@Validated` 를 달지 않는다 (`coding-conventions.md` §8-2).
- 서비스 토큰 인증을 통과한 요청에서는 메서드 검증이 `@PreAuthorize`(허용 호출자)보다 먼저 돈다 — 허용되지 않은 호출자도 형식이 틀리면 400 이다.
  인증 전 단계(internal 체인 — `security-guide.md` §9)에서는 401 `SECURITY_007` 이 먼저다.
- 처음 만든 곳: product-service `SkuInternalController` (#8). 코드 표는 `ProductValidationMessage` (`PRODUCT_103` 빈 목록 · `PRODUCT_104` 100개 초과 · `PRODUCT_105` 양수 아님).

**internal 쓰기 API** 도 같은 자리(`/internal/v1/**`, `@Hidden`, 허용 호출자 `@PreAuthorize`)에 둔다. 아래를 더 지킨다.

| 규칙 | 내용 |
|------|------|
| 본문 | `@RequestBody @Valid` record. 목록 필드 상한은 벌크 조회와 같은 **100개**(원문 개수). 필드별 검증 코드는 도메인 `1xx` 대역 |
| 경로 변수 | `@PathVariable @Positive long` — 양수가 아니면 400, 숫자가 아니면 `{DOMAIN}_198` |
| 멱등 | 호출한 쪽이 타임아웃 뒤 같은 요청을 다시 보낸다고 본다. 업무 키(주문 아이디 등)나 대상 행의 상태로 이미 처리됐는지 판단하고, **이미 처리된 요청은 처음 결과와 같은 모양으로 200** 이다 (§6) |
| 상태 전이 | 허용 전이 표 밖은 409 — 어떤 상태라서 거절했는지 코드로 구분한다 (`order-flow-guide.md` §3) |

- 처음 만든 곳: product-service `StockInternalController` (#10) — `POST /internal/v1/stock-reservations` · `.../{reservationId}/confirmation` · `.../{reservationId}/release`.
  검증 코드 `PRODUCT_112`~`PRODUCT_122`(수량 상한 10000 은 `PRODUCT_122`), 도메인 코드 `PRODUCT_003` · `PRODUCT_021`~`PRODUCT_026` (`order-flow-guide.md` §3).

## 2. 응답 구조

- 공통 래퍼 `Response<T>` 를 쓰고, Controller 반환은 `ResponseEntity<Response<T>>` 로 통일한다.
- 내부 `Info` 를 응답 타입으로 직접 노출하지 않는다. 응답 모양은 Presenter 가 마무리한다.
- HTML 을 담는 응답 필드(상품 상세 설명 `descriptionHtml`)는 서버가 정제하지 않은 원문이다. 클라이언트가 렌더 전에 허용 목록으로 sanitize 한다.
  쓰기 시점 서버 sanitize 는 상품 등록(#9) · 이관(#19)이 맡는다.
- 시각 필드는 `Instant` 그대로 내린다 — **UTC ISO-8601 문자열**(`"2026-10-18T09:30:00Z"`)이다. 화면 시간대 변환은 클라이언트(또는 고객 응답의 Presenter)가 한다.

```java
ProductDetailResponse response = productWebUseCase.getProduct(productId);
return ResponseEntity.ok().body(Response.success(response));
```

### 2-1. 오류 봉투 계약

`dataHeader` 의 칸마다 타입이 하나씩만 있다. 오류 종류에 따라 타입이 갈리지 않는다.

| 필드 | 타입 | 값 |
|------|------|-----|
| `success` | `boolean` | 성공 여부 |
| `resultCode` | `string \| null` | 대표 오류 코드. 성공이면 `null` |
| `resultMessage` | `string \| null` | 사용자에게 보여줄 대표 메시지. 성공이면 `null` |
| `fieldErrors` | `ValidationErrorItem[] \| null` | 필드 단위 검증 오류. 검증 실패가 아니면 `null` |

```json
{"dataHeader":{"success":false,"resultCode":"ORDER_001",
  "resultMessage":"존재하지 않는 주문입니다.","fieldErrors":null},"dataBody":null}

{"dataHeader":{"success":false,"resultCode":"MEMBER_103",
  "resultMessage":"휴대폰 번호 형식이 올바르지 않습니다.",
  "fieldErrors":[{"code":"MEMBER_103","field":"phone","message":"휴대폰 번호 형식이 올바르지 않습니다."}]},"dataBody":null}
```

- **게이트웨이가 끊은 응답(JWT 거부, ADMIN 아님)도 같은 봉투로 나간다.** 클라이언트는 어디서 끊겼는지 모른다.
- 인증·인가 오류 코드는 `SECURITY_00x` 하나로 통일한다. **게이트웨이도 JWT 거부는 `SECURITY_*` 를 쓴다.**
  코드 표와 `WWW-Authenticate` 값의 정본은 [security-guide.md](security-guide.md) §4 다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `SECURITY_001` | 401 | 토큰 없이 인증 필요 API · 어드민 경로에 왔다 (access 쿠키가 만료로 사라져 Authorization 없이 온 요청도 여기다) |
| `SECURITY_002` | 401 | 보낸 access 토큰이 만료됐다 |
| `SECURITY_003` · `SECURITY_004` | 401 | 위조 · 형식 오류 · 다른 발급자 / 로그아웃된 토큰 |
| `SECURITY_005` | 403 | 역할 부족 (CUSTOMER 가 어드민 API) |
| `SECURITY_006` | 503 | 토큰 검증 저장소 장애 · 검증 중 시스템 오류 |
| `SECURITY_007` | 401 | 서비스 간 인증 실패 — internal API 전용이라 프론트는 받지 않는다 |

| HTTP | 프론트(BFF) 처리 — 정본 [frontend/docs/architecture.md](../../frontend/docs/architecture.md) §2 · §3 |
|------|------------------------------------------------------|
| 401 (001 · 002 · 003 · 004) | refresh 쿠키가 있으면 **코드와 무관하게** 한 번 재발급하고 원 요청을 한 번 재시도한다. 재발급이 명시적으로 거절되면(4xx · `success=false`) 쿠키를 지우고 로그인 화면, 재발급 일시 장애는 쿠키를 둔 채 `BFF_002` 503 |
| 403 (005) | 권한 없음 안내 |
| 503 (006) | 잠시 후 재시도. 로그아웃시키지 않는다 |

- 재발급 여부를 코드로 가르지 않는다. access 쿠키가 만료로 사라지면 Authorization 없이 보내 001 이 나오므로, "002 만 재발급" 이면 access 수명마다 로그아웃된다.
  코드는 원인을 알리는 용도다(로그 · 모니터링 · 화면 문구). 서버 컴포넌트의 `serverFetch` 는 재발급하지 않고 401 이면 로그인으로 보낸다(같은 문서 §2).

- 토큰을 보냈는데 틀리면 **비로그인으로 되는 공개 API 여도 401** 이다. 틀린 토큰을 익명으로 취급하지 않는다.

#### 공통 오류 코드 (서비스)

서비스까지 왔지만 도메인 advice · 보안 advice 가 받지 않은 오류의 코드다 — 매핑 없는 경로, 지원하지 않는 메서드 · 미디어 타입,
도메인 핸들러가 없는 바인딩 오류, 처리되지 않은 예외(DB 장애 등). 정본은 common-core 의 `CommonErrorCode` 다.
게이트웨이 코드와 같은 짜임이라 클라이언트는 접두어(`GATEWAY_` · `COMMON_` · 도메인)로 어디서 끊겼는지만 구분한다.

| 코드 | HTTP | 언제 | 클라이언트 처리 |
|------|------|------|-----------------|
| `COMMON_000` | 500 · 그 밖의 5xx | 처리되지 않은 예외 | 일시 오류 안내 |
| `COMMON_001` | 404 | 서비스에 매핑이 없는 경로 | 경로 오류 |
| `COMMON_002` | 405 + `Allow` | 경로는 있으나 메서드를 지원하지 않는다 | 요청 수정 |
| `COMMON_003` | 415 | 지원하지 않는 `Content-Type` | 요청 수정 |
| `COMMON_004` | 400 · 406 · 그 밖의 4xx | 도메인 핸들러가 받지 않은 바인딩 · 협상 오류 (필수 헤더 누락 등) | 요청 수정 |

- **도메인 오류가 먼저다.** 폴백은 MVC 예외 처리 사슬에서 모든 `@RestControllerAdvice` 뒤에 선다 — 도메인 `*ExceptionHandler` 가 받는 예외는
  `@Order` 없이도 도메인 코드로 나간다. 검증 오류(`{DOMAIN}_1xx`)도 도메인 핸들러가 그대로 받는다.
- 응답 본문에 예외 메시지 · 스택을 싣지 않는다. 500 의 원인은 서비스 로그에만 남는다(경로는 앞 3구간만).
  예외 메시지에 입력값이 섞이는 오류(제약 위반 등)는 폴백에 맡기지 말고 도메인에서 먼저 받아 도메인 코드로 바꾼다 — 폴백 로그는 예외를 그대로 남긴다.
- 인증 · 인가 실패를 `ResponseStatusException(401/403)` 으로 던지지 않는다. 폴백으로 오면 `SECURITY_00x` 가 아니라 `COMMON_004` 로 나간다(경고 로그가 남는다).
- internal 경로(`/internal/**`)의 매핑 없는 요청은 인증 전 단계에서 401 `SECURITY_007` 이 먼저다.
- 폴백은 두 곳에 있다. MVC(`DispatcherServlet`) 안의 오류는 `FallbackErrorResolver` 가, **MVC 밖의 오류**(서블릿 필터 예외 · Spring Security 방화벽의
  비정규 경로 거부 · 그 밖의 `sendError`)는 `/error` 를 맡는 `FallbackErrorController`(Boot `BasicErrorController` 대체, #81)가 같은 표로 `COMMON_00x` 봉투를 쓴다.
  보안 필터의 인증 · 인가 거부는 여전히 `SECURITY_00x` 진입점이 직접 쓴다(`/error` 로 가지 않는다).
  - `/error` 응답은 `Accept` 와 무관하게 언제나 JSON 봉투다(HTML 화이트라벨을 내지 않는다). 직접 `GET /error` 는 404 `COMMON_001` 이다.
  - 방화벽 거부는 400 `COMMON_004` 다 (`;` · `%2e` · `//` 등 — 정상 클라이언트는 게이트웨이 `GATEWAY_004` 에서 먼저 끊긴다).
  - 예외: 인코딩된 슬래시(`%2f`)는 Tomcat 이 애플리케이션 전에 400 HTML 로 끊는다 — 봉투가 아니고 서버 이름 · 버전은 싣지 않는다. 게이트웨이가 먼저 거절한다.
- `@WebMvcTest` 슬라이스에는 이 자동 구성이 들어가지 않는다 — 슬라이스의 404 · 500 모양은 운영과 다르다. 폴백 모양은 `*FallbackErrorSmokeTest`(전체 컨텍스트)가,
  `/error` 경로는 `*ErrorPageEnvelopeSmokeTest`(실제 서블릿 컨테이너 — MockMvc 는 `/error` 재전송을 재현하지 않는다)가 본다.

#### 게이트웨이 오류 코드

서비스까지 가지 못하고 게이트웨이에서 끊긴 요청의 코드다. 서비스가 돌려준 오류는 바꾸지 않고 그대로 중계한다.
정본은 `cloud/api-gateway` 의 `GatewayErrorCode` 다.

| 코드 | HTTP | 언제 | 클라이언트 처리 |
|------|------|------|-----------------|
| `GATEWAY_000` | 500 | 그 밖의 예상하지 못한 게이트웨이 오류 | 일시 오류 안내 |
| `GATEWAY_001` | 404 | 어느 라우트에도 없는 경로. `/internal` 과 그 아래는 라우팅 · 토큰 검증 전에 명시적으로 이 코드다 (대소문자 · 인코딩 변형 포함) | 경로 오류 |
| `GATEWAY_002` | 503 | 대상 서비스의 살아 있는 인스턴스가 없거나 연결에 실패했다 (요청이 서비스에 닿지 않았다) | 잠시 후 재시도 |
| `GATEWAY_003` | 504 | 서비스가 응답 타임아웃(10초) 안에 답하지 않았다 | 처리 여부 불명 (아래) |
| `GATEWAY_004` | 400 · 405 · 그 밖의 4xx | 라우팅 전에 거절한 요청 — 정규화되지 않은 경로(400), `Authorization` 헤더가 둘 이상(400), 허용하지 않는 메서드(405 + `Allow`), chunked 본문 수신 시간 초과(408) · 동시 수신 총량 초과(411, `Content-Length` 를 붙여 다시 보낸다) | 요청 수정 |
| `GATEWAY_005` | 502 | 요청을 보낸 뒤 서비스가 응답 전에 연결을 끊었다 | 처리 여부 불명 (아래) |
| `GATEWAY_006` | 429 + `Retry-After` | 회원 단위 쓰기 요청 한도를 넘었다 — 장바구니 · 주문 쓰기 (`security-guide.md` §7-3) | `Retry-After` 초 뒤 재시도. 요청은 서비스에 닿지 않았다. 재발급 · 로그아웃하지 않는다 (#91) |
| `GATEWAY_007` | 413 | 요청 본문이 상한(2MB)을 넘었다 (`security-guide.md` §7-4) | 요청은 서비스에 닿지 않았다. 본문을 줄여 다시 보낸다 |

- **502 · 504 는 처리 여부를 알 수 없다** — 쓰기 요청은 같은 `Idempotency-Key` 로 재시도하거나 조회로 확인한다 (§6).
- 경로 거절(400): 원문 경로에 `.` · `..` 세그먼트, `//`, `;`, 인코딩된 `.` · `/` · `\`(`%2e` · `%2f` · `%5c`), 이중 인코딩(`%25`),
  역슬래시, 제어문자가 있으면 라우팅 전에 끊는다. 쿼리 문자열은 보지 않는다. 정상 클라이언트는 이런 경로를 만들지 않는다.
- 허용 메서드는 `GET` · `HEAD` · `POST` · `PUT` · `PATCH` · `DELETE` · `OPTIONS` 다. `TRACE` · `CONNECT` 등은 405 다.
- 응답 본문에 예외 메시지·스택을 싣지 않는다. 경로·라우트·예외 타입은 게이트웨이 로그에만 남는다.
- 허용되지 않은 origin 은 preflight 뿐 아니라 **실제 요청도** 라우팅 전에 봉투 없이 `403` 으로 거부된다(브라우저가 막는 응답이다).
  허용 목록에 없는 요청 헤더를 쓴 preflight 도 `403` 이다.

### 2-2. 부재는 언제 200 + null 이고 언제 404 인가

- **id 로 지정한 리소스가 없거나 타인 소유면 404** — 남의 주문은 403 이 아니라 404 로 답해 존재를 숨긴다.
- **부모당 0~1개인 선택적 하위 리소스의 부재는 200 + `dataBody: null`** — 예: 아직 송장이 없는 주문의 배송 정보.

### 2-3. 금액 표현

```json
{ "salePrice": { "amount": 129000, "currency": "KRW" } }
{ "salePrice": { "amount": 8950,   "currency": "USD" } }
```

- `amount` 는 **최소 화폐 단위 정수**다 (KRW 원, USD 센트 — 위 USD 는 89.50 달러).
- 표시 포맷(천 단위 구분, 소수점)은 프론트가 `currency` 로 정한다. 서버가 포맷된 문자열을 내리지 않는다.
- 이 계약은 FE 와 합의한 것이므로 바꿀 때는 `seonghoho` 에게 먼저 알린다.

### 2-4. 회원 · 인증 (auth-service)

| API | 인가 | 성공 | 주요 오류 |
|-----|------|------|-----------|
| `POST /api/v1/auth/sign-up` | `permitAll()` | 201 `{memberId}` — 토큰은 주지 않는다(로그인을 따로 한다) | 400 `AUTH_1xx` · 409 `AUTH_002` |
| `GET /api/v1/members/me` | `isAuthenticated()` | 200 `{memberId, email, name, phone, marketingAgreed}` | 401 `SECURITY_001` · 404 `AUTH_003` |
| `PATCH /api/v1/members/me` | `isAuthenticated()` | 200 바뀐 내 정보 — `{name?, phone?, marketingAgreed?}` 중 보낸 칸만 바꾼다 | 400 `AUTH_1xx` · 404 `AUTH_003` · 409 `AUTH_004` |

오류 코드의 정본은 `MemberErrorCode` · `MemberValidationMessage` 다. 접두어는 서비스 단위 `AUTH_` 이고, 로그인 · 토큰(#4 A3 · A4)이 `AUTH_010~` 을 이어 쓴다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `AUTH_001` | 400 | 로그인 실패 — 이메일 없음 · 비밀번호 틀림 · 관리자 계정을 구분하지 않는다. 401 은 토큰 거절에만 쓴다 (#4 결정 5, 로그인은 A3) |
| `AUTH_002` | 409 | 이미 가입된 이메일. 사전 조회를 함께 지난 동시 가입은 유니크 키(`uk_member_email`) 위반을 같은 코드로 바꾼다. 계정 존재를 드러내는 것은 감수한 위험이다 (`security-guide.md` §10) |
| `AUTH_003` | 404 | 토큰은 유효하지만 회원이 없다 |
| `AUTH_004` | 409 | 같은 회원을 다른 요청이 먼저 고쳤다(낙관적 락 — 마케팅 동의만 바꿔도 회원 버전이 오른다). 다시 조회한 뒤 보낸다 |
| `AUTH_100` | 400 | 깨진 JSON · 본문 없음 · 타입 불일치 (필드는 `fieldErrors` 로 밝힌다) |
| `AUTH_101~115` | 400 | 필드별 검증 — 이메일 101~103 · 비밀번호 104~106 · 이름 107 · 108 · 114 · 전화 109 · 동의 110~113 · 수정 칸 없음 115 |

입력 규칙

- **이메일** — 254자 이하, 도메인에 점이 있는 형식. 앞뒤를 포함해 공백이 있으면 거절한다(103 — 프론트가 trim 해서 보낸다).
  소문자로 바꿔 저장 · 조회하므로(`Member.normalizeEmail`) 대소문자만 다른 이메일은 같은 회원이다. DB 도 `ck_member_email_normalized`(`email = lower(email)`)로 강제한다.
- **비밀번호** — 8~64자, 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII 출력 문자(영문 · 숫자 · 특수문자)만. ASCII 로 묶는 이유는 BCrypt 가
  72바이트까지만 쓰기 때문이다(64자 ASCII = 64바이트). 저장은 `BCryptPasswordEncoder`(강도 10) 해시뿐이고 원문 · 해시를 로그 · 응답에 싣지 않는다.
- **이름** — 1~50자, 앞뒤 공백을 지우고 저장한다. 공백만인 값은 가입에서는 필수 누락(107), 내 정보 수정에서는 114 다 —
  가입은 필수 칸이라 `@NotBlank` 로, 수정은 선택 칸이라 "보냈다면 공백만은 안 된다" 로 검사한다.
- **전화번호** — 선택. 국내 휴대폰(`01012345678` · `010-1234-5678`)을 받고 숫자만 저장한다. 가입에서 빈 문자열은 109 다.
  내 정보 수정에서는 빈 문자열(`""`)만 "지우기" 이고, 공백만 있는 값은 109 다. 해외 번호는 영문 스토어 회원가입에서 넓힌다.
- 저장된 값은 검증 없이 읽는다(`Member.restore`) — 이관(#19) 데이터가 지금 규칙 밖이어도 조회 · 로그인은 된다. 규칙은 가입 · 수정 때 바꾸는 칸에만 건다.
- **동의** — 가입 때 `agreements.{termsOfService, privacyPolicy, marketing}` 세 칸을 모두 보낸다. 앞의 둘은 `true` 여야 한다.
  세 칸 모두 동의 이력(`member_agreement`, 추가만 한다)에 한 줄씩 남고 약관 버전은 서버 설정 `auth.agreement.versions.*` 다 — 클라이언트가 보내지 않는다.
  마케팅 현재 상태는 그 회원의 가장 최근 마케팅 행이고, 내 정보 수정에서 값이 바뀔 때만 한 줄 더한다.
- 역할은 요청으로 받지 않는다. 가입은 언제나 `CUSTOMER` 다. 관리자 계정은 같은 `member` 테이블에 `role=ADMIN` 으로 따로 만든다(#6).

## 3. 계층 흐름

```text
Controller → UseCase → Facade → Processor → Port/Adapter
Info → Presenter → Response
```

## 4. 정렬 / 페이지네이션

| 화면 | 응답 | 이유 |
|------|------|------|
| 스토어프론트 목록 (상품 목록, 리뷰) | `SliceResponse` (커서/무한 스크롤) 기본 | 총계가 필요 없고 count 질의 비용이 없다 |
| 어드민 목록 (주문·상품·회원 관리 표) | `PageResponse` (`page`, `size`, `totalElements`, `totalPages`) | 페이지 번호·전체 건수가 업무에 필요하다 |

```json
{ "contents": [ ... ], "hasNext": true }
{ "contents": [ ... ], "page": 0, "size": 20, "totalElements": 135, "totalPages": 7 }
```

- 두 타입은 `persistence-core` 의 `SliceResponse` · `PageResponse` 다. Presenter 는 `map(...)` 으로 항목만 바꾼다.
- **`page` 는 0 부터 센다** (Spring `Pageable` 과 같다). 화면의 "1페이지" 는 프론트가 +1 해서 보여준다.
- 정렬은 enum `@RequestParam` 으로 받는다. Swagger 에 허용값이 드러난다.
  - **기본은 `sortType` 단일 enum 이다.** 방향을 값에 담는다 — 상품 목록 `NEWEST`(기본) / `PRICE_ASC` / `PRICE_DESC`.
  - 방향이 따로 의미를 갖는 경우(같은 기준을 사용자가 오름 · 내림으로 뒤집는 표 등)만 `orderType` 을 더한다.
  - 모든 정렬은 마지막에 아이디 내림차순(최신순)으로 동률을 끊는다. 페이지 경계에서 같은 항목이 두 번 나오거나 빠지지 않게 한다.
- `totalCount` 를 내리는 응답은 **자르기 전 개수**여야 한다. Presenter 가 `items.size()` 로 채우지 않는다.

## 5. 보안 API 설계

- 고객·어드민 API(`adapter/in/web` · `adapter/in/admin`)의 **모든 핸들러에 `@PreAuthorize` 를 단다.** 서비스 URL 단계가 열려 있어(default-allow)
  빠뜨리면 공개 API 가 된다. 공개 API 도 `@PreAuthorize("permitAll()")` 로 명시하고, 로그인 필요는 `isAuthenticated()`, 어드민은 `hasRole('ADMIN')` 이다.
  본문 검증(`@RequestBody @Valid`)은 인자 바인딩 단계라 메서드 보안보다 먼저 돈다 — `isAuthenticated()` 쓰기 API 에 익명으로 형식이 틀린 본문을 보내면
  401 이 아니라 400 이다(형식이 맞으면 401 · `SECURITY_001`). 400 응답에는 리소스 데이터가 없고 유스케이스에 닿지 않는다 (처음 확인: 장바구니 #12).
- internal API(`adapter/in/internal`, `/internal/**`)의 **모든 핸들러에 허용 호출자를 `@PreAuthorize` 로 단다** —
  `hasAuthority('SERVICE_order-service')`, 여럿이면 `hasAnyAuthority(...)`. `isAuthenticated()` · `permitAll()` 은 쓰지 않는다(서비스 5종 누구나 부르게 된다).
  사용자 JWT 는 internal 로 오지 않으므로 사용자 식별이 필요하면 memberId 를 요청 본문 · 파라미터로 받는다 — 호출 서비스가 사용자 JWT 로
  이미 검증한 값이다. 아래 "클라이언트가 보낸 memberId 를 믿지 않는다" 는 고객 · 어드민 API 의 규칙이다 (`security-guide.md` §9-4).
- member 식별은 JWT claim 기준이다. **클라이언트가 보낸 memberId 로 소유자를 판정하지 않는다.**
  컨트롤러는 `@AuthenticationPrincipal MemberPrincipal member` 로 받고 `member.memberId()` 만 UseCase 로 넘긴다.

```java
@PreAuthorize("isAuthenticated()")
@GetMapping("/api/v1/orders/{orderId}")
public ResponseEntity<Response<OrderDetailResponse>> getOrder(
    @AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId
) {
    return ResponseEntity.ok().body(Response.success(orderWebUseCase.getOrder(member.memberId(), orderId)));
}
```

- 본문 검증(`@Valid @RequestBody`)은 인자를 만들 때 돌아 `@PreAuthorize`(메서드 호출 시점)보다 **먼저**다. 인증이 필요한 쓰기 API 에
  익명이 틀린 본문을 보내면 401 이 아니라 400(`{DOMAIN}_1xx`)이 나온다 — 검증 규칙은 공개 정보라 막지 않는다. 인가 스모크의 익명 401 케이스는
  올바른 본문으로 보낸다 (예: `AuthServiceSecuritySmokeTest`).
- 소유 확인은 Facade 첫 줄에서 한다 — 주문 상세는 `orderId` 와 JWT 의 memberId 가 모두 맞아야 조회된다.
- 비회원 주문을 지원하면 주문번호 + 주문 비밀번호(해시)로 조회한다. 별도 이슈에서 확정한다.

## 6. 멱등성 (쓰기 API)

중복 클릭·네트워크 재시도가 **두 번 주문하거나 두 번 결제하는** 일이 없어야 한다.

- 주문 생성·결제 승인·결제 취소·환불은 **멱등**이어야 한다.
- 클라이언트는 `Idempotency-Key` 헤더(UUID)를 보낸다. 서버는 `(memberId, key)` 로 결과를 저장하고, 같은 키의
  재요청에는 **처음 결과를 그대로** 돌려준다. 처리 중인 같은 키가 오면 `409` 로 답한다.
- 결제 승인은 PG 쪽 멱등 키와 별개로 우리 쪽 상태(`payment.status`)로도 중복을 막는다 (`payment-guide.md` §3).
- **서비스 간(internal) 쓰기는 `Idempotency-Key` 대신 업무 키로 멱등을 만든다.** 재고 선점은 `orderId` 가 키다 — 같은 주문의 활성 선점이 있으면
  항목이 같을 때 기존 선점을 그대로 200 으로 돌려주고, 다르면 409(`PRODUCT_023`)다. 주문당 활성 선점 하나는 DB 유니크 키(`uk_stock_reservation_active_order_id`)가
  보장하고, 동시에 겹친 생성은 409(`PRODUCT_026`) 뒤 재시도가 200 이 된다. 확정 · 해제는 선점 행의 상태로 이미 처리됐는지 판단한다 (`order-flow-guide.md` §3).

### 6-1. 설정 전체 교체 (`PUT` + `version`)

어드민이 폼 하나로 편집하는 싱글톤 설정(배송비 정책 등)은 부분 수정 대신 **전체 교체 `PUT`** 으로 받고, 읽지 않고 덮어쓰는 것을 막는다.

- 요청 본문에 GET 으로 읽은 `version` 을 되돌려 보낸다. 아직 없으면 `null` 로 보내 처음 만든다.
- 있는데 `version` 이 없거나 다르면 `409` 이고 아무것도 바꾸지 않는다. 클라이언트는 다시 불러와 편집한다.
- 응답 유실 뒤 같은 본문을 재전송하면 `409` 다 — 조회로 반영 여부를 확인한다. `Idempotency-Key` 는 쓰지 않는다.
- 하위 행(도서산간 구간 등)은 교체마다 아이디가 새로 발급된다. 클라이언트는 하위 행 아이디를 보관하지 않는다.
- 처음 만든 곳: delivery-service `ShippingPolicyAdminController` (#11, `DELIVERY_006`). 해외 정책(`InternationalShippingPolicyAdminController`, #22)도 같은 규칙이다.
- 설정의 기준 값 중 바꾸면 기존 흐름이 한꺼번에 깨지는 것(배송비 정책 통화)은 `PUT` 으로 바꾸지 않고 `409` 로 거절한다(`DELIVERY_012`, `order-flow-guide.md` §2).

## 7. 비동기 작업

엑셀 일괄 등록(카페24 이관), 대량 송장 등록처럼 오래 걸리는 작업은 다음 패턴을 따른다.

- 제출 `POST {resource}` → `202 Accepted` + `jobId`. 같은 사용자의 같은 요청이 진행 중이면 기존 `jobId` 재사용.
- 상태 조회 `GET /jobs/{jobId}` — 본인(또는 ADMIN) 작업만. 남의 jobId 는 404.
- 작업 실패는 `200 OK` + `status=FAILED` + `errorCode/errorMessage` 로 답한다 (HTTP 5xx 가 아니다).
- 워커는 서비스 전용 `ThreadPoolTaskExecutor` 빈 + `@Async("<빈이름>")`. 전역 기본 executor 를 공유하지 않는다.
  빈 이름 `{도메인}{용도}TaskExecutor`, 풀 크기·큐·graceful shutdown 을 명시한다.

## 8. Swagger

- `@Tag` 는 고객/어드민을 나눠 단다: `@Tag(name = "상품")`, `@Tag(name = "[어드민] 상품 관리")`.
- 사유 코드처럼 문자열로 내리는 enum 값은 `@Schema(allowableValues = {...})` 로 허용값을 밝힌다.
