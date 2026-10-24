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
  계산 리터럴을 자주 부르는 화면이면 게이트웨이 요청 제한에서 그 리터럴을 쓰기 정책보다 앞에 따로 둔다 — 미리보기는 `order-quote`, 생성은 `order-write` (`security-guide.md` §7-3).

### 1-2. 서비스별 경로 소유

| 서비스 | 고객 | 어드민 |
|--------|------|--------|
| auth-service | `/api/v1/auth/**`, `/api/v1/members/**` | `/api/v1/admin/members/**` |
| product-service | `/api/v1/categories/**`, `/api/v1/products/**` | `/api/v1/admin/categories/**`, `/api/v1/admin/products/**`, `/api/v1/admin/stocks/**` |
| order-service | `/api/v1/carts/**`, `/api/v1/orders/**` | `/api/v1/admin/orders/**`, `/api/v1/admin/claims/**` |
| payment-service | `/api/v1/payments/**` | `/api/v1/admin/payments/**` |
| delivery-service | `/api/v1/deliveries/**`, `/api/v1/shipping-fees/**` | `/api/v1/admin/deliveries/**`, `/api/v1/admin/shipping-policies/**` |
| operations-service | — | `/api/v1/admin/jobs/**` |

게이트웨이 라우트는 이 표를 그대로 옮긴다. 새 경로 접두어를 만들면 이 표와 게이트웨이를 함께 고친다.

