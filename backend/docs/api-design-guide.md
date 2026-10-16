# Backend API Design Guide

## 1. 경로 체계

| 구분 | 접두어 | 사용자 | 게이트웨이 | 진입 패키지 |
|------|--------|--------|-----------|-------------|
| 고객(스토어프론트) | `/api/v1/**` | 비로그인 · `CUSTOMER` | 라우팅 | `adapter/in/web` |
| 어드민 콘솔 | `/api/v1/admin/**` | `ADMIN` | 라우팅 + **ADMIN 역할 확인** | `adapter/in/admin` |
| 서비스 간 | `/internal/v1/**` | 다른 서비스 | **라우팅하지 않는다** | `adapter/in/internal` |
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

## 2. 응답 구조

- 공통 래퍼 `Response<T>` 를 쓰고, Controller 반환은 `ResponseEntity<Response<T>>` 로 통일한다.
- 내부 `Info` 를 응답 타입으로 직접 노출하지 않는다. 응답 모양은 Presenter 가 마무리한다.

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
| `SECURITY_006` | 503 | 토큰 검증 저장소 장애 |

| HTTP | 프론트(BFF) 처리 — 정본 [frontend/docs/architecture.md](../../frontend/docs/architecture.md) §2 · §3 |
|------|------------------------------------------------------|
| 401 (001 · 002 · 003 · 004) | refresh 쿠키가 있으면 **코드와 무관하게** 한 번 재발급하고 원 요청을 한 번 재시도한다. 재발급이 명시적으로 거절되면(4xx · `success=false`) 쿠키를 지우고 로그인 화면, 재발급 일시 장애는 쿠키를 둔 채 `BFF_002` 503 |
| 403 (005) | 권한 없음 안내 |
| 503 (006) | 잠시 후 재시도. 로그아웃시키지 않는다 |

- 재발급 여부를 코드로 가르지 않는다. access 쿠키가 만료로 사라지면 Authorization 없이 보내 001 이 나오므로, "002 만 재발급" 이면 access 수명마다 로그아웃된다.
  코드는 원인을 알리는 용도다(로그 · 모니터링 · 화면 문구). 서버 컴포넌트의 `serverFetch` 는 재발급하지 않고 401 이면 로그인으로 보낸다(같은 문서 §2).

- 토큰을 보냈는데 틀리면 **비로그인으로 되는 공개 API 여도 401** 이다. 틀린 토큰을 익명으로 취급하지 않는다.

#### 게이트웨이 오류 코드

서비스까지 가지 못하고 게이트웨이에서 끊긴 요청의 코드다. 서비스가 돌려준 오류는 바꾸지 않고 그대로 중계한다.
정본은 `cloud/api-gateway` 의 `GatewayErrorCode` 다.

| 코드 | HTTP | 언제 | 클라이언트 처리 |
|------|------|------|-----------------|
| `GATEWAY_000` | 500 | 그 밖의 예상하지 못한 게이트웨이 오류 | 일시 오류 안내 |
| `GATEWAY_001` | 404 | 어느 라우트에도 없는 경로 (`/internal/**` 포함) | 경로 오류 |
| `GATEWAY_002` | 503 | 대상 서비스의 살아 있는 인스턴스가 없거나 연결에 실패했다 (요청이 서비스에 닿지 않았다) | 잠시 후 재시도 |
| `GATEWAY_003` | 504 | 서비스가 응답 타임아웃(10초) 안에 답하지 않았다 | 처리 여부 불명 (아래) |
| `GATEWAY_004` | 400 · 405 · 그 밖의 4xx | 라우팅 전에 거절한 요청 — 정규화되지 않은 경로(400), 허용하지 않는 메서드(405 + `Allow`) | 요청 수정 |
| `GATEWAY_005` | 502 | 요청을 보낸 뒤 서비스가 응답 전에 연결을 끊었다 | 처리 여부 불명 (아래) |

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
- 정렬은 enum `RequestParam`(`sortType`, `orderType`) 으로 받는다. Swagger 에 허용값이 드러난다.
- `totalCount` 를 내리는 응답은 **자르기 전 개수**여야 한다. Presenter 가 `items.size()` 로 채우지 않는다.

## 5. 보안 API 설계

- 고객·어드민 API(`adapter/in/web` · `adapter/in/admin`)의 **모든 핸들러에 `@PreAuthorize` 를 단다.** 서비스 URL 단계가 열려 있어(default-allow)
  빠뜨리면 공개 API 가 된다. 공개 API 도 `@PreAuthorize("permitAll()")` 로 명시하고, 로그인 필요는 `isAuthenticated()`, 어드민은 `hasRole('ADMIN')` 이다.
  internal API(`/internal/**`)는 JWT 없이 호출되므로 `@PreAuthorize` 를 달지 않는다 (`security-guide.md` §5-1).
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

- 소유 확인은 Facade 첫 줄에서 한다 — 주문 상세는 `orderId` 와 JWT 의 memberId 가 모두 맞아야 조회된다.
- 비회원 주문을 지원하면 주문번호 + 주문 비밀번호(해시)로 조회한다. 별도 이슈에서 확정한다.

## 6. 멱등성 (쓰기 API)

중복 클릭·네트워크 재시도가 **두 번 주문하거나 두 번 결제하는** 일이 없어야 한다.

- 주문 생성·결제 승인·결제 취소·환불은 **멱등**이어야 한다.
- 클라이언트는 `Idempotency-Key` 헤더(UUID)를 보낸다. 서버는 `(memberId, key)` 로 결과를 저장하고, 같은 키의
  재요청에는 **처음 결과를 그대로** 돌려준다. 처리 중인 같은 키가 오면 `409` 로 답한다.
- 결제 승인은 PG 쪽 멱등 키와 별개로 우리 쪽 상태(`payment.status`)로도 중복을 막는다 (`payment-guide.md` §3).

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
