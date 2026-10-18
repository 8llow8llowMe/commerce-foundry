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
  order-service 는 `POST /internal/v1/shipping-fees/quotes` 의 `shippingFee` 를 더하고 주문에 스냅숏한다. 계산 계약(#11):

  | 항목 | 규칙 |
  |------|------|
  | 기준 금액(`orderAmount`) | **할인 후 상품 합계(배송비 제외)**. delivery 는 받은 값을 비교만 한다 |
  | 무료배송 | 기준 금액 **이상**이면 기본 배송비 0. 기준이 없으면(null) 항상 기본 배송비 |
  | 도서산간 추가금 | 우편번호(5자리) 구간에 들면 **무료배송이어도 별도 부과** |
  | 정책 미설정 | 409 `DELIVERY_001` — 0원 배송으로 열리지 않게 주문을 막는다 |
  | 국내 외 국가 · 통화 불일치 | 400 `DELIVERY_002` · `DELIVERY_003` (해외는 #22) |

  order-service 는 견적 응답의 기본 배송비 · 무료배송 기준 · 도서산간 추가금 · 정책 버전을 **주문에 스냅숏한다**.
  §5 의 "무료배송 기준 미달 시 재부과" 는 이 스냅숏(주문 시점 기준 · 기본 배송비)으로 order-service 가 계산하고,
  **현재 정책으로 다시 견적하지 않는다** — 주문 뒤 정책이 바뀌어도 지난 주문의 환불액은 바뀌지 않는다. 배송지가 그대로면 도서산간 추가금은 다시 매기지 않는다.

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
  기본값은 설정(`order.stock-reservation-ttl`)으로 둔다.
- 해제·복원은 **멱등**이어야 한다. 선점 행의 상태로 이미 처리됐는지 판단한다 — 같은 해제가 두 번 오면 재고가 늘어난다.
- 여러 SKU 를 선점할 때는 **SKU ID 오름차순**으로 처리한다 (교착 방지). 하나라도 실패하면 앞서 잡은 것을 해제한다.
- 맞춤 제작 상품은 재고 무제한(`stock_managed = false`) 옵션을 둔다.

## 4. 배송

| 상태 | 뜻 |
|------|-----|
| `READY` | 출고 대기 (주문 `PREPARING`) |
| `SHIPPED` | 송장 등록 (주문 `SHIPPING`) |
| `IN_TRANSIT` | 택배사 이동 중 |
| `DELIVERED` | 배송 완료 (주문 `DELIVERED`) |

- 송장 등록은 어드민에서 단건/일괄(엑셀)로 한다. 택배사 코드는 enum 으로 관리한다.
- 배송 상태 추적은 외부 추적 API(`ShipmentTrackingPort`)로 하되, 실패해도 주문 흐름을 막지 않는다. 어드민이 수동으로도 바꿀 수 있다.
- 해외 배송(EMS 등)은 국가 코드·무게·통관 정보(HS 코드, 신고 금액)를 별도로 받는다 — 해외 판매 이슈에서 확정한다.
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
