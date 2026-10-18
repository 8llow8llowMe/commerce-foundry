# Order Flow Guide

> 주문 · 재고 · 배송 · 클레임의 **상태 전이** 정본이다. order-service / product-service(재고) / delivery-service 가
> 이 문서를 따르고, `/order-flow-guard` 스킬이 이 문서로 점검한다. 결제 상태는 `payment-guide.md` §5.

## 1. 주문 상태

```text
PENDING_PAYMENT ──▶ PAID ──▶ PREPARING ──▶ SHIPPING ──▶ DELIVERED ──▶ PURCHASE_CONFIRMED
      │               │  └─▶ MAKING ─┘(주문 제작)            │
      ▼               ▼                                       ▼
   EXPIRED      CANCEL_REQUESTED ──▶ CANCELED         RETURN_REQUESTED ──▶ RETURNED
```

| 상태 | 뜻 | 누가 바꾸나 |
|------|-----|-------------|
| `PENDING_PAYMENT` | 주문 생성, 결제 대기 (재고 선점 중) | 고객 |
| `EXPIRED` | 결제 없이 선점 시간 초과 | 스케줄러 |
| `PAID` | 결제 완료 | payment-service (internal) |
| `MAKING` | 맞춤 제작 중 — `store.features.custom-order` 가 켜진 상품만 | 어드민 |
| `PREPARING` | 출고 준비 | 어드민 |
| `SHIPPING` | 송장 등록, 배송 중 | delivery-service / 어드민 |
| `DELIVERED` | 배송 완료 | 택배사 추적 / 어드민 |
| `PURCHASE_CONFIRMED` | 구매 확정 (고객 확정 또는 N일 경과 자동) | 고객 / 스케줄러 |
| `CANCEL_REQUESTED` → `CANCELED` | 출고 전 취소 (환불 포함) | 고객 요청 → 어드민/자동 승인 |
| `RETURN_REQUESTED` → `RETURNED` | 배송 후 반품 (회수 후 환불) | 고객 요청 → 어드민 |

### 1-1. 전이 규칙 (필수)

- **상태는 도메인 메서드로만 바꾼다** — `order.markPaid(...)`, `order.startPreparing()`, `order.ship(...)`.
  허용 전이 표를 도메인 안에 두고, 표에 없는 전이는 `ORDER_STATUS_TRANSITION_INVALID` 로 거절한다.
- 엔티티에 `setStatus()` 를 두지 않는다. 어드민의 "상태 변경" API 도 도메인 메서드를 거친다.
- **고객 취소 가능 범위**: `PAID` · `MAKING`(제작 착수 전 정책은 스토어 설정) · `PREPARING`. `SHIPPING` 부터는 반품 경로다.
- 맞춤 제작 상품은 **제작 착수 후 단순 변심 취소 불가**가 일반적이다 — 정책 문구와 함께 스토어 설정으로 둔다.
- 상태 변경 이력은 `order_status_history` 에 남긴다 (언제, 누가, 이전 → 이후, 사유). 어드민이 바꾼 것은 관리자 ID 를 남긴다.

### 1-2. 동시성

- 주문 행에 `@Version` 을 둔다. 고객 취소와 어드민 출고 처리가 동시에 오면 하나만 성공해야 한다.
- 결제 완료 알림(internal)은 재전송될 수 있다 — **이미 `PAID` 이후 상태면 성공으로 응답**하고 아무것도 바꾸지 않는다.

## 2. 주문 금액

- 주문 금액은 **order-service 가 서버에서 계산한다**: Σ(SKU 판매가 × 수량) + 옵션 추가금 − 할인 + 배송비.
- 가격·옵션 추가금은 주문 시점에 product-service 에서 조회하고 **주문 항목에 스냅숏으로 저장**한다.
  나중에 상품 가격이 바뀌어도 지난 주문은 바뀌지 않는다. 상품명·옵션명도 스냅숏한다.