- **관리자 로그인은 `POST /api/v1/auth/admin/login` 이다(고객 열의 `/api/v1/auth/**`, #6).** **이슈 #6 원문의 `/api/v1/admin/auth/login` 과 다르다** — `/api/v1/admin/**` 아래에 두지 않는다 —
  그 경로는 게이트웨이 · 서비스 URL 규칙이 둘 다 ADMIN 토큰을 요구해 토큰을 받으러 오는 익명 요청이 닿지 못한다. 진입 패키지도 `adapter/in/web` 이고
  `@PreAuthorize("permitAll()")` 다. 어드민 콘솔 화면의 API 는 로그인 하나만 빼고 모두 `/api/v1/admin/**` 이다.
  Swagger 는 경로가 아니라 쓰는 화면으로 묶어 `/api/v1/auth/admin/**` 을 어드민 그룹에 넣고 고객 그룹에서 뺀다(`AuthServiceSwaggerConfig`).

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
- 응답 항목에 칸을 **더하는 것**은 하위 호환이다 — 호출하는 쪽 Feign DTO 는 자기가 읽는 칸만 선언하고 모르는 칸은 무시한다.
  이름 · 타입 변경과 삭제는 호출하는 쪽과 같은 PR 로 바꾼다. 값이 없을 수 있는 칸은 키를 빼지 않고 `null` 로 낸다
  (예: SKU `maxQuantityPerOrder` — `order-flow-guide.md` §2-1, `weightGrams` · `hsCode` — §2-2).
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
- 결제 후 재고 복원: product-service `StockRestorationInternalController` (#85) — `POST /internal/v1/stock-restorations`. 업무 키는 `claimId`(클레임당 복원 1건),
  검증 코드 `PRODUCT_127`~`PRODUCT_130`(이름 · 뜻이 같은 `reservationId` 양수 · `items` 이하는 선점 코드 재사용), 도메인 코드 `PRODUCT_003` · `PRODUCT_027`~`PRODUCT_031`.
  상태 전이가 없는 쓰기라 `@Version` 대신 대상 행(선점 헤더) 비관적 잠금으로 같은 대상의 동시 쓰기를 줄 세운다. 잠금 대기 상한(2000ms)을 넘으면
  재시도 가능한 409 `PRODUCT_031` 이다 (`order-flow-guide.md` §3-2, `coding-conventions.md` §9-1).

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

- 검증 오류가 여럿이면 `fieldErrors` 를 필드 선언 순서 → 필수 → 길이 → 범위 → 형식으로 정렬하고, 첫 항목이 `resultCode` · `resultMessage` 가 된다.
  서비스가 만든 제약은 `@ConstraintPriority` 로 이 자리를 밝힌다 — 정본은 [coding-conventions.md](coding-conventions.md) §8-2 (#261).

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
- **401 은 토큰(access · refresh) 거절에만 쓴다.** 로그인 실패(이메일 없음 · 비밀번호 틀림 · 관리자 계정)는 400 `AUTH_001` 이다 (#4 결정 5) —
  BFF 가 401 을 "재발급 후 재시도" 신호로 쓰므로 자격 증명 오류가 그 경로에 섞이지 않게 한다. refresh 거절은 401 `AUTH_010~012`, 토큰 저장소 장애는 503 `AUTH_013` (§2-4).

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
- 응답 본문에 예외 메시지 · 스택을 싣지 않는다. 500 의 원인은 서비스 로그에만 남는다(경로는 앞 3구간만, 그 구간의 `;` 매트릭스 파라미터도 `;…` 로 가린다 — `/api/v1/admin;jsessionid=x/y` → `/api/v1/admin;…/…`).
  예외 메시지에 입력값이 섞이는 오류(제약 위반 등)는 폴백에 맡기지 말고 도메인에서 먼저 받아 도메인 코드로 바꾼다 — 폴백 로그는 예외를 그대로 남긴다.
- 인증 · 인가 실패를 `ResponseStatusException(401/403)` 으로 던지지 않는다. 폴백으로 오면 `SECURITY_00x` 가 아니라 `COMMON_004` 로 나간다(경고 로그가 남는다).
- internal 경로(`/internal/**`)의 매핑 없는 요청은 인증 전 단계에서 401 `SECURITY_007` 이 먼저다. 단 방화벽이 거부한 비정규 internal 경로는
  보안 체인보다 앞에서 끊겨 400 `COMMON_004` 다(`security-guide.md` §5-1).
- 폴백은 두 곳에 있다. MVC(`DispatcherServlet`) 안의 오류는 `FallbackErrorResolver` 가, **MVC 밖의 오류**(서블릿 필터 예외 · Spring Security 방화벽의
  비정규 경로 거부 · 그 밖의 `sendError`)는 `/error` 를 맡는 `FallbackErrorController`(Boot `BasicErrorController` 대체, #81)가 같은 표로 `COMMON_00x` 봉투를 쓴다.
  보안 필터의 인증 · 인가 거부는 여전히 `SECURITY_00x` 진입점이 직접 쓴다(`/error` 로 가지 않는다).
  - `/error` 응답은 `Accept` 와 무관하게 언제나 JSON 봉투다(HTML 화이트라벨을 내지 않는다). 직접 `GET /error` 는 404 `COMMON_001` 이다.
  - 오류 경로를 바꾸려면 Boot 4 키 `spring.web.error.path` 를 쓴다(컨테이너 오류 페이지와 컨트롤러가 함께 옮겨진다). Boot 3 의 `server.error.path` 는 쓰지 않는다.
  - 응답 헤더(`Allow` · `Retry-After` 등)는 `sendError` 를 부른 쪽이 넣는다. 컨트롤러는 상태와 봉투만 쓴다.
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
| `GATEWAY_006` | 429 + `Retry-After` | 회원 단위 쓰기 요청 한도를 넘었다 — 장바구니 · 주문 쓰기. 비로그인 IP 계수(`gateway.client-ip.enabled`, 기본 끔)를 켜면 토큰 없는 요청은 클라이언트 IP 단위로 센다 (`security-guide.md` §7-3) | `Retry-After` 초 뒤 재시도. 요청은 서비스에 닿지 않았다. 재발급 · 로그아웃하지 않는다 (#91) |
| `GATEWAY_007` | 413 | 요청 본문이 상한(2MB)을 넘었다 (`security-guide.md` §7-4) | 요청은 서비스에 닿지 않았다. 본문을 줄여 다시 보낸다 |

- **502 · 504 는 처리 여부를 알 수 없다** — 쓰기 요청은 같은 `Idempotency-Key` 로 재시도하거나 조회로 확인한다 (§6).
- 경로 거절(400): 원문 경로에 `.` · `..` 세그먼트, `//`, `;`, 인코딩된 `.` · `/` · `\`(`%2e` · `%2f` · `%5c`), 이중 인코딩(`%25`),
  역슬래시, 제어문자가 있으면 라우팅 전에 끊는다. 쿼리 문자열은 보지 않는다. 정상 클라이언트는 이런 경로를 만들지 않는다.
- 허용 메서드는 `GET` · `HEAD` · `POST` · `PUT` · `PATCH` · `DELETE` · `OPTIONS` 다. `TRACE` · `CONNECT` 등은 405 다.
- 응답 본문에 예외 메시지·스택을 싣지 않는다. 경로·라우트·예외 타입은 게이트웨이 로그에만 남는다.
- 허용되지 않은 origin 은 preflight 뿐 아니라 **실제 요청도** 라우팅 전에 봉투 없이 `403` 으로 거부된다(브라우저가 막는 응답이다).
  허용 목록에 없는 요청 헤더를 쓴 preflight 도 `403` 이다.
- 클라이언트가 보낸 `X-Client-Subject` 는 게이트웨이가 늘 지운다. 하류 서비스에는 게이트웨이가 만든 값(클라이언트 IP 의 HMAC)만 닿는다 — 계약은 `security-guide.md` §7-3.

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
| `POST /api/v1/auth/login` | `permitAll()` | 200 토큰 쌍(아래) · `Cache-Control: no-store` — 본문 `{email, password}` | 400 `AUTH_001` · `AUTH_101` · `AUTH_102` · `AUTH_104` · 503 `AUTH_013` |
| `POST /api/v1/auth/token/reissue` | `permitAll()` | 200 토큰 쌍 · `Cache-Control: no-store` — 본문 `{refreshToken}`. 보낸 refresh 는 회전돼 더 쓰지 않는다 | 401 `AUTH_010`(없음 · 빈 값 포함) · `AUTH_011` · `AUTH_012` · 503 `AUTH_013` |
| `POST /api/v1/auth/logout` | `isAuthenticated()` | 200 `dataBody: null` — 본문 `{refreshToken?}`(본문 생략 가능). 모르는 · 남의 refresh 여도 access 는 막고 200(멱등) | 401 `SECURITY_001` · 503 `AUTH_013` |
| `POST /api/v1/auth/admin/login` (#6) | `permitAll()` | 200 `{accessToken, accessTokenExpiresIn}` · `Cache-Control: no-store` — 본문 `{email, password}`. refresh 없음 | 400 `AUTH_030` · `AUTH_130` · `AUTH_131` · `AUTH_132` |
| `GET /api/v1/admin/members` (#6) | `hasRole('ADMIN')` | 200 `PageResponse<{memberId, email, name, phone, role, createdAt}>` — 개인정보는 마스킹(아래). `?page=0&size=20&role=` | 400 `AUTH_133` · `AUTH_134` · `AUTH_198` · 401 `SECURITY_001` · 403 `SECURITY_005` |

토큰 쌍 (로그인 · 재발급 공통, #4 결정 1 — 전달은 응답 본문이고 쿠키는 BFF 가 만든다)

| 필드 | 타입 | 값 |
|------|------|-----|
| `accessToken` | `string` | access 토큰(JWT, `security-guide.md` §2) |
| `accessTokenExpiresIn` | `number` | access 토큰의 남은 수명(초) — 기본 1800 |
| `refreshToken` | `string` | 불투명 base64url 43자. 재발급할 때마다 새 값이다 |
| `refreshTokenExpiresIn` | `number` | refresh 토큰의 남은 수명(초) — 기본 1209600(14일). 재발급마다 다시 꽉 찬다 |

- 같은 refresh 를 30초 안에 다시 보내면(동시 요청 · 재시도) 새 쌍을 더 준다(토큰당 3개까지). 그 뒤 · 넷째의 재사용은 그 로그인의 토큰을 모두 폐기하고 401 `AUTH_012` 다 (`security-guide.md` §2-3).
- BFF 는 재발급이 4xx(`success=false`)면 쿠키를 지우고 로그인으로, 503 이면 쿠키를 둔다(§2-1 표).

오류 코드의 정본은 `MemberErrorCode` · `MemberValidationMessage` 다. 접두어는 서비스 단위 `AUTH_` 이고, 001~009 는 회원 · 로그인, 010~ 은 토큰,
030~ 은 관리자다(#6). 필드 검증은 101~ 이 고객, 130~ 이 관리자다 — 진입 DTO 를 공유하지 않으므로 같은 뜻이어도 코드를 따로 둔다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `AUTH_001` | 400 | 로그인 실패 — 이메일 없음 · 비밀번호 틀림 · 관리자 계정을 구분하지 않는다. 401 은 토큰 거절에만 쓴다 (#4 결정 5, 로그인은 A3) |
| `AUTH_002` | 409 | 이미 가입된 이메일. 사전 조회를 함께 지난 동시 가입은 유니크 키(`uk_member_email`) 위반을 같은 코드로 바꾼다. 계정 존재를 드러내는 것은 감수한 위험이다 (`security-guide.md` §10) |
| `AUTH_003` | 404 | 토큰은 유효하지만 회원이 없다 |
| `AUTH_004` | 409 | 같은 회원을 다른 요청이 먼저 고쳤다(낙관적 락 — 마케팅 동의만 바꿔도 회원 버전이 오른다). 다시 조회한 뒤 보낸다 |
| `AUTH_010` | 401 | refresh 토큰이 없거나 발급 모양(base64url 43자)이 아니다 — 저장소를 보기 전에 거절한다. 필드 검증(400)을 따로 두지 않는다 |
| `AUTH_011` | 401 | refresh 토큰 무효 — 만료 · 로그아웃 · 폐기 · 회원 없음 · 로그인 절대 수명(최초 로그인부터, 기본 30일 `auth.token.refresh-family-max-lifetime`) 초과 · 저장값 손상을 구분하지 않는다 (손상은 서버 warn 로그로만 나눈다) |
| `AUTH_012` | 401 | refresh 토큰 재사용 감지 — 이미 회전한 토큰이 유예(30초) 밖에서 다시 왔거나 유예 안 형제가 3개를 넘었거나, 저장된 회전 시각이 손상(정수 아님 · 지금 + 60초보다 미래)이다. 그 로그인(family)의 토큰을 모두 폐기했다 |
| `AUTH_013` | 503 | 토큰 저장소(Redis) 장애 — 로그인 · 재발급 · 로그아웃. 로그인 상태(쿠키)는 그대로 두고 다시 시도한다 |
| `AUTH_020` | 404 | 배송지가 없거나 다른 회원의 것이다 — 둘을 구분하지 않는다(§2-2, 존재를 숨긴다) |
| `AUTH_021` | 409 | 배송지 회원당 상한(`auth.shipping-address.max-per-member`, 기본 20)에 닿았다. 요청 값은 맞고 주소록 상태가 막으므로 400 이 아니라 409 다 |
| `AUTH_022` | 409 | 같은 배송지를 다른 요청이 먼저 고쳤거나 지웠다(낙관적 락), 또는 기본 배송지 유니크 키 위반. 다시 조회한 뒤 보낸다 |
| `AUTH_023` | 409 | 같은 회원의 다른 배송지 쓰기가 회원 잠금을 쥐고 있어 5초(트랜잭션 시간 상한) 안에 끝나지 않았다. 그 회원 요청끼리 겹친 것이라 503 이 아니다 |
| `AUTH_030` | 400 | 관리자 로그인 실패 (#6) — 이메일 없음 · 비밀번호 틀림 · 고객 계정을 구분하지 않는다. 문구는 `AUTH_001` 과 같다 (`security-guide.md` §11-1) |
| `AUTH_100` | 400 | 깨진 JSON · 본문 없음 · 타입 불일치 (필드는 `fieldErrors` 로 밝힌다) |
| `AUTH_101~115` | 400 | 필드별 검증 — 이메일 101~103 · 비밀번호 104~106 · 이름 107 · 108 · 114 · 전화 109 · 동의 110~113 · 수정 칸 없음 115. 로그인은 같은 뜻의 101 · 102 · 104 만 본다(비밀번호 형식은 보지 않는다 — 틀리면 `AUTH_001`) |
| `AUTH_116~119` | — | 예약 없음 (비워 둔다) |
| `AUTH_120~129` | 400 | 배송지 검증 — 별칭 120 · 받는 사람 121 · 연락처 122 · 123(형식) · 국가 124 · 우편번호 125(KR 필수) · 126(형식) · 주소 127 · 길이 128 · 해외 도시 129 |
| `AUTH_130~134` | 400 | 관리자 필드 검증 (#6) — 관리자 로그인 이메일 필수 130 · 254자 131 · 비밀번호 필수 132(형식 · 64자 초과는 필드 검증하지 않는다 — `AUTH_030`), 어드민 목록 `page` 0 이상 133 · `size` 1~100 134 |
| `AUTH_198` · `AUTH_199` | 400 | 파라미터 타입 불일치(`page=x` · 없는 `role`) · 필수 파라미터 누락 |

코드 대역 — 회원 `001~009` · 토큰 `010~019` · 배송지 `020~029` · 관리자(#6) `030~`, 검증은 회원 `101~115` · 배송지 `120~129` · 관리자(#6) `130~` 이다.
요청 검증 예외(본문 · 파라미터 · 타입)는 컨텍스트와 무관하게 `MemberExceptionHandler` 하나가 서비스 공용으로 봉투를 만든다 — 메시지가 코드를 싣고 오므로 컨텍스트를 몰라도 된다.
다른 컨텍스트의 advice(`ShippingAddressExceptionHandler`)는 자기 도메인 예외만 잡고 `@Order` 로 앞에 선다. 같은 예외를 두 advice 가 다투지 않는다.

입력 규칙

- **이메일** — 254자 이하, 도메인에 점이 있는 형식. 앞뒤를 포함해 공백이 있으면 거절한다(103 — 프론트가 trim 해서 보낸다).
  소문자로 바꿔 저장 · 조회하므로(`Member.normalizeEmail`) 대소문자만 다른 이메일은 같은 회원이다. DB 도 `ck_member_email_normalized`(`email = lower(email)`)로 강제한다.
  가입에서 길이는 원문과 정규화(앞뒤 공백 제거 · 소문자 — `Member.canonicalEmail`) 뒤 모두 254자 이하다. `İ`(U+0130)처럼 소문자로 바꾸면 길어지는 글자 때문에
  정규화 뒤에만 넘는 값도 102 다(`@ValidNormalizedEmailLength`, #233 — 이전에는 500 이었다). 로그인은 같은 값을 "없는 회원"(`AUTH_001` · `AUTH_030`)으로 끝낸다(#66).
- **비밀번호** — 8~64자, 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII 출력 문자(영문 · 숫자 · 특수문자)만. ASCII 로 묶는 이유는 BCrypt 가
  72바이트까지만 쓰기 때문이다(64자 ASCII = 64바이트). 저장은 `BCryptPasswordEncoder`(강도 10) 해시뿐이고 원문 · 해시를 로그 · 응답에 싣지 않는다.
- **이름** — 1~50자, 앞뒤 공백을 지우고 저장한다. 공백만인 값은 가입에서는 필수 누락(107), 내 정보 수정에서는 114 다 —
  가입은 필수 칸이라 `@NotBlank` 로, 수정은 선택 칸이라 "보냈다면 공백만은 안 된다" 로 검사한다.
  "공백" 은 도메인이 지우는 `String#strip()` 과 같은 `Character.isWhitespace` 다 — 전각 공백(U+3000)만인 값도 400 이다(#155, 수정 쪽 정규식이 ASCII 공백만 봐 500 이었다).
- **전화번호** — 선택. 국내 휴대폰(`01012345678` · `010-1234-5678`)을 받고 숫자만 저장한다. 가입에서 빈 문자열은 109 다.
  내 정보 수정에서는 빈 문자열(`""`)만 "지우기" 이고, 공백만 있는 값은 109 다. 해외 번호는 영문 스토어 회원가입에서 넓힌다.
- 저장된 값은 검증 없이 읽는다(`Member.restore`) — 이관(#19) 데이터가 지금 규칙 밖이어도 조회 · 로그인은 된다. 규칙은 가입 · 수정 때 바꾸는 칸에만 건다.
- **동의** — 가입 때 `agreements.{termsOfService, privacyPolicy, marketing}` 세 칸을 모두 보낸다. 앞의 둘은 `true` 여야 한다.
  세 칸 모두 동의 이력(`member_agreement`, 추가만 한다)에 한 줄씩 남고 약관 버전은 서버 설정 `auth.agreement.versions.*` 다 — 클라이언트가 보내지 않는다.
  마케팅 현재 상태는 그 회원의 가장 최근 마케팅 행이고, 내 정보 수정에서 값이 바뀔 때만 한 줄 더한다.
- 역할은 요청으로 받지 않는다. 가입은 언제나 `CUSTOMER` 다. 관리자 계정은 같은 `member` 테이블에 `role=ADMIN` 으로 따로 만든다(#6).

#### 배송지 주소록 (#7)

모두 `isAuthenticated()` 이고 회원은 JWT 의 memberId 로만 정한다. 주소는 언제나 (`addressId`, memberId) 로 찾는다 — 없거나 남의 주소는 같은 404 `AUTH_020` 이다.
경로는 `/api/v1/members/me/shipping-addresses` (아래 `…`).

| API | 성공 | 주요 오류 |
|-----|------|-----------|
| `GET …` | 200 `{addresses: [항목], maxCount}` — 기본 먼저, 그다음 최신순(아이디 내림차순). 페이지 없이 전체 | 401 |
| `GET …/{addressId}` | 200 위 항목 하나 | 404 `AUTH_020` |
| `POST …` | 201 `{addressId}`. 본문에 `isDefault`(선택, 기본 false) — 회원의 첫 주소는 값과 무관하게 기본이 된다 | 400 `AUTH_120~129` · 404 `AUTH_003` · 409 `AUTH_021` · `AUTH_023` |
| `PUT …/{addressId}` | 200 바뀐 주소. 전체 교체 — 보내지 않은 선택 칸은 지운다. 기본 여부는 바꾸지 않는다(`isDefault` 를 보내도 무시) | 400 · 404 `AUTH_020` · 409 `AUTH_022` |
| `DELETE …/{addressId}` | 200, `dataBody: null`. 기본을 지우고 주소가 남으면 가장 최근(아이디 최대) 주소가 기본이 된다 | 404 `AUTH_020` · `AUTH_003` · 409 `AUTH_023` |
| `PUT …/{addressId}/default` | 200, `dataBody: null`. 기존 기본은 풀린다. 이미 기본이어도 200(멱등) | 404 `AUTH_020` · `AUTH_003` · 409 `AUTH_023` |

- **항목** — `{addressId, label, recipientName, recipientPhone, countryCode, postalCode, addressLine1, addressLine2, city, stateProvince, isDefault}`.
  `addressId` 는 문자열, 없는 선택 칸은 null 이다. 한 검증 코드가 여러 칸에 걸치면(121 · 124 · 126 · 128) `fieldErrors[].field` 로 가린다.
- **불변식** — 주소가 있으면 기본 배송지는 정확히 하나다. DB 는 `default_member_id`(기본이면 `member_id` 와 같은 값, 아니면 null)의 유니크 키
  `uk_shipping_address_default_member_id` 로 "최대 하나" 를 강제한다(PostgreSQL 부분 인덱스는 JPA 로 선언할 수 없어 이 방식을 쓴다).
- **동시성** — 추가 · 기본 지정 · 삭제는 같은 트랜잭션 맨 앞에서 회원 행을 잠가(`MemberLockProcessor`, `PESSIMISTIC_WRITE`) 회원 단위로 줄을 세운다.
  개수 상한 검사와 기본 교체가 경합하지 않는다. 기본 교체는 기존 기본 해제 UPDATE 를 새 기본 지정보다 **먼저** 내보낸다 —
  PostgreSQL 은 이 유니크 키를 즉시(행 단위) 검사한다(deferrable 이 아님).
  해제 · 지정 UPDATE 는 행 버전을 올려 옛 버전으로 읽은 전체 교체가 기본 여부를 되돌려 쓰지 못하게 한다(409 `AUTH_022`).
- **잠금 대기 상한** — 잠그는 세 메서드는 `@Transactional(timeout = 5)` 다. JPA 트랜잭션 관리자가 남은 시간을 쿼리 타임아웃으로 넣어
  잠금을 기다리는 `select ... for update` 도 5초 안에 드라이버가 취소한다(Spring `QueryTimeoutException`). 상한이 지난 뒤의 쿼리는 `TransactionTimedOutException` 이다.
  Facade 가 둘(과 `PessimisticLockingFailureException`)을 409 `AUTH_023` 으로 바꾼다. 회원이 없으면 잠금에서 404 `AUTH_003` 이 그대로 나간다.
- **도메인 규칙 위반** — 요청 검증을 비켜 온 값이 도메인 규칙에 걸리면 Processor 가 400 `AUTH_100`(칸은 밝히지 않는다)으로 바꾼다 — 500 이 되지 않게.
  필수 칸(받는 사람 · 연락처 · 주소)의 전각 공백(U+3000)처럼 `@NotBlank`(trim 기준)를 지나는 유니코드 공백은 클래스 검증기가 도메인과 같은 `strip()` 기준으로 121 · 122 · 127 로 거절한다.
- **국가 코드** — ISO 3166-1 alpha-2 대문자 형식만 본다(지원 국가 목록은 보지 않는다). DB 도 `ck_shipping_address_country_code` 로 강제한다.
- **우편번호** — KR 이면 숫자 5자리 필수, 그 밖은 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하). delivery-service 견적(#92)과 같은 규칙이다.
- **연락처** — 필수. 하이픈 · 공백을 빼고 저장한다. KR 이면 0 으로 시작하는 숫자 9~11자리 — 휴대폰(01x 10~11자리)과 지역번호 유선(02 · 031 …)을 한 규칙으로 받는다.
  대표번호(1588 · 8자리)와 `+82` 표기는 받지 않는다. 그 밖의 국가는 `+` 로 시작할 수 있는 숫자 7~15자리(E.164)이고 `+` 는 그대로 둔다.
- **도시** — KR 이 아니면 필수(129). 국내 주소는 도로명 주소에 시 · 도가 들어 있어 선택이다.
- **길이** — 별칭 30 · 받는 사람 50 · 주소 · 상세 주소 200 · 도시 · 주(도) 100자. 선택 칸은 앞뒤 공백을 지우고 비면 null 로 저장한다.
- **상한** — 회원당 `auth.shipping-address.max-per-member`(기본 20, 1~100). 목록 응답의 `maxCount` 로 내린다.
- 받는 사람 · 연락처 · 주소는 개인정보라 요청 · 커맨드 · 도메인 · 응답의 `toString` 에 싣지 않고 로그 · 예외 메시지에도 남기지 않는다.
- 주문 배송지는 이 API 를 거치지 않는 스냅숏이다 (`order-flow-guide.md` §4).

관리자 로그인 응답 (#6) — `{accessToken, accessTokenExpiresIn}` 두 칸이다. refresh 토큰이 없어 재발급할 수 없고, 만료(기본 1800초)되면 다시 로그인한다.
로그아웃은 고객과 같은 `POST /api/v1/auth/logout` 을 본문 없이 부른다(access 차단만). 정책은 `security-guide.md` §11.

어드민 회원 조회 (#6) — `GET /api/v1/admin/members`

- `page`(기본 0, 0 이상 — 133) · `size`(기본 20, 1~100 — 134) · `role`(선택, `CUSTOMER` · `ADMIN`, 없으면 전체 · 틀리면 198). 정렬은 최신순 하나(회원 아이디 내림차순 —
  Snowflake 라 생성 순서다)라 `sortType` 이 없다. 마지막 페이지 너머는 빈 `contents` 다. 조회는 관리자 감사 로그에 남는다(`security-guide.md` §11-4).
- 항목 `{memberId(string), email, name, phone, role, createdAt}` — **이메일 · 이름 · 전화는 마스킹한 값만 내린다**(원문 조회 API 는 없다). 규칙(`MemberMasking`):

| 칸 | 규칙 | 예 |
|----|------|-----|
| `email` | 아이디 앞 2자(2자면 1자, 1자면 0자) + `***` + `@` + 도메인 첫 글자 + `***` + 마지막 점부터(최상위 도메인). `@` 가 없는 이관 값은 앞 1자 + `***` | `customer@example.com` → `cu***@e***.com` |
| `name` | 3자 이상은 첫 글자 + `*` + 마지막 글자(별은 하나 — 길이를 드러내지 않는다), 2자는 첫 글자 + `*`, 1자는 `*`. 코드 포인트로 센다 | `홍길동` → `홍*동`, `남궁민수` → `남*수` |
| `phone` | 앞 3자리 + `-****-` + 끝 4자리. 7자 미만 이관 값은 `****`, 없으면 `null` | `01012345678` → `010-****-5678` |

### 2-5. 어드민 카테고리 관리 (product-service, #9)

모두 `hasRole('ADMIN')` 이다. 고객 트리(`GET /api/v1/categories` — 숨긴 카테고리는 하위까지 빠진다)와 DTO 를 공유하지 않는다. 경로는 `/api/v1/admin/categories` (아래 `…`).

| API | 성공 | 주요 오류 |
|-----|------|-----------|
| `GET …` | 200 `{categories: [노드]}` — 숨김 포함 전체 트리. 같은 상위 안에서 노출 순서 → 아이디 오름차순 | — |
| `POST …` | 201 `{categoryId, parentId, name, displayOrder, visible}` | 400 `PRODUCT_040` · `PRODUCT_044` · `PRODUCT_131~136` · `138` · 409 `PRODUCT_045` |
| `PUT …/{categoryId}` | 200 위와 같은 모양. 본문은 생성과 같고 **전체 교체**다 — `parentId` 를 바꾸면 하위까지 함께 옮긴다 | 404 `PRODUCT_004` · 400 `PRODUCT_040` · `041` · `044` · `131~138` · 409 `PRODUCT_045` |
| `DELETE …/{categoryId}` | 200, `dataBody: null` | 404 `PRODUCT_004` · 409 `PRODUCT_042` · `PRODUCT_043` · `PRODUCT_045` · 400 `PRODUCT_137` |

- **본문** — `{parentId?, name, displayOrder, visible}`. `parentId` 는 문자열로 보내도 되고 없거나 null 이면 최상위다.
  `displayOrder` 는 0 이상이다. 같은 상위 안의 이름 · 순서에 유니크 키가 없어 순서를 맞바꿔도 된다.
- **이름** — 앞뒤 공백을 `strip()`(유니코드 공백 — 전각 공백 U+3000 포함)으로 빼고 저장하며, 뺀 뒤 코드 포인트 1~50자다(133).
  공백 · 서식 문자(제로폭 공백 U+200B 등)만이면 필수 누락 132 다 — `@NotBlank`(trim 기준)가 통과시키는 값을 막는다(배송지 #7 과 같은 기준, §2-4).
  제어 문자(`\p{Cc}` — 개행 · 탭 · NUL)와 서식 문자(`\p{Cf}`)는 어디에 있든 138 이다. 판정은 요청 DTO 전용 검증기 `CategoryNameValidator` 이고 한 값에 위반 하나만 낸다.
- **노드** — `{categoryId, parentId, name, displayOrder, visible, children[]}`. 아이디는 문자열, 최상위 `parentId` 는 null 이다.
- **깊이 · 순환** — 깊이는 3단계(대 · 중 · 소)까지다. 하위를 거느린 카테고리를 옮기면 하위 높이까지 센다(옮긴 뒤 넘으면 `PRODUCT_040`).
  자기 자신이나 자기 하위를 상위로 지정하면 `PRODUCT_041` 이다. 상위를 그대로 두는 수정(이름 · 순서 · 노출)은 깊이 · 순환을 다시 보지 않는다.
- **없는 상위** — 본문의 `parentId` 가 없는 카테고리면 404 가 아니라 400 `PRODUCT_044` 다. 경로 리소스가 아니라 본문 참조라서다(§2-2).
- **삭제** — 하위 카테고리가 있으면 409 `PRODUCT_042`, 상품의 대표(`product.category_id`) · 진열(`product_category`) 카테고리로 쓰이면 숨김 · 판매 종료 상품까지 세어
  409 `PRODUCT_043` 이다. 진열 연결을 자동으로 풀지 않는다. 메뉴에서만 감추려면 삭제 대신 `visible=false` 로 수정한다 — 하위까지 메뉴에서 빠지고 상품 노출은 막지 않는다.
  삭제는 행을 지운다(soft delete 아님). 성공 뒤 같은 요청을 다시 보내면 404 다.
- **숨김과 상품 상세 breadcrumb (#164)** — 고객 상품 상세의 `categoryPath` 는 메뉴(`GET /api/v1/categories`)와 **같은 규칙**이다. 최상위부터 따라가다 처음 숨긴
  카테고리에서 끊고, 그 아래는 보이는 카테고리여도 내지 않는다 — 메뉴로 갈 수 없는 카테고리 이름을 드러내지 않는다. 대표 카테고리 자체나 그 위가 숨김이면 그 위까지만,
  최상위가 숨김이면 빈 목록이다. 그래서 마지막 항목이 대표 카테고리가 아니라 그 상위일 수 있다. 상품 상세는 그대로 열린다(숨김은 상품 노출을 막지 않는다).
  판정은 `CategoryTree` 의 한 함수를 메뉴와 같이 쓴다.
- **동시성** — 생성 · 수정 · 삭제는 트랜잭션 맨 앞에서 모든 카테고리 행을 아이디 순으로 잠그고(`PESSIMISTIC_WRITE`) 다시 읽어 판정한다. 어드민 쓰기끼리 줄을 서므로
  두 카테고리를 동시에 서로의 아래로 옮겨 순환이 생기거나, 삭제 판정 뒤 그 아래에 하위가 생기지 않는다. 카테고리는 수가 적어 표 전체 잠금의 비용이 작다.
  잠그는 세 메서드는 `@Transactional(timeout = 5)` 다 — 잠금 대기 문장은 5초 안에 드라이버가 취소한다(`QueryTimeoutException`). 상한 뒤 쿼리의
  `TransactionTimedOutException`, `PessimisticLockingFailureException` 과 함께 Facade 가 409 `PRODUCT_045` 로 바꾼다(배송지 `AUTH_023` 과 같은 모양, §2-4).
  잠금 · 쓰기 Processor 메서드(`CategoryProcessor.getTreeForUpdate` · `create` · `update` · `delete`)는 `Propagation.MANDATORY` 라 트랜잭션 밖에서 부르면 바로 실패한다.
  상품 쓰기(#9 PR-E)는 이 잠금 밖이다 — 카테고리를 참조할 때 같은 잠금(또는 `FOR SHARE`)으로 존재를 확인해야 한다(DB FK 없음).
- 카테고리 표에 작성자 · 수정자 칸이 없어 어드민 memberId 를 받지 않는다. `version` · `Idempotency-Key` 도 두지 않는다 — 동시 편집은 마지막 쓰기가 이긴다.
- 계층 — 컨트롤러 · Facade · 판정 Processor 는 상품 컨텍스트에 둔다(`CategoryAdminFacade` · `CategoryAdminProcessor`). "연결된 상품" 을 상품 컨텍스트가 알기 때문이다.
  트리 판정(`CategoryTree.placementViolationOf` · `hasChildren`)과 쓰기는 카테고리 컨텍스트 `CategoryProcessor` 가 하고, 상품 컨텍스트는 그 Processor 와
  domain model 만 쓴다(`architecture-guide.md` §3 컨텍스트 간 의존 규칙, 의존 방향 product → category).

### 2-6. 어드민 상품 목록 (product-service, #9)

`GET /api/v1/admin/products` — `hasRole('ADMIN')`. 고객 목록(`GET /api/v1/products` — 숨김 제외 `SliceResponse`)과 DTO 를 공유하지 않는다.

| 파라미터 | 값 | 오류 |
|----------|----|------|
| `keyword` | 상품명 부분 일치 · 대소문자 무시. 원문 100자 이하, 앞뒤 공백을 빼고 비면 조건 없음. `%` · `_` 는 글자로 찾는다 | 400 `PRODUCT_139` |
| `categoryId` | 진열 카테고리 — **하위 포함**(숨긴 카테고리 포함, 고객 목록과 같다). 없는 카테고리면 빈 페이지(`totalElements` 0) | 400 `PRODUCT_137` · `198` |
| `saleStatus` | 저장된 판매 상태 `ON_SALE` · `STOP_SELLING` | 400 `PRODUCT_198` |
| `visible` | `true` · `false`. **없으면 숨김 포함 전체** | 400 `PRODUCT_198` |
| `availability` | 재고를 반영한 판매 가능 상태 `ON_SALE` · `SOLD_OUT` · `STOP_SELLING` — 아래 규칙 | 400 `PRODUCT_198` |
| `sortType` | `NEWEST`(기본, 아이디 내림차순) · `NAME_ASC`(상품명 오름차순, DB collation). 동률은 아이디 내림차순 | 400 `PRODUCT_198` |
| `page` · `size` | 기본 0 · 20, size 1~100 (§4) | 400 `PRODUCT_101` · `102` |

- **응답** — `PageResponse<{productId, name, thumbnailUrl, salePrice, listPrice, saleStatus, visible, availability, skuCount, updatedAt}>`.
  아이디는 문자열, 금액은 `{amount, currency}`, `saleStatus` · `availability` 는 `{code, name, description}`, `updatedAt` 은 UTC ISO 다.
- **가격은 저장된 값 그대로다** — `salePrice` 는 기본 판매가(옵션 추가금 · 판매중 옵션 최저가를 반영하지 않는다), `listPrice` 는 정가(없으면 null)다.
  고객 목록처럼 할인이 아닌 정가를 숨기지 않는다. `thumbnailUrl` 은 대표 이미지 주소(없으면 null), `skuCount` 는 판매 상태와 무관한 SKU 수다.
  `updatedAt` 은 상품 행의 수정 시각이라 SKU · 재고 · 이미지만 바뀐 것은 반영하지 않는다.
- **판매 가능 상태** — 고객 뱃지와 같은 규칙(`SaleAvailability.ofProduct`)이고 노출 여부는 보지 않는다. 상품이 판매 종료이거나 판매중 SKU 가 없으면(SKU 0개 포함) `STOP_SELLING`,
  판매중 SKU 중 재고가 무제한이거나 수량 > 0 인 것이 있으면 `ON_SALE`, 그 밖이면 `SOLD_OUT` 이다. **재고 행이 없는 SKU 는 품절**이다.
  항목의 값은 SKU 집계로, 필터는 같은 판정을 SQL EXISTS 로 건다 — 둘은 `SkuCustomRepositoryImpl` 의 `onSale` · `inStock` 식을 함께 쓴다.
- **쿼리** — 목록 1 + count 1 + SKU 집계 1(재고 left join) + 대표 이미지 1, 집계 · 이미지는 그 페이지 상품 아이디 in 절이다. 결과가 비면 집계 · 이미지를 읽지 않고,
  첫 페이지가 다 차지 않으면 count 를 건너뛴다(`PageableExecutionUtils`). 조건은 product 행 기준 EXISTS(카테고리 · SKU · 재고)라 JOIN 으로 행이 겹치지 않는다.
  인덱스는 추가하지 않았다 — 카테고리 EXISTS 는 `idx_product_category_category_id_product_id`, SKU EXISTS 는 `uk_sku_product_id_option_value_key` 선두, 재고는 PK 를 탄다.
  상품명 부분 일치(`lower(name) like '%…%'`)와 이름순은 product 스캔 · 정렬이다(어드민 전용, 상품 수 규모에서 수용).
- 삭제(soft delete, #9 PR-D)가 생기면 삭제 상품 제외 조건을 `ProductCustomRepositoryImpl#searchForAdmin` 의 조건 배열에 더한다.
- 계층 — `ProductAdminController` → `ProductAdminUseCase` → `ProductAdminFacade`(읽기 전용 트랜잭션, 카테고리 하위 펼치기는 `CategoryProcessor.subtreeIdsOf`)
  → `ProductAdminQueryProcessor` → `ProductRepositoryPort#searchForAdmin`. 컨트롤러는 domain enum 을 쓰지 않으므로(H1) 판매 상태 · 판매 가능 상태 · 정렬은
  `application/model` 의 입력 enum(`ProductSaleStatusFilter` · `ProductAvailabilityFilter` · `ProductAdminSortType`)으로 받고 Processor 가 domain 으로 바꾼다.
  정렬은 고객 `ProductSortType` 과 나눈다 — 이름순을 고객 목록에 열지 않는다.

### 2-7. 고객 상품의 프리오더 · 영상 필드 (product-service, #61)

| 응답 | 필드 | 뜻 |
|------|------|----|
| 목록 `GET /api/v1/products` | `preorder: boolean` | 프리오더(예약 판매) 상품인가. `store.features.preorder` 가 꺼져 있으면 언제나 `false` |
| 상세 `GET /api/v1/products/{productId}` | `preorder: { expectedShipDate } \| null` | 프리오더가 아니거나 플래그가 꺼지면 `null`. `expectedShipDate` 가 `null` 이면 "발송일 추후 안내" |
| 상세 | `images[].mediaType` | `IMAGE` · `VIDEO`. 화면 분기용 제어 코드라 metadata 객체가 아니라 문자열이다(§11 예외) |
| 상세 | `images[].posterUrl` | VIDEO 의 재생 전 정지 이미지 URL. IMAGE 면 `null` |

- **날짜만 있는 값(`LocalDate`)은 ISO 날짜 문자열 `yyyy-MM-dd`(스토어 현지 날짜)로 내린다.** 시각(`Instant`)과 달리 시간대를 붙이지 않는다.
- 발송 예정은 고정 날짜("○월 ○일 순차 발송")다. 주문일 기준 일수는 맞춤 제작 `customOrder.leadTimeDays` 이고 서로 겹치지 않는다.
- 상세 `images[]` 는 갤러리 이미지와(`store.features.product-video` 가 켜져 있으면) 영상을 `displayOrder` → 아이디 오름차순으로 섞는다. 꺼지면 VIDEO 를 뺀다.
  대표 이미지(`thumbnail`)는 THUMBNAIL 만이다 — VIDEO 가 대표가 되지 않는다.
- 프리오더 상품의 판매 가능 · 품절 판정과 재고 선점은 일반 상품과 같다. 화면에 발송 예정 안내만 더한다.
- 어드민 입력(#9 몫): 영상 · 포스터 URL 은 우리 오브젝트 스토리지 공개 URL(`STORAGE_PUBLIC_BASE_URL` 접두)만 받는다 — 외부 영상 URL 은 받지 않는다.
  VIDEO 는 포스터 필수(빈 문자열 거절), 그 밖에는 포스터를 받지 않는다. 프리오더가 아니면 발송 예정일을 받지 않는다(DB check 와 같은 규칙).
  유일 키는 유형별 `display_order` 라 GALLERY 와 VIDEO 가 같은 값을 가질 수 있다 — 어드민 등록은 갤러리 이미지 · 영상을 합친 순번을 매겨 동률을 만들지 않는다.

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
- 페이지 파라미터는 `page`(기본 0, 0 이상) · `size`(기본 20, 1~100)이고 검증 코드는 도메인의 `page` · `size` 공용 코드를 쓴다
  (product 는 `PRODUCT_101` · `PRODUCT_102`, delivery 는 page `DELIVERY_137`, 배송비 정책 이력 size `DELIVERY_138`).
  마지막 페이지를 넘긴 요청은 400 이 아니라 빈 `contents` 와 그대로인 `totalElements` 다.
  정렬을 고를 수 없는 목록(감사 이력처럼 최신순 하나뿐)은 `sortType` 을 두지 않고 아이디 내림차순으로 고정한다 —
  처음 만든 곳: 어드민 재고 조정 이력 `GET /api/v1/admin/stocks/{skuId}/adjustments` (#86). 배송비 정책 교체 이력(§6-2, #69)도 같다.
- 어드민 화면이 아이디 묶음으로 한 번에 읽는 조회(`?skuIds=1,2,3`)는 서비스 간 벌크 조회와 같은 규칙이다 — 1~100개(중복 포함 원문 개수), 양수,
  중복은 첫 등장 한 번, 요청 순서대로 응답, **없는 아이디는 빼고 404 로 묶음 전체를 막지 않는다**(호출한 쪽이 누락을 판단한다).
  처음 만든 곳: 어드민 재고 조회 `GET /api/v1/admin/stocks` (#86).

## 5. 보안 API 설계

- 고객·어드민 API(`adapter/in/web` · `adapter/in/admin`)의 **모든 핸들러에 `@PreAuthorize` 를 단다.** 서비스 URL 단계가 열려 있어(default-allow)
  빠뜨리면 공개 API 가 된다. 공개 API 도 `@PreAuthorize("permitAll()")` 로 명시하고, 로그인 필요는 `isAuthenticated()`, 어드민은 `hasRole('ADMIN')` 이다.
  본문 검증(`@RequestBody @Valid`)은 인자 바인딩 단계라 메서드 보안보다 먼저 돈다 — `isAuthenticated()` 쓰기 API 에 익명으로 형식이 틀린 본문을 보내면
  401 이 아니라 400 이다(형식이 맞으면 401 · `SECURITY_001`). 400 응답에는 리소스 데이터가 없고 유스케이스에 닿지 않는다 (처음 확인: 장바구니 #12).
- internal API(`adapter/in/internal`, `/internal/**`)의 **모든 핸들러에 허용 호출자를 `@PreAuthorize` 로 단다** —
  `hasAuthority('SERVICE_order-service')`, 여럿이면 `hasAnyAuthority(...)`. `isAuthenticated()` · `permitAll()` 은 쓰지 않는다(어느 서비스나 부르게 된다).
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
  결제 후 재고 복원은 `claimId` 가 키다 — 같은 클레임의 복원이 있으면 선점 · 사유 · 항목이 같을 때 처음 결과를 200 으로, 다르면 409(`PRODUCT_029`)다.
  클레임당 복원 하나는 DB 유니크 키(`uk_stock_restoration_claim_id`)가 보장하고, 같은 선점의 동시 요청은 선점 헤더 잠금에서 줄을 서 재시도 없이 200 이 된다
  (잠금을 비켜 간 동시 생성만 409 `PRODUCT_030` 뒤 재시도).

### 6-1. 설정 전체 교체 (`PUT` + `version`)

어드민이 폼 하나로 편집하는 싱글톤 설정(배송비 정책 등)은 부분 수정 대신 **전체 교체 `PUT`** 으로 받고, 읽지 않고 덮어쓰는 것을 막는다.

- 요청 본문에 GET 으로 읽은 `version` 을 되돌려 보낸다. 아직 없으면 `null` 로 보내 처음 만든다.
- 있는데 `version` 이 없거나 다르면 `409` 이고 아무것도 바꾸지 않는다. 클라이언트는 다시 불러와 편집한다.
- 응답 유실 뒤 같은 본문을 재전송하면 `409` 다 — 조회로 반영 여부를 확인한다. `Idempotency-Key` 는 쓰지 않는다.
- 하위 행(도서산간 구간 등)은 교체마다 아이디가 새로 발급된다. 클라이언트는 하위 행 아이디를 보관하지 않는다.
- 처음 만든 곳: delivery-service `ShippingPolicyAdminController` (#11, `DELIVERY_006`). 해외 정책(`InternationalShippingPolicyAdminController`, #22)도 같은 규칙이다.
- 설정의 기준 값 중 바꾸면 기존 흐름이 한꺼번에 깨지는 것(배송비 정책 통화)은 `PUT` 으로 바꾸지 않고 `409` 로 거절한다(`DELIVERY_012`, `order-flow-guide.md` §2).

### 6-2. 설정 교체 이력 (delivery-service 국내 배송비 정책, #69)

전체 교체 `PUT` 이 성공하면 **같은 트랜잭션에서 이력 한 건을 append** 한다 — 교체 전 · 후 값, 교체한 어드민, 시각, 교체 전 · 후 `version`.
이력 저장이 실패하면 교체도 롤백된다(이력 insert 를 `saveAndFlush` 로 바로 내보내 실패가 트랜잭션 안에서 드러난다). 버전 충돌 · 검증 실패로 교체가 거절되면 이력도 없다.

| API | 성공 | 주요 오류 |
|-----|------|-----------|
| `GET /api/v1/admin/shipping-policies/domestic/histories?page=&size=` | 200 `PageResponse<{historyId, adminMemberId, changedAt, before, after}>` — 최신순(아이디 내림차순) 고정 | 400 `DELIVERY_137`(page) · `138`(size 1~20) · `198`(형식) |

- `before` · `after` 는 `{baseFee, freeShippingThreshold, remoteAreas[{name, postalCodeFrom, postalCodeTo, surcharge}], version}` 이다. 금액은 `{amount, currency}`,
  무료배송 기준이 없으면 `null`, 구간은 시작 우편번호 오름차순이다. 정책을 처음 만든 교체는 `before` 가 `null` 이다.
  구간 아이디는 싣지 않는다 — 교체마다 새로 발급돼(§6-1) 이력에서 뜻이 없다.
- `historyId` · `adminMemberId` 는 문자열, `changedAt` 은 UTC ISO 다. `adminMemberId` 는 access 토큰의 memberId 이고 요청 본문으로 받지 않는다.
  내부 회원 아이디라 마스킹하지 않는다(재고 조정 이력 #86 과 같다). 어드민 이름 표시는 auth-service 조회가 필요해 범위 밖이다.
- **값이 그대로인 교체(같은 본문 재전송)도 이력을 남긴다** — `version` 이 오르므로 교체 사실 자체가 있고, 누가 언제 눌렀는지가 감사 대상이다. 화면이 "변경 없음" 을
  가리고 싶으면 `before` · `after` 의 값(버전 제외)을 비교한다.
- page 는 §4 공용 규칙(기본 0, `DELIVERY_137`)이다. **size 는 기본 20 · 상한 20(`DELIVERY_138`)** 으로 공용 상한(100)보다 작다 — 이력 한 건이 구간을
  최대 400행(교체 전 · 후 200개씩) 펼쳐, 한 페이지가 최대 8,000행을 넘지 않게 한다. 138 은 이력 전용이다 — 공용 상한을 쓰는 delivery 목록이 생기면 코드를 새로 둔다.
- 저장은 delivery 전용 테이블 `shipping_policy_history`(교체 전 · 후 금액 · 버전 칸) + `shipping_policy_history_area`(구간 스냅숏, 쪽 `BEFORE` · `AFTER`)다.
  JSON 스냅숏 한 칸이 아니라 행으로 둔 이유 — 금액 · 통화 짝과 우편번호 형식을 정책 테이블과 같은 check 제약으로 막고, 스냅숏 테스트 · `validate` 가 모양을 대조하게 하려고다
  (저장소에 JSON 컬럼 선례가 없고, 직렬화 호환을 따로 관리하지 않아도 된다). 조회는 목록 1 + 건수 1 + 그 페이지 이력의 구간 in 절 1 이다.
- **어드민 공통 감사 로그가 아직 없어 전용 테이블로 둔다** (`security-guide.md` §11-4 — DB 에 업무 이력이 있으면 그것이 감사의 정본). 공통 감사 로그(#163 · #192)가
  생기면 교체 행위를 그쪽에도 함께 남길지 재검토한다.
- **해외 정책(#22) 이력은 아직 남기지 않는다.** 이력 테이블에 범위(`scope`) 칸과 `(scope, id)` 인덱스를 둬 같은 테이블에 넣을 수 있게 했지만, 국가 그룹 · 무게 구간
  스냅숏 설계와 해외용 조회 API 는 해외 이력을 더할 때 정한다.

## 7. 비동기 작업

리포트 · 정산 대사 · 집계 · AI 생성 · 엑셀 일괄 등록처럼 오래 걸리는 어드민 작업은 operations-service 의 작업 엔진에 맡긴다 (`architecture-guide.md` §10, #212).

- 제출은 각 기능 API 다 — `POST {resource}` → `202 Accepted` + `jobId`. 기능 Facade 가 작업을 등록한다(같은 서비스면 `JobRegistrationProcessor`).
  같은 요청이 진행 중이면 멱등 키(`dedupKey`, 종류마다 따로)로 기존 `jobId` 를 돌려준다. **범용 등록 API 는 두지 않는다.**
- 상태 조회 · 취소는 어드민 작업 API 하나로 한다 — `GET /api/v1/admin/jobs` · `GET /api/v1/admin/jobs/{jobId}` · `POST /api/v1/admin/jobs/{jobId}/cancel`
  (ADMIN, operations-service). 계약은 아래 §7-1.
- 작업 실패는 `200 OK` + `status=FAILED` + `errorCode/errorMessage` 로 답한다 (HTTP 5xx 가 아니다).
- 워커는 `@Async` 가 아니라 엔진의 전용 실행기 · 폴러가 돈다 — 기능은 domain `JobHandler` 만 구현한다(`architecture-guide.md` §10).
- 고객이 자기 작업을 보는 API(본인 작업만, 남의 jobId 는 404)는 필요한 기능이 생길 때 그 기능 서비스에 둔다.

### 7-1. 어드민 작업 API (operations-service, #212 PR-C)

모두 `hasRole('ADMIN')` 이다. 게이트웨이 라우트는 `ServiceRoute.OPERATIONS`(`/api/v1/admin/jobs/**`).

| API | 응답 | 오류 |
|-----|------|------|
| `GET /api/v1/admin/jobs?status=&type=&page=&size=` | `PageResponse<{jobId, type, status, progressPercent, attempt, maxAttempts, cancelRequested, errorCode, requestedByMemberId, scheduledAt, startedAt, finishedAt}>` — 아이디 내림차순(등록 최신순) 고정 | 400 `JOB_101`(page) · `102`(size 1~100) · `103`(type `[A-Z0-9_]{1,64}`) · `198`(없는 status · 형식) |
| `GET /api/v1/admin/jobs/{jobId}` | 목록 항목 + `priority` · `progressMessage` · `cancelRequestedByMemberId` · `dedupKey` · `payloadJson` · `resultJson` · `errorMessage` | 404 `JOB_001`, 400 `JOB_104`(양수) · `198` |
| `POST /api/v1/admin/jobs/{jobId}/cancel` | 상세와 같다. QUEUED 는 바로 `CANCELLED`, RUNNING 은 `cancelRequested=true` 로 남고 상태는 그대로(멱등) | 404 `JOB_001`, 409 `JOB_022`(이미 끝남) · `JOB_021`(워커와 계속 겹침 — 다시 시도) |

- `status` 는 `{code, name, description}`, 아이디(작업 · 회원)는 문자열, 시각은 UTC ISO 다. `payloadJson` · `resultJson` 은 JSON 원문 문자열이다.
  워커 아이디(호스트 · pid)는 내리지 않는다.
- 상태 · 종류는 `application/model` 의 `JobStatusFilter` · 문자열로 받는다(H1). 조건 조합마다 파생 쿼리 하나다 — 종류 조건은 `idx_job_type_id` 를 그대로 타고,
  상태 조건은 상태 선두 인덱스로 거른 뒤 정렬한다(어드민 전용). 이 비용은 끝난 작업을 90일 뒤 지우는 보존 정리(`JOB_RETENTION_CLEANUP`, #212 PR-D)를 전제로 한다 —
  그 전까지는 끝난 작업이 쌓이므로, 정리 없이 행이 크게 늘면 `(status, id)` 인덱스를 검토한다.
- 그 밖의 요청 검증 실패(메시지에 코드가 없는 제약)는 400 `JOB_100` 이다.
- 취소는 `JobAdminFacade` 가 `JobRunProcessor.requestCancel` 을 시도마다 새 트랜잭션으로 부른다 — 워커의 가져가기 · 종료와 겹쳐 버전에서 지면 다시 읽어 최대 3번 시도한다.
  취소한 어드민은 access 토큰의 memberId 다. 성공 · 실패 모두 감사 로그 `action=JOB_CANCEL target=job:<id>` 를 남긴다(`security-guide.md` §11-4).

## 8. Swagger

- `@Tag` 는 고객/어드민을 나눠 단다: `@Tag(name = "상품")`, `@Tag(name = "[어드민] 상품 관리")`.
- 사유 코드처럼 문자열로 내리는 enum 값은 `@Schema(allowableValues = {...})` 로 허용값을 밝힌다.
