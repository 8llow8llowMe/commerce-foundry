# Backend API Design Guide

주문 생성(#13 C3): `POST /api/v1/orders`는 CUSTOMER 전용이며 UUID `Idempotency-Key`가 필수다(누락 `ORDER_199`, 형식 오류 `ORDER_198`). 새 주문은 201, 동일 회원·키·정규화 본문 재생은 200이다. 본문은 items·shippingAddress·선택적 expectedTotalAmount이며 서버가 공유 주문서 계산으로 금액을 정한다. 배송지는 city·stateProvince를 포함하고 해외 city는 필수다. 응답은 주문·상품·맞춤 입력·배송지·배송비 스냅숏과 문자열 ID, `PENDING_PAYMENT`, 선점 만료와 같은 paymentDueAt을 포함한다. 동일 키 다른 본문은 `ORDER_019`, 유효한 처리 lease는 `ORDER_018`이다. 완료 재생은 원격 재계산 없이 소유 주문을 읽는다. FE는 재시도 동안 키와 본문을 유지한다. 장바구니 자동 비우기·결제 API·만료 스케줄러는 후속 범위다. FE 계약 검토자는 `seonghoho`다.

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
| auth-service | `/api/v1/auth/**`, `/api/v1/members/**` | `/api/v1/admin/members/**`, `/api/v1/admin/audit-logs/**`, `/api/v1/admin/admins/**`, `/api/v1/admin/me/**` |
| product-service | `/api/v1/categories/**`, `/api/v1/products/**`, `/api/v1/displays/**`, `/api/v1/looks/**` | `/api/v1/admin/categories/**`, `/api/v1/admin/products/**`, `/api/v1/admin/stocks/**`, `/api/v1/admin/displays/**`, `/api/v1/admin/size-chart-templates/**`, `/api/v1/admin/looks/**`, `/api/v1/admin/skus/**` |
| order-service | `/api/v1/carts/**`, `/api/v1/orders/**` | `/api/v1/admin/orders/**`, `/api/v1/admin/claims/**` |
| payment-service | `/api/v1/payments/**` | `/api/v1/admin/payments/**` |
| delivery-service | `/api/v1/deliveries/**`, `/api/v1/shipping-fees/**` | `/api/v1/admin/deliveries/**`, `/api/v1/admin/shipping-policies/**` |
| operations-service | — | `/api/v1/admin/jobs/**`, `/api/v1/admin/notifications/**`, `/api/v1/admin/onboarding/**` |

게이트웨이 라우트는 이 표를 그대로 옮긴다. 새 경로 접두어를 만들면 이 표와 게이트웨이를 함께 고친다.
상품 사이즈표(#216)의 고객 · 어드민 조회는 새 접두어가 아니다 — `GET /api/v1/products/{productId}/size-chart`,
`GET|PUT /api/v1/admin/products/{productId}/size-chart` 는 기존 `products/**` 다. 템플릿 컬렉션만 `/api/v1/admin/size-chart-templates/**` 다.
SKU 원가 이력(#361)은 SKU 하위 리소스라 `/api/v1/admin/skus/**` 를 새로 둔다(§2-12).

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
  `customOrderFields` 는 맞춤 제작 항목 정의(`fieldKey` · `inputType` · `required` · `min` · `max` · `step`)다. 맞춤 제작이 아니면 빈 배열이고,
  그 페이지의 맞춤 제작 상품 아이디로 in 절 한 번만 읽는다 (`order-flow-guide.md` §2-3, #97).
- 처음 만든 곳: product-service `SkuInternalController` (#8). 코드 표는 `ProductValidationMessage` (`PRODUCT_103` 빈 목록 · `PRODUCT_104` 100개 초과 · `PRODUCT_105` 양수 아님).
- 회원 표시 이름: auth-service `GET /internal/v1/member-display-names?ids=` (#322). 허용 호출자는 delivery-service.
  항목은 `{memberId, displayName}` 이고 `displayName` 은 마스킹된 이름만이다(원문 이름은 없다). 회원이 있으나 이름이 없으면 `displayName` 은 null, 없는 아이디는 빠진다.
  검증 코드는 `AUTH_137`(빈 목록) · `AUTH_138`(100개 초과) · `AUTH_139`(양수 아님). 배송비 정책 이력은 이 조회로 페이지당 한 번 이름을 붙이고, 실패하면 503 `DELIVERY_013` 이다.
- 운영자 온보딩 사실(단건): delivery-service `GET /internal/v1/onboarding-facts/shipping-policy?memberId=` (#357). 허용 호출자는 operations-service.
  운영자 한 명의 사실이라 `ids` 벌크가 아니다. `memberId` 는 양수(`DELIVERY_139`), 숫자가 아니면 `DELIVERY_198`, 없으면 `DELIVERY_199` 다. 계약은 아래 "운영자 온보딩 계약 (#357)".

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
- **429 는 요청 수 제한에만 쓴다** — 게이트웨이 `GATEWAY_006`, 로그인 · 가입 시도 제한 `AUTH_040` · `AUTH_041`(#66). 모두 `Retry-After`(초)를 싣고,
  재발급 · 로그아웃 신호가 아니다. 클라이언트는 그 초만큼 버튼을 막고 안내한다.

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
- 응답 본문에 예외 메시지·스택을 싣지 않는다. 경로·라우트·예외 타입은 게이트웨이 로그에만 남는다 — 경로는 서비스와 같이 앞 3구간만(`;` 매트릭스 파라미터도 `;…`),
  500 · 응답 커밋 뒤 오류는 원인 사슬의 예외 타입 · 스택 프레임만 남기고 예외 메시지는 남기지 않는다(`RedactedThrowable` · `LogPathMask`, `coding-conventions.md` §8 "5xx 의 원인을 로그에 남기는 기준", #285 · #291).
  커밋 뒤에도 원래 예외를 다시 던지지 않는다 — reactor-netty 가 그 예외의 체크포인트(원문 경로 · 쿼리)를 ERROR 로 남긴다.
  원래 예외 · 원문 경로 · 쿼리를 찍는 프레임워크 로거(`HttpWebHandlerAdapter`)는 끈다. 예외 — 요청 가드의 경로 규칙 위반 · internal 경로 거절은 공격 문자열이 판단 근거라 원문(200자까지, DEBUG)을 남긴다.
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
| `POST /api/v1/auth/sign-up` | `permitAll()` | 201 `{memberId}` — 토큰은 주지 않는다(로그인을 따로 한다) | 400 `AUTH_1xx` · 409 `AUTH_002` · 429 `AUTH_041` + `Retry-After` |
| `GET /api/v1/members/me` | `isAuthenticated()` | 200 `{memberId, email, name, phone, marketingAgreed}` | 401 `SECURITY_001` · 404 `AUTH_003` |
| `PATCH /api/v1/members/me` | `isAuthenticated()` | 200 바뀐 내 정보 — `{name?, phone?, marketingAgreed?}` 중 보낸 칸만 바꾼다 | 400 `AUTH_1xx` · 404 `AUTH_003` · 409 `AUTH_004` |
| `POST /api/v1/auth/login` | `permitAll()` | 200 토큰 쌍(아래) · `Cache-Control: no-store` — 본문 `{email, password}` | 400 `AUTH_001` · `AUTH_101` · `AUTH_102` · `AUTH_104` · 429 `AUTH_040` + `Retry-After` · 503 `AUTH_013` |
| `POST /api/v1/auth/token/reissue` | `permitAll()` | 200 토큰 쌍 · `Cache-Control: no-store` — 본문 `{refreshToken}`. 보낸 refresh 는 회전돼 더 쓰지 않는다 | 401 `AUTH_010`(없음 · 빈 값 포함) · `AUTH_011` · `AUTH_012` · 503 `AUTH_013` |
| `POST /api/v1/auth/logout` | `isAuthenticated()` | 200 `dataBody: null` — 본문 `{refreshToken?}`(본문 생략 가능). 모르는 · 남의 refresh 여도 access 는 막고 200(멱등) | 401 `SECURITY_001` · 503 `AUTH_013` |
| `POST /api/v1/auth/admin/login` (#6) | `permitAll()` | 200 `{accessToken, accessTokenExpiresIn}` · `Cache-Control: no-store` — 본문 `{email, password}`. refresh 없음. 비활성 관리자도 `AUTH_030` | 400 `AUTH_030` · `AUTH_130` · `AUTH_131` · `AUTH_132` · 429 `AUTH_040` + `Retry-After` · 503 `AUTH_013` |
| `POST /api/v1/admin/admins` (#193) | `hasRole('ADMIN')` | 201 `{memberId}` — 본문 `{email, password, name}`. 비밀번호는 응답에 없다. 고객 이메일은 승격하지 않는다 | 400 `AUTH_130` · `AUTH_131` · `AUTH_132` · `AUTH_140`~`144` · 409 `AUTH_002` · 401 `SECURITY_001` · 403 `SECURITY_005` |
| `PATCH /api/v1/admin/admins/{memberId}` (#193) | `hasRole('ADMIN')` | 200 `dataBody: null` — 활성 관리자를 끈다. 마지막 한 명은 거절. 옛 access 토큰은 게이트웨이 컷오프 | 404 `AUTH_033` · 409 `AUTH_031` · 400 `AUTH_146` · 503 `AUTH_013` |
| `PATCH /api/v1/admin/me/password` (#193) | `hasRole('ADMIN')` | 200 `dataBody: null` — 본문 `{currentPassword, newPassword}`. 본인만. 바꾼 뒤 다시 로그인 | 400 `AUTH_032` · `AUTH_141` · `AUTH_142` · `AUTH_145` · 503 `AUTH_013` |
| `GET /api/v1/admin/members` (#6) | `hasRole('ADMIN')` | 200 `PageResponse<{memberId, email, name, phone, role, createdAt}>` — 개인정보는 마스킹(아래). `?page=0&size=20&role=` | 400 `AUTH_133` · `AUTH_134` · `AUTH_198` · 401 `SECURITY_001` · 403 `SECURITY_005` |
| `GET /api/v1/admin/audit-logs` (#192 · #286 · #322) | `hasRole('ADMIN')` | 200 `PageResponse<{auditLogId, action, actorMemberId, actorDisplayName, target, targetDisplayName, result, createdAt}>` — 최신순(아래). `?action=&actorMemberId=&target=&result=&page=0&size=20` | 400 `AUTH_133` · `AUTH_134` · `AUTH_135` · `AUTH_136` · `AUTH_198` · 401 `SECURITY_001` · 403 `SECURITY_005` |

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
030~ 은 관리자(#6), 040~049 는 시도 제한(#66)이다. 필드 검증은 101~ 이 고객, 130~ 이 관리자다 — 진입 DTO 를 공유하지 않으므로 같은 뜻이어도 코드를 따로 둔다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `AUTH_001` | 400 | 로그인 실패 — 이메일 없음 · 비밀번호 틀림 · 관리자 계정을 구분하지 않는다. 401 은 토큰 거절에만 쓴다 (#4 결정 5, 로그인은 A3) |
| `AUTH_002` | 409 | 이미 가입된 이메일. 사전 조회를 함께 지난 동시 가입은 유니크 키(`uk_member_email`) 위반을 같은 코드로 바꾼다. 계정 존재를 드러내는 것은 감수한 위험이다 (`security-guide.md` §10) |
| `AUTH_003` | 404 | 토큰은 유효하지만 회원이 없다 |
| `AUTH_004` | 409 | 같은 회원을 다른 요청이 먼저 고쳤다(낙관적 락 — 마케팅 동의만 바꿔도 회원 버전이 오른다). 다시 조회한 뒤 보낸다 |
| `AUTH_010` | 401 | refresh 토큰이 없거나 발급 모양(base64url 43자)이 아니다 — 저장소를 보기 전에 거절한다. 필드 검증(400)을 따로 두지 않는다 |
| `AUTH_011` | 401 | refresh 토큰 무효 — 만료 · 로그아웃 · 폐기 · 회원 없음 · 로그인 절대 수명(최초 로그인부터, 기본 30일 `auth.token.refresh-family-max-lifetime`) 초과 · 저장값 손상을 구분하지 않는다 (손상은 서버 warn 로그로만 나눈다) |
| `AUTH_012` | 401 | refresh 토큰 재사용 감지 — 이미 회전한 토큰이 유예(30초) 밖에서 다시 왔거나 유예 안 형제가 3개를 넘었거나, 저장된 회전 시각이 손상(정수 아님 · 지금 + 60초보다 미래)이다. 그 로그인(family)의 토큰을 모두 폐기했다 |
| `AUTH_013` | 503 | 토큰 · 시도 계수 저장소(Redis) 장애 — 로그인 · 관리자 로그인 · 재발급 · 로그아웃. 로그인 상태(쿠키)는 그대로 두고 다시 시도한다. 회원가입은 계수기 장애에도 이 코드를 내지 않는다(fail-open) |
| `AUTH_020` | 404 | 배송지가 없거나 다른 회원의 것이다 — 둘을 구분하지 않는다(§2-2, 존재를 숨긴다) |
| `AUTH_021` | 409 | 배송지 회원당 상한(`auth.shipping-address.max-per-member`, 기본 20)에 닿았다. 요청 값은 맞고 주소록 상태가 막으므로 400 이 아니라 409 다 |
| `AUTH_022` | 409 | 같은 배송지를 다른 요청이 먼저 고쳤거나 지웠다(낙관적 락), 또는 기본 배송지 유니크 키 위반. 다시 조회한 뒤 보낸다 |
| `AUTH_023` | 409 | 같은 회원의 다른 배송지 쓰기가 회원 잠금을 쥐고 있어 5초(트랜잭션 시간 상한) 안에 끝나지 않았다. 그 회원 요청끼리 겹친 것이라 503 이 아니다 |
| `AUTH_030` | 400 | 관리자 로그인 실패 (#6) — 이메일 없음 · 비밀번호 틀림 · 고객 계정을 구분하지 않는다. 문구는 `AUTH_001` 과 같다 (`security-guide.md` §11-1) |
| `AUTH_040` | 429 + `Retry-After` | 로그인 시도 초과 (#66) — 고객 · 관리자 로그인. 같은 이메일(정규화)의 시도가 창(기본 15분)의 허용 횟수(기본 5회)를 넘었다. 비밀번호가 맞아도 거절하고, 계정이 있든 없든 같은 본문 · 헤더다. 관리자 로그인은 성공한 시도도 창에서 지우지 않는다. `Retry-After` 초 뒤 다시 허용한다 (`security-guide.md` §12) |
| `AUTH_041` | 429 + `Retry-After` | 회원가입 시도 초과 (#66) — 같은 이메일의 가입 시도가 창(기본 10분)의 허용 횟수(기본 5회)를 넘었다. 가입된 이메일이든 아니든 같다 |
| `AUTH_100` | 400 | 깨진 JSON · 본문 없음 · 타입 불일치 (필드는 `fieldErrors` 로 밝힌다) |
| `AUTH_101~115` | 400 | 필드별 검증 — 이메일 101~103 · 비밀번호 104~106 · 이름 107 · 108 · 114 · 전화 109 · 동의 110~113 · 수정 칸 없음 115. 로그인은 같은 뜻의 101 · 102 · 104 만 본다(비밀번호 형식은 보지 않는다 — 틀리면 `AUTH_001`) |
| `AUTH_116~119` | — | 예약 없음 (비워 둔다) |
| `AUTH_120~129` | 400 | 배송지 검증 — 별칭 120 · 받는 사람 121 · 연락처 122 · 123(형식) · 국가 124 · 우편번호 125(KR 필수) · 126(형식) · 주소 127 · 길이 128 · 해외 도시 129 |
| `AUTH_130~146` | 400 | 관리자 · 표시 이름 · 운영자 계정 필드 검증 (#6 · #286 · #322 · #193) — 관리자 로그인 이메일 필수 130 · 254자 131 · 비밀번호 필수 132, 어드민 목록 `page` 133 · `size` 134, 감사 로그 `actorMemberId` 135 · `target` 136, 표시 이름 `ids` 137 · 100개 138 · 양수 139, 운영자 추가 이메일 형식 140 · 비밀번호 12~64자 141 · 비밀번호 형식 142 · 이름 필수 143 · 이름 길이 144 · 현재 비밀번호 필수 145 · 관리자 아이디 양수 146 |
| `AUTH_198` · `AUTH_199` | 400 | 파라미터 타입 불일치(`page=x` · 없는 `role`) · 필수 파라미터 누락 |

코드 대역 — 회원 `001~009` · 토큰 `010~019` · 배송지 `020~029` · 관리자(#6) `030~039` · 시도 제한(#66) `040~049`, 검증은 회원 `101~115` · 배송지 `120~129` · 관리자(#6) `130~` 이다.
`AUTH_042` 는 BCrypt 동시 실행 상한(bulkhead, 503)용으로 남겨 둔다(아직 없다).
시도 제한 429 는 `Retry-After`(초, `max(1, ceil(남은 ms / 1000))`)를 싣는다 — 게이트웨이 `GATEWAY_006` 과 같은 모양이다. BFF 는 이 헤더를 클라이언트로 중계한다.
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

어드민 감사 로그 조회 (#192) — `GET /api/v1/admin/audit-logs`

- `action`(선택, `ADMIN_LOGIN` · `MEMBER_SEARCH`, 틀리면 198) · `actorMemberId`(선택, 양수 — 135) · `target`(선택, 완전 일치, 공백 없이 1~200자 ASCII — 136) ·
  `result`(선택, `SUCCESS` · `FAILURE`, 틀리면 198) · `page` · `size`(회원 조회와 같은 133 · 134).
  정렬은 최신순 하나(감사 로그 아이디 내림차순)라 `sortType` 이 없다. 조건은 각각 켜지고 꺼지므로 QueryDSL 이고, `(action, id)` · `(actor_member_id, id)` · `(target, id)` 인덱스를 탄다.
  로그인 실패는 행위자가 없어 `actorMemberId` 만으로는 한 계정을 못 고른다. `target=member:<id>&result=FAILURE` 로 고른다 (#286).
- 항목 `{auditLogId(string), action, actorMemberId(string | null), actorDisplayName(string | null), target(string | null), targetDisplayName(string | null), result, createdAt}`.
  저장된 칸은 `admin.audit` 로그 한 줄과 같다. `actorDisplayName` 은 행위한 관리자의 마스킹 이름, `targetDisplayName` 은 `target` 이 `member:<id>` 일 때만 그 회원의 마스킹 이름이다 (#322).
  회원이 없거나 이름이 없으면 null 이다(`maskName(null)` 의 `*` 를 쓰지 않는다). 그 외 target(`members?role=...` 등)은 `targetDisplayName` 이 null 이다.
  이름은 그 페이지의 아이디를 모아 회원 테이블을 한 번만 읽고 Presenter 에서 마스킹한다. 어드민 회원 목록으로 이름을 붙이지 않는다 — 그 조회는 감사 대상이고 size 상한이 100이다.
  감사 로그를 거를 관리자 선택용 경량 목록 API 도 두지 않는다. 로그인 실패는 행위자를 모르므로 `actorMemberId` · `actorDisplayName` 이 null 이고 노린 관리자 계정이 `target` 에 남는다.
- 이 조회 자체는 감사하지 않는다 — 저장된 행에 개인정보 원문이 없고, 남기면 조회마다 행이 늘어난다. 표시 이름을 읽는 것도 회원 목록 조회가 아니라서 `MEMBER_SEARCH` 행이 생기지 않는다.
  기록 방식은 `security-guide.md` §11-4. 화면(#320)은 `actorDisplayName` · `targetDisplayName` 이 있으면 그 이름을, null 이면 관리자로 보인다. FE 코드는 이 변경에 포함하지 않는다.

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
| 목록 `GET /api/v1/products` | `hoverImage` | 목록 카드 hover 용 두 번째 정지 이미지. 모양은 `thumbnail`(`ProductThumbnailItem`)과 같다. 대표 · 갤러리를 노출 순서 → 아이디 오름차순으로 세어 두 번째이고, 영상은 세지 않는다. 1장 이하면 `null` |
| 목록 `GET /api/v1/products` | `preorder: boolean` | 프리오더(예약 판매) 상품인가. `store.features.preorder` 가 꺼져 있으면 언제나 `false` |
| 상세 `GET /api/v1/products/{productId}` | `preorder: { expectedShipDate } \| null` | 프리오더가 아니거나 플래그가 꺼지면 `null`. `expectedShipDate` 가 `null` 이면 "발송일 추후 안내" |
| 상세 | `images[].mediaType` | `IMAGE` · `VIDEO`. 화면 분기용 제어 코드라 metadata 객체가 아니라 문자열이다(§11 예외) |
| 상세 | `images[].posterUrl` | VIDEO 의 재생 전 정지 이미지 URL. IMAGE 면 `null` |

- **날짜만 있는 값(`LocalDate`)은 ISO 날짜 문자열 `yyyy-MM-dd`(스토어 현지 날짜)로 내린다.** 시각(`Instant`)과 달리 시간대를 붙이지 않는다.
- 발송 예정은 고정 날짜("○월 ○일 순차 발송")다. 주문일 기준 일수는 맞춤 제작 `customOrder.leadTimeDays` 이고 서로 겹치지 않는다.
- 상세 `images[]` 는 갤러리 이미지와(`store.features.product-video` 가 켜져 있으면) 영상을 `displayOrder` → 아이디 오름차순으로 섞는다. 꺼지면 VIDEO 를 뺀다.
  대표 이미지(`thumbnail`)는 THUMBNAIL 만이다 — VIDEO 가 대표가 되지 않는다.
  목록 `hoverImage` 도 정지 이미지만 본다. 그 페이지 상품의 정지 이미지는 아이디 in 절 쿼리 한 번으로 읽고, 상품마다 다시 읽지 않는다.
- 프리오더 상품의 판매 가능 · 품절 판정과 재고 선점은 일반 상품과 같다. 화면에 발송 예정 안내만 더한다.
- 어드민 입력(#9 몫): 영상 · 포스터 URL 은 우리 오브젝트 스토리지 공개 URL(`STORAGE_PUBLIC_BASE_URL` 접두)만 받는다 — 외부 영상 URL 은 받지 않는다.
  VIDEO 는 포스터 필수(빈 문자열 거절), 그 밖에는 포스터를 받지 않는다. 프리오더가 아니면 발송 예정일을 받지 않는다(DB check 와 같은 규칙).
  유일 키는 유형별 `display_order` 라 GALLERY 와 VIDEO 가 같은 값을 가질 수 있다 — 어드민 등록은 갤러리 이미지 · 영상을 합친 순번을 매겨 동률을 만들지 않는다.

### 2-8. 어드민 응답 원칙 — 사용자는 쇼핑몰 운영자다 (#305)

어드민 콘솔을 쓰는 사람은 개발자가 아니라 **쇼핑몰 운영자(비개발자)** 다. 어드민 응답은 운영자가 읽고 판단하는 **업무 언어**만 담는다.

| 내는 것 | 내지 않는 것 (개발자 도구의 몫) |
|---------|------------------------------|
| 이름 · 상태(`{code, name, description}`) · 진행률 · 진행 문구 · 사람이 읽는 안내 문구 · 업무 시각 · 업무 금액 · 결과 파일 링크 · 운영자가 할 수 있는 동작 여부(예: `cancellable`) | 시도 횟수 · 재시도 상한 · 우선순위 · 멱등 키 · 입력 · 결과 원문(JSON) · 오류 코드 · 오류 원문 · 스택 · 워커 · 호스트 정보 · 내부 버전 |

- **실패는 안내 문구로 알린다** — 오류 코드를 그대로 내지 않고, 운영자가 다음에 할 일(다시 요청 · 범위 줄이기 · 파일 확인 · 관리자 문의)을 적은 문장으로 바꾼다.
  코드 → 문구 매핑은 그 기능이 갖는다(예: 작업은 핸들러의 `failureNotice` → 엔진 공통 문구 → 일반 문구 순, `JobQueryProcessor`).
- 기술 진단(왜 실패했나 · 몇 번 시도했나 · 어떤 입력이었나)은 로그 · 대시보드(Grafana) · 메시징 콘솔(Kafka UI, #304)에서 본다 — 어드민 API 로 열지 않는다.
- 운영자가 거를 때 쓰는 코드 값(작업 종류 코드 · 상태 코드)은 낼 수 있다 — 표시는 이름(`typeName` · `status.name`)으로 한다.
- 이미 있는 어드민 API 도 이 기준으로 본다. 검토는 `/backend-api-check` 의 점검 항목이다.
- 처음 적용한 곳: 어드민 작업 API(§7-1).

### 2-9. 메인 전시 (product-service, #63)

메인 화면의 섹션(블록)을 어드민이 바꾼다. 섹션 유형은 브랜드와 무관한 블록 이름(`HERO` · `PRODUCT_PICK` · `STORY` · `LOOK` · `DETAIL`)이고,
브랜드 문구는 섹션의 **제목 값**이다 — 코드에 고객사 이름 · 문구를 두지 않는다(`architecture-guide.md` §9). 이 유형은 페이지 빌더(#74)의 블록으로 그대로 옮긴다.

| 경로 | 권한 | 성공 | 주요 오류 |
|------|------|------|-----------|
| `GET /api/v1/displays/main` | `permitAll()` | 200 노출 중인 섹션 목록 · `Cache-Control: max-age=60, public` | — |
| `POST /api/v1/admin/displays/preview` | ADMIN | 200 현재 노출 가능한 초안 · 고객 메인과 같은 JSON · `Cache-Control: no-store` | 400 `PRODUCT_047~054` · 검증 `140~168` · 503 `PRODUCT_055` |
| `GET /api/v1/admin/displays/sections` | ADMIN | 200 전체 섹션(비노출 · 기간 밖 포함, 배열 순서 = 노출 순서, `liveStatus` 파생) | — |
| `GET /api/v1/admin/displays/sections/{sectionId}` | ADMIN | 200 | 404 `PRODUCT_046` |
| `POST /api/v1/admin/displays/sections` | ADMIN | 201 — 순서는 마지막 + 1 | 400 `PRODUCT_047~054` · 409 `PRODUCT_056` · `PRODUCT_058` · 503 `PRODUCT_055` |
| `PUT /api/v1/admin/displays/sections/{sectionId}` | ADMIN | 200 — 섹션 필드 · 아이템 전체 교체 | 위와 같음 + 404 `PRODUCT_046` |
| `DELETE /api/v1/admin/displays/sections/{sectionId}` | ADMIN | 200 — 아이템도 지운다. 남은 순서를 다시 매기지 않는다 | 404 `PRODUCT_046` · 409 `PRODUCT_058` |
| `PATCH /api/v1/admin/displays/sections/order` | ADMIN | 200 새 순서의 전체 목록 — 본문 `{sectionIds}` 는 현재 전체 집합과 같아야 한다 | 400 `PRODUCT_057` · 409 `PRODUCT_058` |

- **저장 전 미리보기(#340)**: 본문 `{sections: [DisplaySectionUpsertRequest, ...]}` 은 전체 초안이며 최대 50개, 빈 배열을 허용한다.
  `sections` 누락 · null 은 400 `PRODUCT_166`, 50개 초과는 `PRODUCT_167`, null 원소는 `PRODUCT_168`이다. 중첩 요청은 기존 `PRODUCT_140~165` 검증을 그대로 쓴다.
  숨김 · 기간 밖 초안도 모든 섹션 규칙(`PRODUCT_047~054`, 미디어 미설정 `055`)과 상품 존재를 확인한다. 없는 상품은 `050`, 숨긴 상품은 허용 후 고객 카드에서 제외한다.
  응답은 `DisplayMainResponse`와 같은 섹션 · 미디어 · 상품 카드 JSON 형태다. 현재 서버 Clock으로 노출을 판정하며 임의 시각 파라미터는 받지 않는다.
  섹션과 아이템은 요청 배열 순서다. `sectionId`는 `preview-section-<섹션 인덱스>`, `itemId`는 `preview-item-<섹션 인덱스 * 30 + 아이템 인덱스>`인 임시 문자열이다.
  필터 전 배열 위치를 쓰므로 필터 후 번호가 건너뛸 수 있다. 저장 식별자가 아니므로 저장 · 수정 요청의 식별자로 쓰면 안 된다. 상품 · 이미지 식별자는 실제 식별자다.
  전시 DB 읽기 · 쓰기 · 잠금과 Snowflake 발급은 없고 상품만 bulk 조회한다. DB의 현재 전시 구성과 합치지 않는다. 응답은 `no-store`다.
  FE 영향 · 계약 리뷰어: `seonghoho` — 어드민 인증 BFF에서 preview를 호출하고 결과를 기존 미리보기 렌더러에 전달한다. 임시 ID는 저장 ID와 구분한다.
  storefront 공개 action으로 ADMIN 자격증명을 전달하거나 해당 API 인가를 완화하지 않는다. 어드민 관리 조회의 metadata 형태와 달리
  미리보기의 `itemType` · `mediaType`은 고객 메인처럼 문자열이며 고객/어드민 DTO·Presenter는 분리한다.
- **노출 조건**: `visible` 이고 `startAt ≤ now < endAt`(둘 다 비면 제한 없음). 섹션 · 아이템은 순서 → 아이디 오름차순이다.
  시각은 마이크로초로 잘라 저장 · 판정한다(DB `timestamp(6)` 과 같은 값). 0001 ~ 9999년 밖은 400 `PRODUCT_165`.
- **아이템**: `MEDIA`(PC · 모바일 주소 따로, 모바일이 없으면 PC, VIDEO 는 PC 포스터 필수) 또는 `PRODUCT`(상품 아이디). 유형별 허용 아이템 · 개수는
  HERO 미디어 1~10 · PRODUCT_PICK 상품 1~20 · STORY 미디어 0~10 · LOOK 미디어 1~10 + 상품 0~20 · DETAIL 미디어 1~10, 섹션당 아이템 30 · 섹션 50 상한이다.
  LOOK 은 룩 API(#62)가 생기면 바뀔 수 있다.
- **상품 카드**는 고객 상품 목록 항목과 같은 필드 · 같은 계산(`ProductSaleSummary`)이다 — `hoverImage` 포함. `DisplayProductCardParityTest` 가 두 응답을 비교한다.
  숨긴 상품 아이템은 고객 응답에서 빠지고, 그래서 `PRODUCT_PICK` 이 비어도 섹션은 남는다. 숨긴 상품도 등록은 된다(없는 상품만 400 `PRODUCT_050`).
- **미디어 주소**는 스토리지 공개 주소(`store.media.public-base-url` ← `STORAGE_PUBLIC_BASE_URL`) 아래만 받는다. 키는 `[A-Za-z0-9._~-/]` 와 `%XX` 만,
  `..` · `//` · 쿼리 · 프래그먼트 금지(400 `PRODUCT_054`). 공개 주소가 없으면 미디어가 있는 쓰기를 503 `PRODUCT_055` 로 닫는다. 판정은 쓰기 시점에만 한다 —
  공개 주소(CDN)를 바꾸면 저장된 주소는 그대로다.
- **링크**는 `/` 로 시작하는 상대 경로 또는 https 만(`//host` · `javascript:` · 백슬래시 · 공백 거절). https 의 호스트는 가리지 않는다 — FE 는 외부 링크에
  `rel="noopener noreferrer"` 를 붙인다.
- **모든 문구(제목 · 부제 · 본문 · 링크 문구 · caption · altText)는 평문이다.** HTML 로 해석하지 않는다 — FE 는 텍스트 노드로만 렌더하고 개행은
  `white-space` 로 처리한다. 제어 문자는 400 `PRODUCT_164`(본문만 개행 · 탭 허용). 길이는 UTF-16 코드 유닛 기준이다.
- **동시성**: 쓰기 4종은 전체 섹션 행 잠금(5초) 뒤 다시 읽어 판정하고 마지막 쓰기가 이긴다(§2-5 카테고리와 같다). 섹션이 0개일 때만 동시 생성이 직렬화되지 않아
  둘 다 순서 0 을 받는다 — 아이디 순으로 정렬되고 순서 변경으로 정리된다.
- **캐시**: `public` 은 공개 · 사용자 무관 · 개인정보 없는 응답에만 쓴다. 고객 메인은 1분 늦어도 가격 · 재고를 상세 · 주문이 다시 계산하므로 결제 금액을 바꾸지 않는다.
  어드민 미리보기는 `POST /api/v1/admin/displays/preview` 를 쓴다. 언어 · 통화별 응답이 생기면 `Vary` 를 두거나 경로를 나눈다.
- 오류 코드: `PRODUCT_046`(섹션 없음 404) · `047`(유형이 받지 않는 아이템) · `048`(아이템 수 범위) · `049`(상품 중복) · `050`(없는 상품) · `051`(기간) ·
  `052`(링크 주소) · `053`(문구만 있는 링크) · `054`(미디어 주소) · `055`(공개 주소 미설정 503) · `056`(섹션 상한 409) · `057`(순서 변경 집합) · `058`(잠금 409).
  검증 코드는 `PRODUCT_140~168`(`DisplayValidationMessage`). 번호 공간 `PRODUCT_` 를 `ProductErrorCode` · `DisplayErrorCode` · `SizeChartErrorCode`(059~, §2-10)가 나눠 쓴다 — 새 번호는 세 클래스를 모두 보고 고른다.
  **검증 대역 `PRODUCT_1xx` 는 소진됐다** — 새 컨텍스트는 자기 접두어를 연다(룩은 `LOOK_`, §2-11 · SKU 원가는 `SKU_COST_`, §2-12 · `coding-conventions.md` §8-2).
- 계층 — display 컨텍스트는 product 컨텍스트의 Processor(`ProductListProcessor.summarizeVisible` · `ProductLookupProcessor`)와 domain(`ProductSummary`)만 쓴다(H8).
  상품 카드의 `customOrder` · `preorder` 는 목록과 같은 플래그 판정(#61)이다.
- **어드민 응답은 운영자 언어다(#305).** 순서는 배열 순서로만 전하고 내부 순서 번호(`displayOrder`, 삭제 뒤 빈 번호가 남는다) · 저장마다 새로 발급되는
  아이템 아이디는 내지 않는다. `itemType` · `mediaType` 은 `{code,name,description}`(미디어 · 상품, 이미지 · 영상)이고 — 고객 응답은 분기용 문자열 그대로 —
  노출 상태는 `liveStatus`(LIVE 노출 중 · HIDDEN 숨김 · SCHEDULED 예정 · ENDED 종료, 숨김이 기간보다 앞선다)로 낸다. 고객 메인 노출과 같은 판정(`DisplaySection.liveStatusAt`)이다.
  요청은 code 문자열로 받는다.

### 2-10. 상품 사이즈표 (product-service, #216)

상품 상세 본문에는 사이즈표를 넣지 않는다. 공개 상품 상세 계약(#9)과 갈라 두어, 표가 없어도 상세 응답이 바뀌지 않게 한다.
부모 상품당 0~1개인 선택 하위 리소스다(§2-2). 고객 · 어드민 DTO 를 공유하지 않는다. 아이디는 문자열이다.
Idempotency-Key · 작성자 칸은 없다. 치수는 금액이 아니라 `numeric(10,2)` JSON number 다.

| 경로 | 권한 | 성공 | 주요 오류 |
|------|------|------|-----------|
| `GET /api/v1/admin/size-chart-templates?page=&size=` | ADMIN | 200 `PageResponse` 요약 `{templateId, name, baseUnit, columnCount}` — 최신순(아이디 내림차순). page/size 기본 0/20, size 1~100 | 400 `PRODUCT_101` · `PRODUCT_102` |
| `GET /api/v1/admin/size-chart-templates/{templateId}` | ADMIN | 200 항목 포함 | 404 `PRODUCT_059` |
| `POST /api/v1/admin/size-chart-templates` | ADMIN | 201 생성한 템플릿 | 400 `PRODUCT_060` · `PRODUCT_061` · `PRODUCT_100` · `PRODUCT_170~182` |
| `PUT /api/v1/admin/size-chart-templates/{templateId}` | ADMIN | 200 전체 교체 | 404 `PRODUCT_059` · 409 `PRODUCT_062` · `PRODUCT_072` · 400 `PRODUCT_060` · `PRODUCT_061` · `PRODUCT_071` |
| `DELETE /api/v1/admin/size-chart-templates/{templateId}` | ADMIN | 200 `dataBody: null` — 항목까지 행 삭제 | 404 `PRODUCT_059` · 409 `PRODUCT_063` · `PRODUCT_072` |
| `GET /api/v1/admin/products/{productId}/size-chart` | ADMIN | 200 저장 값. 숨긴 상품 포함. 표가 없으면 `dataBody: null` | 404 `PRODUCT_064` |
| `PUT /api/v1/admin/products/{productId}/size-chart` | ADMIN | 200 전체 교체(없으면 생성). 값은 저장 단위 그대로 | 404 `PRODUCT_064` · 400 `PRODUCT_065~070` · `PRODUCT_183~197` · 409 `PRODUCT_072` · `PRODUCT_073` |
| `GET /api/v1/products/{productId}/size-chart?unit=` | `permitAll()` | 200. `unit` 생략 시 저장 단위. `unit=CM\|INCH` 이면 그 단위로 바꾼 값. 표가 없으면 `dataBody: null` | 404 `PRODUCT_001`(없거나 숨긴 상품) · 400 `PRODUCT_198`(unit) |

- **템플릿**: `{name, baseUnit, columns:[{name, description?, displayOrder}]}`. 항목 1~20. 이름 1~50, 항목 이름 1~30, 설명 200(nullable), `displayOrder` 0 이상.
  이름 · 순서가 템플릿 안에서 겹치면 400 `PRODUCT_060` · `PRODUCT_061`. `baseUnit` 은 `CM` · `INCH` 이고 치수를 저장하는 단위다.
- **쓰는 중인 템플릿 수정**: 상품 사이즈표가 이 템플릿을 쓰면 `baseUnit` 변경과 항목 추가 · 삭제는 409 `PRODUCT_062` 다. 본문에 기존 `columnId` 를 실어 이름 · 설명 · 순서만 바꾼다.
  아무도 안 쓰면 항목을 새 아이디로 통째로 다시 만든다(요청의 `columnId` 는 쓰지 않는다).
- **상품 사이즈표**: `{templateId, note?, rows:[{sizeLabel, displayOrder, cells:[{columnId, value}]}]}`. 행 1~30. 사이즈 이름 1~20, note 500(nullable).
  각 행은 그 템플릿의 항목을 빠짐없이 한 번씩 담는다. 모르는 `columnId` · 누락 · 한 행 안의 중복은 400 `PRODUCT_066` · `PRODUCT_067` · `PRODUCT_068`.
  사이즈 이름 · 순서 중복은 400 `PRODUCT_069` · `PRODUCT_070`. 값은 0 초과 9999.99 이하, 소수 둘째 자리까지(400 `PRODUCT_197`).
  템플릿이 없으면 경로가 아니라 본문 참조라 400 `PRODUCT_065` 다. 어드민 경로의 상품이 없으면 404 `PRODUCT_064` — 상품 상세의 `PRODUCT_001` 을 재사용하지 않는다. 숨긴 상품은 있는 상품이다.
- **고객 조회**: 없거나 숨긴 상품은 상품 상세와 같이 404 `PRODUCT_001` 이다. 보이는 상품에 표가 없으면 200 · `dataBody: null` 이다. 어드민 조회도 표가 없으면 200 · `dataBody: null` 이다(상품 자체가 없을 때만 404).
- **단위**: 저장은 템플릿 `baseUnit` 그대로다. 변환은 고객 응답에서만 한다. `unit` 을 빼면 `displayUnit` 은 저장 단위와 같고 값은 그대로다(scale 을 다시 매기지 않는다).
  `unit=CM` 또는 `INCH` 이면 1 인치 = 2.54 센티미터, 소수 둘째 자리 반올림(`HALF_UP`)한 값을 `displayUnit` 기준으로 낸다. 어드민 응답은 항상 저장 값이고 `displayUnit` 을 내지 않는다.
  단위는 판매 상태와 같이 `{code, name, description}` 다. 잘못된 `unit` 은 400 `PRODUCT_198` 이고, 본문 enum 이 아니면 400 `PRODUCT_100` 이다.
- **순서 번호는 응답에 넣는다.** 수정 본문이 `displayOrder` · `columnId` 를 다시 받아야 해서다(전시 §2-8 이 내부 순서를 숨기는 것과 다르다). 표 · 행 아이디는 저장마다 다시 매길 수 있어 내지 않는다.
- **동시성**: 템플릿 행을 먼저 잠그고 그다음 상품 표 행을 잠근다(5초). 잠금을 못 얻으면 409 `PRODUCT_072`. 다른 템플릿 잠금을 잡은 저장이 같은 상품에 먼저 커밋되면 409 `PRODUCT_073` 이다. 마지막 쓰기가 이긴다.
- 오류 코드: `PRODUCT_059`(템플릿 없음 404) · `060`(항목 이름 중복) · `061`(항목 순서 중복) · `062`(사용 중 구조 변경 409) · `063`(사용 중 삭제 409) ·
  `064`(어드민 상품 없음 404) · `065`(본문 템플릿 없음 400) · `066`(모르는 항목) · `067`(항목 누락) · `068`(행 안 항목 중복) · `069`(사이즈 이름 중복) ·
  `070`(사이즈 순서 중복) · `071`(항목 아이디 중복) · `072`(잠금 409) · `073`(상품 표 유니크 409).
  필드 검증은 `PRODUCT_170~197`(`SizeChartValidationMessage`). page · size 는 서비스 공용 `PRODUCT_101` · `PRODUCT_102` 다.
- **게이트웨이**: 상품 사이즈표는 기존 `products/**` 로 라우팅된다. 템플릿 접두어 `/api/v1/admin/size-chart-templates/**` 는 §1-2 와 `ServiceRoute.PRODUCT` 에 함께 있다.

### 2-11. 룩 — Shop the Look (product-service, #62)

룩은 대표 이미지 한 장과 구성 상품 1~20개의 묶음이다. 어드민이 만들고 순서를 정한다. 메인 전시의 `LOOK` 섹션(§2-9)과는 별도 리소스다.
고객 노출은 기능 플래그 `store.features.looks`(기본 꺼짐)가 정하고, 어드민 API 는 플래그와 무관하게 동작한다(켜기 전에 미리 만들어 둔다, `architecture-guide.md` §9).
플래그 · 숨긴 룩 판정은 고객 조회 Processor(`LookWebProcessor`) 한 곳에서만 한다 — 어드민 Processor(`LookProcessor`)에 섞지 않는다.

| 경로 | 권한 | 성공 | 주요 오류 |
|------|------|------|-----------|
| `GET /api/v1/looks?page=&size=` | `permitAll()` | 200 `SliceResponse` `{contents:[{lookId, title, image, products}], hasNext}` — 보이는 룩, 노출 순서. page/size 기본 0/20, size 1~100 · `Cache-Control: max-age=60, public` | 400 `PRODUCT_101` · `PRODUCT_102` · `PRODUCT_198` |
| `GET /api/v1/looks/{lookId}` | `permitAll()` | 200 `{lookId, title, description, image, products}` · 같은 캐시 | 404 `LOOK_001`(없음 · 숨김 · 플래그 꺼짐) · 400 `LOOK_118` · `PRODUCT_198`(숫자 아님) |
| `GET /api/v1/products/{productId}/looks` | `permitAll()` | 200 `{looks:[...목록 항목과 같은 모양]}` — 상품이 든 보이는 룩 전체, 노출 순서 · 같은 캐시 | 404 `PRODUCT_001`(없거나 숨긴 상품, 플래그보다 먼저) · 400 `PRODUCT_198`(숫자 아님) |
| `GET /api/v1/admin/looks` | ADMIN | 200 전체(숨김 포함, 최대 100개, 배열 순서 = 노출 순서) `{looks:[...]}` | — |
| `GET /api/v1/admin/looks/{lookId}` | ADMIN | 200 | 404 `LOOK_001` · 400 `LOOK_118` |
| `POST /api/v1/admin/looks` | ADMIN | 201 — 순서는 마지막 + 1 | 400 `LOOK_003~005` · `LOOK_101~117` · 409 `LOOK_002` · `LOOK_008` · 503 `LOOK_006` |
| `PUT /api/v1/admin/looks/{lookId}` | ADMIN | 200 — 룩 칸 · 구성 상품 전체 교체(순서 그대로) | 위와 같음 + 404 `LOOK_001` · 400 `LOOK_118`(경로) |
| `DELETE /api/v1/admin/looks/{lookId}` | ADMIN | 200 `dataBody: null` — 구성 상품도 지운다. 남은 순서를 다시 매기지 않는다 | 404 `LOOK_001` · 400 `LOOK_118`(경로) · 409 `LOOK_008` |
| `PATCH /api/v1/admin/looks/order` | ADMIN | 200 새 순서의 전체 목록 — 본문 `{lookIds}` 는 현재 전체 집합과 같아야 한다 | 400 `LOOK_007` · `LOOK_119~122` · 409 `LOOK_008` |

- **본문**: `{title, description?, image:{url, width?, height?, altText?}, visible, productIds:[...]}`. 제목 1~100, 설명 1000(개행 · 탭 허용), 주소 500,
  대체 텍스트 200, 크기는 양수. `productIds` 는 1~20개이고 **배열 순서가 구성 순서**다. 아이디는 문자열로 보내도 된다.
- **응답**: `{lookId, title, description, image:{url, width, height, altText}, visible, products:[{productId, name, visible, saleStatus}]}`. 아이디는 문자열이다.
  순서는 배열 순서로만 전하고 내부 순서 번호(`displayOrder`) · 아이템 아이디는 내지 않는다(§2-8). `saleStatus` 는 `{code, name, description}`(판매중 · 판매 종료)이다.
- **비노출 · 판매 종료 상품**: 어드민 쓰기는 숨김 · 판매 종료 상품을 받는다 — 없는 상품만 400 `LOOK_004`(메시지에 없는 아이디를 모두 싣는다).
  어드민 조회는 상품마다 `visible` · `saleStatus` 를 내고, 상품 행이 없으면(데이터 오류) `name` · `saleStatus` 가 null · `visible=false` 다.
- **고객 응답 규칙**:
  - 기능 플래그가 꺼지면 데이터는 그대로 두고 응답에서만 숨긴다 — 목록 200 빈 `contents`(저장소를 읽지 않는다), 상세 404 `LOOK_001`, 상품별 200 `looks: []`.
    상품별 룩은 상품 확인(없거나 숨긴 상품 404 `PRODUCT_001`)이 플래그보다 먼저다 — 상품 상세와 같은 존재 판정을 지킨다.
  - 숨긴 룩은 목록에 없고 상세는 없는 룩과 같은 404 `LOOK_001` 이다(존재를 드러내지 않는다).
  - `products` 는 상품 목록 항목(`GET /api/v1/products`)과 같은 카드 · 같은 계산(`ProductListProcessor.summarizeVisible`)이다 — `LookProductCardParityTest` 가 JSON 을 비교한다.
    숨긴 상품은 빠지고, 판매 종료 · 품절은 `saleStatus` 로 남는다. **상품이 모두 빠져도 룩은 남는다(`products: []`)** — 그때 FE 는 "함께 담기" 를 숨긴다.
  - 정렬은 `display_order` 오름차순 → 아이디 오름차순으로 **어드민 목록과 같다** — 어드민이 본 배열 순서가 고객 순서다.
  - 읽기는 잠그지 않는다. 룩 행을 읽은 뒤 아이템을 읽기 전에 삭제가 커밋되어 아이템이 비면 그 룩은 목록에서 빠지고(한 쪽이 size 보다 작을 수 있다) 상세는 404 `LOOK_001` 이다.
    상품별 룩도 같다 — 상품 확인 · 룩 · 아이템을 각각 다른 문장으로 읽고 응답은 공유 캐시(60초)에 담기므로, 어드민 변경 · 상품 숨김이 최대 캐시 시간 수준 늦게 보일 수 있다.
  - `Cache-Control: max-age=60, public` — 메인 전시(§2-9)와 같은 판단이다. 어드민 변경 · 상품 숨김이 최대 1분 늦게 보인다.
- **이미지 주소**는 메인 전시와 같은 규칙(`MediaUrlPolicy`, §2-9)이다 — 공개 주소 밖은 400 `LOOK_005`(메시지에 공개 주소를 싣지 않는다), 공개 주소가 없으면 이미지가 필수라
  모든 쓰기가 503 `LOOK_006` 이다.
- **`title` · `description` · `image.altText` 는 평문이다.** 서버가 HTML 을 정제하지 않으므로 FE 는 텍스트 노드로만 렌더한다(`description` 의 개행은 `white-space` 로).
  제어 문자는 400(`LOOK_103` · `105` · `112`, 설명만 개행 · 탭 허용). 길이는 UTF-16 코드 유닛 기준이고, 앞뒤 공백을 빼기 전 원문 기준이다(저장은 앞뒤 공백을 뺀 값).
  필수 칸(`title` · `image.url`)은 앞뒤 공백(전각 공백 등 유니코드 공백 포함, `strip()` 기준)을 뺀 뒤 비면 400(`LOOK_101` · `LOOK_107`)이고, 선택 칸(`description` · `altText`)은 같은 기준으로 비면 null 로 저장한다.
- **동시성**: 쓰기 4종은 전체 룩 행 잠금(대기 2초 · 트랜잭션 5초) 뒤 다시 읽어 판정하고 마지막 쓰기가 이긴다(메인 전시와 같다). 버전 칸은 없다.
  잠금을 못 얻으면 409 `LOOK_008`. 룩이 0개일 때만 동시 생성이 직렬화되지 않아 둘 다 순서 0 을 받는다 — 아이디 순으로 정렬된다.
- 오류 코드: `LOOK_001`(룩 없음 404) · `002`(룩 100개 상한 409) · `003`(같은 상품 중복) · `004`(없는 상품) · `005`(이미지 주소) · `006`(공개 주소 미설정 503) ·
  `007`(순서 변경 집합) · `008`(잠금 409). 검증은 `LOOK_101~122`(`LookValidationMessage`). 깨진 JSON · 타입 불일치 · 필수 파라미터는 서비스 공용 `PRODUCT_100` · `198` · `199` 다.
  `PRODUCT_1xx` 검증 대역이 다 차서 컨텍스트 접두어 `LOOK_` 를 새로 열었다(`coding-conventions.md` §8-2).
- 계층 — look 컨텍스트는 product 컨텍스트의 Processor(어드민 `ProductLookupProcessor`, 고객 `ProductListProcessor.summarizeVisible` · `ProductDetailProcessor.getVisibleProduct`)와
  domain(`Product` · `ProductSummary`)만 쓴다(H8). 판매 상태 · 상품 카드는 Info 로 옮겨 Presenter 에 넘긴다.
- **게이트웨이**: `/api/v1/looks/**` · `/api/v1/admin/looks/**` 는 §1-2 와 `ServiceRoute.PRODUCT` 에 함께 있다. 상품별 룩은 기존 `/api/v1/products/**` 다.
  게이트웨이 · 서비스 모두 `/api/v1/admin/**` 밖은 익명 허용이고, 고객 3경로는 메서드의 `permitAll()` 로 연다(메인 전시와 같다).

### 2-12. SKU 원가 이력 (product-service, #361)

SKU 원가(매입 · 제작 원가, 포장 변동비)를 적용 시작 시각이 있는 이력으로 둔다 — 고치지 않고 행을 더한다(append-only). 주문 시점의 원가를 나중에 그대로 다시 고르기 위해서다.
원가는 **어드민 전용**이다 — 고객 응답 · 서비스 간 SKU 조회(`SkuInternalItem`)에 넣지 않는다. 컨텍스트는 `domainlayer/skucost` 이고 고객 진입(`adapter/in/web`)이 없다.
Idempotency-Key 는 없다(같은 시작 시각의 재전송은 유니크 키가 409 로 막는다). 기록한 어드민은 토큰의 memberId 만 남기고 이름을 내지 않는다. 아이디는 문자열이다.

| 경로 | 권한 | 성공 | 주요 오류 |
|------|------|------|-----------|
| `GET /api/v1/admin/skus/{skuId}/costs` | ADMIN | 200 `{skuId, costs:[항목]}` — 지금 적용되는 유형별 원가, GOODS → PACKAGING 순 | 404 `PRODUCT_002` · 400 `SKU_COST_110` |
| `GET /api/v1/admin/skus/{skuId}/costs/histories?costType=&page=&size=` | ADMIN | 200 `PageResponse<항목>` — 적용 시작 내림차순 · 아이디 내림차순 고정. page/size 기본 0/20, size 1~100 | 404 `PRODUCT_002` · 400 `PRODUCT_101` · `102` · `198`(costType) · `SKU_COST_110` |
| `POST /api/v1/admin/skus/{skuId}/costs` | ADMIN | 201 추가한 항목 | 404 `PRODUCT_002` · 400 `SKU_COST_001` · `PRODUCT_100` · `SKU_COST_101~110` · 409 `SKU_COST_002~006` |

- **항목**: `{costId, costType{code,name,description}, amount{amount,currency}, effectiveFrom, reason, adminMemberId, createdAt}`. `createdAt` 은 기록 시각, `effectiveFrom` 은 적용 시작(포함)이다.
- **본문**: `{costType, amount:{amount,currency}, effectiveFrom?, reason}`. `costType` 은 `GOODS`(매입 · 제작 원가) · `PACKAGING`(포장 변동비)다.
  금액은 최소 화폐 단위 정수 0 이상이다 — **0 은 "0원이라고 명시했다"** 이고 행이 없는 것(미입력)과 다르다. `reason` 은 앞뒤 공백을 빼고 1~200자, 제어 · 서식 문자 금지다.
- **시간 규칙**: `effectiveFrom` 을 빼면 서버 시각(지금)이다. 시작과 지금을 모두 마이크로초로 잘라 저장 · 판정한다. 지금 + 5분보다 뒤는 400 `SKU_COST_001` — 예약 적용은 받지 않고 화면 시계 오차만 받는다.
  1970-01-01T00:00:00Z 보다 이르면 400 `SKU_COST_109`.
  - **첫 행**(그 SKU · 유형에 행이 없을 때)만 지금보다 과거 시작을 받는다 — 미입력을 값으로 채울 뿐 이미 알던 원가를 바꾸지 않는다.
  - **행이 있으면** 시작이 **지금 이후**(`effectiveFrom >= now`)여야 하고(아니면 409 `SKU_COST_006`) **최신 행 시작보다 반드시 뒤**여야 한다(아니면 409 `SKU_COST_002`).
    지난 구간의 원가(그 구간에 난 주문의 원가)를 나중에 몰래 바꾸지 않기 위해서다(#361 완료 기준).
  - 판정 순서: 미래 한도(`001`) → 통화(`003`) → 소급(`006`) → 최신보다 뒤(`002`). 소급과 `002` 가 함께 걸리면(지금보다 앞이면서 최신 행 시작 이하) `006` 을 낸다.
    지금 이후인데 `002` 인 경우는 최신 행이 5분 오차 안의 미래 시작일 때뿐이다.
  - FE: "지금부터 적용" 은 `effectiveFrom` 을 비워 보낸다(화면 시계로 채우면 서버 시각보다 앞서 `006` 이 날 수 있다).
- **통화 고정**: SKU · 유형 타임라인의 통화는 첫 행 통화다. 다른 통화는 409 `SKU_COST_003`(메시지에 현재 통화). 판매가 통화와 같을 필요는 없다 — 불일치 처리는 #362.
- **미입력 = 부재**: 현재 원가 조회에서 한 번도 입력하지 않은 유형은 항목이 없다. 0 으로 채우지 않는다. 시작이 아직 오지 않은 행(5분 오차 안)만 있는 유형도 지금은 항목이 없다.
- **동시성**: 추가는 SKU 행을 잠그고(`SkuRepository#findByIdForUpdate`, 대기 2초 · 트랜잭션 5초) 최신 행을 읽어 판정한다. 잠금을 못 얻으면 409 `SKU_COST_005`(다시 시도).
  잠금을 비켜 간 같은 시작 시각의 동시 추가는 유니크 `uk_sku_cost_sku_id_cost_type_effective_from` 이 409 `SKU_COST_004` 로 막는다. 엔티티는 `@Immutable` 이고 `@Version` 이 없다.
- 오류 코드: `SKU_COST_001`(미래 시작 400) · `002`(최신보다 뒤 아님 409) · `003`(통화 불일치 409) · `004`(유니크 409) · `005`(잠금 409) · `006`(이미 원가가 있는 유형의 소급 409). 없는 SKU 는 상품 컨텍스트의 `PRODUCT_002` 다.
  필드 검증은 `SKU_COST_101~109`, 경로 skuId 양수는 `SKU_COST_110`(룩의 `LOOK_118` 과 같은 자기 코드)이다(`SkuCostValidationMessage`).
  `PRODUCT_1xx` 검증 대역이 차서 컨텍스트 접두어 `SKU_COST_` 를 새로 열었다(룩 §2-11 과 같다, `coding-conventions.md` §8-2). 깨진 JSON · 타입 불일치 · 필수 파라미터는 서비스 공용 `PRODUCT_100` · `198` · `199`,
  page · size 는 서비스 공용 `PRODUCT_101` · `PRODUCT_102` 다. 오류 메시지 · 로그에 금액을 넣지 않는다.
- **게이트웨이**: 새 접두어 `/api/v1/admin/skus/**` 는 §1-2 와 `ServiceRoute.PRODUCT` 에 함께 있다. 서비스 간 시점 원가 벌크 조회(`/internal/v1/sku-costs/query`)는 #361 PR-B 다.

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
| `GET /api/v1/admin/shipping-policies/domestic/histories?page=&size=` | 200 `PageResponse<{historyId, adminMemberId, adminDisplayName, changedAt, before, after}>` — 최신순(아이디 내림차순) 고정 | 400 `DELIVERY_137`(page) · `138`(size 1~20) · `198`(형식) · 503 `DELIVERY_013`(표시 이름 조회 실패) |

- `before` · `after` 는 `{baseFee, freeShippingThreshold, remoteAreas[{name, postalCodeFrom, postalCodeTo, surcharge}], version}` 이다. 금액은 `{amount, currency}`,
  무료배송 기준이 없으면 `null`, 구간은 시작 우편번호 오름차순이다. 정책을 처음 만든 교체는 `before` 가 `null` 이다.
  구간 아이디는 싣지 않는다 — 교체마다 새로 발급돼(§6-1) 이력에서 뜻이 없다.
- `historyId` · `adminMemberId` 는 문자열, `changedAt` 은 UTC ISO 다. `adminMemberId` 는 access 토큰의 memberId 이고 요청 본문으로 받지 않는다.
  내부 회원 아이디라 마스킹하지 않는다(재고 조정 이력 #86 과 같다). `adminDisplayName` 은 그 관리자의 마스킹 이름이다 (#322).
  페이지의 `adminMemberId` 를 `GET /internal/v1/member-display-names` 로 한 번에 읽고, delivery 는 마스킹된 값만 받는다. 회원을 모르거나 이름이 없으면 null 이다.
  auth-service 가 실패하면 이름을 null 로 숨기지 않고 503 `DELIVERY_013` 이다. 화면(#319)은 이름이 있으면 그 값을, null 이면 관리자로 보인다. FE 코드는 이 변경에 포함하지 않는다.
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
| `GET /api/v1/admin/jobs?status=&type=&page=&size=` | `PageResponse<{jobId, type, typeName, status, progressPercent, progressMessage, notice, cancellable, requestedAt, finishedAt}>` — 아이디 내림차순(등록 최신순) 고정 | 400 `JOB_101`(page) · `102`(size 1~100) · `103`(type `[A-Z0-9_]{1,64}`) · `198`(없는 status · 형식) |
| `GET /api/v1/admin/jobs/{jobId}` | 목록 항목과 같은 칸 — 진행 중인 작업 하나를 다시 읽을 때 쓴다 | 404 `JOB_001`, 400 `JOB_104`(양수) · `198` |
| `POST /api/v1/admin/jobs/{jobId}/cancel` | 상세와 같다. QUEUED 는 바로 `CANCELLED`, RUNNING 은 상태 그대로 취소 요청 안내 문구가 붙고 `cancellable=false`(멱등) | 404 `JOB_001`, 409 `JOB_022`(이미 끝남) · `JOB_021`(워커와 계속 겹침 — 다시 시도) |

- **운영자 칸만 낸다(§2-8, #305)** — 작업 이름(`typeName`, 핸들러의 `displayName`, 없으면 종류 코드) · 상태 `{code, name, description}` · 진행률 · 진행 문구 ·
  안내 문구(`notice`) · 취소 가능 여부 · 요청 시각(행의 생성 시각) · 끝난 시각. 시도 · 우선순위 · 멱등 키 · 입력 · 결과 원문 · 오류 코드 · 오류 원문 · 워커 · 요청자 · 취소자 아이디는 내리지 않는다.
- 안내 문구 — 실패면 핸들러 `failureNotice(errorCode)` → 엔진 공통 문구(실행 시간 초과 · 결과 과대 · 일시 오류) → 일반 문구 순, 재시도를 기다리는 대기면 "잠시 뒤 다시 시도",
  취소를 요청한 대기 · 실행이면 "취소를 요청했습니다". 성공 · 취소로 끝났으면 없다(null). 결과 파일 링크는 결과 파일(#212 PR-E)이 생기면 더한다.
- 아이디는 문자열, 시각은 UTC ISO 다. 조회는 운영자 칸만 읽는 읽기 모델(`JobSummaryQueryResult`)로 한다 — 도메인 `Job` 을 통째로 읽지 않는다.
- 상태 · 종류는 `application/model` 의 `JobStatusFilter` · 문자열로 받는다(H1). 조건 조합마다 파생 쿼리 하나다 — 종류 조건은 `idx_job_type_id` 를 그대로 타고,
  상태 조건은 상태 선두 인덱스로 거른 뒤 정렬한다(어드민 전용). 이 비용은 끝난 작업을 90일 뒤 지우는 보존 정리(`JOB_RETENTION_CLEANUP`, #212 PR-D)를 전제로 한다 —
  그 전까지는 끝난 작업이 쌓이므로, 정리 없이 행이 크게 늘면 `(status, id)` 인덱스를 검토한다.
- 그 밖의 요청 검증 실패(메시지에 코드가 없는 제약)는 400 `JOB_100` 이다.
- 취소는 `JobAdminFacade` 가 `JobRunProcessor.requestCancel` 을 시도마다 새 트랜잭션으로 부른다 — 워커의 가져가기 · 종료와 겹쳐 버전에서 지면 다시 읽어 최대 3번 시도한다.
  취소한 어드민은 access 토큰의 memberId 다. 성공 · 실패 모두 감사 로그 `action=JOB_CANCEL target=job:<id>` 를 남긴다(`security-guide.md` §11-4).

### 7-2. 어드민 알림 센터 (operations-service, #213)

모두 `hasRole('ADMIN')` 이다. 게이트웨이 라우트는 `ServiceRoute.OPERATIONS`(`/api/v1/admin/notifications/**`).
알림은 요청한 어드민 것만 보인다. 다른 관리자 알림 아이디는 404(`NOTIFICATION_001`)다.

| API | 응답 | 오류 |
|-----|------|------|
| `GET /api/v1/admin/notifications?page=&size=` | `PageResponse<{notificationId, kind, severity, title, linkPath, read, createdAt}>` — 아이디 내림차순 | 400 `NOTIFICATION_101`(page) · `102`(size 1~100) · `100` |
| `GET /api/v1/admin/notifications/unread-count` | `{count}` — 읽지 않은 수 | — |
| `POST /api/v1/admin/notifications/{notificationId}/read` | 목록 항목과 같다. 이미 읽었으면 그대로(멱등) | 404 `NOTIFICATION_001`, 400 `NOTIFICATION_103`(양수) · `198`, 409 `NOTIFICATION_020`(버전 충돌) |
| `POST /api/v1/admin/notifications/read-all` | `{count}` — 처리 뒤 읽지 않은 수(0) | — |
| `GET /api/v1/admin/notifications/stream` | `text/event-stream`. 이벤트 이름 `notification`, data 는 목록 항목과 같은 JSON(봉투 없음). 알림이 없어도 5초마다 주석(`: heartbeat`)을 보낸다 — 게이트웨이 response-timeout(10초) 동안 바이트가 없으면 연결이 끊긴다. 끊기면 목록으로 따라잡는다 | 무토큰 401 · CUSTOMER 403 |

- 종류 · 심각도는 `{code, name, description}` 다. 제목 · 링크에 고객 개인정보 원문을 넣지 않는다. `linkPath` 는 어드민 화면 경로(`/admin/jobs/{jobId}` 처럼 `/` 로 시작)이거나 null 이다.
- 작업이 끝나면(#212 종료 이벤트) 요청한 어드민에게 알림 한 건이 생긴다. 같은 작업 · 같은 종류의 재전달은 새 행을 만들지 않는다. 요청자가 없는 시스템 작업은 알림을 만들지 않는다.
- 다른 기능의 Facade 는 `NotificationRecordProcessor.record(NotificationRequest)` 만 부른다. 보존(기본 90일)이 지난 행은 스케줄러가 지운다.
- 모두 읽음은 그 어드민의 읽지 않은 행만 조건부 UPDATE 한 문장으로 처리한다(`read_at` · `updated_at` · 버전). 한 건 읽음은 도메인 전이 뒤 버전 비교 merge 다.
- 아이디는 문자열, 시각은 UTC ISO 다.

## 8. Swagger

- `@Tag` 는 고객/어드민을 나눠 단다: `@Tag(name = "상품")`, `@Tag(name = "[어드민] 상품 관리")`.
- 사유 코드처럼 문자열로 내리는 enum 값은 `@Schema(allowableValues = {...})` 로 허용값을 밝힌다.

## 소셜 로그인 계약 (#5)

고객 공개 API이며 provider는 kakao 또는 naver다. 두 응답 모두 Cache-Control: no-store다.

| 메서드 · 경로 | 입력 | 응답 dataBody |
|---|---|---|
| GET /api/v1/auth/oauth/{provider}/authorize-url | provider | authorizeUrl · state · verifier |
| POST /api/v1/auth/oauth/{provider}/login | code · state · verifier · agreements(신규 가입만 필수) | 기존 AuthTokenResponse와 같은 accessToken · accessTokenExpiresIn · refreshToken · refreshTokenExpiresIn |

FE는 응답 state/verifier를 같은 탭 sessionStorage에 보관한 뒤 authorizeUrl로 이동한다.
콜백의 state와 보관한 state가 일치하는지 먼저 확인하고 code/state/verifier를 서버로 보낸다.
verifier는 인가 URL·쿼리·로그에 넣지 않는다. 완료/실패/거절 후 sessionStorage 값을 지운다.
state는 기본 5분이고 provider와 verifier가 모두 일치할 때만 원자 소비된다. 불일치는 소비하지 않는다.
소비 후 provider 오류·약관 누락·이메일 충돌·토큰 저장 장애 등이 나면 인가 URL부터 새로 시작한다.

최초 가입의 agreements는 기존 일반 가입과 같은 termsOfService=true · privacyPolicy=true · marketing=true/false다.
기존 identity 로그인은 agreements를 생략할 수 있다. provider+subject로만 식별하며 기존 이메일은 변경하지 않는다.
같은 이메일인 일반/관리자/다른 소셜 계정에는 자동 연결하지 않고 409 AUTH_002를 반환한다.
Kakao 신규 가입 이메일은 is_email_valid와 is_email_verified가 모두 true여야 한다.
Naver는 공식 email_verified 필드가 없으며 id로 식별하고 신규 가입에 email이 없으면 거절한다.
휴대폰은 자동 수집하지 않는다. 신규 비밀번호는 추측할 수 없는 임의 비밀의 BCrypt 해시만 저장하며 사용자에게 발급하지 않는다.

| 오류 | HTTP | 의미 |
|---|---|---|
| AUTH_050 | 400 | 지원하지 않는 provider |
| AUTH_051 | 503 | 전체 또는 해당 제공자 비활성화 |
| AUTH_052 | 400 | state 만료·재사용·provider/verifier 불일치 |
| AUTH_053 | 400 | 제공자 인가 코드 거절 |
| AUTH_054 | 503 | 제공자 timeout·서킷 열림·응답 계약 오류 |
| AUTH_055 | 400 | 신규 가입에 사용할 수 있는 이메일 없음 |
| AUTH_056 | 400 | 신규 가입 필수 약관 없음 |
| AUTH_057 | 409 | 동일 identity 등록 경합 뒤 일치 계정을 찾지 못함 |
| AUTH_150/151/152 | 400 | code/state/verifier 요청 필드 검증 |
| AUTH_111/112/113 | 400 | 기존 약관 동의 요청 검증 |

## 기존 계정의 소셜 연결 계약 (#355, FE #356)

세 API 모두 CUSTOMER access JWT가 필요하다. memberId는 JWT에서만 얻는다. ADMIN은 403, 미인증은 401이며 응답은 공통 봉투다.
성공 응답은 모두 `Cache-Control: no-store`다. provider는 `kakao` 또는 `naver`다.

| 메서드 · 경로 | JSON 입력 | 응답 dataBody |
|---|---|---|
| GET /api/v1/members/me/social-accounts | 없음 | `{"providers":["kakao","naver"]}` (미연결은 빈 배열) |
| POST /api/v1/members/me/social-accounts/{provider}/authorize-url | `{"currentPassword":"현재 비밀번호"}` | authorizeUrl · state · verifier |
| POST /api/v1/members/me/social-accounts/{provider} | code · state · verifier | 연결 후 providers 목록 |

providers는 화면 제어용 코드 목록으로 enum 메타데이터 응답의 예외다. FE가 고정 제공자 이름·아이콘을 매핑한다.
subject · 이메일 · provider 토큰 · memberId를 반환하지 않으며 새 로그인 토큰도 발급하지 않는다.
code는 공백 불가·2048자 이하, state/verifier는 base64url 43자다. currentPassword는 공백 불가이고 64자 초과도 같은 재인증 실패로 처리한다.

FE #356은 마이페이지에서 현재 비밀번호를 받아 연결용 authorize-url을 호출한다. 응답 state/verifier와 `purpose=LINK`, provider를
로그인용 저장소 항목과 구분해 같은 탭 sessionStorage에 보관한다. 기존 OAuth redirect-uri를 사용하되 콜백에서 저장한 목적에 따라
연결 완료 API로 분기하고 CUSTOMER JWT를 보낸다. 콜백 state와 저장한 state를 먼저 비교하며 verifier는 URL·로그에 넣지 않는다.
로그인/연결 콜백을 혼용하거나, 콜백 중 다른 계정으로 로그인하면 실패한다. 완료·실패·거절 후 해당 저장소 항목을 지운다.
state 소비 뒤 provider 실패·충돌·DB 타임아웃이 나면 비밀번호 재인증과 새 인가부터 다시 시작한다.
같은 회원의 같은 provider+subject 재연결은 **새 인가 증명**으로 보내면 200이고 중복 행을 만들지 않는다. 소비된 state의 재전송은 400이다.
같은 provider의 다른 subject 또는 다른 회원의 identity는 409이며 자동 이전·회원 병합·이메일 기반 연결은 제공하지 않는다.
연결은 기존 회원의 이메일·프로필·비밀번호·약관·주문 및 배송지 참조를 변경하지 않는다. 연결 해제·비밀번호 복구는 범위 밖이다.

| 오류 | HTTP | 의미 · FE 동작 |
|---|---|---|
| AUTH_058 | 400 | 현재 비밀번호 확인 실패. 원인을 구분하지 않는다 |
| AUTH_059 | 429 | 고정 창의 재인증 시도 한도 초과. 창 만료 후 재시도 (Retry-After는 제공하지 않음) |
| AUTH_060 | 503 | `auth.login-guard.enabled=false`여서 연결 시작 차단 |
| AUTH_013 | 503 | 재인증 계수·state 저장소 장애. 연결을 진행하지 않는다 |
| AUTH_057 | 409 | 다른 회원 identity 또는 같은 회원·제공자의 다른 subject 충돌 |
| AUTH_004 | 409 | 회원 잠금 경합·DB 처리 5초 상한. 새 인가부터 재시도 |
| AUTH_003 | 404 | JWT의 고객 회원이 현재 존재하지 않음 |
| AUTH_050/051/052/053/054 | 기존 소셜 계약과 동일 | 미지원·비활성·state 오류·인가 거절·제공자 장애 |
| AUTH_104/150/151/152 | 400 | currentPassword/code/state/verifier 필드 검증 |

운영에서는 연결 사용 전에 기존 `AUTH_LOGIN_GUARD_ENABLED=true`와 Vault의 `AUTH_LOGIN_GUARD_HASH_KEY`를 설정해야 한다.
기존 일반 로그인·가입 제한 흐름의 활성화 방식은 이 변경에서 바꾸지 않는다. 연결 재인증만 기존 LOGIN 카운터·정책을 사용한다.

### #355 운영 DDL

엔티티 전체 PostgreSQL 17 생성 스냅숏은 `service/auth-service/src/test/resources/ddl/auth-schema.sql`이다.
운영에서는 기존 `(provider, subject)` 제약을 보존하고 아래 제약만 추가한다. 적용 전 중복을 점검하고 결과가 있으면 운영자가 해결한 뒤 적용한다.
중복 identity를 자동 삭제하거나 다른 회원으로 옮기지 않는다. DDL 적용은 운영자가 수행한다.

```sql
SELECT member_id, provider, count(*)
FROM auth.social_identity
GROUP BY member_id, provider
HAVING count(*) > 1;

SET client_encoding = 'UTF8';
BEGIN;
ALTER TABLE auth.social_identity
    ADD CONSTRAINT uk_social_identity_member_id_provider UNIQUE (member_id, provider);
COMMIT;
```

FE 영향: 로그인 시작/콜백과 sessionStorage verifier 처리를 추가하고 재시작 오류를 안내한다.
인증 계약 변경이므로 PR에 FE 리뷰어 seonghoho를 요청한다.

## 운영자 온보딩 계약 (#357)

어드민 안내 투어(#228)의 진행을 브라우저가 아니라 운영자별로 서버에 둔다. 두 API 모두 `hasRole('ADMIN')` 이고 operations-service 다(게이트웨이 `ServiceRoute.OPERATIONS`,
`/api/v1/admin/onboarding/**`). 운영자는 access 토큰의 memberId 로만 정한다 — 경로 · 본문으로 받지 않아 남의 기록을 읽거나 쓸 수 없다.

| API | 입력 | 응답 dataBody |
|---|---|---|
| `GET /api/v1/admin/onboarding/me` | — | `{tours, tasks}` — `tours` 는 배포된 투어 전부(설정 순서), `tasks` 는 아직 항상 `[]` |
| `PUT /api/v1/admin/onboarding/me/tours/{guideKey}` | `{guideVersion, status, currentStep}` | tour 항목 하나 |

- tour 항목은 `{guideKey, guideVersion, status{code,name,description}, currentStep, finishedAt}` 다. `guideVersion` 은 지금 배포된 버전, `finishedAt` 은 UTC ISO 다.
  status 는 `NOT_STARTED` · `IN_PROGRESS` · `COMPLETED` · `SKIPPED` 다. **안내를 본 기록일 뿐 실제 업무 완료가 아니다** — 업무 완료(`tasks`)는 담당 서비스의 사실로만 판정한다(#357 PR-B).
- 허용 목록은 yml `operations.onboarding.guides`(key · version)다. key 는 FE 투어 id(`tours.ts`)에서 `-v1` 꼬리를 뗀 것이다. 안내를 바꿔 다시 보여 주려면 version 을 올린다.
- **미시작은 행이 없다.** 기록이 없거나 기록 버전이 배포 버전과 다르면 응답에서 `NOT_STARTED` + 배포 버전이다.
- 기록은 합친다(다른 기기 · 동시 탭). 같은 본문을 다시 보내도 결과가 같다(멱등).
  - 같은 버전 — 순위 `NOT_STARTED < IN_PROGRESS < {COMPLETED, SKIPPED}` 가 내려가지 않는다. 끝난 기록에 `IN_PROGRESS` 가 오면 그대로다. `SKIPPED → COMPLETED` 만 허용하고
    `COMPLETED → SKIPPED` 는 그대로다. 진행 중끼리는 `currentStep` 이 큰 쪽이다. 완료 · 건너뜀이 되는 순간 `finishedAt` 을 남긴다.
  - `status=NOT_STARTED` 는 재실행 — **버전과 관계없이** 그 투어 기록을 지우고 배포 버전의 `NOT_STARTED` 를 낸다(없어도 같다). 실제 상품 · 설정과 업무 사실은 건드리지 않는다.
- **버전 규칙** (재실행이 아닌 기록) — 요청 버전은 배포 버전과 같아야 한다.

  | 요청 버전 vs 배포 버전 | 결과 |
  |---|---|
  | 요청 > 배포 | 400 `ONBOARDING_103` (배포되지 않은 안내) |
  | 요청 < 배포 | 409 `ONBOARDING_020` (옛 번들 — 저장하지 않는다) |
  | 요청 = 배포, 기록 없음 | 새 기록 |
  | 요청 = 배포, 기록 버전 < 요청 | 요청으로 교체 (안내가 새 버전으로 바뀜) |
  | 요청 = 배포, 기록 버전 = 요청 | 위 규칙으로 병합 |
  | 요청 = 배포, 기록 버전 > 배포 | 그 기록을 없는 것으로 보고 요청으로 교체 (배포 버전 롤백) |

- **배포 버전은 내리지 않는 것을 권장한다.** 내려도 위 마지막 줄로 복구된다 — 롤백 직후 GET 은 그 투어를 `NOT_STARTED` 로 보이고, 첫 기록이 옛 행을 교체한다.
- 동시 갱신은 낙관 락(`version`)과 `(member_id, guide_key)` 유니크 키로 막는다. 진 쪽은 새 트랜잭션에서 다시 읽어 합치고(최대 3번), 그래도 지면 409 `ONBOARDING_021` 이다.
- **FE 동작별 요청**
  - "다시 보기" — 그 투어에 `status=NOT_STARTED` 를 보낸 뒤 진행을 기록한다.
  - "전체 초기화" — 투어마다 `status=NOT_STARTED` 를 하나씩 보낸다(일괄 API 없음). 일부가 실패하면 실패한 투어만 다시 보낸다 — 재실행은 멱등이다.
  - 재실행 뒤에도 열려 있던 다른 탭이 이후 진행을 보고하면 다시 기록된다. 의도한 동작이다 — 재실행은 "지금까지 본 기록을 지운다" 이지 이후 기록을 막지 않는다.
- **FE 폴백 · 오류 대응**
  - 404 `ONBOARDING_001`(허용 목록에 없는 투어 · 아직 배포되지 않은 서버) — 서버 기록 없이 기존처럼 로컬 저장으로 진행한다.
  - 409 `ONBOARDING_020` — GET 을 **한 번** 다시 불러온다. 응답의 `guideVersion` 이 여전히 번들의 투어 버전과 다르면 번들 갱신 대상이다(새로고침 안내).
    재로드를 무한 반복하지 않는다 — 그 세션에서는 서버 기록 없이 로컬 상태로 진행한다.
  - 409 `ONBOARDING_021` · 5xx · 네트워크 오류 — 로컬 상태를 유지한 채 나중에 다시 보낸다.

| 오류 | HTTP | 의미 |
|---|---|---|
| ONBOARDING_001 | 404 | 허용 목록에 없는 guideKey |
| ONBOARDING_020 | 409 | 배포 버전보다 오래된 guideVersion (옛 번들) |
| ONBOARDING_021 | 409 | 다른 기기 · 탭과 계속 겹쳐 반영하지 못함 — 다시 시도 |
| ONBOARDING_100 | 400 | 깨진 JSON · 범위 밖 enum · 타입 불일치(폴백) |
| ONBOARDING_101 | 400 | guideKey 형식(`^[a-z][a-z0-9-]{0,63}$`) |
| ONBOARDING_102 | 400 | guideVersion 필수 · 1 이상 |
| ONBOARDING_103 | 400 | 배포되지 않은 guideVersion |
| ONBOARDING_104 | 400 | status 필수 |
| ONBOARDING_105 | 400 | currentStep 0 ~ 200 |
| ONBOARDING_198 | 400 | 파라미터 타입 불일치 |
| ONBOARDING_199 | 400 | 필수 파라미터 없음 |

### 업무 사실 internal — 배송 (#357 PR-B1)

배송 업무는 **국내 배송비 정책 저장**이다(행위자 이력이 있는 쪽만 — 해외 정책 교체는 이력을 남기지 않아 보지 않는다).
delivery-service `GET /internal/v1/onboarding-facts/shipping-policy?memberId={id}` 가 사실을 내고, 허용 호출자는 `SERVICE_operations-service` 하나다
(`@Hidden`, 서비스 토큰 없음 · 사용자 토큰 401 `SECURITY_007`, 다른 서비스 403 `SECURITY_005`). `memberId` 는 operations 가 access 토큰으로 확인한 운영자이고
양수가 아니면 400 `DELIVERY_139`, 숫자가 아니면 `DELIVERY_198`, 없으면 `DELIVERY_199` 다. 응답 dataBody 는 `{storeReady, memberFirstSavedAt}` 이다.
`storeReady` 는 국내(`DOMESTIC`) 정책 행이 있는가(누가 저장했든), `memberFirstSavedAt` 은 `delivery.shipping_policy_history` 에서 scope `DOMESTIC` ·
`admin_member_id = memberId` 인 행의 가장 이른 저장 시각(UTC ISO)이고 없으면 null(키는 남긴다)이다. 다른 운영자가 만든 정책이 있으면 `storeReady=true` ·
`memberFirstSavedAt=null` 일 수 있다 — 둘을 어떻게 `tasks` 로 합칠지와 원천 장애 시 `UNAVAILABLE` 처리는 operations(PR-B2)가 정한다.

## 로그인·회원가입 시도 제한 (#66)

고객·관리자 로그인은 429 `AUTH_040`, 회원가입은 429 `AUTH_041`과 `Retry-After`(남은 고정 창의 초 단위 올림, 최소 1초)를 반환한다.
로그인 카운터 장애는 503 `AUTH_013`이고 회원가입 카운터 장애는 fail-open이다.
회원가입의 선택적 `X-Client-Subject` 헤더는 게이트웨이 전용 불투명 IP 식별자이며 FE가 만들거나 저장하지 않는다.
이메일과 별도 30회·10분 IP 창을 먼저 검사한다. 헤더가 없으면 IP 차원만 건너뛴다. 요청 JSON·성공 응답 형식은 그대로다.
FE는 기존 400·409와 별도로 429 안내 및 Retry-After 대기를 처리해야 한다. API 리뷰 대상은 `seonghoho`다.