- 프론트가 보낸 금액·가격은 쓰지 않는다. 주문서 미리보기(`POST /api/v1/orders/quotes`)와 실제 주문 생성은
  같은 계산 코드를 쓴다.
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
| 상한 | 줄 50개, 줄당 수량 1~99 이고 SKU 의 `maxQuantityPerOrder` 가 더 작으면 그것. **50줄은 느슨한 상한이다** — 줄 수를 센 뒤 넣으므로 동시 담기 · 병합에서 소폭 넘을 수 있다. SKU 조회는 100개씩 나눠 불러 넘어도 조회 · 쓰기 응답 · 삭제가 막히지 않는다 |
| 담기 거절 | 없는 SKU 404, `purchasable=false`(품절 · 판매 종료 · 숨김) 409, 맞춤 제작이 아닌데 입력이 있음 400, 수량 · 줄 상한 초과 409. 입력값이 상품 입력 항목 정의에 맞는지는 주문 생성(#13)이 본다 |
| 수량 변경 · 삭제 | 품절 · 판매 종료 줄도 허용한다. 수량 변경은 트랜잭션 두 구간(소유 확인 → 원격 상한 조회 → 다시 읽어 쓰기)이라 사이의 변경은 마지막 쓰기가 이긴다. `@Version` 409(`CART_007`)는 쓰기 구간 안의 동시 수정에서만 난다 |
| 조회 상태 | SKU 없음 → `STOP_SELLING`, `purchasable` → `ON_SALE`, 판매 상태 `SOLD_OUT` → `SOLD_OUT`(숨긴 상품이라도 품절이면 품절), 그 밖(판매 종료 · 품절이 아닌 숨김) → `STOP_SELLING`. 합계는 `ON_SALE` 이고 수량이 현재 상한 안인 줄만 더한다(없으면 null) |
| 병합 | 로그인 직후 로컬 장바구니를 한 번 보낸다(최대 50개, 오래 전에 담은 것부터 — 새 줄은 요청 순서로 아이디가 커져 나중 항목이 위에 보인다). 담을 수 없는 항목은 **항목 단위로** 건너뛰어 `skippedItems: [{index, skuId, reason}]` 로 알린다 — reason 은 `SKU_NOT_FOUND` · `NOT_PURCHASABLE` · `CUSTOM_OPTION_NOT_ALLOWED` · `LINE_LIMIT_EXCEEDED` · `QUANTITY_LIMIT_EXCEEDED`(1회 주문 최대 수량 0) 순으로 판정한다. 같은 SKU 라도 항목마다 따로 판정한다. 같은 줄은 더한 뒤 상한에서 자른다(거절하지 않는다, 이미 넘은 줄을 줄이지 않는다). **멱등하지 않다** — 재전송하면 한 번 더 더해진다. 결제 · 재고와 무관하고 상한이 피해를 제한해 멱등 키를 두지 않는다 |
| 원격 호출 | SKU 는 `catalog` 컨텍스트(`CatalogSkuProcessor`, 주문 생성과 공용)가 `GET /internal/v1/skus` 로 몇 줄이든 100개씩 묶어 부른다(보통 한 번, 장바구니가 비면 호출하지 않는다). Facade 에 트랜잭션을 걸지 않고 DB 구간만 Processor 에서 좁힌다. product-service 장애는 503 `CART_008` 이다(`CATALOG_001` 을 `CartExceptionHandler` 가 번역) — 쓰기 뒤 장바구니 재조회가 실패해도 쓰기는 커밋돼 있다 |

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
| 결제 후 취소 · 반품 입고 | 복원 | `available += qty` (반품은 검수 후 어드민이 복원 여부 선택) |

- **조회 후 차감(read-modify-write)을 쓰지 않는다** — 동시 주문에서 초과 판매가 난다. 조건부 UPDATE 한 문장으로 한다.
  영향 행 수가 0 이면 재고 부족(`STOCK_INSUFFICIENT`)이다.
- 선점 만료 시간은 **PG 결제 인증 제한 시간보다 길게** 잡는다 (결제창을 열어 둔 고객의 재고가 먼저 풀리지 않게).
  설정 키는 product-service 의 `stock.reservation.ttl` 이고 기본값 `30m` 은 **결제창 제한 시간을 확인하기 전 임시값**이다.
- 해제·복원은 **멱등**이어야 한다. 선점 행의 상태로 이미 처리됐는지 판단한다 — 같은 해제가 두 번 오면 재고가 늘어난다.
- 여러 SKU 를 선점할 때는 **SKU ID 오름차순**으로 처리한다 (교착 방지). 하나라도 실패하면 앞서 잡은 것을 해제한다.
- 맞춤 제작 상품은 재고 무제한(`stock_managed = false`) 옵션을 둔다.

### 3-1. 재고 데이터 (product-service, #10)

| 테이블 | 내용 |
|--------|------|
| `stock` | SKU 와 1:1 (`sku_id` PK) — `stock_managed`, `available_quantity >= 0`. **행이 없는 SKU 는 품절**이다(닫힌 기본값). `@Version` 없음 — 모든 쓰기가 조건부 UPDATE |
| `stock_reservation` | 주문 하나에 한 행 — `status`, `expires_at`, `confirmed_at` · `released_at` · `release_reason`, `@Version`. `active_order_id` 는 RESERVED · CONFIRMED 동안만 `order_id` 이고 RELEASED 면 null — 유니크 키가 **주문당 활성 선점을 하나**로 막는다 |
| `stock_reservation_item` | 선점 × SKU — `quantity > 0`, `deducted`. 무제한 SKU 는 `deducted = false` 로 차감하지 않고 해제 때 되돌리지 않는다 |
| `stock_adjustment_history` | 어드민 조정 이력 (append-only) |

- 어드민 재고 조정(`PATCH /api/v1/admin/stocks/{skuId}`)은 수량 **절대값 + 기대 수량 CAS**(`expectedQuantity`) 다 — 그 사이 주문이 차감했으면 409 `PRODUCT_020`.
  재고 행이 없는 SKU 의 첫 등록은 INSERT 이고, 동시 첫 등록은 PK 충돌로 409 다.

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
| `POST /internal/v1/stock-reservations/{reservationId}/release` | 해제 (결제 전 취소 · 주문 생성 실패) |

응답은 셋 다 같은 모양이고 신규 · 기존 모두 200 이다 —
`{ reservationId: String, orderId: String, status, expiresAt(UTC ISO-8601), items: [{ skuId: String, quantity, deducted }] }`. 항목은 SKU 아이디 오름차순이다.

선점 처리 순서 (Facade 트랜잭션 하나, 기본 격리 READ COMMITTED):

1. 같은 `skuId` 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다.
2. 같은 주문의 활성 선점이 있으면 — SKU 별 수량이 같으면 그 선점을 그대로 200(멱등), 다르면 409 `PRODUCT_023`.
3. SKU · 상품(숨김 포함)을 in 절로 읽어 판매 가능 조건(상품 노출 · 판매중 && SKU 판매중 — internal SKU 조회의 `purchasable` 과 같은 규칙에서 재고만 뺀 것)을
   확인한다. 없는 SKU · 숨긴 상품 · 판매 종료는 모두 모아 409 `PRODUCT_022`.
4. 선점 **헤더만** INSERT 하고 flush 한다(RESERVED, `expires_at = now + ttl`) — 같은 주문의 동시 생성은 유니크 키에서 먼저 커밋된 쪽을 기다렸다가
   409 `PRODUCT_026` 로 끝나 **재고를 건드리지 않는다**. 차감을 먼저 하면 재고가 빠듯할 때 중복 요청이 409 대신 `PRODUCT_021` 을 받는다.
5. **모든 항목**에 SKU 오름차순으로 조건부 차감(`stock_managed = true and available_quantity >= qty`)을 시도한다. 1행이면 `deducted = true`.
6. 0행인 SKU 만 재고 행을 in 절로 **다시 읽는다**(같은 트랜잭션, READ COMMITTED 라 최신 커밋 값). 무제한이면 `deducted = false` 로 선점하고,
   관리 중 부족이거나 **행이 없으면(품절)** 부족이다. 차감 여부를 미리 읽은 스냅숏으로 정하지 않는다 — 읽은 뒤 어드민이 관리 여부를 바꾸면
   관리 SKU 를 차감 없이 선점하거나 무제한 SKU 를 부족으로 거절한다(TOCTOU).
7. 부족한 SKU 가 하나라도 있으면 모두 모아 409 `PRODUCT_021`("…: [1001, 1003]") — **예외가 같은 트랜잭션을 롤백해 앞서 차감한 재고와 헤더가 함께 사라진다(전체 해제)**.
   따로 보상 해제를 부르지 않는다. 없으면 `deducted` 가 정해진 항목을 INSERT 한다.

### 3-3. 선점 상태 전이

```text
RESERVED ──확정──▶ CONFIRMED
   └──────해제 · 만료──▶ RELEASED (release_reason = RELEASED_BY_CALLER / EXPIRED)
```

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
- **만료 후 확정** — 스케줄러가 먼저 풀었으면 확정은 409 `PRODUCT_025` 다. order-service 의 처리는 아래 §3-4 를 따른다.
- 허용 전이 표 밖의 요청은 도메인이 `StockReservationTransitionException`(현재 상태 · 시도한 동작)으로 알리고, `StockReservationProcessor` 가
  `PRODUCT_024` · `PRODUCT_025` 를 붙인다 — domain 은 application 의 오류 코드를 모른다 (`coding-conventions.md` §8-1).

| 코드 | HTTP | 뜻 |
|------|------|----|
| `PRODUCT_003` | 404 | 없는 재고 선점 |
| `PRODUCT_021` | 409 | 재고 부족 (행 없는 SKU 포함) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_022` | 409 | 판매 불가 SKU (없는 SKU · 숨긴 상품 · 판매 종료) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_023` | 409 | 같은 주문의 활성 선점과 항목이 다르다 |
| `PRODUCT_024` | 409 | 이미 확정된 선점의 해제 |
| `PRODUCT_025` | 409 | 이미 해제 · 만료된 선점의 확정 |
| `PRODUCT_026` | 409 | 동시 변경(`@Version`) · 같은 주문의 동시 선점 생성 — 다시 시도한다 |
| `PRODUCT_112`~`PRODUCT_123` | 400 | 요청 검증 (`ProductValidationMessage`) — 선점 수량 상한 `PRODUCT_122`, 어드민 조정 경로의 `skuId` 양수 `PRODUCT_123` |

- **결제 후 취소 · 반품의 재고 복원**(위 표의 "복원")은 이 API 범위 밖이다 — 확정된 선점은 해제하지 않는다. 후속 이슈에서 다룬다.

### 3-4. order-service 호출 규칙

- **주문을 PAID 로 확정하기 전에 선점 확정(`/confirmation`)의 200 · `CONFIRMED` 응답을 받아야 한다.** 확정 응답 없이 PAID 로 두면 만료 스케줄러가 선점을 풀어
  돈은 받았는데 재고는 다른 주문에 팔린다. 결제 완료 알림이 다시 와도(웹훅 재전송 · 재조회) 확정을 **멱등으로 다시 부른다** — 이미 CONFIRMED 면 200 이다.
- **만료 후 확정(409 `PRODUCT_025`)** — 같은 `orderId` 로 다시 선점하고(해제된 주문은 활성 유니크 키에 걸리지 않는다) **곧바로 같은 흐름에서 확정**한다.
  재선점과 확정 사이를 비우면 새 선점도 `ttl` 뒤 다시 만료돼 같은 경합이 되풀이된다. 재선점이 `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면
  결제를 전액 취소한다 (`payment-guide.md` §6).
- **409 는 `resultCode` 로 분기한다.** `PRODUCT_026`(동시 변경 · 동시 생성)만 재시도 대상이고 **새 트랜잭션(새 요청)** 으로 다시 부른다 — 다시 부르면 바뀐 상태를
  기준으로 답한다. `PRODUCT_021` · `PRODUCT_022` · `PRODUCT_023` · `PRODUCT_024` · `PRODUCT_025` 는 재시도해도 결과가 같은 종결 오류다.
- **409 를 같은 트랜잭션 안에서 잡고 계속하지 않는다.** product-service 안에서도 어댑터가 409 로 바꾼 시점에는 flush 가 이미 실패해 트랜잭션이 rollback-only 다 —
  잡고 계속하면 커밋에서 실패한다. order-service 도 Feign 409 를 받은 트랜잭션에서 주문 상태를 바꾸고 재시도까지 하지 말고, 재시도는 새 트랜잭션에서 한다.
- **확정 · 해제는 `reservationId` 하나로 식별한다** — 서비스 토큰(`SERVICE_order-service`)을 가진 호출자를 신뢰한다는 전제다. order-service 는 자기 주문의
  `reservationId` 만 저장 · 사용한다.
- **오류 메시지를 고객 응답에 그대로 넘기지 않는다.** `PRODUCT_021` · `PRODUCT_022` 메시지에는 SKU 아이디 목록이 실린다 — order-service 는 자기 오류 코드
  (예: `ORDER_*`)와 고객용 문구로 바꾸고, 필요한 SKU 정보는 자기 주문 항목에서 골라 보여 준다.

## 4. 배송

| 상태 | 뜻 |
|------|-----|
| `READY` | 출고 대기 (주문 `PREPARING`) |
| `SHIPPED` | 송장 등록 (주문 `SHIPPING`) |
| `IN_TRANSIT` | 택배사 이동 중 |
| `DELIVERED` | 배송 완료 (주문 `DELIVERED`) |

- 송장 등록은 어드민에서 단건/일괄(엑셀)로 한다. 택배사 코드는 enum 으로 관리한다.
- 배송 상태 추적은 외부 추적 API(`ShipmentTrackingPort`)로 하되, 실패해도 주문 흐름을 막지 않는다. 어드민이 수동으로도 바꿀 수 있다.
- 해외 배송비는 국가 그룹 · 무게 구간으로 정한다(§2, #22). 무게는 견적 요청이 싣고(SKU 무게 × 수량 합), SKU 무게 · HS 코드 필드는
  product-service 후속 이슈, 통관 신고 금액(주문 시점 금액)은 송장 · 출고(#18) · 주문(#13)에서 다룬다.
- 배송지 정보는 주문 시점 스냅숏이다. 회원 주소록을 바꿔도 지난 주문 배송지는 바뀌지 않는다.

## 5. 클레임 (취소 · 반품 · 교환)

- 클레임은 `claim` 엔티티로 따로 둔다 (유형, 대상 주문 항목, 수량, 사유, 상태, 환불 예정 금액).
- **부분 클레임**을 전제로 설계한다 — 주문 3개 중 1개만 반품.
- 환불 금액 계산(배송비 차감, 무료배송 기준 미달 시 재부과)은 order-service 가 하고, 실행은 payment-service 가 한다.
- 환불 실행이 실패하면 클레임은 `REFUND_FAILED` 로 남기고 어드민에 노출한다. 조용히 완료 처리하지 않는다.
- 교환은 v1 에서 "반품 + 재주문" 으로 운영하고, 전용 흐름은 필요해질 때 만든다.

## 6. 어드민 운영 화면이 보는 것

어드민 API 는 위 상태를 그대로 다룬다. 최소 범위:

- 주문 목록(상태·기간·결제수단 필터, `PageResponse`), 주문 상세(항목·결제·배송·이력)
- 상태 일괄 변경(`PAID → PREPARING`), 송장 일괄 등록
- 취소·반품 요청 처리(승인/거절, 환불 실행)
- 결제 `UNKNOWN`·`REFUND_FAILED`·대사 불일치 건 목록 — **사람이 봐야 하는 건을 모아서 보여준다**
