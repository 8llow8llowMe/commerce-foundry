# Order Flow Guide

> 주문 · 재고 · 배송 · 클레임의 **상태 전이** 정본이다. order-service / product-service(재고) / delivery-service 가
> 이 문서를 따르고, `/order-flow-guard` 스킬이 이 문서로 점검한다. 결제 상태는 `payment-guide.md` §5.

## 1. 주문 상태

```text
PENDING_PAYMENT ──▶ PAID ──▶ PREPARING ──▶ SHIPPING ──▶ DELIVERED ──▶ PURCHASE_CONFIRMED
      │               │  └─▶ MAKING ─┘(주문 제작)            │
      ▼               ▼                                       ▼
   EXPIRED      CANCEL_REQUESTED ──▶ CANCELED         RETURN_REQUESTED ──▶ RETURNED

EXPIRED ──▶ PAID (결제 확인 + 재선점 + 확정 200 CONFIRMED)
PENDING_PAYMENT ──▶ CANCELED (결제 전 고객 취소, #16 — 선점 해제는 order-canceled 이벤트)
```

| 상태 | 뜻 | 누가 바꾸나 |
|------|-----|-------------|
| `PENDING_PAYMENT` | 주문 생성, 결제 대기 (재고 선점 중) | 고객 |
| `EXPIRED` | 결제 없이 선점 시간 초과 | 스케줄러 |
| `PAID` | 결제 완료 | payment-service (internal) |
| `MAKING` | 맞춤 제작 중 — `store.features.custom-order` 가 켜진 상품만 | 어드민 |
| `PREPARING` | 출고 준비 | 어드민 |
| `SHIPPING` | 송장 등록, 배송 중 | delivery-service (internal 출고 반영, #18 — §4) |
| `DELIVERED` | 배송 완료 | delivery-service (internal 배송 완료 반영, #18 — §4). 어드민 수동 완료(사유 필수, #17)는 예외 경로 |
| `PURCHASE_CONFIRMED` | 구매 확정 (고객 확정 또는 N일 경과 자동) | 고객 / 스케줄러 |
| `CANCEL_REQUESTED` → `CANCELED` | 출고 전 취소 (환불 포함) | 고객 요청 → 어드민/자동 승인 |
| `PENDING_PAYMENT` → `CANCELED` | 결제 전 취소 (환불 없음, 선점 해제) | 고객 (#16) |
| `RETURN_REQUESTED` → `RETURNED` | 배송 후 반품 (회수 후 환불) | 고객 요청 → 어드민 |

### 1-1. 전이 규칙 (필수)

- **상태는 도메인 메서드로만 바꾼다** — `order.markPaid(...)`, `order.startPreparing()`, `order.ship(...)`.
  허용 전이 표를 도메인 안에 두고, 표에 없는 전이는 `ORDER_STATUS_TRANSITION_INVALID` 로 거절한다.
- 엔티티에 `setStatus()` 를 두지 않는다. 어드민의 "상태 변경" API 도 도메인 메서드를 거친다 — `Order.changeStatus`(표 검사) 앞에서 `AdminStatusChangePolicy` 가
  어드민이 직접 바꾸는 전이만 허용한다(결제 완료 → 제작 중 · 상품 준비중, 제작 중 → 상품 준비중, 배송 중 → 배송 완료). 결제 · 만료 · 출고(송장) · 취소 · 반품은
  각자의 흐름이 바꾸고, 어드민 화면에서 누르면 `ORDER_023` 이다 (#17, `api-design-guide.md` §2-16).
- **고객 취소 가능 범위**: 결제 전 `PENDING_PAYMENT`(바로 `CANCELED`, #16 PR-1) · 결제 뒤 `PAID` · `MAKING`(제작 착수 전 정책은 스토어 설정) · `PREPARING`
  (`CANCEL_REQUESTED` — 환불 · 재고 복원과 함께, #16 PR-2). `SHIPPING` 부터는 반품 경로다. 만료(`EXPIRED`)는 취소할 것이 없어 409 `ORDER_020` 이다.
- **결제 전 취소** (`Order#cancelBeforePayment`): 상태 · `CUSTOMER` 이력(사유 = 취소 사유 코드) · `order-canceled` outbox 를 한 트랜잭션으로 쓴다(§3-5).
  이미 취소됐으면 아무것도 쓰지 않고 성공이다. 읽은 뒤 결제 완료 · 만료가 먼저 커밋하면 `@Version` 비교로 롤백하고, 다시 읽어 취소 완료가 아니면 409 `ORDER_020` 이다.
  **남는 경합**: payment-service 가 승인 직전 주문을 다시 확인한 뒤(`payment-guide.md` §6) 고객이 취소하면 PG 승인이 취소된 주문에 대해 끝날 수 있다 —
  PG 타임아웃으로 결제가 `UNKNOWN` · `IN_PROGRESS` 로 남은 채(주문은 결제 대기) 주문 내역에서 취소해도 같다 — 창이 분 단위다. 결제 → 주문 반영(#308)이
  `CANCELED` 주문의 결제 완료를 받으면 확정 · 재선점 없이 거절하고 전액 취소해야 한다(재선점은 결제 대기 · 만료 주문만, §3-4). 확정 뒤 `markPaid` 가 `CANCELED` 에 지면
  결제 후 복원(`stock-restorations`)으로 재고를 되돌린다 — product 는 확정된 선점의 해제 이벤트를 무시한다(§3-5).
- 결제 기한이 지났지만 만료 스윕 전인 결제 대기 주문도 고객이 취소할 수 있다 — `CANCELED` · 고객 사유로 남고, 이미 풀린 선점의 해제는 product 가 그대로 둔다(§3-5).
- 결제 전 취소는 #231 활성 시도 수를 돌려주지 않는다(창이 지나야 풀린다 — 취소 · 재주문 반복을 막는다). #426 결제 대기 수량 합은 취소 커밋과 함께 빠지지만,
  product 의 실제 선점 해제는 이벤트(비동기)라 그 사이 다시 주문하면 잠깐 재고 부족(`ORDER_016`)일 수 있다.
- 어드민의 결제 전 취소(#17 PR-2)도 `Order#cancelBeforePayment` 와 같은 트랜잭션의 `order-canceled` 를 쓴다 — 전이 표만 보는 일반 상태 변경으로 `CANCELED` 를
  만들지 않는다(선점이 TTL 까지 묶인다).
- **출고 · 배송 완료** (`Order#ship(shippedAt)` · `Order#deliver(deliveredAt)`, #18): 상품 준비중 → 배송 중은 `ship`, 배송 중 → 배송 완료는 `deliver` 로만 바꾸고
  `orders.shipped_at` · `orders.delivered_at`(timestamptz, nullable, 마이크로초로 자름)을 함께 채운다. `changeStatus(SHIPPING)` · `changeStatus(DELIVERED)` 는 도메인이 거절한다
  — 시각 없이 배송 중 · 배송 완료가 되지 않게. 어드민 수동 배송 완료(#17)도 `deliver(now)` 를 거쳐 `delivered_at` 을 채운다. 이미 그 뒤 상태면(`ship` 은 배송 중 ·
  배송 완료 · 구매 확정 · 반품, `deliver` 는 배송 완료 · 구매 확정 · 반품 — 전이 표에서 배송 중 · 배송 완료로부터 닿는 상태와 같고 `OrderTest` 가 표로 계산해 맞춘다)
  바꾸지 않은 값을 돌려주고 처음 시각을 덮지 않는다. 그 밖은 `ORDER_020` 이다 — 상품 준비중에서 바로 배송 완료하지 않는다. 취소 요청(`CANCEL_REQUESTED`) 주문도
  출고하지 않는다 — 먼저 취소를 처리한다. 취소 거절 전이(취소 요청 → 상품 준비중)는 표에 없고 #16 PR-2 에서 정한다.
  `delivered_at` 은 자동 구매 확정(#18 PR-C)의 기준이고 `idx_orders_status_delivered_at_id(status, delivered_at, id)` 로 읽는다.
  두 칸이 생기기 전에 배송 중 · 배송 완료가 된 주문은 **배포 전 운영 SQL(PR 본문)** 로 상태 이력 시각을 채운다 — 채우지 못한 행은 null 로 남을 수 있어
  상태와 묶는 check 를 두지 않는다.
- 맞춤 제작 상품은 **제작 착수 후 단순 변심 취소 불가**가 일반적이다 — 정책 문구와 함께 스토어 설정으로 둔다.
- 상태 변경 이력은 `order_status_history` 에 남긴다 (언제, 누가, 이전 → 이후, 사유). 어드민이 바꾼 것은 관리자 ID 를 남긴다.

### 1-2. 동시성

- `EXPIRED → PAID`는 만료 후 결제 확인, 같은 주문의 재선점, 선점 확정의 **200 · CONFIRMED 응답**을 모두 확인한 경우에만 허용한다. 위 도식의 만료에서 PAID로 복귀하는 조건부 경로다 (`payment-guide.md` §6 · 이 문서 §3-4). 재선점 실패 시 전액 취소한다.
- **결제 완료 전이는 `Order#markPaid(reservationId)` 하나다 (#308 PR-B1).** 결제 대기 · 만료 → `PAID` 이고 재고 선점 아이디를 확정 200 을 받은 선점으로 바꾼다 —
  그 밖의 상태(이미 결제 완료 · 취소 등)는 `OrderStatusTransitionException` 이다. 부르기 전에 진위 확인(payment 재조회) · 결제 대조 · 확정 200 을 끝낸다(§3-4).
  이력은 `OrderStatusHistory.changedByService(…, PAYMENT_APPROVED)`(주체 `SERVICE`), 결제 연결은 주문 행이 아니라 `orders.order_payment`(아래)에 남긴다.
  부르는 컨슈머(`payment-approved`)는 PR-B2 다 — 주문 상태 · 이력 · `order_payment` · inbox · 회신 outbox 를 한 트랜잭션으로 쓴다.
- **결제 반영 기록 `orders.order_payment`** (#308 PR-B1, `OrderPaymentRepositoryPort`) — 결제당 한 행(`uk_order_payment_payment_id`), 주문당 결제 완료 하나
  (`paid_order_id` 는 결과 `PAID` 일 때만 주문 아이디, 아니면 null — `uk_order_payment_paid_order_id`). 결과는 `PAID` 또는 거절 `REJECTED_*`(회신 reason
  `ORDER_CANCELED` · `ORDER_NOT_FOUND` · `PAYMENT_MISMATCH` · `STOCK_UNAVAILABLE` · `ALREADY_PAID` · `PAYMENT_NOT_DONE` 와 일대일)이고 금액 · 통화 · 승인 시각 ·
  확정 선점 · eventId 를 남긴다. `PAYMENT_NOT_DONE` 은 재조회가 DONE 이 아닌 경우(부분 취소 등 돈이 남은 상태 포함)용 값이고 판정은 PR-B2 다.
  **확정 선점(`stock_reservation_id`)은 `PAID` · `REJECTED_ORDER_CANCELED` 에만 기록한다**(`ck_order_payment_reservation_scope`) — 이중 결제에서 진 쪽(`ALREADY_PAID`)이
  이긴 결제가 확정한 같은 선점을 가리키면 PR-C 복원이 이긴 주문의 재고를 되돌려 초과 판매가 된다. `restoration_claim_id` · `restored_at` 은 확정 선점이 있는
  `REJECTED_ORDER_CANCELED`(확정 뒤 취소가 이긴 경합)에만 있고(`ck_order_payment_restoration_consistent`), PR-C 가 조건부 UPDATE(`… and restoration_claim_id is null` ·
  `… and restored_at is null`)로 채운다. 유니크 위반은 `OrderPaymentSaveException`(`PAYMENT_ALREADY_RECORDED` · `ORDER_ALREADY_PAID`, 같은 트랜잭션에 먼저 넣은 inbox 의
  중복은 `EVENT_ALREADY_PROCESSED`)으로 나오고 그 트랜잭션은 rollback-only 다 — 새 트랜잭션에서 다시 읽어 판정한다. 컨슈머 권장 순서는 주문 저장(`@Version` flush) →
  `order_payment` insert → inbox record → 회신 outbox 다.

- 주문 행에 `@Version` 을 둔다. 고객 취소와 어드민 출고 처리가 동시에 오면 하나만 성공해야 한다.
- 같은 결제 승인 이벤트는 다시 올 수 있다(릴레이 재발행 · 컨슈머 재전달) — inbox(`orders.processed_event`)가 같은 eventId 를 거르고, 다른 eventId 의 같은 결제는
  `order_payment.payment_id` 유니크가 막는다. 이미 그 결제로 `PAID` 이후 상태면 아무것도 바꾸지 않는다. 재전송 스케줄러는 없다(`payment-guide.md` §6).

### 1-3. 미결제 주문 만료 스윕 (#96)

- `OrderExpiryScheduler → OrderSchedulerUseCase → OrderSchedulerFacade → OrderExpiryProcessor` 순서다. `order.payment-expiry.sweep`의 기본값은 `enabled=true`, `interval=30s`, `batch-size=100`, `max-batches-per-run=10`이다. 간격은 양수 밀리초 이상, 건수는 각각 1–100·1–10이며 잘못된 설정은 기동을 막는다. 끄는 env 스위치는 두지 않고(product 선점 만료 스윕과 같다) 테스트 프로파일만 `enabled: false` 로 스케줄러를 끈다.
- 만료 스윕은 ShedLock을 쓰지 않는다 — 인스턴스마다 훑어도 같은 주문은 `@Version` 검사로 한 건만 커밋한다(product `StockReservationExpiryScheduler` 선례). `orders.shedlock`은 outbox 릴레이·정리용이다.
- 실행 시작 시각 하나(마이크로초로 자름 — DB `timestamp(6)` 판정과 도메인 `expire(now)` 판정을 같은 값으로)로 기한을 판정하고 `(payment_due_at, id)` 오름차순 키셋을 읽는다. 첫 배치와 다음 배치는 쿼리를 나눈다(null 커서를 `:after is null or …` 로 넘기면 PostgreSQL 이 파라미터 타입을 정하지 못한다). 다음 배치 쿼리는 OR 키셋 조건에 뜻이 같은 하한 `payment_due_at >= :afterDueAt` 을 더해 인덱스 range 시작점으로 쓴다. 조회는 아이디·기한만 투영해 out-port 가 `OrderExpiryCandidateQueryResult` 로 돌려주고, Processor 가 Facade 용 커서(`OrderExpiryCandidate`)로 바꾼다 — 다음 배치는 Processor 가 커서의 (기한, 아이디)를 풀어 넘긴다. `idx_orders_status_payment_due_at_id(status, payment_due_at, id)`를 사용한다. 처리 실패도 커서를 전진시켜 뒤 주문을 계속 처리하고, 다음 실행은 처음부터 읽어 실패 주문을 재시도한다.
- Facade는 트랜잭션 밖이고 각 주문은 별도 `REQUIRES_NEW`, 제한 5초다. 건별 트랜잭션에서 주문 전체를 재조회하고 도메인 `expire(now)`를 호출한다. 이는 서로 다른 주문을 배치 조립하는 N+1 조회가 아니라 주문별 원자적 전이 경계이며, 각 주문은 기존 집계 조회(주문·배송지·항목·입력) 4문장을 사용한다.
- 저장은 만료 전용 `OrderRepositoryPort.saveExpiry`다. 주문 헤더만 `@Version`을 포함해 `saveAndFlush`(merge의 `UPDATE … where version = ?`가 실제 보호)한 뒤 `SYSTEM`·`PAYMENT_TIMEOUT` 이력과 `order-expired` outbox를 같은 트랜잭션으로 넣는다. 배송지·항목·맞춤 입력을 다시 저장하지 않는다. 이미 만료·결제 완료·미래 기한이면 건너뛰고 동시 결제·중복 만료는 버전 충돌로 한쪽만 커밋한다. 이력·outbox 실패도 주문 상태와 함께 롤백한다. 버전 충돌(정상 경합)은 WARN, 그 밖의 실패는 ERROR 로 남기고 다음 주문으로 간다. ERROR 는 주문 아이디 · 예외 타입 · 루트 원인 타입과 `RedactedThrowable` 사본(타입 · 스택 프레임 · SQLState)만 남긴다 — 원래 예외를 넘기면 원인 사슬의 메시지(PostgreSQL `Failing row contains (...)` 같은 행 값)가 찍힌다(`coding-conventions.md` §8).
- 이벤트 계약은 §3-5다. `MESSAGING_ENABLED=false`여도 outbox를 저장하며 릴레이만 멈춘다. order-service가 `orders.outbox`·`orders.shedlock`과 미발행 수·나이 지표를 소유한다. inbox(`orders.processed_event`)는 결제 승인 컨슈머용으로 #308 PR-B1 에서 생겼다(만료 발행과 무관). 직접 HTTP 해제·커밋 후 보상·product 선점 TTL 유예 변경은 이 범위에 없다. 현재 product TTL 스윕은 안전망으로 유지한다.
- 운영 배포 전 Hibernate PostgreSQL 17 생성 스냅숏(`service/order-service/src/test/resources/ddl/order-schema.sql`)의 신규 인덱스·outbox·shedlock DDL을 적용한다. 신규 토픽과 DLT는 인프라가 미리 만들고, 메시징을 켜면 존재 여부를 확인한다. 이 변경에 FE HTTP 계약 영향은 없다.
- #308 PR-B1 이 더한 `orders.processed_event`(inbox) · `orders.order_payment` 도 같은 스냅숏에서 가져와 **DDL 적용 → 배포** 순서로 넣는다(`ddl-auto=none` 이라 앱이 만들지 않는다).
  order 의 `MESSAGING_ENABLED` 가 켜진 환경이면 `processed_event` 가 배포 전에 있어야 한다 — inbox 빈이 생겨 messaging-core 정리가 이 테이블을 지우려 하고, 없으면 주기마다
  정리 WARN 이 난다. `order_payment.result` 의 값 목록 check 는 Hibernate 가 이름 없이 만든다 — 결과 값을 더하면 그 check 를 지우고 다시 만드는 운영 DDL 이 필요하다.

## 2. 주문 금액

### 주문 생성 API (#13 C3)

- `POST /api/v1/orders`는 CUSTOMER만 호출한다. UUID `Idempotency-Key`는 필수이며 누락은 `ORDER_199`, 형식 오류는 `ORDER_198`이다. 새 주문은 201, 동일 회원·키·정규화 본문은 저장된 현재 주문으로 200을 반환한다. 식별자는 Presenter에서 문자열로 바꾼다.
- Facade는 외부 트랜잭션을 거절한다(`NEVER`). 요청 기록 확인 → 완료 재생(원격 호출 없음) → SKU 벌크 조회·공유 `OrderQuoteProcessor` 계산(상품 수량 1차 판정)·선택적 기대 총액 대조 → 기록 획득(상품별 수량 항목과 함께) → 활성 시도 수 재검사(#231) → **상품 수량 재검사(#436)** → 동일 주문 ID로 재고 단일 선점 → 주문 저장 순서다. 배송지의 도시·주를 포함한 모든 필드와 항목·맞춤 입력·기대 총액은 지문에 포함한다. 주소록은 조회하지 않는다.
- `OrderProcessor#create`는 독립된 5초 트랜잭션에서 현재 시각의 선점 유효성·요청 바인딩을 확인하고 `completeOwned`를 먼저 호출한다. C1 주문·스냅숏·생성 이력까지 같은 트랜잭션에서 커밋하며 뒤쪽 저장 실패는 요청 완료도 롤백한다. 오래된 주인·만료된 lease는 주문을 넣지 못한다.
- 선점 실패·UNKNOWN·저장 실패는 해당 버전의 lease만 조용히 끝낸다. 동일 키 재시도는 기록의 동일 주문 ID를 인수한다. 자동 재전송·보상 해제·새 주문 ID 발급을 하지 않는다. 주문 PK 중복만 소유 주문 재생으로 처리하며 다른 저장 거절은 `ORDER_021`이다.
- **회원별 활성 시도 상한 (#231).** 그 회원이 창(`order.creation.active-request-window`, 기본 30분 = product 선점 TTL) 안에 움직인(만들거나 이어받거나 끝낸 — `updated_at`) 다른 `order_request` 수(이 시도의 기록 제외)를
  기록 확인 직후 · 원격 호출 전에 한 번, 기록을 잡은 뒤 · 재고 선점 전에 한 번 센다. 상한(`order.creation.max-active-requests-per-member`, 기본 5) 이상이면 409 `ORDER_022` 다 —
  앞의 거절은 아무것도 쓰지 않고, 뒤의 거절은 기록의 lease 를 끝낸다(선점 없음). 완료 재생은 세지 않는다. 뒤의 판정이 동시 새 키의 넘침을 막는다
  (인덱스 `idx_order_request_member_id_updated_at`, 세부는 `security-guide.md` §7-3).
  이 상한은 시도 수만 묶는다 — 수량 축은 §2-3 의 상품 수량 상한(비어 있으면 엔진 기본값, 진행 중 시도 · 결제 대기 주문 수량 합산, #426 · #436)이 묶는다.
  수량도 같은 "기록 먼저 커밋 → 그 뒤 세기" 순서로 기록을 잡은 뒤 · 선점 전(시도 수 재검사 바로 뒤)에 다시 판정한다 — 넘치면 409 `ORDER_005` 이고 기존 실패 경로가 기록의 lease 를 끝낸다(선점 없음).
  **운영 권고** — 재고가 적은 상품(맞춤 · 핸드메이드 · 한정)은 상품 등록 때 `maxQuantityPerOrder` 를 기본값(10)보다 작게 둔다.
- 배송지는 입력 앞뒤 공백과 빈 선택 칸을 정규화하고 연락처의 공백·하이픈을 제거한다. 국내 연락처는 0으로 시작하는 9~11자리, 해외는 선택적 +와 숫자 7~15자리이며 해외 도시는 필수다. 형식 오류는 원격 호출 전에 `ORDER_1xx`로 반환한다. 배송지 자유 텍스트와 맞춤 입력 value 에 제어 문자 · 짝 없는 서로게이트가 있으면 400 `ORDER_143` 이다(#426 — 장바구니 `CART_115` 와 같은 `NoControlCharacters` 검증기, NUL 이 선점 뒤 저장을 500 으로 깨뜨려 주문 없는 선점을 남기는 경로를 막는다). 요청·응답·command 문자열은 개인정보·맞춤 입력을 가린다.
- 장바구니 자동 비우기는 제공하지 않는다. 결제 준비/승인 연동은 후속 범위다. 미결제 만료 스케줄러(#96)는 `paymentDueAt <= now`인 `PENDING_PAYMENT` 주문을 `EXPIRED`로 바꾼다.

### 주문 저장 기반 (#13 C1)

- `orders`, `order_shipping_address`, `order_item`, `order_item_custom_option`, `order_status_history` 다섯 테이블을 주문 하나의 저장 단위로 둔다. 주소 · SKU · 맞춤 입력 · 배송비는 주문 시점의 불변 스냅숏이며 이력은 추가만 한다. raw FK만 저장하고 JPA 연관관계는 쓰지 않는다.
- 저장은 신규 `PENDING_PAYMENT` 주문과 같은 주문 · 회원의 `null → PENDING_PAYMENT` 생성 이력만 받는다. 어댑터는 기존 트랜잭션을 필수로 요구한다(`MANDATORY`). 후속 Processor가 주문 · 스냅숏 · 이력을 한 번에 커밋하며 뒤쪽 저장 실패도 모두 롤백한다. 외부 호출은 그 트랜잭션 밖에서 한다.
- `orders.version`으로 동시 수정을 검출한다. 조회는 주문 · 회원 아이디를 함께 제한하며 스냅숏은 묶음 조회한다. 주문 PK 중복만 `ALREADY_SAVED`, 다른 주문의 선점 중복 등은 `REJECTED`다. 로그는 주문 아이디 · 예외 타입만 남기고 원인 예외 · 행 값 · 제약 문자열은 싣지 않는다.
- 요청 기록은 아래 C2a, 재고 선점 클라이언트는 C2b, 주문 생성·복구 조합은 위 C3가 제공한다. 결제 확정 연동은 후속 단계다. 운영 DDL 정본은 `service/order-service/src/test/resources/ddl/order-schema.sql`이며 C3에는 DDL 변경이 없다.

### 주문 요청 기록 기반 (#13 C2a)

- `orders.order_request`는 회원 × UUID 멱등 키마다 한 행이다. 최초 생성한 주문 ID와 요청 SHA-256 소문자 hex 지문을 보존하며 주소·맞춤 입력 원문은 저장하지 않는다. 회원·키·지문·주문 ID는 갱신하지 않는다. 기록 정리 작업은 아직 없으며 임의로 삭제해 멱등 재시도 이력을 잃지 않는다.
- `OrderRequestProcessor`는 외부 트랜잭션을 거절한다(`NEVER`). 획득·인수·기한 종료는 어댑터의 독립된 짧은 트랜잭션(`REQUIRES_NEW`, 5초 상한)이다. INSERT 유니크 충돌을 실패 트랜잭션 종료 뒤 판정하므로 후속 조회가 중단된 PostgreSQL 트랜잭션을 재사용하지 않는다.
- 같은 키·다른 지문은 기존 `ORDER_019`, 유효한 처리 기한은 `ORDER_018`이다. 정확히 기한이 되는 시각부터 동일 주문 ID로 인수할 수 있다. 완료된 같은 지문은 기한과 무관하게 재생 대상이다. 시각은 PostgreSQL timestamp(6)에 맞춰 마이크로초로 절삭한다.
- 설정 `order.creation.processing-lease`는 기본 30초, 1초~5분이다. `order.creation.active-request-window` 는 이 값 이상, `order.creation.completion-margin`(기본 10초, #436)은 0 초과 · 이 값 미만이어야 기동한다. 처리 중 중복 호출을 줄이는 기한이며 원격 재고 작업의 소유권을 보장하지 않는다. C2b/C3에서 원격 호출 시간과 대조한다. 기한만 갱신해도 버전을 올리고, 인수·종료·완료 UPDATE는 회원·키·지문·주문 ID·버전·상태를 함께 대조한다.
- `completeOwned`는 기존 쓰기 트랜잭션을 필수로 요구한다(`MANDATORY`). 완료 시점에도 유효한 기한과 동일 버전이어야 한다. **C3는 소유권 검증/완료를 먼저 하고 C1 주문 저장을 같은 트랜잭션에서 수행해야 한다.** 호출자 Processor가 짧은 트랜잭션 시간 상한을 설정한다(MANDATORY 자체는 기존 트랜잭션의 시간 상한을 바꾸지 않는다). 요청 완료만 단독 커밋하거나 실패를 잡고 부분 주문을 커밋하면 안 된다. 저장 실패 시 요청 상태·주문·스냅숏·이력을 모두 롤백한다.
- 기한을 잃은 옛 시도는 다른 시도의 기한을 끝내거나 완료할 수 없다. 저장 장애로 자신의 기한 종료에 실패하면 원래 오류를 유지하고 고정 기한 만료 뒤 같은 키·주문 ID로 재시도한다. 원격 결과 미확정에는 새 주문 ID 발급이나 자동 선점 해제를 하지 않는다. 이후 C3에서도 옛 시도가 공유 선점을 해제하지 않도록 해야 한다.
- **상품별 수량 항목 (#436).** `orders.order_request_item` 은 기록 하나의 상품별 수량 합(기록 아이디 · 상품 아이디 · 수량, `uk_order_request_item_order_request_id_product_id`, `quantity >= 1`)이다. 원문 · SKU · 맞춤 입력 · 개인정보는 두지 않는다. 새 기록을 넣는 같은 `REQUIRES_NEW` 트랜잭션에서 기록 → 항목 순으로 넣고, 키 유니크 충돌이면 항목도 함께 롤백된다. 인수 · 기한 종료 · 완료는 항목을 건드리지 않는다(같은 본문이라 같은 값이다). 진행 중 수량 계수(`sumInFlightQuantitiesByProduct`)는 기록 × 항목 theta join JPQL 한 문장으로, 회원 · 처리 중 · lease 가 살아 있음 · #231 창 안(`idx_order_request_member_id_updated_at` 범위) · 자기 기록 제외 조건이다.
- HTTP API·재고 호출·주문 생성 조합은 위 C3가 담당한다. 미결제 만료 스케줄러는 아래 #96 계약을 따른다. C2a 신규 테이블 운영 DDL은 Hibernate 생성 스냅숏 중 `order_request` 부분이며 사용자 적용 후 배포한다. #436 의 `order_request_item` 도 같은 스냅숏의 해당 부분을 배포 전에 적용한다.

### 재고 선점 호출 기반 (#13 C2b)

- `StockReservationPort` · `OrderStockProcessor`는 선점만 제공한다. 동일 SKU 줄은 `Math.addExact`로 수량을 합치고 SKU 아이디 오름차순으로 한 번 보낸다. Processor는 `NEVER`로 기존 트랜잭션을 거절하므로 원격 호출을 DB 트랜잭션에 포함하지 않는다.
- Feign은 클라이언트별 `contextId`와 서비스 토큰을 쓰며 기존 connect 2초/read 3초, `product-service` 서킷(4xx 제외)을 따른다. 전용 설정의 `Retryer.NEVER_RETRY`와 기본 `Client.Default`의 고정 길이 스트리밍 POST를 전제로 자동 재전송하지 않는다. 전송 구현을 바꾸면 재시도 전략을 다시 확인한다. HTTP 로그는 끈다.
- HTTP **409**의 `PRODUCT_021/022/023/026`만 각각 `ORDER_016/003/017/018`로 번역한다. `022`는 이후 숨겨진 상품명도 노출하지 않도록 일반 문구를 쓴다. 그 밖의 4xx는 `REJECTED`, 타임아웃 · 전송 실패 · 5xx · 서킷 오픈 · 디코딩 실패는 `UNKNOWN`이며 둘 다 `ORDER_002`다. 로그에는 정제한 resultCode · 상태 · 예외 타입만 싣고 원격 본문 · 메시지 · 원인 throwable은 싣지 않는다.
- 성공 봉투, 양수 선점/주문 아이디, 요청과 같은 주문 아이디, `RESERVED`, null이 아닌 미래 만료 시각, 요청과 정확히 같은 SKU/수량(오름차순 · 중복/누락/추가/null/비양수 없음)을 확인한다. `deducted`는 product의 재고 관리 정보라 주문은 판단하지 않는다. 계약 위반은 `UNKNOWN` → `ORDER_002`이며 선점이 생겼을 가능성을 보존한다.
- 결과 불명에 자동 재시도 · 자동 해제 · 새 주문 ID 발급을 하지 않는다. **C3는 request-record의 같은 orderId로 재선점하여 복구한다.** 확정/해제 호출은 후속 범위다.

- 주문 금액은 **order-service 가 서버에서 계산한다**: Σ(SKU 판매가(기본 판매가 + 옵션 추가금) × 수량) − 할인 + 배송비.
  SKU 판매가에 옵션 추가금이 이미 들어 있다(product-service `SkuInternalProcessor` · order `CatalogSku.salePrice`) — 추가금을 따로 더하지 않는다.
- 가격·옵션 추가금은 주문 시점에 product-service 에서 조회하고 **주문 항목에 스냅숏으로 저장**한다.
  나중에 상품 가격이 바뀌어도 지난 주문은 바뀌지 않는다. 상품명·옵션명도 스냅숏한다.
- 프론트가 보낸 금액·가격은 쓰지 않는다. 주문서 미리보기(`POST /api/v1/orders/quotes`)와 실제 주문 생성은
  같은 계산 코드를 쓴다 (§2-3).
- 배송비는 delivery-service 의 정책(국내 기본/도서산간/무료배송 기준, 해외 국가·무게별)으로 계산한다.
  order-service 는 `POST /internal/v1/shipping-fees/quotes` 의 `shippingFee` 를 더하고 주문에 스냅숏한다. 계산 계약(국내 #11 · 해외 #22):

  | 항목 | 규칙 |
  |------|------|
  | 범위 | `countryCode` 가 KR 이면 국내 정책, 그 밖이면 해외 정책. 응답 `scope` 가 `DOMESTIC` / `INTERNATIONAL` 이다 |
  | 기준 금액(`orderAmount`) | **할인 후 상품 합계(배송비 제외)**. delivery 는 받은 값을 비교만 한다 |
  | 무료배송 | 기준 금액 **이상**이면 국내는 기본 배송비 0, 해외는 기본료 + 무게 요금 0. 기준이 없으면(null) 무료배송 없음 |
  | 도서산간 추가금 (국내) | 우편번호(5자리, KR 필수) 구간에 들면 **무료배송이어도 별도 부과**. 해외에는 없다 |
  | 해외 배송비 | **기본료 + 무게 요금**. 국가가 든 국가 그룹에서 상한(`maxWeightGrams`)이 무게 이상인 가장 작은 구간의 요금 — **상한 포함**, 상한 + 1g 은 다음 구간 |
  | 해외 무게(`weightGrams`) | KR 외 국가면 **필수**(1g 이상, 없으면 400 `DELIVERY_118`). order-service 가 SKU 무게 × 수량 합으로 보낸다(#13). KR 이면 무시한다. 해외 우편번호는 선택(비거나 공백뿐이면 없는 값)이고 계산에 쓰지 않는다 |
  | 가장 큰 구간 초과 | 400 `DELIVERY_011` — 그 그룹으로는 배송할 수 없는 무게다 |
  | 정책 미설정 | 409 `DELIVERY_001` — 0원 배송으로 열리지 않게 주문을 막는다 |
  | 국가 코드 형식 오류 | 400 `DELIVERY_105`(없음) · `DELIVERY_106`(대문자 두 글자가 아님) — 본문 검증 |
  | 배송할 수 없는 국가 | 400 `DELIVERY_002` — 형식은 맞지만 배송할 수 없는 국가: 해외 배송 플래그(`store.features.international-shipping`)가 꺼졌거나, 어느 국가 그룹에도 없는 국가 |
  | 검증 순서 | 본문 검증(`105` · `106` · `118` · `119` · `120` 등)이 플래그 · 정책 확인보다 먼저다 — 플래그가 꺼져 있어도 무게 없는 해외 요청은 `118` 이다 |
  | 통화 불일치 | 400 `DELIVERY_003` — 아래 #70 결정 |

  **정책 통화가 견적 통화다 (#70).** 주문 통화가 그 범위 정책의 통화와 다르면 견적하지 않는다(`DELIVERY_003`) — **환산하지 않는다**.
  영문 스토어(USD) 주문 + 국내(KR) 배송지는 국내 정책(KRW)과 통화가 달라 거절된다. 국내 배송은 국내 정책 통화의 주문만 받는다.
  환율 기준(시점 · 출처)을 delivery 가 정하면 주문 금액을 다시 만들 수 없기 때문이다. 통화별 상품 가격은 #20 이 다룬다.
  같은 이유로 **저장된 정책의 통화는 PUT 으로 바꾸지 않는다**(409 `DELIVERY_012`) — 어드민 실수 한 번에 그 통화의 모든 주문이 003 으로 막히지 않게.
  통화를 바꾸려면 운영 절차(DDL)로 한다. 엔진 재사용을 위해 통화를 KRW 로 고정하지는 않는다 — 정책을 처음 만들 때 고른다.

  order-service 는 견적 응답의 기본 배송비 · 무료배송 기준 · 도서산간 추가금 · 정책 버전(해외는 국가 그룹 이름 · 무게 요금도)을 **주문에 스냅숏한다**.
  §5 의 "무료배송 기준 미달 시 재부과" 는 이 스냅숏(주문 시점 기준 · 기본 배송비)으로 order-service 가 계산하고,
  **현재 정책으로 다시 견적하지 않는다** — 주문 뒤 정책이 바뀌어도 지난 주문의 환불액은 바뀌지 않는다. 배송지가 그대로면 도서산간 추가금은 다시 매기지 않는다.

### 2-1. 장바구니 (#12)

장바구니는 주문 금액의 입력이 아니다 — 가격 · 상품명을 저장하지 않고, 조회할 때마다 product-service 의 현재 SKU 로 보여 준다. 주문 생성은 위 규칙대로 다시 조회 · 스냅숏한다.

| 항목 | 규칙 |
|------|------|
| 소유 | 로그인 회원 전용, 회원당 하나 (`/api/v1/carts/me/**`). 회원은 JWT 주체로만 정한다. 비로그인 장바구니는 프론트 로컬에 둔다 |
| 저장 | PostgreSQL `orders.cart_item` · `orders.cart_item_custom_option` (Redis 아님) |
| 줄 동일성 | (회원, SKU, 맞춤 제작 입력 해시). 같은 줄을 다시 담으면 수량을 더한다. 키 · 값은 앞뒤 공백을 자르고 Unicode NFC 로 정규화한다(저장값과 해시 모두 — NFD 로 온 한글도 같은 줄). 해시는 fieldKey 순으로 정렬한 정규화 입력의 SHA-256, 입력이 없으면 빈 문자열. 유니크 키가 동시 담기 중복을 막는다(409 `CART_007`) |
| 상한 | 줄 50개, 줄당 수량 1~99 이고 상품의 `maxQuantityPerOrder`(1회 주문 최대 수량 — 상품 컬럼)가 더 작으면 그것. 장바구니는 **줄 단위로만** 막는다 — 같은 상품의 다른 SKU(S · M) · 다른 맞춤 입력 줄의 합은 보지 않으므로, 담긴 그대로 주문하면 상품 단위 상한(§2-3 `ORDER_005`)에서 거절될 수 있다. 상품 상한이 비어 있을 때 장바구니는 99 지만 주문은 엔진 기본값(#426, 기본 10)이다. **50줄은 느슨한 상한이다** — 줄 수를 센 뒤 넣으므로 동시 담기 · 병합에서 소폭 넘을 수 있다. SKU 조회는 100개씩 나눠 불러 넘어도 조회 · 쓰기 응답 · 삭제가 막히지 않는다 |
| 담기 거절 | 없는 SKU 404, `purchasable=false`(품절 · 판매 종료 · 숨김 · 삭제) 409, 맞춤 제작이 아닌데 입력이 있음 400, 수량 · 줄 상한 초과 409. 입력값이 상품 입력 항목 정의에 맞는지는 주문서 계산(#13 · #97)이 본다. 장바구니 담기에는 같은 대조를 하지 않는다 — 담긴 뒤 정의가 바뀌거나 치수가 빠지면 주문서에서 400 으로 거절된다 |
| 입력 문자 | 맞춤 제작 value 에 제어 문자(`\p{Cc}` — C0 · DEL · C1, NUL · 개행 · 탭 포함)나 짝 없는 서로게이트가 있으면 위치와 상관없이 400 `CART_115` 다(#232). 앞뒤 공백을 자르기 전 원문으로 본다 — `strip()` 은 탭 · 개행은 지우지만 NUL 은 남긴다. NUL 은 PostgreSQL text 가 거절해(22021) 저장이 500 이 되고, 짝 없는 서로게이트는 pgjdbc 가 `?` 로 바꿔 입력과 다른 값이 저장된다. 서식 문자(`\p{Cf}`)는 막지 않는다 — 이모지 ZWJ 시퀀스가 여기 든다. fieldKey 는 형식(`CART_109`)이 이미 막는다. 판정은 요청 DTO 검증기 `NoControlCharacters` 이고 도메인 `CustomOption` 은 다시 보지 않는다(길이 · fieldKey 형식과 같은 요청 형식 규칙). 병합도 요청 전체 400(`items[i].customOptions[j].value`)이고 항목 단위로 건너뛰지 않는다. **주문 생성(#13)의 맞춤 입력에는 아직 이 검사가 없다** |
| 수량 변경 · 삭제 | 품절 · 판매 종료 줄도 허용한다. 수량 변경은 트랜잭션 두 구간(소유 확인 → 원격 상한 조회 → 다시 읽어 쓰기)이라 사이의 변경은 마지막 쓰기가 이긴다. `@Version` 409(`CART_007`)는 쓰기 구간 안의 동시 수정에서만 난다 |
| 조회 상태 | SKU 없음 → `STOP_SELLING`, `purchasable` → `ON_SALE`, 판매 상태 `SOLD_OUT` → `SOLD_OUT`(숨긴 상품이라도 품절이면 품절), 그 밖(판매 종료 · 품절이 아닌 숨김) → `STOP_SELLING`. 삭제(soft delete)된 상품의 SKU 도 행이 오고 숨김 + 판매 종료와 같은 결이라 `STOP_SELLING` 이다 — "없는 SKU" 가 되지 않아 줄의 이름 · 가격이 남는다(`api-design-guide.md` §2-13). 합계는 `ON_SALE` 이고 수량이 현재 상한 안인 줄만 더한다(없으면 null) |
| 병합 | 로그인 직후 로컬 장바구니를 한 번 보낸다(최대 50개, 오래 전에 담은 것부터 — 새 줄은 요청 순서로 아이디가 커져 나중 항목이 위에 보인다). 담을 수 없는 항목은 **항목 단위로** 건너뛰어 `skippedItems: [{index, skuId, reason}]` 로 알린다 — reason 은 `SKU_NOT_FOUND` · `NOT_PURCHASABLE` · `CUSTOM_OPTION_NOT_ALLOWED` · `LINE_LIMIT_EXCEEDED` · `QUANTITY_LIMIT_EXCEEDED`(1회 주문 최대 수량 0) 순으로 판정한다. 같은 SKU 라도 항목마다 따로 판정한다. 같은 줄은 더한 뒤 상한에서 자른다(거절하지 않는다, 이미 넘은 줄을 줄이지 않는다). **멱등하지 않다** — 재전송하면 한 번 더 더해진다. 결제 · 재고와 무관하고 상한이 피해를 제한해 멱등 키를 두지 않는다 |
| 원격 호출 | SKU 는 `catalog` 컨텍스트(`CatalogSkuProcessor`, 주문 생성과 공용)가 `GET /internal/v1/skus` 로 몇 줄이든 100개씩 묶어 부른다(보통 한 번, 장바구니가 비면 호출하지 않는다). Facade 에 트랜잭션을 걸지 않고 DB 구간만 Processor 에서 좁힌다. product-service 장애는 503 `CART_008` 이다(`CATALOG_001` 을 `CartExceptionHandler` 가 번역) — 쓰기 뒤 장바구니 재조회가 실패해도 쓰기는 커밋돼 있다 |

### 2-2. 해외 주문의 SKU 무게 · HS 코드 (#80)

해외 배송비 견적(무게 구간)과 통관 신고(HS 코드 · 신고 금액)의 입력은 SKU 의 **배송 무게**와 **HS 코드**다. 둘 다 해외 판매 상품만 필수라 비어 있을 수 있다.
**비어 있는 값을 거절하는 쪽은 order-service 다** — product-service 는 null 을 그대로 내리고, 배송비 견적에서 delivery-service 는 order-service 가 만든 총 무게만 받는다(통관 품목은 아래 "통관 정보").

| 항목 | 규칙 |
|------|------|
| 저장 (product-service) | `product.sku.weight_grams` integer(그램, 1 이상) · `hs_code` varchar(10)(숫자 6~10자리), 둘 다 null 허용 — null 은 미입력이다. 도메인(`Sku` · `HsCode`)과 DB check(무게 양수 `ck_sku_weight_grams_positive`, HS 코드 숫자 6~10자리 `ck_sku_hs_code_format`)가 같은 규칙을 막는다 — 운영 SQL 로 형식이 틀린 값이 들어가 그 SKU 를 읽는 상세 · internal 조회 · 재고 선점(국내 주문 포함)이 500 이 되지 않게. 점 · 하이픈 · 공백(`6211.42.00`)은 고쳐 받지 않는다 — 정규화는 입력 쪽(#9 · #19)이 한다 |
| 내려주기 | `GET /internal/v1/skus` 항목의 `weightGrams`(number, nullable) · `hsCode`(string, nullable). 미입력이면 키를 빼지 않고 `null` 이다. 기본값으로 채우지 않고 거절하지도 않는다 — 국내 주문에는 필요 없는 값이다. 고객 API(상품 상세)에는 내리지 않는다 |
| 해외 주문 | **배송 국가 ≠ KR** 인 주문 하나로 판정한다(통화는 보지 않는다). delivery 견적이 해외 정책(응답 `scope` = `INTERNATIONAL`, #22)으로 계산하는 주문과 같다. 통화와 배송 국가의 관계는 #22 · #70 을 따른다 |
| 거절 (order-service, #13) | 해외 주문의 주문서 미리보기 · 주문 생성에서 항목 중 **무게 또는 HS 코드가 null 인 SKU 가 하나라도 있으면 거절**한다 — 장바구니 `purchasable=false` 와 같은 결의 409, 어느 SKU 인지 알 수 있게 한다(`ORDER_007`, §2-3). delivery 견적을 부르기 **전에** 거절한다 — 무게 없이 부르면 delivery 의 무게 필수 오류(`DELIVERY_118` 400, #22)가 나와 원인이 흐려진다. 다만 **배송할 수 없는 국가**(해외 배송 꺼짐 · 정책에 없는 국가 — delivery `DELIVERY_002`)라는 사유가 무게 미입력보다 앞선다 — 해외 판매 전에는 대부분의 SKU 가 미입력이라 순서가 바뀌면 엉뚱한 사유가 보인다. 판정 순서는 배송 가능 국가 → 무게 · HS 코드 미입력 → 총 무게 → 견적이고, 국가 판정은 미입력 SKU 가 있을 때만 확인용 견적(1g)으로 먼저 한다(§2-3). 국내 주문은 두 값이 비어 있어도 거절하지 않고 견적에 무게를 싣지 않는다 — 값이 있으면 형식은 위 저장 규칙대로 늘 맞다 |
| 총 무게 (#13 · #22) | order-service 가 **Σ(SKU `weightGrams` × 수량)** 을 `long` 으로 더해 견적 요청 `weightGrams`(Integer)로 보낸다. int 범위를 넘으면 견적을 부르지 않고 배송할 수 없는 무게로 거절한다 — delivery 의 가장 큰 구간 초과(`DELIVERY_011`)를 받았을 때와 같은 응답(`ORDER_015`)이다. 포장재 무게는 따로 더하지 않는다 |
| 스냅숏 (#13) | 주문 항목에 단위 무게 · HS 코드를 **주문 시점 값으로 저장**한다(가격 · 상품명과 같은 규칙, 국내 주문은 null 일 수 있다). 총 무게는 배송 견적 스냅숏과 함께 주문에 저장한다. 주문 뒤 SKU 값을 고쳐도 지난 주문은 바뀌지 않는다 |
| 통관 정보 (#18) | 송장 · 출고는 통관 신고 품목(HS 코드 · 수량 · 단위 무게 · **신고 금액 = 주문 항목의 주문 시점 금액**, 주문 통화)을 **주문 항목 스냅숏에서** 만든다 — delivery-service 가 order-service internal 로 읽고 product-service SKU 를 다시 읽지 않는다. 할인 반영 금액으로 신고할지는 후속 이슈(통관 품목 internal)가 정한다 — 신고 금액은 실거래가가 원칙이다 |
| 입력 | 어드민 상품 등록 · 수정(#9)에 입력 칸을 추가한다 — 그 전에는 운영 SQL 로 넣는다. 카페24 이관(#19)의 원천 필드는 **미정**이다 — 카페24 상품 무게 필드의 단위 · HS 코드 유무를 #19 에서 확인해 매핑하고, 없으면 이관 뒤 어드민이 채운다 |

### 2-3. 주문서 계산 (#13)

주문서 미리보기와 주문 생성은 **같은 계산**(`OrderQuoteProcessor#quote`)으로 금액을 만든다. SKU 는 Facade 가 `catalog`(`CatalogSkuProcessor#findSkus`)로 한 번 읽어
넘기고(컨텍스트 간 Processor 주입 금지 — `architecture-guide.md` §3), 계산 Processor 는 배송비 견적(delivery)만 부른다. DB 를 쓰지 않아 트랜잭션이 없다.

판정은 아래 순서로 **처음 걸리는 단계 하나**로 거절하고, 그 단계에 걸린 줄은 모두 모아 고객이 알아볼 이름("상품명(옵션명)", 옵션이 없으면 상품명, 없는 SKU 는
"찾을 수 없는 상품")으로 메시지에 싣는다. SKU 아이디 · product-service 메시지는 넣지 않는다. 품절이 아닌데 `purchasable=false` 인 SKU(숨김 · 판매 종료 · 삭제 —
응답으로는 가를 수 없다)는 "판매하지 않는 상품" 이다 — 아이디만 넣은 요청으로 숨긴 상품명이 새지 않게. 품절은 이름을 알린다(§2-1 조회 상태와 같은 경계).
중복 줄(1)이 판매 가능(2)보다 먼저라 중복 메시지도 이 규칙을 거친다.

1. 맞춤 제작 입력을 정규화(§2-1 "줄 동일성" 과 같은 규칙 — order 의 `OrderCustomOptions`)한 뒤 같은 (SKU, 입력 해시) 줄이 두 번 이상 → `ORDER_008`
2. 없는 SKU · `purchasable=false` → `ORDER_003`
3. 맞춤 제작 상품이 아닌데 입력이 있다 → `ORDER_004`
4. 맞춤 제작 상품이면 입력을 그 상품의 항목 정의와 대조한다(#97). 정의는 `GET /internal/v1/skus` 의 `customOrderFields`
   (`fieldKey` · `inputType` · `required` · `min` · `max` · `step`)이고, 맞춤 제작이 아니면 빈 배열이다. product-service 는 그 페이지의 맞춤 제작 상품 아이디로
   in 절 한 번만 읽는다. 거절은 400 이고 `fieldErrors[].field` 는 `items[i].customOptions[j].value` 다. 빠진 필수 항목은 그 줄에 있던 입력 개수 다음 칸 번호다.
   필수 누락 `ORDER_132`, 모르는 fieldKey `ORDER_133`, NUMBER 가 숫자 아님 `ORDER_134`, 범위 `ORDER_135`, step `ORDER_136`, TEXT 길이 `ORDER_137`.
   대표 코드는 요청 줄 순서 → 입력 순서 → 빠진 필수(정의 순서)의 첫 위반이다. 입력값(치수 · 메모)은 메시지 · 로그에 넣지 않는다. 장바구니는 이 단계를 하지 않는다(§2-1).
5. **상품 단위** 수량 합 > 상품의 `maxQuantityPerOrder` → `ORDER_005`. 상한이 상품 컬럼이라 SKU 별로 세면 S 2 + M 2 로 "1인 2벌" 이 우회된다.
   - **비어 있으면(null) 엔진 기본값** `order.quantity-limit.default-per-product`(기본 10, 1–99)이다 (#426) — 비어 있다고 무제한이면 회원 한 명이 시도 하나로
     재고가 적은 상품을 전부 선점한다. 상품에 값을 두면 그 값이 이긴다(기본값보다 커도 된다 — 대량 구매 상품).
   - **주문 생성은 그 회원이 이미 쥔 같은 상품 수량을 더한다** — (1) 진행 중인 다른 시도의 요청 기록 수량(`order_request_item` — 처리 중 · lease 가 살아 있음 · #231 창 안,
     이 시도의 기록 제외) → (2) 결제 대기(`PENDING_PAYMENT`) 주문 수량 **순서로** 읽어 합친다(#436). 둘 다 선점을 쥐었거나 쥐려는 수량이라, 주문을 나누거나 새 키로 동시에 몰아
     상한을 넘지 못한다. 메시지는 "상품명(최대 N개, 결제 대기 주문에 M개)" 이고 M 에 진행 중 수량이 합쳐진다(문구는 그대로 — FE 영향 없음).
     결제 완료 주문은 세지 않는다(재고를 묶지 않는다 — 1인 누적 구매 제한이 아니다).
   - **판정은 두 번이다** — 견적 단계(1차, 인수면 자기 기록 제외)와 기록을 잡은 뒤 · 선점 전(확정, #231 시도 수 재검사 바로 뒤, 자기 기록 제외).
     #231 과 같은 "기록 먼저 커밋 → 그 뒤 세기" 순서라 잠금 없이 직렬화된다 — 통과한 시도 중 마지막으로 센 시도는 다른 통과 시도의 기록(또는 그 주문)을 반드시 보므로
     통과 합 ≤ 상한이다. 동시 몰림에서 서로를 세어 함께 거절될 수 있다(#231 과 같은 허용). 확정 판정에서 넘치면 기존 실패 경로가 lease 를 끝낸다(선점 없음).
   - **(1) → (2) 순서는 규칙이다.** 완료는 기록 COMPLETED 와 주문 INSERT 가 한 트랜잭션이라 기록을 먼저 읽으면 그 사이 완료된 시도는 어느 한쪽에 반드시 보인다
     (둘 다 보이면 과잉 거절 쪽). 반대로 읽으면 어느 쪽에도 안 세는 구멍이 생긴다. 두 문장은 각자 짧은 읽기 트랜잭션이다(`OrderProcessor#heldQuantitiesByProduct`).
   - **lease 가 끝난 기록은 세지 않는다** — 실패한 시도는 lease 를 바로 끝내므로 고객이 새 키로 곧바로 다시 주문할 수 있다. 배포 전에 만든 기록(항목 없음)은 0 으로 센다(30분 창 안 한정).
     남는 틈은 **선점 뒤 실패 잔여** — 저장 실패 · 선점 결과 불명(UNKNOWN) · lease 를 넘긴 선점(lease 가 끝난 뒤 돌아온 선점 응답)으로 기록은 세지 않는데 선점은 TTL 까지 남는 것 —
     뿐이고 #231 시도 상한으로 유계다. 전제: 원격 호출 타임아웃 합 + 저장 트랜잭션 timeout(5초) < `order.creation.processing-lease`(기본 30초).
   - **완료는 lease 가 `order.creation.completion-margin`(기본 10초) 넘게 남았을 때만 한다** — 완료 커밋이 lease 끝 뒤로 밀리면 다른 시도의 (1) 은 이 시도를 lease 끝남으로 빼고
     (2) 는 커밋 전 주문을 못 봐 둘 다 통과할 수 있다. margin 은 저장 timeout(5초) + 인스턴스 간 시계 차보다 크게 두고, 0 초과 · `processing-lease` 미만이어야 기동한다.
     margin 안이면 완료가 인수당한 것과 같이 거절돼 저장 전체가 롤백되고 lease 를 끝낸 뒤 409 `ORDER_018` 이다 — 같은 키 재시도가 같은 주문 아이디로 이어받아
     기존 선점(200)으로 다시 저장한다.
   - **주문서 미리보기는 진행 중 · 결제 대기 수량을 더하지 않는다**(회원 조회 없이 같은 계산 코드를 쓴다) — 미리보기를 통과해도 주문 생성이 `ORDER_005` 일 수 있다.
   같은 SKU 를 다른 맞춤 입력으로 나눈 줄도 더한다. 장바구니는 줄 단위로만 막으므로(§2-1) 여기서 거절될 수 있다
6. SKU 판매가 통화가 섞였다 → `ORDER_006`. 주문 통화 = SKU 판매가 통화다(#20 전에는 단일 통화)
7. 줄 금액 = 단가 × 수량, 상품 합계 = Σ 줄 금액 (`Money.times` · `plus`)
8. 배송 국가로 국내 · 해외를 가른다(KR 이면 국내, 통화는 보지 않는다 — 판정은 shared-commerce `ShippingScope.ofCountry` 한 곳, #144).
   형식이 아니면 빈 값인데, 요청 검증이 먼저 거르므로 빈 값은 조립 버그(`IllegalArgumentException`)다. 해외면 §2-2 순서대로:
   1. 무게 또는 HS 코드가 null 인 SKU 가 있으면 **확인용 견적**(아래)으로 배송 가능 국가를 먼저 본다 — 배송할 수 없으면 `ORDER_013`, 있으면 `ORDER_007`.
      국가 판정 전 사유(정책 미설정 · 통화 불일치)로 거절되면 그 코드(`ORDER_012` · `ORDER_014`)다
   2. 무게 < 1 은 product-service 응답 계약 위반이라 error 로그 + `ORDER_002`(503). 모든 줄을 본 뒤 다음 단계로 간다
   3. Σ(무게 × 수량)을 `long` 으로 더해 int 를 넘으면 견적을 부르지 않고 `ORDER_015`

   국내면 무게 · HS 를 보지 않고(비어 있어도 통과) 견적에 무게를 싣지 않는다(SKU 에 값이 있어도).
9. 배송비 견적 — 기준 금액은 상품 합계. 국내는 우편번호, 해외는 총 무게와 (있으면) 우편번호를 싣는다. 견적 결과는 주문 배송비 스냅숏 값(`ShippingFeeQuote`, 해외는 총 무게 포함)으로 바꾼다.
   응답의 배송비가 delivery 산식(국내 (무료면 0, 아니면 기본 배송비) + 도서산간, 해외 무료면 0, 아니면 기본료 + 무게 요금)과 다르거나, 무료배송 판정이
   "기준이 있고 기준 금액 ≥ 기준" 과 맞지 않으면 계약 위반 `ORDER_011`(503)이다 — 배송비를 추측하지 않는다(`ShippingFeeInternalResponseSupport`, domain 생성자도 산식을 다시 본다)
10. 총액 = 상품 합계 + 배송비

**해외 국가 판정을 확인용 견적으로 하는 이유** — 해외 배송 플래그 · 국가 그룹은 delivery 에만 있고, delivery 견적은 본문 검증(`DELIVERY_118` 무게 필수)이 플래그 · 정책 확인보다
먼저라(§2 "검증 순서") 무게 없이 국가만 물을 수 없다. 그래서 **미입력 SKU 가 있을 때만** 같은 견적 엔드포인트에 총 무게 **1g** 으로 한 번 묻는다.
delivery 해외 견적의 판정 순서는 해외 배송 플래그(`002`) → 정책 없음(`001`) → 통화 불일치(`003`) → 국가 그룹(`002`) → 무게 구간(`011`)이다. 그래서
`DELIVERY_002`(→ `ORDER_013`)면 배송할 수 없는 국가, `DELIVERY_011` · 200 이면 국가는 배송할 수 있어 미입력(`ORDER_007` 409)으로 답한다.
`DELIVERY_001` · `003` 은 국가를 판정하기 전 사유라 그 코드(`ORDER_012` · `ORDER_014`)로 답한다 — SKU 를 채워도 실제 견적이 같은 이유로 실패한다.
그 밖의 실패(`ORDER_011` 503)는 그대로 던진다 — 배송 가능 여부를 추측하지 않는다. 해석은 `ShippingFeeClientAdapter#isDestinationSupported` 한 곳에 있고 Processor 는 1g 사실을 모른다.
delivery 의 번역 대상(`ShippingFeeInternalResponseSupport`)이 늘면 이 분류도 같이 본다.
무게 · HS 가 모두 있으면 확인 없이 바로 견적한다 — 앞 단계 사유가 없으니 배송할 수 없는 국가는 그 견적의 `DELIVERY_002` 로 드러난다.
무게 · HS 가 모두 있으면 무게 계약 위반(< 1, 503) · 총 무게 int 초과(`ORDER_015`)를 견적 전에 판정한다 — §2-2 순서의 "총 무게" 단계라 배송할 수 없는 국가(견적의 `002`)보다 앞선다.
delivery 에 배송 가능 국가 조회를 새로 여는 안(서비스 변경 · PR 추가)과 해외 배송 플래그를 order 에 복제하는 안(정책 외 국가를 못 가린다)은 쓰지 않았다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `ORDER_002` | 503 | product-service 장애(catalog `CATALOG_001` 번역) · SKU 응답 계약 위반(해외 주문의 무게 1 미만) |
| `ORDER_003` | 409 | 없는 SKU · 구매할 수 없는 SKU(품절 · 판매 종료 · 숨김) — 이름 목록 |
| `ORDER_004` | 400 | 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다 — 이름 목록 |
| `ORDER_005` | 409 | 상품 단위 수량 합(주문 생성은 진행 중 시도 · 결제 대기 주문 수량 포함 — 기록을 잡은 뒤 · 선점 전에 다시 판정, #436)이 상품 상한(비어 있으면 엔진 기본값)을 넘는다 — "상품명(최대 N개)" · "상품명(최대 N개, 결제 대기 주문에 M개)" 목록 |
| `ORDER_006` | 409 | 판매가 통화가 섞였다 |
| `ORDER_007` | 409 | 해외 주문에 무게 · HS 코드 미입력 SKU 가 있다 — 이름 목록 |
| `ORDER_008` | 400 | 같은 (SKU, 맞춤 제작 입력) 줄이 요청 안에 중복 — 이름 목록 |
| `ORDER_011` | 503 | delivery 장애 · 번역하지 않는 4xx · 견적 응답 계약 위반 |
| `ORDER_012` | 409 | 배송비 정책 미설정 (`DELIVERY_001`) |
| `ORDER_013` | 400 | 배송할 수 없는 국가 (`DELIVERY_002`, 확인용 견적 포함) |
| `ORDER_014` | 400 | 주문 통화가 배송 범위 정책 통화와 다르다 (`DELIVERY_003`, #70) |
| `ORDER_015` | 400 | 배송할 수 있는 무게 초과 (`DELIVERY_011`, 총 무게 int 초과 포함) |

- `009` 는 비워 두고 `010` 은 주문 금액 변경(주문 생성의 `expectedTotalAmount`)이다. 요청 형식(줄 1~50 · 수량 1~99 · 배송지 형식, `ORDER_1xx`)은 진입 API 의 요청 DTO 가 본다 —
  배송지 형식이 delivery 견적 본문 규칙보다 느슨하면 고객 입력 오류가 503 `ORDER_011` 로 보인다.
- 로그에 배송지(우편번호) · 맞춤 제작 입력 값을 남기지 않는다 — 우편번호를 싣는 값(`ShippingFeeQuoteQuery` · `OrderQuoteCommand`)과 맞춤 입력(`OrderCustomOption`)의 `toString` 이 가린다.

### 2-5. 결제용 주문 요약 internal (#14)

payment-service 가 결제 준비 때 금액 · 주문명 · 상태를 order-service 에서 읽는다 — 결제 금액은 프론트가 아니라 이 응답(서버가 계산해 스냅숏한 총액)이 정본이다
(`payment-guide.md`). 진입은 `OrderPaymentSummaryInternalController → OrderInternalUseCase → OrderInternalFacade(readOnly) → OrderProcessor#getOwned →
OrderPaymentSummaryProcessor` 다.

- **계약**: `GET /internal/v1/orders/{orderId}/payment-summary?memberId={memberId}` (`@Hidden`). 허용 호출자는 `SERVICE_payment-service` 하나다 —
  서비스 토큰 없음 · 사용자 토큰 · `aud` 불일치는 401 `SECURITY_007`, 다른 서비스는 403 `SECURITY_005` (`security-guide.md` §9).
  사용자 토큰이 internal 로 오지 않으므로 `memberId` 는 payment-service 가 access 토큰으로 확인한 값을 넘긴다.
- **응답** `{orderId, memberId, status, totalAmount: {amount, currency}, orderName, paymentDueAt}` — 아이디는 문자열, `status` 는 `OrderStatus` 제어 코드,
  `paymentDueAt` 은 UTC ISO-8601(재고 선점 만료 시각). 재고 선점 아이디는 내리지 않는다 — 결제는 선점을 직접 다루지 않는다.
- **상태 판정은 payment 몫이다.** `PENDING_PAYMENT` 가 아니어도(`EXPIRED` · `PAID` 등) 200 으로 지금 상태를 낸다. 결제를 열지 · 막을지는 payment-service 가 정한다.
- **404 `ORDER_001`**: 없는 주문과 다른 회원의 주문을 가르지 않는다(고객 조회와 같은 `getOwned`).
- **검증**: `orderId` · `memberId` 양수 아님 `ORDER_141` · `ORDER_142`, 숫자가 아님 `ORDER_198`, `memberId` 누락 `ORDER_199` — 모두 400 이고 유스케이스에 닿지 않는다.
- **주문명(`orderName`) 규칙** — `OrderPaymentSummaryProcessor` 한 곳에 있다.
  - 줄 1개 → 그 줄의 주문 시점 상품명(스냅숏). 줄 N개 → `"{첫 줄 상품명} 외 {N-1}건"`. 첫 줄은 저장 순서(`line_no` 오름차순)의 첫 줄이다.
  - 상품명 앞뒤 공백은 지운다. 비었거나 공백뿐이면 `"주문 {orderId}"` 로 대신한다.
  - 상한 100자는 **코드포인트**로 센다(토스페이먼츠 `orderName` 상한). 넘으면 **첫 줄 상품명을 먼저 잘라** 접미사 `" 외 N건"` 을 지키고, 자른 끝 공백은 지운다.
    서로게이트 쌍(이모지) 가운데서 자르지 않는다. 결합 문자 · ZWJ 이모지 같은 자소 묶음은 코드포인트 경계에서 갈릴 수 있다.

## 3. 재고

재고는 product-service 의 SKU(상품 × 옵션 조합) 단위로 관리한다.

```text
available (판매 가능) ──선점──▶ reserved (결제 대기) ──결제 완료──▶ 차감 확정
                     ◀──해제── (만료 · 취소)
```

| 시점 | 동작 | 방법 |
|------|------|------|
| 주문 생성 | 선점 | `available -= qty` 조건부 UPDATE (`available >= qty`) + 선점 행(만료 시각) 생성 |
| 결제 완료 | 확정 | 선점 행을 `CONFIRMED` 로 |
| 결제 없이 만료 · 결제 전 취소 | 해제 | 선점 행을 `RELEASED` 로, `available += qty` |
| 결제 후 취소 · 반품 입고 | 복원 | 선점은 `CONFIRMED` 그대로, 클레임마다 복원 기록(`stock_restoration`)을 남기고 차감했던 SKU 만 `available += qty` (반품은 검수 후 어드민이 복원을 고른 경우만, #85) |

- **조회 후 차감(read-modify-write)을 쓰지 않는다** — 동시 주문에서 초과 판매가 난다. 조건부 UPDATE 한 문장으로 한다.
  영향 행 수가 0 이면 재고 부족(`STOCK_INSUFFICIENT`)이다.
- 선점 만료 시간은 **PG 결제 인증 제한 시간보다 길게** 잡는다 (결제창을 열어 둔 고객의 재고가 먼저 풀리지 않게).
  설정 키는 product-service 의 `stock.reservation.ttl` 이고 기본값 `30m` 은 **결제창 제한 시간을 확인하기 전 임시값**이다.
  **바꿀 때는 order-service 의 `order.creation.active-request-window`(회원별 활성 시도 상한의 창, #231)도 같은 값으로 바꾼다** — 창이 TTL 보다 짧으면 아직 선점 중인 시도가 세지지 않아 상한이 느슨해진다.
- 해제·복원은 **멱등**이어야 한다. 해제는 선점 행의 상태로, 결제 후 복원은 클레임 아이디(복원 기록의 유니크 키)로 이미 처리됐는지 판단한다 —
  같은 해제 · 복원이 두 번 오면 재고가 늘어난다.
- 여러 SKU 를 선점할 때는 **SKU ID 오름차순**으로 처리한다 (교착 방지). 하나라도 실패하면 앞서 잡은 것을 해제한다.
- 맞춤 제작 상품은 재고 무제한(`stock_managed = false`) 옵션을 둔다.

### 3-1. 재고 데이터 (product-service, #10)

| 테이블 | 내용 |
|--------|------|
| `stock` | SKU 와 1:1 (`sku_id` PK) — `stock_managed`, `available_quantity >= 0`. **행이 없는 SKU 는 품절**이다(닫힌 기본값). `@Version` 없음 — 모든 쓰기가 조건부 UPDATE |
| `stock_reservation` | 주문 하나에 한 행 — `status`, `expires_at`, `confirmed_at` · `released_at` · `release_reason`, `@Version`. `active_order_id` 는 RESERVED · CONFIRMED 동안만 `order_id` 이고 RELEASED 면 null — 유니크 키가 **주문당 활성 선점을 하나**로 막는다 |
| `stock_reservation_item` | 선점 × SKU — `quantity > 0`, `deducted`. 무제한 SKU 는 `deducted = false` 로 차감하지 않고 해제 때 되돌리지 않는다 |
| `stock_adjustment_history` | 어드민 조정 이력 (append-only) |
| `stock_restoration` | 결제 후 재고 복원 — 클레임 하나에 한 행(append-only, #85). `claim_id` 유니크(`uk_stock_restoration_claim_id`)가 **클레임당 복원을 하나**로 막는 멱등 키, `reservation_id`(확정 선점) · `order_id`(선점에서 복사) · `reason`(`CANCELLATION` / `RETURN`) |
| `stock_restoration_item` | 복원 × SKU — `quantity > 0`, `restored` = **되돌릴 대상(선점 때 차감)이었는가**. 선점 항목이 `deducted = true` 였던 SKU 만 `true` 이고 재고를 되돌린다 — 재고 행이 없으면 WARN 만 남기고 수량은 늘지 않는다(`true` 그대로). 무제한 SKU 는 `false` 로 기록만 남긴다. 기록 합이 복원 상한을 정한다 |

- 어드민 재고 조정(`PATCH /api/v1/admin/stocks/{skuId}`)은 수량 **절대값 + 기대 수량 CAS**(`expectedQuantity`) 다 — 그 사이 주문이 차감했으면 409 `PRODUCT_020`.
  재고 행이 없는 SKU 의 첫 등록은 INSERT 이고, 동시 첫 등록은 PK 충돌로 409 다.

#### 어드민 재고 조회 · 조정 이력 (#86)

| 경로 | 동작 |
|------|------|
| `GET /api/v1/admin/stocks?skuIds=1,2,3` | SKU 재고 목록 — `{ stocks: [{ skuId: String, stockManaged, availableQuantity, reservedQuantity, updatedAt }] }`. 항목은 PATCH 응답과 같은 모양이다 |
| `GET /api/v1/admin/stocks/{skuId}/adjustments?page=&size=` | 조정 이력 `PageResponse` — 최신순(아이디 내림차순). 항목 `{ id, beforeQuantity, afterQuantity, delta, beforeStockManaged, afterStockManaged, reason{code,name,description}, memo, adminMemberId, createdAt }` |

- **SKU 목록 방식이다 — 상품 단위 조회는 하지 않는다.** 어드민 상품 상세(`GET /api/v1/admin/products/{productId}`, #9)가 내려 주는
  `skus[].skuId` 묶음으로 화면이 이 API 를 부른다. 상품 단위 재고 API 가 필요해지면 그때 따로 연다.
- skuIds 는 1~100개 · 양수(`PRODUCT_124` 필수 · `PRODUCT_125` 상한 · `PRODUCT_126` 양수), 중복은 첫 등장 한 번이고 요청 순서대로 낸다.
  **없는 SKU 는 응답에서 빠진다** — 서비스 간 SKU 조회와 같은 관례이고, 지워진 SKU 하나 때문에 나머지 SKU 조정까지 막지 않는다.
- **재고 행이 없는 SKU 는 품절로 낸다** — `stockManaged=true · availableQuantity=0 · updatedAt=null`. `reservedQuantity` 는 재고 행이 없으면
  차감 선점이 생길 수 없어 정상이면 0 이다 (읽은 값을 그대로 낸다). 화면은 조회한
  `availableQuantity` 를 **그대로 PATCH 의 `expectedQuantity` 로 보낸다** — 행 없는 SKU 의 첫 등록이 기대 수량 0 을 요구하는 규칙과 맞물린다.
- 재고 목록은 `REPEATABLE_READ` 읽기 전용 트랜잭션이다 — 재고 행과 선점 합을 한 스냅숏으로 읽어 "실물 = 판매 가능 + 선점" 계산이 엇갈리지 않게 한다.
- 쿼리는 재고 목록이 SKU 존재(in 1) · 재고 행(in 1) · 선점 합(SKU 묶음 group by 1) 3번(SKU 수와 무관), 조정 이력이 SKU 존재 1 · 목록 1 ·
  전체 건수는 필요할 때만 1번(최대 3번 — Spring Data 는 첫 페이지가 size 보다 짧으면 count 를 생략한다)이다.
  선점 합은 PATCH 도 같은 묶음 쿼리를 SKU 하나로 부른다. 이력은 `idx_stock_adjustment_history_sku_id_id` 를 탄다.
- 조정 이력의 `adminMemberId` 는 내부 회원 아이디라 그대로 낸다(개인정보 아님). 어드민 이름 표시는 auth-service 조회가 필요해 범위 밖이다.
- 이력 조회의 없는 SKU 는 404 `PRODUCT_002`, 이력이 없는 SKU 는 빈 목록이다. 두 조회 모두 `hasRole('ADMIN')`, Facade 는 `@Transactional(readOnly = true)` 다.

#### 어드민 조정 운영 규칙

- **`availableQuantity`(요청의 `quantity`)는 선점분을 뺀 판매 가능 수량이다 — 실물 수량이 아니다.** 응답의 `reservedQuantity` 는 그 SKU 의
  RESERVED 선점 중 실제로 차감한(`deducted = true`) 수량 합이다. **실사 수량을 입력할 때는 `quantity = 실물 − reservedQuantity`** 로 보낸다.
  실물을 그대로 넣으면 결제 대기 중인 선점분만큼 초과 판매된다. 결제 완료(CONFIRMED) 뒤 아직 출고하지 않은 수량도 실물에 있으므로 함께 빼야 한다 —
  배송 연동 전까지는 운영자가 확인한다. `reservedQuantity` 는 응답 순간의 참고치이고 CAS 판정에는 쓰지 않는다.
- **무제한 → 관리 전환 직후의 선점** — 무제한일 때 만든 선점은 항목이 `deducted = false` 다. 그 선점이 RESERVED 로 남아 있는 동안 관리로 바꾸면,
  그 선점은 재고를 차감하지 않은 채 확정될 수 있다(확정은 재고를 다시 보지 않는다). 전환할 때는 **그 SKU 의 결제 대기 주문이 끝난 뒤(최대 `stock.reservation.ttl`)
  실사 수량을 다시 맞춘다.** 반대로 관리 → 무제한 전환 뒤 풀리는 선점은 차감했던 수량을 무제한 SKU 에 되돌린다 — 판매 판정에는 영향이 없다.

### 3-2. 서비스 간 API (호출자 order-service)

| 경로 | 동작 |
|------|------|
| `POST /internal/v1/stock-reservations` | 선점 — 요청 `{ orderId, items: [{ skuId, quantity }] }` (항목 1~100개, 수량 1~10000) |
| `POST /internal/v1/stock-reservations/{reservationId}/confirmation` | 확정 (결제 완료) |
| `POST /internal/v1/stock-reservations/{reservationId}/release` | 해제 (주문 생성 실패 보상. 만료는 `order-expired` 이벤트(#96, §3-5), 결제 전 취소는 `order-canceled` 이벤트(#16, §3-5)라 order-service 는 부르지 않는다) |
| `POST /internal/v1/stock-restorations` | 결제 후 복원 (결제 후 취소 · 반품 입고, #85) — 아래 "결제 후 복원" |

선점 · 확정 · 해제 응답은 같은 모양이고 신규 · 기존 모두 200 이다 —
`{ reservationId: String, orderId: String, status, expiresAt(UTC ISO-8601), items: [{ skuId: String, quantity, deducted }] }`. 항목은 SKU 아이디 오름차순이다.

선점 처리 순서 (Facade 트랜잭션 하나, 기본 격리 READ COMMITTED):

1. 같은 `skuId` 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다.
2. 같은 주문의 활성 선점이 있으면 — SKU 별 수량이 같으면 그 선점을 그대로 200(멱등), 다르면 409 `PRODUCT_023`.
3. SKU · 상품(숨김 · 삭제 포함)을 in 절로 읽어 판매 가능 조건(상품 삭제 안 됨 · 노출 · 판매중 && SKU 판매중 — internal SKU 조회의 `purchasable` 과 같은 규칙에서
   재고만 뺀 것, `SaleAvailability.isOpenForSale`)을 확인한다. 없는 SKU · 숨긴 상품 · 삭제된 상품 · 판매 종료는 모두 모아 409 `PRODUCT_022`.
4. 선점 **헤더만** INSERT 하고 flush 한다(RESERVED, `expires_at = now + ttl`) — 같은 주문의 동시 생성은 유니크 키에서 먼저 커밋된 쪽을 기다렸다가
   409 `PRODUCT_026` 로 끝나 **재고를 건드리지 않는다**. 차감을 먼저 하면 재고가 빠듯할 때 중복 요청이 409 대신 `PRODUCT_021` 을 받는다.
5. **모든 항목**에 SKU 오름차순으로 조건부 차감(`stock_managed = true and available_quantity >= qty`)을 시도한다. 1행이면 `deducted = true`.
6. 0행인 SKU 만 재고 행을 in 절로 **다시 읽는다**(같은 트랜잭션, READ COMMITTED 라 최신 커밋 값). 무제한이면 `deducted = false` 로 선점하고,
   관리 중 부족이거나 **행이 없으면(품절)** 부족이다. 차감 여부를 미리 읽은 스냅숏으로 정하지 않는다 — 읽은 뒤 어드민이 관리 여부를 바꾸면
   관리 SKU 를 차감 없이 선점하거나 무제한 SKU 를 부족으로 거절한다(TOCTOU).
7. 부족한 SKU 가 하나라도 있으면 모두 모아 409 `PRODUCT_021`("…: [1001, 1003]") — **예외가 같은 트랜잭션을 롤백해 앞서 차감한 재고와 헤더가 함께 사라진다(전체 해제)**.
   따로 보상 해제를 부르지 않는다. 없으면 `deducted` 가 정해진 항목을 INSERT 한다.

#### 결제 후 복원 (#85)

요청 `{ claimId, reservationId, reason: CANCELLATION | RETURN, items: [{ skuId, quantity }] }` (항목 1~100개, 수량 1~10000 — 선점과 같은 상한).
응답 200 `{ restorationId: String, claimId: String, reservationId: String, reason, items: [{ skuId: String, quantity, restored }] }` — 신규 · 같은 클레임의
재요청 모두 같은 모양이다. `reason` 은 선점 응답의 `status` 처럼 분기용 제어 코드 문자열이다. 항목은 SKU 아이디 오름차순이다.

- **확정된 선점의 상태는 바꾸지 않는다** (`CONFIRMED` 그대로 — 상태 전이 없음). 복원은 클레임마다 append-only 기록(`stock_restoration` · `_item`)으로 남기고,
  그 기록으로 멱등(클레임 아이디)과 상한(선점 수량 − 이미 복원한 수량)을 판정한다. **부분 클레임**(3개 중 1개 반품)이 한 선점에 여러 번 온다.
- **반품 복원 여부는 product-service 가 판단하지 않는다.** 검수 뒤 어드민이 "재고로 되돌림" 을 고른 반품만 order-service 가 `reason = RETURN` 으로 부르고,
  되돌리지 않는 반품(파손 · 불량)은 **부르지 않는다**.

처리 순서 (Facade 트랜잭션 하나, 기본 격리 READ COMMITTED):

1. 같은 `skuId` 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다.
2. 선점 헤더를 **비관적 쓰기 잠금**(`@Lock(PESSIMISTIC_WRITE)` 정적 JPQL, PostgreSQL 에서는 `for no key update`)으로 읽는다. 없으면 404 `PRODUCT_003`.
   잠금 대기는 2000ms(호출자 Feign read timeout 3000ms 보다 짧게)까지이고, 넘으면 아무것도 바꾸지 않고 409 `PRODUCT_031`(재시도)이다.
   복원은 상태 전이가 없어 `@Version` 이 경합을 잡지 못한다 — 잠금이 없으면 같은 선점의 서로 다른 클레임 둘이 "이미 복원한 수량" 을 각자 읽고 함께 상한을 넘긴다.
   같은 클레임의 동시 재요청도 여기서 줄을 서 늦은 쪽이 3단계에서 먼저 커밋된 복원을 본다. 잠금 순서는 선점 헤더 → 재고 행(SKU 오름차순)으로
   해제 · 만료(헤더 UPDATE → 재고 오름차순)와 같아 교착이 없다.
3. 같은 `claimId` 의 복원이 있으면 — 선점 · 사유 · SKU 별 수량이 같으면 그 복원을 그대로 200(멱등, 선점 상태와 무관), 다르면 409 `PRODUCT_029`.
4. 선점이 `CONFIRMED` 가 아니면 409 `PRODUCT_027` — 결제 전(`RESERVED`) · 이미 해제(`RELEASED`)는 복원이 아니라 해제 경로다.
5. 이 선점에서 SKU 별로 이미 복원한 수량을 group by 한 번으로 읽는다(`idx_stock_restoration_reservation_id` → 항목 유니크 키 접두).
   재고를 되돌리지 않은 무제한 항목도 합에 들어간다 — 상한은 선점 수량 기준이다.
6. 항목마다 (선점 항목 수량 − 이미 복원한 수량)을 넘으면 — 선점에 없는 SKU 포함 — 모두 모아 409 `PRODUCT_028`("…: [1001, 9999]"). 아무것도 쓰지 않는다.
7. 복원 기록(헤더 · 항목)을 INSERT 하고 flush 한다. 클레임 유니크 키 위반(2단계 잠금을 비켜 간 동시 생성 — 다른 `reservationId` 로 온 경우)은 409 `PRODUCT_030`.
8. 선점 항목이 `deducted = true` 였던 SKU 만 **SKU 오름차순 가산 UPDATE**(`available_quantity += qty` — 관리 여부 무관, 해제와 같은 쿼리)를 한다 —
   무제한 SKU 는 `restored = false` 로 기록만 남는다.
   재고 행이 없어 0행이면 해제 경로와 같이 WARN 로그만 남기고 막지 않는다(재고 행은 지우지 않으므로 데이터 오류다).

### 3-3. 선점 상태 전이

```text
RESERVED ──확정──▶ CONFIRMED
   └──────해제 · 만료──▶ RELEASED (release_reason = RELEASED_BY_CALLER / EXPIRED / ORDER_EXPIRED / ORDER_CANCELED)
```

| `release_reason` | 누가 |
|------------------|------|
| `RELEASED_BY_CALLER` | internal 해제 API (주문 생성 실패 보상) |
| `EXPIRED` | 만료 스윕 (안전망) |
| `ORDER_EXPIRED` · `ORDER_CANCELED` | 주문 만료 · 결제 전 취소 이벤트 컨슈머 (§3-5) |

| 현재 | confirm | release |
|------|---------|---------|
| `RESERVED` (만료 시각 전 · 후, 스케줄러가 풀기 전 모두) | `CONFIRMED` 200 | `RELEASED` + 재고 복원 200 |
| `CONFIRMED` | 200 (그대로) | 409 `PRODUCT_024` |
| `RELEASED` | 409 `PRODUCT_025` | 200 (그대로) |
| 없음 | 404 `PRODUCT_003` | 404 `PRODUCT_003` |
| 동시 변경 (`@Version`) | 409 `PRODUCT_026` | 409 `PRODUCT_026` |

- 전이는 도메인 `StockReservation.confirm(now)` · `release(now, reason)` 만 한다. 상태를 저장(merge · 버전 검사 · flush)한 **뒤** `deducted` 항목을 SKU 오름차순으로
  복원한다 — 같은 해제가 동시에 와도 버전에서 진 쪽은 복원까지 가지 않아 재고가 한 번만 늘어난다.
- 만료 스케줄러(`StockReservationExpiryScheduler`)는 `stock.reservation.expiry-sweep.*`(기본 fixed-delay 60s · batch-size 100 · max-batches-per-run 10) 주기로
  `RESERVED && expires_at <= now` 를 `(expires_at, id)` 순으로 읽고(`idx_stock_reservation_status_expires_at_id`), **건마다 트랜잭션**으로 다시 읽어
  아직 만료 대상일 때만 `release(EXPIRED)` + 복원한다. 한 실행 안에서는 배치의 마지막 건 다음부터 이어 읽는다(keyset) — 실패한 건이 RESERVED 로 남아도
  같은 실행이 그 건으로 배치를 다시 채우지 않는다(head-of-line blocking 없음). 실패한 건은 다음 실행이 다시 읽는다.
  한 건의 실패는 로그만 남기고 다음 건으로 간다 — 409 `PRODUCT_026`(확정 · 다른 인스턴스와 경합)은 WARN, 그 밖의 업무 오류 · 예상 못 한 예외는 ERROR 이고
  업무 오류면 오류 코드를 남긴다. 확정과 만료가 겹치면 `@Version` 이 하나만 통과시킨다. 인스턴스가 여럿이어도 분산 락을 두지 않는다.
  **메시징이 켜져 있으면 역할은 안전망이다 (#309)** — 주문 만료 이벤트(§3-5)가 먼저 풀고, 스윕은 만료 시각 + `stock.reservation.expiry-sweep.event-grace`(기본 2분)가
  지나도 RESERVED 인 선점만 푼다. 주문 결제 기한이 선점 만료 시각과 같으므로(#13 C3) grace 가 없으면 스윕이 이벤트와 같은 기한에 돌아 상당수를 먼저 푼다 —
  grace 는 order 만료 스윕(30s) + 릴레이(1s) + 컨슈머 재시도 총 시간(`commerce.messaging.retry.*` 기본 약 15초) + 호스트 간 시계 오차보다 넉넉히 둔다 —
  짧으면 재시도 중인 이벤트보다 스윕이 먼저 풀어 WARN 오탐이 는다(재고는 틀어지지 않는다). 조회와 건별 재확인이 같은 기준 시각(`StockReservationProcessor.sweepCutoff`)을 쓴다.
  - 켜는 순서는 order(outbox 릴레이) → product, 끌 때는 반대다. product 만 켜져 있으면 모든 만료 선점이 grace 만큼 늦게 풀리고 WARN 이 매 실행 나온다 —
    WARN 이 계속되면 order 의 `MESSAGING_ENABLED` · 릴레이 상태부터 본다. order 쪽 발행이 아직 없는 과도기에 product 만 켜야 하면 `event-grace: 0s` 로 둔다.
  - 회원별 활성 시도 상한(#231)의 창은 선점 TTL 과 같게 두지만, 이벤트가 오지 않은 선점은 TTL + grace + 스윕 주기까지 남아 그 꼬리(약 3분)만큼 한 회원이
    묶는 재고가 상한을 조금 넘을 수 있다. 이벤트가 정상이면 TTL + 약 30초 안에 풀려 차이가 없다.
  - 안전망이 실제로 풀면 WARN(`released by safety-net sweep`, 건수 · grace)이다 — 이벤트가 오지 않은 신호다(발행 전 과도기 · order 릴레이 지연 · DLT · 컨슈머 정지).
    ERROR 가 아닌 이유: 주문 저장 실패로 **주문 없이 남은 선점**(#13 C3 — 같은 키 재시도가 없으면 스윕이 푼다)은 만료 이벤트가 나올 수 없어 늘 이 경로다.
    WARN 이 평소보다 늘면 order 릴레이 · DLT · `commerce.messaging.consumer.dlt.total` 을 본다.
  - 이벤트가 오지 않은 선점은 grace 동안 만료 시각이 지난 RESERVED 로 남는다. 확정은 만료 시각을 보지 않으므로 이 사이에 온 결제 확정은 200 `CONFIRMED` 다 —
    order 의 만료 후 결제(§3-4)는 재선점이 기존 선점을 200 으로 받고 확정한다. 재고가 grace 만큼 늦게 돌아오는 것이 비용이다.
    이때 재선점 응답의 `expiresAt` 은 이미 지난 기존 값일 수 있다 — 결제 기한으로 쓰지 말고, 재선점 뒤 곧바로 확정한다(§3-4).
  - **메시징이 꺼져 있으면(기본) 주 경로다** — grace 를 쓰지 않고 만료 시각에 바로 풀며 INFO 다.
- **만료 후 확정** — 스케줄러가 먼저 풀었으면 확정은 409 `PRODUCT_025` 다. order-service 의 처리는 아래 §3-4 를 따른다.
- 허용 전이 표 밖의 요청은 도메인이 `StockReservationTransitionException`(현재 상태 · 시도한 동작)으로 알리고, `StockReservationProcessor` 가
  `PRODUCT_024` · `PRODUCT_025` 를 붙인다 — domain 은 application 의 오류 코드를 모른다 (`coding-conventions.md` §8-1).

| 코드 | HTTP | 뜻 |
|------|------|----|
| `PRODUCT_003` | 404 | 없는 재고 선점 |
| `PRODUCT_021` | 409 | 재고 부족 (행 없는 SKU 포함) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_022` | 409 | 판매 불가 SKU (없는 SKU · 숨긴 상품 · 삭제된 상품 · 판매 종료) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_023` | 409 | 같은 주문의 활성 선점과 항목이 다르다 |
| `PRODUCT_024` | 409 | 이미 확정된 선점의 해제 |
| `PRODUCT_025` | 409 | 이미 해제 · 만료된 선점의 확정 |
| `PRODUCT_026` | 409 | 동시 변경(`@Version`) · 같은 주문의 동시 선점 생성 — 다시 시도한다 |
| `PRODUCT_027` | 409 | 결제 후 복원 — 선점이 확정(`CONFIRMED`)이 아니다 |
| `PRODUCT_028` | 409 | 결제 후 복원 — (선점 수량 − 이미 복원한 수량)을 넘는 SKU(선점에 없는 SKU 포함) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_029` | 409 | 결제 후 복원 — 같은 클레임의 기존 복원과 선점 · 사유 · 항목이 다르다 |
| `PRODUCT_030` | 409 | 결제 후 복원 — 같은 클레임의 동시 복원 생성 — 다시 시도한다 |
| `PRODUCT_031` | 409 | 결제 후 복원 — 선점 헤더 잠금 대기 상한 초과(같은 선점을 처리 중) — 다시 시도한다 |
| `PRODUCT_112`~`PRODUCT_130` | 400 | 요청 검증 (`ProductValidationMessage`) — 선점 수량 상한 `PRODUCT_122`, 어드민 조정 · 이력 경로의 `skuId` 양수 `PRODUCT_123`, 어드민 재고 조회 `skuIds` `PRODUCT_124`~`PRODUCT_126`, 복원 `claimId` 필수 · 양수 `PRODUCT_127` · `PRODUCT_128`, `reservationId` 필수 `PRODUCT_129`, `reason` 필수 `PRODUCT_130`. 복원의 `reservationId` 양수와 `items` 이하는 선점 코드(`PRODUCT_121` · `PRODUCT_114`~`PRODUCT_120` · `PRODUCT_122`)를 그대로 쓴다 |

- 확정된 선점은 해제하지 않는다(`PRODUCT_024`). 결제 후 취소 · 반품의 재고는 위 "결제 후 복원" 경로로 되돌린다 — 선점 상태는 `CONFIRMED` 그대로다.

### 3-4. order-service 호출 규칙

- **주문을 PAID 로 확정하기 전에 선점 확정(`/confirmation`)의 200 · `CONFIRMED` 응답을 받아야 한다.** 확정 응답 없이 PAID 로 두면 만료 스케줄러가 선점을 풀어
  돈은 받았는데 재고는 다른 주문에 팔린다. 같은 결제 승인 이벤트가 다시 와도(릴레이 재발행 · 컨슈머 재전달) 확정을 **멱등으로 다시 부른다** — 이미 CONFIRMED 면 200 이다.
- **만료 후 확정(409 `PRODUCT_025`)** — 같은 `orderId` 로 다시 선점하고(해제된 주문은 활성 유니크 키에 걸리지 않는다) **곧바로 같은 흐름에서 확정**한다.
  재선점과 확정 사이를 비우면 새 선점도 `ttl` 뒤 다시 만료돼 같은 경합이 되풀이된다. 재선점이 `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면
  `STOCK_UNAVAILABLE` 로 거절 회신하고 payment 가 전액 취소 대상으로 넘긴다 (`payment-guide.md` §6).
- **구현 (#308 PR-B1, `OrderStockProcessor#confirmOrReReserve(order)`)** — 트랜잭션 없음(`NEVER`), 주문을 바꾸지 않고 결과(`StockConfirmation`)만 돌려준다.
  결제 대기 · 만료 주문만 받는다(결제 전 취소된 주문은 부르는 쪽이 확정 · 재선점 없이 거절한다).
  1. 주문의 `stockReservationId` 를 확정한다 — 200 · `CONFIRMED` 이고 응답의 주문 아이디가 같으면 `CONFIRMED`.
  2. 확정이 409 `PRODUCT_025`(해제 · 만료) · 404 `PRODUCT_003`(없음)이면 같은 `orderId` · **주문 항목 스냅숏**의 SKU 별 수량 합(주문 생성과 같은 `quantitiesBySkuId`)으로
     재선점(`StockReservationPort#reReserve`)하고 곧바로 그 선점을 확정한다 — `RE_RESERVED_AND_CONFIRMED`. 재선점 응답은 같은 주문의 이미 확정된 선점(`CONFIRMED`,
     앞선 처리가 반영 전에 끊긴 재전달)도 받고 만료 시각은 보지 않는다.
  3. 재선점 `PRODUCT_021` · `PRODUCT_022` 는 예외가 아니라 `STOCK_UNAVAILABLE` 결과다.
  4. 그 밖은 예외다 — `PRODUCT_026` · 타임아웃 · 5xx · 서킷 · 모르는 4xx · 읽을 수 없는 응답 · 재선점 직후 확정이 다시 해제는 503 `ORDER_002`(재전달로 다시 부른다),
     `PRODUCT_023`(같은 주문의 다른 항목) · 다른 주문의 선점이 확정됨은 500 `ORDER_017`(다시 해도 같다, error 로그). 여기서 다시 보내지 않는다.
  확정 · 재선점이 모두 멱등이라 재전달이 처음부터 다시 불러도 재고가 두 번 움직이지 않는다.
- **409 는 `resultCode` 로 분기한다.** `PRODUCT_026`(동시 변경 · 동시 생성) · `PRODUCT_030`(같은 클레임의 동시 복원 생성) ·
  `PRODUCT_031`(선점 잠금 대기 초과)만 재시도 대상이고 **새 트랜잭션(새 요청)** 으로 다시 부른다 — 다시 부르면 바뀐 상태를 기준으로 답한다.
  재시도는 **상한을 둔다(예: 3회, 간격을 늘려 가며)** — 넘으면 영속 재전송(아래 복원 보장)으로 넘긴다.
  `PRODUCT_030` 재시도는 같은 본문이면 먼저 끝난 복원으로 200, 다른 선점으로 보냈으면 `PRODUCT_029`(같은 claimId 를 두 선점에 쓴 호출자 버그 — 종결)다.
  `PRODUCT_021` · `PRODUCT_022` · `PRODUCT_023` · `PRODUCT_024` · `PRODUCT_025` · `PRODUCT_027` · `PRODUCT_028` · `PRODUCT_029` 는 재시도해도 결과가 같은 종결 오류다.
- **결제 후 복원(`/internal/v1/stock-restorations`)은 클레임이 확정될 때 부른다** — 결제 후 취소는 취소 확정(환불 요청과 같은 흐름), 반품은 검수 뒤
  어드민이 "재고로 되돌림" 을 고른 경우만(`reason = RETURN`). 되돌리지 않는 반품은 부르지 않는다. **`claimId` 가 멱등 키**다 — 타임아웃 · 재시도는
  같은 `claimId` · 같은 본문으로 다시 부른다(재고는 한 번만 늘고 200). 같은 클레임에 다른 수량을 보내면 `PRODUCT_029` 이므로, 클레임 수량을 바꿔야 하면
  새 클레임으로 다룬다. 항목 수량은 그 클레임의 SKU 별 수량이고 선점의 `reservationId` 는 주문에 저장한 확정 선점이다.
  `PRODUCT_028` 은 호출한 쪽의 클레임 수량 계산 오류(확정 · 이미 복원한 수량 초과)다 — 조용히 완료 처리하지 않는다.
- **복원의 클레임 아이디는 order-service 가 먼저 만든 클레임 행의 아이디다** (#16 · #17 이 지킬 호출자 계약).
  - 결제 후 취소는 **경로와 상관없이** — 고객 취소 · 어드민 취소 · PG 웹훅 / PG 콘솔 취소 · 자동 전액 취소(재선점 실패 등) — order-service 가 먼저 클레임 행을
    만들고 그 `claimId` 로 복원한다. 재시도마다 새 아이디를 만들지 않는다 — 새 아이디면 멱등이 깨져 재고가 두 번 는다(상한 안이면 409 도 나지 않는다).
  - **복원 호출은 영속적으로 보장한다.** 클레임에 "재고 복원 대기" 표시를 두고 200 을 받으면 지운다. 실패(타임아웃 · 서킷 오픈 · 재시도 대상 409 상한 초과)하면
    스케줄러가 **같은 claimId · 같은 본문**으로 다시 보낸다(클레임 쪽 재전송이다 — 결제 반영은 재전송 대신 이벤트 재전달 · DLT 를 쓴다, `payment-guide.md` §6).
    종결 409 는 어드민에 노출한다. 결제 반영 경합(확정 뒤 취소가 이김)의 복원은 클레임 행 대신 `order_payment.restoration_claim_id` 를 claimId 로 쓴다(#308 PR-C).
  - **복원을 부른 클레임은 철회하지 않는다.** 환불이 실패(`REFUND_FAILED`)해도 환불만 재시도한다 — 재고를 다시 빼는(복원을 되돌리는) API 는 없다.
- **409 를 같은 트랜잭션 안에서 잡고 계속하지 않는다.** product-service 안에서도 어댑터가 409 로 바꾼 시점에는 flush 가 이미 실패해 트랜잭션이 rollback-only 다 —
  잡고 계속하면 커밋에서 실패한다. order-service 도 Feign 409 를 받은 트랜잭션에서 주문 상태를 바꾸고 재시도까지 하지 말고, 재시도는 새 트랜잭션에서 한다.
- **확정 · 해제 · 복원은 `reservationId` 로 대상 선점을 식별한다** — 서비스 토큰(`SERVICE_order-service`)을 가진 호출자를 신뢰한다는 전제다. order-service 는 자기 주문의
  `reservationId` 만 저장 · 사용한다.
- **주문 만료 · 결제 전 취소의 해제는 이벤트다** (§3-5) — #96이 주문 만료 전이와 같은 트랜잭션으로 `order-expired`를 outbox에 넣는다.
  `order-canceled` 는 #16 의 결제 전 고객 취소(`PENDING_PAYMENT → CANCELED`, §1-1)가 전이와 **같은 트랜잭션으로** `OrderEventPort` 를 거쳐 outbox 에 넣는다
  (payload 는 §3-5 와 같고 `reason` 은 취소 사유 코드). 결제 뒤 취소(PAID · MAKING · PREPARING)의 재고는 선점 해제가 아니라 결제 후 복원(§3-4)이라 이 이벤트 대상이 아니다.
  어드민이 결제 전 취소를 더하면(#17 PR-2) 같은 이벤트를 같은 방식으로 낸다.
  C3 주문 생성 실패는 공유 선점의 보상 해제를 호출하지 않는다. 같은 키·주문 ID로 복구하며 재시도가 없으면 product의 선점 만료 스윕이 푼다. internal 해제(`/release`) API 자체는 product-service 계약으로 유지한다.
- **오류 메시지를 고객 응답에 그대로 넘기지 않는다.** `PRODUCT_021` · `PRODUCT_022` · `PRODUCT_028` 메시지에는 SKU 아이디 목록이 실린다 — order-service 는 자기 오류 코드
  (예: `ORDER_*`)와 고객용 문구로 바꾸고, 필요한 SKU 정보는 자기 주문 항목에서 골라 보여 준다.

### 3-5. 주문 만료 · 취소 이벤트 → 선점 해제 (#309)

order-service 가 낸 주문 만료 · 결제 전 취소를 product-service 컨슈머(`OrderStockReleaseEventListener`)가 받아 선점을 푼다. 공통 규칙은 `coding-conventions.md` §12 ·
`core/messaging-core/README.md` 다. `MESSAGING_ENABLED=false`(기본)면 컨슈머가 뜨지 않고 지금처럼 internal 해제 · 만료 스윕이 푼다.
메시징이 꺼져 있으면 order-service 는 이벤트를 outbox(`orders.outbox`)에 쌓기만 하고, 켜면 쌓인 것부터 나간다.

| 항목 | 값 |
|------|----|
| 토픽 | `<ns>.order.order-expired` · `<ns>.order.order-canceled` (+ `.dlt`, `docker/kafka/topics.txt`) — 파티션 키는 주문 아이디 문자열 |
| 봉투 | `eventType` = `order-expired` / `order-canceled`, `aggregateType` = `order`, `aggregateId` = 주문 아이디, `schemaVersion` = 1, `producer` = `order`. 발생 시각은 봉투 `occurredAt` |
| 컨슈머 그룹 | `<ns>.product.order-stock-release` (두 토픽을 한 그룹이 받는다) |
| 해제 사유 | `order-expired` → `ORDER_EXPIRED`, `order-canceled` → `ORDER_CANCELED` |
| `reason` 코드 | `order-expired` → `PAYMENT_TIMEOUT`, `order-canceled` → 고객 취소 사유 `CHANGE_OF_MIND` · `ORDER_MISTAKE` · `PAYMENT_ISSUE` · `OTHER` (`OrderCancelReason`, 더하기만 한다) |

payload (두 이벤트 모두 같은 모양 — **이 JSON 이 계약 정본이다**. product-service 리스너 테스트가 이 블록을 읽어 역직렬화한다):

```json
{ "orderId": "1840000000000000001", "reservationId": "1840000000000000002", "reason": "PAYMENT_TIMEOUT" }
```

- 아이디는 문자열이다(JS 정밀도, `JobRunMessage` 와 같다). **`reservationId` 가 해제 키**이고 `orderId` 는 교차 검증이다 — 주문 아이디로 풀면 §3-4 "만료 후 확정 → 같은 `orderId` 로 재선점"
  뒤에 늦게 온 옛 `order-expired` 가 새 RESERVED 선점을 푼다. `reason` 은 order-service 소유 코드이고 product-service 는 읽지 않는다(모르는 필드는 무시).
  SKU · 수량은 싣지 않는다 — product-service 가 선점 항목을 갖고 있다.
- 처리 — 처리 기록(`processed_event`)과 해제 · 재고 복원이 한 트랜잭션이다(`IdempotentEventProcessor.process`). 전이 · 저장 순서는 §3-3 과 같다(상태 먼저 저장 → 복원).

  | 결과 | 처리 |
  |------|------|
  | RESERVED → RELEASED + 재고 복원 | 정상 |
  | 이미 RELEASED (스윕 · internal 해제가 먼저 풂, 같은 이벤트 재전달) | 정상 — 아무것도 바꾸지 않는다 |
  | CONFIRMED (결제가 먼저 끝남) | WARN(eventId · reservationId · orderId) 후 정상 — 기록을 남기고 풀지 않는다 |
  | 선점 없음 · `orderId` 불일치 · 아이디 형식 오류 · 모르는 새 버전 · 읽을 수 없는 봉투 | 계약 위반 — 재시도 없이 DLT, 기록 롤백 |
  | 동시 변경 `PRODUCT_026` · DB 오류 | 컨슈머 안 재시도(`commerce.messaging.retry.*`) 뒤 DLT |
- **internal 재조회를 하지 않는다** — `coding-conventions.md` §12 "재고를 바꾸는 컨슈머는 원본 서비스로 재조회" 의 예외다. 이 컨슈머는 RESERVED 를 푸는 일만 해 효과가 TTL 만료와 같고,
  CONFIRMED 는 건드리지 않는다. 위조 이벤트의 최대 피해는 결제 대기 재고가 TTL 보다 일찍 풀리는 것이고, 그 주문의 확정은 `PRODUCT_025` → 재선점(§3-4)으로 흡수된다.
  클러스터는 PLAINTEXT · ACL 없음이다. 결제 이벤트(#308)도 봉투 서명 · SASL · ACL 을 더하지 않기로 했다 — 돈 · 재고를 바꾸는 결제 승인 컨슈머는
  payment internal 재조회로 진위를 확정한다(`payment-guide.md` §6). 클러스터 인증 · ACL 은 운영 인프라 과제로 따로 본다.
- DLT 재처리는 사람이 한다(messaging-core README). DLT 에 남은 선점은 만료 스윕(안전망, §3-3)이 TTL 뒤 푼다.

### 3-6. 결제 승인 소비 (#308 PR-B2)

`PaymentApprovedEventListener` → `OrderPaymentEventUseCase` → `OrderPaymentEventFacade`가 `payment-approved` v1을 처리한다.
토픽은 `<ns>.payment.payment-approved`, 그룹은 `<ns>.order.payment-approved`, Kafka key와 aggregateId는 payload.orderId다.
원격 I/O가 있어 Facade는 `NEVER`, DB 쓰기만 `OrderPaymentProcessor.complete`의 `REQUIRES_NEW(timeout=10s)`다.

1. 봉투 종류/생산자/aggregate/version, 양수 문자열 ID, 통화·양수금액·승인시각·수단 코드를 검증한다. inbox에 있으면 건너뛴다.
2. `PaymentApprovalProcessor`만 payment internal을 조회하고 이벤트와 ID/회원/금액/통화/승인시각/수단을 대조한다. 승인시각은 timestamp(6)에 맞춰 마이크로초로 자른다.
   Facade에는 `VerifiedPayment` application model만 넘긴다. 404/400/계약위반/알림↔원본 불일치는 즉시 DLT, 401/403/5xx/timeout은 재시도다.
3. 원본↔주문의 소유/금액/통화 불일치는 `PAYMENT_MISMATCH` 회신이다. 주문 없음은 `ORDER_NOT_FOUND`, 다른 결제로 이미 결제완료면 `ALREADY_PAID`다.
   CANCELED는 확정·재선점 없이 `ORDER_CANCELED`. PENDING_PAYMENT/EXPIRED만 §3-4의 확정·재선점→즉시확정을 호출한다.
4. DONE이 아닌 결제는 WARN + `REJECTED_PAYMENT_NOT_DONE` 기록/inbox를 남긴다. CANCELED(전액취소)는 회신 없이 끝내고,
   PARTIAL_CANCELED 등 잔액이 남을 수 있는 상태는 `PAYMENT_NOT_DONE` 거절 회신을 낸다. 조회 amount는 원승인 총액이므로 잔액을 계산하지 않는다.
5. DB 쓰기 순서는 **주문 @Version flush → order_payment INSERT → inbox → 회신 outbox**다. 성공 회신 `order-paid`는
   `{orderId,paymentId,stockReservationId,paidAt}`, 거절 `order-payment-rejected`는 `{orderId,paymentId,reason}`(모든 ID 문자열)다. 한 트랜잭션에 커밋/롤백한다.
6. 주문 버전·결제/주문/inbox 유니크 경합은 롤백된 tx 밖에서 잡고 새 tx로 최대3회 재판정한다. 소진은 Kafka 재시도다.
   이미 받은 확정선점 ID는 이 로컬 재시도 동안 유지한다. 취소가 이겼으면 `REJECTED_ORDER_CANCELED.stock_reservation_id`에 남겨 C가 복원한다.
   다른 결제가 이겼으면 `REJECTED_ALREADY_PAID`에 선점을 넣지 않는다(이긴 주문의 재고를 복원하면 안 된다).
7. inbox 보존7d가 지난 재처리/서로 다른 eventId의 같은 payment도 영구 `order_payment.payment_id` 기록으로 회신 중복을 막는다.
   기존 회신은 outbox가 전달하며 중복 알림 때 새 회신을 만들지 않는다. DB 커밋 뒤 ack 전 재시작은 같은 inbox로 거른다.

**배포 gate** — `MESSAGING_ENABLED=true`와 `ORDER_PAYMENT_APPROVAL_CONSUMER_ENABLED=true`(기본 false)가 모두 필요하다.
전용 소비 gate를 켤 때만 `OrderPaymentConsumerConfig`가 승인/성공회신/거절회신 토픽과 각 `.dlt` 6개를 확인한다. 없거나 Kafka 불통이면 기동 실패다.
gate OFF에서는 기존 required-topics(만료 토픽)만 확인하므로 기존 만료 outbox 릴레이를 계속 운영할 수 있다.
**PR-C와 #15 완료 및 아래 미해결 복구창 검증 전에는 소비 gate와 운영 payment.enabled를 열지 않는다.**

**남은 필수 복구창(PR-C)** — 확정 성공 뒤 DB 기록 전에 프로세스가 종료되고 그 사이 고객 취소가 커밋되면 재전달은 CANCELED부터 본다.
메모리의 확정선점 ID는 사라졌고 재선점이 새 ID였으면 orders.stockReservationId는 옛 값이다. product는 orderId로 활성선점을 찾는 reserve 명령만 제공하고
조회 API는 없다. CANCELED에 reserve/confirm을 호출할 수 없으므로 B2는 이 창의 확정선점 증거를 복구하지 못한다. **해결되지 않았다.**
C는 안전한 조회 또는 영속증거 복구 경로 및 동일 claimId 복원을 설계·검증해야 한다. 기존 주문 ID만으로 복원가능하다고 가정하지 않는다.
또한 확정→DB실패/프로세스종료 뒤 payment가 CANCELED/PARTIAL_CANCELED가 되어 재전달되면, 주문이 CANCELED가 아니어도
`REJECTED_PAYMENT_NOT_DONE` 기록이 확정선점 ID 없이 영구히 남을 수 있다. B1의 도메인/DB check는 NOT_DONE에 선점참조를 허용하지 않는다.
따라서 기존 모델로 이 변형을 해결했다고 간주하지 않는다. C는 취소주문 경합과 함께 **결제상태가 먼저 바뀐 복구창**도 모델/조회계약을 재설계하고
실패주입·동일claimId복원을 검증해야 한다. 두 변형을 증명하기 전 gate OFF를 유지한다.

**재시도와 liveness** — attempts8, initial2s, multiplier2, max60s. 7대기의 합은 **182s 순수대기**이며 성공시간 보장이 아니다.
DefaultErrorHandler의 기본 seek 방식은 실패 레코드로 되돌린 뒤 poll해 재호출한다. 따라서 전체182s를 한 번의 poll 간격으로 합산하지 않는다.
한 번의 backoff 최대60s에 한 처리의 원격/DB 대기가 더해진다. max.poll.records=1, max.poll.interval.ms=300000으로 배치 처리량을 제한한다.
원격 호출의 명목 connect2s+read3s, 재고 최초확정/재선점/즉시확정 최대3회(15s)와 payment조회(5s), 읽기/쓰기 DB구간은 각10s timeout이다.
초기 inbox읽기+최대3회 inspect/complete를 모두 합친 명목 DB70s와 원격20s+backoff60s=150s는 poll interval300s보다 작다.
다만 커넥션풀 획득 대기/드라이버·OS·DNS가 이 명목 계산에 포함되지 않고 JDBC tx timeout도 벽시계 강제종료가 아니다. 무조건 liveness를 보장하지 않는다.
운영에서 poll 지연/rebalance/DB pool timeout을 관찰하고 조정한다. 재시도 동안 같은 파티션의 뒤 주문도 지연되며 상한 뒤 DLT는 사람이 원인 해결 후 재전송한다.

## 4. 배송

송장(배송)은 **delivery-service 소유**다 — 컨텍스트 `domainlayer/shipment`, **주문당 1건**. 주문 상태 반영은 **동기 internal 명령**(Feign)이다.
outbox 이벤트로 하지 않는다 — 출고할 수 있는지를 운영자가 송장 등록 응답에서 바로 알아야 하고, `MESSAGING_ENABLED=false` 기본에서 출고가 막히면 안 된다 (#18 설계 확정).

| 배송 상태 | 뜻 | 주문 상태 |
|-----------|-----|-----------|
| `SHIPPED` | 송장 등록 · 출고 | `SHIPPING` |
| `IN_TRANSIT` | 택배사 이동 중 | `SHIPPING` (주문은 바꾸지 않는다) |
| `DELIVERED` | 배송 완료 | `DELIVERED` |

출고 대기(`READY`) 상태는 두지 않는다 — 주문 `PREPARING` 이 곧 출고 대기이고, 송장 행은 등록할 때 생긴다. 송장 행은 주문 반영 여부 `order_synced` 를 갖는다.

**송장 등록 (출고)** — 어드민 단건 · 일괄(1~20건, 건별 결과, 요청 처리 마감 8초)

1. delivery tx1 — 송장 INSERT(`order_synced=false`) + 배송 이력
2. Feign `POST /internal/v1/orders/{orderId}/shipment {deliveryId, shippedAt}` — order 가 `Order#ship` 으로 `PREPARING → SHIPPING`, `shipped_at`, `SERVICE` 이력
   (사유 `SHIPMENT_REGISTERED`). 이미 출고된 뒤면 바꾸지 않고 200
3. 200 이면 delivery tx2 — `order_synced=true`, 응답의 `memberId` · 배송 국가 스냅숏

구현(#18 PR-B1) — `POST /api/v1/admin/deliveries`(계약 `api-design-guide.md` §2-19). `ShipmentAdminFacade`(트랜잭션 `NEVER`)가 주문마다
`ShipmentRegisterProcessor#prepare`(tx1, `REQUIRES_NEW` 5초) → `OrderShipmentProcessor#ship`(트랜잭션 밖, `OrderShipmentCommandPort` · 서킷 `order-service`) →
`#confirm`(tx2) 또는 `#discard`(대기 송장 · 이력 삭제, tx2)를 부른다. 출고 시각은 처음 등록 시각(서버 `Clock`, 마이크로초)이고 `deliveryId` 는 송장 아이디다.

- **이미 송장이 있는 주문** — `order_synced=false` 면 이번 요청의 택배사 · 송장 번호로 바꿔 같은 송장 아이디로 다시 보낸다(재시도 경로). **출고 시각은 처음 값 그대로다** —
  반영 전 송장은 결과 모름으로 남은 것이라 order 가 처음 값을 이미 커밋했을 수 있고, 그러면 order 의 `shipped_at` 이 정본이다(멱등 200 은 바꾸지 않는다).
  처음 값이 30일을 넘긴 뒤의 재시도는, order 가 커밋하지 않았다면 400 `ORDER_159` → `DELIVERY_016` 으로 대기 송장이 지워지고 그다음 요청이 새 송장(새 아이디 · 그때 시각)으로 등록된다.
  `true` 면 같은 택배사 · 송장 번호는 `ALREADY_REGISTERED`(order 를 부르지 않는다), 다르면 `DELIVERY_019` 다. 같은 주문의 동시 INSERT(유일 키) · 버전 충돌은 `DELIVERY_020` 이다.
- **재시도는 이력을 더하지 않는다** — 상태가 그대로라 배송 이력의 등록자는 처음 등록한 관리자이고, 재시도한 관리자는 감사 줄(`SHIPMENT_REGISTER`)에 남는다.
- **삭제는 버전 비교다** — tx1 뒤 다른 요청이 같은 송장을 바꿨으면 지우지 않는다(`deletePending` 0건) — 그 요청의 결과가 남는다.
- **반영 완료 저장의 충돌은 지금 행에 맞춘다** — order 200 은 "주문은 출고됐다" 의 증거라 송장을 반영 전으로 남기지 않는다. `#confirm` 이 낙관적 락으로 실패하면(행 없음 포함)
  `#reconcile`(새 트랜잭션)이 주문 아이디로 다시 읽어: 행 없음 → 이번 요청 값 · 같은 송장 아이디로 반영 완료 행 + 등록 이력 INSERT, 반영 전 행 → 그 행을 반영 완료로 표시(그 행의
  송장 번호 유지), 반영된 행 → 그대로. 결과는 남은 송장이 이번 요청과 같은 택배사 · 송장 번호면 `REGISTERED`, 다른 요청의 송장이면 `DELIVERY_019`(주문은 출고됐고 이번 번호는 남지 않았다 —
  다시 보내도 같은 결과라 "성공" 으로 감추지 않는다). 다시 만들 때 유일 키 경합이면 PostgreSQL 이 실패한 트랜잭션에서 다시 읽지 못하므로 새 트랜잭션으로 한 번 더 맞추고,
  두 번 다 겹치면 `DELIVERY_020`(행 유지)이다. 남은 행이 다른 요청의 대기 송장이면 그 행의 출고 시각을 유지하므로 order 의 `shipped_at` 과 다를 수 있다 — 지운 뒤 새 등록과 늦은 order 200 이 겹치는 드문 경합이라 남은 위험으로 둔다.
- **요청 처리 마감** — 게이트웨이 `response-timeout` 10초 안에 응답하도록 상한은 20건이고, 요청 시작 + 8초(서버 `Clock`)가 지나면 남은 건은 prepare 없이 `DELIVERY_022` 다
  (`api-design-guide.md` §2-19).
- **order 응답 번역** (`OrderShipmentInternalResponseSupport`, 아래 표) — 404 `ORDER_001` → `DELIVERY_014`, 409 `ORDER_020` → `DELIVERY_015`(order 메시지의 상태 이름을 짧은 한글일 때만 옮긴다),
  400 `ORDER_159~161` → `DELIVERY_016`(이 셋은 행 삭제, 시계 차이 · 30일 지난 재시도라 error 로그), 409 `ORDER_025` → `DELIVERY_017`, 그 밖 → `DELIVERY_018`(행 유지).
  상태와 코드가 함께 맞아야 한다 — 다른 상태로 온 같은 코드 · 본문 없는 4xx 는 결과 모름이다. 200 의 배송 국가를 읽지 못하면 범위(`shipping_scope`) 없이 반영 완료로 둔다.
  회원 아이디 형식 등 **계약과 다른 200** 은 결과 모름(행 유지)이고 error 로그다 — 다시 보내도 같은 200 이 오므로 **코드를 고치기 전까지 미반영으로 남는다**.
- 응답 `status` 가 `SHIPPING` 이 아니면(이미 배송 완료 · 구매 확정 · 반품) 경고 로그만 남기고 송장은 `SHIPPED` 로 둔다 — 아래 "상태가 갈라지는 경우".
- 저장: `delivery.shipment`(`uk_shipment_order_id`, `idx_shipment_status_id` · `idx_shipment_order_synced_id`, check `ck_shipment_synced_member` · `ck_shipment_delivered_at_status`, `@Version`,
  B2 가 더한 컬럼 · 제약은 아래 "배송 완료") ·
  `delivery.shipment_status_history`(`idx_shipment_status_history_shipment_id_id`, 변경 주체 `ADMIN` · `SYSTEM`). 택배사는 `CarrierCode` enum 이다.

**배송 완료** — `PATCH /api/v1/admin/deliveries/{deliveryId}/status {DELIVERED}` → Feign `POST /internal/v1/orders/{orderId}/delivery-completion {deliveryId, deliveredAt}`
→ order 가 `Order#deliver` 로 `SHIPPING → DELIVERED`, `delivered_at`, `SERVICE` 이력(사유 `DELIVERY_COMPLETED`). 이미 배송 완료된 뒤면 200. 실패 규칙은 아래 표와 같다.

| order 응답 | delivery 처리 |
|------------|---------------|
| 200 | 응답 `status` 와 상관없이 반영 완료(등록 `order_synced=true` · 배송 완료 `completion_pending=false`). 멱등 재요청도 200 이다. `status` 는 판정용이 아니라 정보다 — 출고 응답의 `status` 가 `DELIVERED` 이상(구매 확정 · 반품 포함)이어도 송장은 자동으로 맞추지 않고 경고만 남긴다 |
| 400 `ORDER_159` · `160` · `161`(시각 범위 · 순서) | 보낸 시각이 틀렸다 — 등록이면 대기 송장 행을 지우고, 배송 완료면 송장을 직전 상태로 되돌리고(되돌림 이력 `SYSTEM`) 운영자에게 오류를 낸다(`DELIVERY_016` · `028`). 같은 값으로 다시 보내도 같은 결과다 — 배송 완료는 되돌렸으므로 다시 누르면 그때 시각으로 보낸다 |
| 404 `ORDER_001` · 409 `ORDER_020`(출고 · 완료할 수 없는 상태 — 취소 요청 · 취소 · 출고 전 등) | 등록이면 대기 송장 행을 지우고 운영자에게 오류를 낸다. 배송 완료면 직전 상태로 되돌리고 오류(`DELIVERY_014` · `027`) |
| 409 `ORDER_025`(버전 충돌) · 타임아웃 · 5xx · 서킷 오픈 | 행을 남긴다(등록 `order_synced=false`, 배송 완료 `status=DELIVERED` · `completion_pending=true`). 같은 등록 · 완료 요청을 다시 보내면 처음 시각 그대로 다시 보내 order 멱등으로 수렴한다 |
| 계약과 다른 200(회원 아이디 형식 · 다른 주문 아이디 · 상태 코드 모양 · 성공 봉투 아님) | 행을 남기고(등록 `order_synced=false` · 배송 완료 `completion_pending=true`) error 로그. 다시 보내도 같은 응답이라 **코드 수정 전까지 미반영으로 남는다** |

구현(#18 PR-B2) — `PATCH /api/v1/admin/deliveries/{deliveryId}/status`(계약 `api-design-guide.md` §2-19). `ShipmentAdminFacade#changeStatus`(트랜잭션 `NEVER`)가
`ShipmentStatusChangeProcessor#begin`(tx1, `REQUIRES_NEW` 5초) → 배송 완료면 `OrderShipmentProcessor#completeDelivery`(트랜잭션 밖, 같은 Feign 클라이언트 · 서킷 `order-service`) →
`#confirmCompletion`(tx2) 또는 `#revertCompletion`(tx2)을 부르고, 끝난 뒤 다시 읽은 상세를 돌려준다.

- **전이 표** (`ShipmentStatus#canTransitTo`) — `SHIPPED → IN_TRANSIT`, `SHIPPED → DELIVERED`, `IN_TRANSIT → DELIVERED`. 그 밖은 409 `DELIVERY_024`, 없는 송장 404 `DELIVERY_023`.
  같은 상태 요청은 바꾸지 않고 200(이력을 더하지 않는다) — 단 반영 대기 배송 완료면 아래 재시도다. 판정 순서는 404 → 같은 상태 → 출고 미반영 409 `DELIVERY_025` → 전이 표 → 저장(버전 충돌 409 `DELIVERY_026`).
- **배송 중은 delivery 안의 전이다** — 주문은 배송 중 그대로라 order 를 부르지 않는다. 이력은 `ADMIN`(토큰 memberId · 사유).
- **배송 완료 tx1** — `status=DELIVERED`, `delivered_at` = 서버 `Clock`(마이크로초), `completion_pending=true`, `previous_status` = 바꾸기 전 상태(`SHIPPED` · `IN_TRANSIT`), 이력 `ADMIN`.
  200 이면 tx2 가 다시 읽어 `completion_pending=false` · `previous_status=null`.
- **완료 반영 대기는 새 컬럼 `completion_pending` 이다 (결정)** — `order_synced` 를 "지금 상태가 order 에 반영됐는가" 로 바꿔 쓰면 B1 규칙과 부딪힌다:
  `order_synced=false` 송장은 등록 재시도가 택배사 · 송장 번호를 바꾸고(`Shipment#retarget`) 404 · 020 · 시각 거절이면 행을 지운다(`deletePending` 조건이 `order_synced = false`) —
  배송 완료 대기 송장에 그 규칙이 걸리면 출고된 송장이 지워지거나 번호가 바뀐다. 고객 노출의 "미동기 `SHIPPED` 는 null" 규칙과 등록 결과(`ALREADY_REGISTERED` 판정)도 흐려진다.
  그래서 `order_synced` 는 출고 반영만 뜻하고, 배송 완료 반영 대기는 `completion_pending` 이 따로 든다. check 제약: `ck_shipment_completion_pending_status`(대기는 `DELIVERED` 에만),
  `ck_shipment_unsynced_shipped`(출고 반영 전 송장은 `SHIPPED` — 반영 전 송장은 배송 중 · 배송 완료로 바꾸지 않는다),
  `ck_shipment_previous_status_pending`(직전 상태는 반영 대기에만, `SHIPPED` · `IN_TRANSIT` 로). 인덱스 `idx_shipment_completion_pending_id`.
  `completion_pending` 은 DDL 기본값 `false` 를 둔다(`@ColumnDefault`) — dev `ddl-auto: update` 가 행이 있는 테이블에 `not null` 컬럼을 더할 때 기존 행을 채우게.
- **재시도는 처음 배송 완료 시각을 다시 보낸다** — 반영 대기(`completion_pending=true`)에 같은 `DELIVERED` 를 보내면 tx1 은 아무것도 바꾸지 않고(이력 없음) 저장된 `delivered_at` 으로
  order 를 다시 부른다. order 가 처음 값을 이미 커밋했을 수 있어서다(B1 출고 시각과 같은 원칙). 처음 값이 30일을 넘긴 뒤의 재시도는, order 가 커밋하지 않았다면 400 `ORDER_160` 으로
  되돌려지고 다음 요청이 그때 시각으로 보낸다.
- **되돌림은 지우지 않고 줄을 더한다** — 확실한 거절(404 · 020 · 시각)이면 tx2 가 다시 읽어 지금 행이 이번에 보낸 배송 완료(반영 대기 · 같은 `delivered_at`)일 때만
  tx1 이 저장한 `previous_status` 로 되돌리고(`delivered_at` · `previous_status` 지움 · 버전 비교) `DELIVERED → 직전 상태` 이력(`SYSTEM`)을 더한다. 운영자가 누른 배송 완료는 일어난 일이라
  B1 의 대기 송장 삭제와 달리 이력을 지우지 않는다. 그 사이 다른 요청이 바꿨으면 되돌리지 않는다(결과는 order 의 거절 이유 그대로).
  직전 상태를 이력 줄(마지막 배송 완료 줄의 `fromStatus`)에서 고르지 않는 이유 — 이력 순서는 Snowflake 아이디 · 생성 시각이라 인스턴스 시계가 어긋나면 줄을 잘못 고를 수 있고,
  이력이 없으면 되돌리지 못한다. 반영 대기와 같은 행에 두면 같은 트랜잭션 · 같은 버전으로 움직인다. 되돌림 저장이 실패하면 반영 대기로 남고, 같은 변경 재시도가 다시 판정받아 되돌린다.
- **반영 완료 저장의 충돌** — order 200 뒤 tx2 가 버전 충돌 · 다른 배송 완료로 지면 새 트랜잭션으로 한 번 더 다시 읽는다(B1 `#reconcile` 과 같은 원칙 — order 200 은 반영의 증거다).
  그 사이 같은 배송 완료의 재시도가 반영을 끝냈으면(`DELIVERED` · 대기 아님) 성공이다. 두 번 다 겹치거나 행이 없거나 다른 배송 완료로 바뀌었으면 409 `DELIVERY_026` 이고
  송장은 반영 대기로 남는다(같은 요청 재시도가 수렴). 저장의 예상 못 한 오류는 `DELIVERY_031` 이고 역시 반영 대기로 남는다.
- **실패 코드** — 404 `ORDER_001` → `DELIVERY_014`(등록과 같다), 409 `ORDER_020` → `DELIVERY_027`(상태 이름은 짧은 한글일 때만), 400 시각 → `DELIVERY_028`(error 로그),
  409 `ORDER_025` → `DELIVERY_029`, 그 밖 → `DELIVERY_030`, 저장 오류 · 상태 변경 뒤 응답용 다시 읽기 실패 → `DELIVERY_031`.
- **사유** — 앞뒤 공백을 자르고 비면 `null` 로 이력에 남긴다(어드민 주문 상태 변경 사유와 같은 규칙, `ShipmentStatusChangeCommand`).
- **감사** — `SHIPMENT_STATUS_CHANGE`(대상 `delivery:<id>`, 바꿈 · 그대로 둠 `SUCCESS`, 업무 오류 `FAILURE`, `security-guide.md` §11-4). 상세 · 목록 조회는 개인정보가 없어 남기지 않는다.
  `FAILURE` 는 "요청이 오류로 끝남" 이다 — 상태 변경 여부(결과 모름 029 · 030 은 반영 대기로 남는다, 031 은 바꾼 뒤일 수 있다)의 정본은 배송 이력이다.

**상태가 갈라지는 경우 (B2 결정)** — order 어드민의 수동 배송 완료(#17)와 등록 재시도의 멱등 200(이미 배송 완료 이후)은 delivery 를 거치지 않아 송장이 `SHIPPED` · `IN_TRANSIT` 로 남을 수 있다.
조회 응답(어드민 · 고객)은 **송장 상태 그대로**이고(고객 응답의 배송 완료 반영 대기만 예외 — 아래 "고객 노출"), 어드민 상세 · 목록의 Swagger 에 주문 상태와 다를 수 있다고 적는다.
운영자가 그 송장을 `DELIVERED` 로 바꾸면 order 는 이미 배송 완료라 멱등 200 이고 송장만 맞춰진다.
**어드민 수동 배송 완료 뒤에는 delivery `delivered_at` 이 order 와 다를 수 있다** — delivery 값은 운영자가 송장을 맞춘 시각이다. 효력(구매 확정 · 반품 기간)은 order 값이 정본이고,
order 주문 응답에 `deliveredAt` 을 실어 화면이 그 값을 쓰게 맞추는 정렬은 후속이다.
order → delivery 역방향 호출은 두지 않는다(`service-inventory.md` 순환 금지). 갈라짐을 자동으로 찾는 대조 작업 · 수동 완료를 delivery 쪽으로 옮기는 일은 후속에서 본다.

- **409 는 `code` 로 나눈다** — 같은 409 라도 `ORDER_020` 은 행을 지우고 `ORDER_025` 는 행을 남긴다. HTTP 상태만 보고 처리하지 않는다.
- **시각 검증** — order 는 주문을 실제로 바꿀 때만 `shippedAt` · `deliveredAt` 을 지금 시각(order 의 `Clock`)과 견준다. 판정 순서는 404 → 이미 반영(멱등 200) →
  바꿀 수 없는 상태 409 `ORDER_020` → 범위(지금 − 30일 ~ 지금 + 5분, 밖이면 400 `ORDER_159` · `160`) → 배송 완료가 주문의 출고 시각보다 앞섬(400 `ORDER_161`,
  같은 시각은 받는다 · 출고 시각이 없는 옛 주문은 보지 않는다) → 저장(409 `ORDER_025`). 이미 반영된 주문의 재시도는 시각을 보지 않는다 — 그 사이 30일이 지나도 200 이다.
  미래 5분은 서비스 간 시계 차이, 과거 30일은 택배사 실제 시각 지연 · 밀린 등록의 상한이다 — 먼 미래는 반품 기간 · 자동 구매 확정 시작을 늦추고,
  너무 오래된 배송 완료 시각은 등록 즉시 자동 구매 확정 기간(최대 30일)을 지나 고객이 반품할 틈이 없다.
- **주문당 송장 1건 (B1 전제)** — 주문당 송장 1건(삭제 · 재등록 포함)은 delivery 가 유니크로 보장하는 전제다. order 는 `deliveryId` 를 저장하지 않으므로(추적 로그만)
  다른 송장의 재등록을 구분하지 못한다 — 다른 송장으로 출고를 보내도 이미 배송 중이면 멱등 200 이다. 그래서 **delivery 는 `order_synced=true` 인 송장을 지우거나
  다른 송장으로 바꾸지 않는다.**
- **출고 미반영 송장의 배송 상태 변경은 delivery 가 막는다(B2, 409 `DELIVERY_025`)** — `order_synced=false` 송장은 배송 중 · 배송 완료로 바꾸지 않는다. 먼저 출고 재시도로 반영한다
  (order 쪽에서도 상품 준비중 주문의 배송 완료는 `ORDER_020` 이다).
- **어드민 수동 배송 완료는 예외 경로다** — order 어드민의 `SHIPPING → DELIVERED`(#17, 사유 필수)는 delivery 를 거치지 않아 송장 상태(`SHIPPED` · `IN_TRANSIT`)와
  갈라질 수 있다. 위 "상태가 갈라지는 경우" 대로 조회는 송장 상태 그대로이고 운영자의 배송 완료로 수렴한다. 수동 완료도 `shipped_at` 은 그대로 둔다.
- **자동 재전송하지 않는다** — 주문 반영 실패로 남은 송장(출고 `order_synced=false` · 배송 완료 `completion_pending=true`)은 어드민 목록 `?synced=false` 가 둘 다 고르고,
  같은 요청 재시도(등록 다시 보내기 · 배송 완료 다시 보내기)로 복구한다. **반영 대기는 운영자가 찾아야 남지 않는다** — 송장 관리 화면은 `?synced=false` 를 따로 보여 준다(FE).
  - 가시성(B2) — 대기로 끝나는 요청은 warn(결과 모름 · 충돌) 또는 error(예상 못 한 오류) 로그(`... left pending`)를 남기고, 대기가 10분(`ShipmentAdminFacade.LONG_PENDING_THRESHOLD`)
    넘게 남은 송장의 재시도는 `shipment completion pending for long` warn 을 남긴다. 정상 경로는 order 호출 한 번(read timeout 3초) 안에 대기를 내리므로 10분은 즉시 재시도 ·
    서킷 반열림을 넘긴 "장애 뒤 방치된 대기" 만 고른다. 건수는 운영 확인 쿼리로 본다:
    `select count(*) from delivery.shipment where completion_pending and delivered_at < now() - interval '10 minutes';`
    (출고 미반영은 `not order_synced and shipped_at < now() - interval '10 minutes'`).
  - 후속 — 오래 남은 대기의 건수 지표 · 알림과 자동 재전송 스윕(대기 송장을 `(delivered_at, id)` · `(shipped_at, id)` 키셋으로 읽어 처음 시각 그대로 다시 보내는 스케줄러, order #96 스윕 패턴)은
    delivery 에 스케줄러가 처음 생기는 일이라 따로 이슈로 연다.
- **동시성** — 고객 취소와 출고가 겹치면 주문 `@Version` 비교로 한쪽만 성공한다(§1-2). order 의 출고 · 완료 반영은 주문마다 `REQUIRES_NEW`(5초)이고, 바뀌었을 때만 헤더 · 이력을 쓴다.
- **고객 노출** — 고객 `GET /api/v1/deliveries?orderId=` 는 송장의 `memberId` 스냅숏으로 delivery 가 로컬에서 소유를 판정한다. 송장이 없거나 남의 것 · 미동기 `SHIPPED` 면
  200 + `null` 이다 — 주문 존재 · 소유 404 는 주문 상세(order-service) 몫이다 (`api-design-guide.md` §2-2). ADMIN 토큰은 403 이다(구현 #18 PR-B2).
  보이는 상태는 송장 상태 그대로다 — **단 배송 완료 반영 대기(`completion_pending=true`)는 배송 중(`IN_TRANSIT`) · `deliveredAt=null` 로 보인다**(`ShipmentWebPresenter`).
  주문이 아직 배송 중이라 반품 · 구매 확정을 할 수 없는데 배송 완료로 보이면 고객 화면이 주문 상세와 어긋나고, order 가 거절하면 되돌려질 수도 있다. 어드민 응답은 송장 그대로다.
- **자동 구매 확정** (#18 PR-C) — `OrderAutoConfirmationScheduler → OrderAutoConfirmationUseCase → OrderAutoConfirmationFacade → OrderAutoConfirmationProcessor` 순서다.
  `order.purchase-confirmation.auto` 기본값은 `enabled=true`, `period=7d`(1d~30d, 양 끝 포함), `interval=1h`(1ms 이상), `batch-size=100`(1~100),
  `max-batches-per-run=10`(1~10)이며 잘못된 설정은 기동을 막는다. test 프로파일은 `enabled=false`다. 첫 실행도 interval 뒤에 시작하고 실행 종료 뒤 같은 간격을 둔다.
  실행 시작에 `cutoff = now - period`를 마이크로초로 잘라 고정한다. `status = DELIVERED and delivered_at <= cutoff`를 `(delivered_at, id)` 오름차순 키셋으로 읽는다.
  첫 배치 · 다음 배치를 분리하고 다음 배치는 같은 뜻의 하한 `delivered_at >= afterDeliveredAt`도 적어 기존 `idx_orders_status_delivered_at_id`를 재사용한다.
  배송 완료 시각이 null인 옛 주문 · 기간 전 주문 · 구매 확정 · 반품 요청/완료 등 다른 상태는 제외한다. 실패한 건도 커서를 전진시키고 다음 실행에서 처음부터 재시도한다.
  Facade는 트랜잭션 밖(`NEVER`), 조회는 읽기 전용 5초, 주문마다 `REQUIRES_NEW` 5초다. 건별 집계 재조회(기존 4문장)는 배치 조립 N+1이 아닌 전이 경계다.
  `Order.autoConfirmPurchase(cutoff)`가 상태와 시각을 다시 확인하고 `PURCHASE_CONFIRMED`로 바꾼다. `saveStatusChange`는 헤더만 merge/flush해 `@Version`을 검사한 뒤
  `SYSTEM` · `AUTO_PURCHASE_CONFIRMATION` 이력을 같은 트랜잭션에 쓴다. 이력 실패도 상태와 함께 롤백하고 배송지 · 항목 · 금액 · 배송 완료 시각은 보존한다.
  이미 확정되었으면 저장하지 않으며 동시 확정 · 반품 경합은 하나만 커밋한다. ShedLock · 결제/재고 호출 · outbox 이벤트는 없다.
  버전 충돌은 WARN, 그 밖의 건별 실패는 ERROR(아이디 · 예외 타입 · 루트 타입 · `RedactedThrowable`, 행 값/개인정보 원문 없음) 뒤 다음 주문을 처리한다.
  **배포 전** `delivered_at` 백필은 여전히 필요하다(§1-1). 최초 배송 완료 이력의 `created_at`을 쓰고, 이력이 없는 주문은 null로 남겨 운영 확인한다.
  자동 확정 활성화 전에 백필 결과와 보류 건수를 확인한다. 이 PR은 새 스키마 변경이 없고 기존 nullable 컬럼 · 인덱스를 사용한다.
- **택배사 추적** (`ShipmentTrackingPort`) — 결정만 둔다: 추적 결과로 `IN_TRANSIT` · `DELIVERED` 를 바꾸고, 실패해도 주문 흐름을 막지 않으며, 어드민 수동 변경이 남는다.
  포트 · 어댑터는 후속 이슈다. 택배사 코드는 enum 으로 관리한다.
- 통관 품목 internal(해외 출고) · 송장 출력용 배송지 원문 · 주문 상세의 배송 요약 합성은 후속 이슈다.
- 해외 배송비는 국가 그룹 · 무게 구간으로 정한다(§2, #22). 무게는 견적 요청이 싣고(SKU 무게 × 수량 합 — §2-2), 무게 · HS 코드는 product-service SKU 에 있다(#80).
  통관 정보(HS 코드, 신고 금액 = 주문 항목의 주문 시점 금액)는 **주문 항목 스냅숏**에서 송장 · 출고(#18)로 넘긴다 — §2-2.
- 배송지 정보는 주문 시점 스냅숏이다. 회원 주소록을 바꿔도 지난 주문 배송지는 바뀌지 않는다.
- **주문 생성 요청은 배송지 값 자체를 싣는다** (FE 가 주소록에서 골라 채우거나 새로 입력한다). order-service 는 그 값을 스냅숏으로 저장하고
  auth-service 주소록을 조회하지 않는다 (#7 결정). 그래서 지금은 auth-service 에 배송지 internal API 를 만들지 않는다 — 필요해지면 주문 생성(#13)에서 이슈로 연다.
  주문 화면의 "주소록에 저장" 은 FE 가 주소록 API(`POST /api/v1/members/me/shipping-addresses`, `api-design-guide.md` §2-4)를 따로 부른다.
  스냅숏의 형식 검증은 주소록과 같은 규칙(국가 코드 · 우편번호 · 연락처 · 해외 도시)을 order-service 가 다시 건다 — 클라이언트 값을 그대로 믿지 않는다.
- **미결 — 프리오더 상품 (#61 에서 상품 데이터만 추가, 주문 쪽은 구현하지 않음)**: 프리오더(`product.preorder` · 발송 예정일)는 지금 판매 · 재고 판정에 영향이 없다.
  아래는 주문 생성(#13) 이후 정한다.
  - 프리오더 + 일반 상품이 한 주문에 섞일 때 배송을 나누는가(분리 출고 · 배송비 이중 부과), 아니면 섞어 담지 못하게 막는가
  - 주문 항목 스냅숏에 프리오더 여부 · 발송 예정일을 남기는가
  - 발송 예정일이 바뀌면 이미 주문한 고객에게 알리는가

## 5. 클레임 (취소 · 반품 · 교환)

- 클레임은 `claim` 엔티티로 따로 둔다 (유형, 대상 주문 항목, 수량, 사유, 상태, 환불 예정 금액).
- **부분 클레임**을 전제로 설계한다 — 주문 3개 중 1개만 반품.
- 환불 금액 계산(배송비 차감, 무료배송 기준 미달 시 재부과)은 order-service 가 하고, 실행은 payment-service 가 한다.
- 환불 실행이 실패하면 클레임은 `REFUND_FAILED` 로 남기고 어드민에 노출한다. 조용히 완료 처리하지 않는다.
- **재고 복원은 클레임당 한 번이다** — `claimId` 가 product-service 복원 API 의 멱등 키다(§3-2 "결제 후 복원"). 결제 후 취소는 취소 확정 때,
  반품은 검수 뒤 어드민이 재고로 되돌림을 고른 경우만 부른다. 확정 수량을 넘는 복원은 product-service 가 409 로 거절한다.
  - 결제 후 취소는 경로(고객 · 어드민 · PG 웹훅 / 콘솔 취소 · 자동 전액 취소)와 상관없이 **클레임 행을 먼저 만들고** 그 아이디로 복원한다. 재시도마다 새 아이디를 만들지 않는다.
  - 클레임에 **"재고 복원 대기"** 를 두고, 복원이 실패하면 스케줄러가 같은 claimId · 같은 본문으로 다시 보낸다 (클레임 쪽 영속 재전송이다 — 결제 반영은 재전송 대신 이벤트 재전달 · DLT, `payment-guide.md` §6).
  - 복원을 부른 클레임은 **철회하지 않는다** — `REFUND_FAILED` 면 환불만 재시도한다. 복원을 되돌리는 API 는 없다. 상세는 §3-4.
- 교환은 v1 에서 "반품 + 재주문" 으로 운영하고, 전용 흐름은 필요해질 때 만든다.

## 6. 어드민 운영 화면이 보는 것

어드민 API 는 위 상태를 그대로 다룬다. 최소 범위:

- 주문 목록(상태·기간·결제수단 필터, `PageResponse`), 주문 상세(항목·결제·배송·이력) — #17 PR-1 이 상태 · 기간 필터와 상세(이력 · 다음 상태)를 연다.
  결제수단 필터 · 결제 정보는 결제 연동(#14) 뒤다
- 상태 일괄 변경(`PAID → PREPARING`) — #17 PR-1(`POST /api/v1/admin/orders/status-changes`, 주문마다 따로 · 건별 결과), 송장 일괄 등록(#18)
- 취소·반품 요청 처리(승인/거절, 환불 실행)
- 결제 `UNKNOWN`·`REFUND_FAILED`·대사 불일치 건 목록 — **사람이 봐야 하는 건을 모아서 보여준다**
