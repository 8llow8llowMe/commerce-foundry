---
name: order-flow-guard
description: "Commerce Foundry 주문·재고·배송·클레임 변경의 상태 전이, 서버 금액, 스냅숏, 재고 선점·해제 멱등성과 동시성을 읽기 전용으로 점검한다."
---

# Order Flow Guard

주문·재고·배송 상태가 **어긋나는** 결함(초과 판매, 이중 해제, 불가능한 전이, 금액 변조)을 찾는다.
코드를 고치지 않고 보고한다.

## Read First

1. [backend/docs/order-flow-guide.md](../../../backend/docs/order-flow-guide.md) — 전 절
2. [backend/docs/payment-guide.md](../../../backend/docs/payment-guide.md) §6 (결제 ↔ 주문 동기화)
3. [backend/docs/coding-conventions.md](../../../backend/docs/coding-conventions.md) §9-3, §9-5

## 대상 확보

```bash
git diff --name-only origin/develop...HEAD -- backend/service/order-service backend/service/product-service backend/service/delivery-service
git status --short -- backend
```

상태 enum 이 바뀌었으면 **그 enum 을 쓰는 모든 전이 지점**(고객 API, 어드민 API, internal, 스케줄러)을 함께 본다.

## What to Check

1. **상태 전이** — HIGH
   - 상태가 도메인 메서드로만 바뀌는가 (`setStatus`, 엔티티 필드 직접 대입, JPQL `update ... set status` ✕)
   - 허용 전이 표가 도메인에 있고, 표 밖 전이를 거절하는가
   - **어드민 상태 변경 API 도 같은 도메인 메서드를 거치는가** — 어드민이라고 아무 상태로나 바꾸지 않는다
   - `order_status_history` 에 이전 → 이후, 행위자, 사유가 남는가

2. **주문 금액** — CRITICAL
   - 금액을 서버에서 계산하는가, 프론트 금액·가격을 쓰지 않는가
   - 주문 항목에 가격·상품명·옵션명 **스냅숏**을 저장하는가
   - 주문서 미리보기와 주문 생성이 같은 계산 코드를 쓰는가

3. **재고** — CRITICAL
   - 차감이 조건부 UPDATE 한 문장인가 (조회 후 차감 ✕), 영향 행 0 을 재고 부족으로 처리하는가
   - 선점 → 확정 / 해제 / 복원이 **멱등**인가 (선점 행 상태로 판정)
   - 여러 SKU 선점이 ID 오름차순이고, 중간 실패 시 앞서 잡은 것을 해제하는가
   - 선점 만료 TTL 이 PG 인증 제한 시간보다 긴가, 설정값인가

4. **동시성** — HIGH
   - 주문·재고·클레임 엔티티에 `@Version` 또는 조건부 UPDATE 가 있는가
   - 고객 취소 vs 어드민 출고처럼 동시에 올 수 있는 전이가 하나만 성공하는가
   - 결제 완료 internal 재전송에 멱등한가

5. **배송** — MEDIUM
   - 송장 등록이 주문 상태 전이(`SHIPPING`)와 함께 일어나는가, 한쪽만 바뀌는 실패 경로가 있는가
   - 택배사 추적 실패가 주문 흐름을 막지 않는가
   - 배송지가 주문 시점 스냅숏인가

6. **클레임** — HIGH
   - 부분 클레임(항목·수량 단위)을 지원하는가
   - 환불 금액 계산이 order-service 에 있고, 실패가 `REFUND_FAILED` 로 남는가
   - 취소·반품 시 재고 복원이 클레임당 한 번만 일어나는가

7. **스토어 설정**
   - 맞춤 제작(`MAKING`) 같은 브랜드 특화 흐름이 `store.features.*` 플래그 뒤에 있는가 (`if (mokoyuri)` ✕)

```bash
rg -n 'setStatus\(|\.status = ' backend/service/order-service backend/service/product-service backend/service/delivery-service -g '*.java'
rg -n 'set s\.available|available =' backend/service/product-service -g '*.java'
rg -l '@Version' backend/service/order-service backend/service/product-service backend/service/delivery-service -g '*.java'
```

## Output Format

```text
ORDER FLOW GUARD REPORT
=======================

Scope: [files / flows]

Findings:
1. [CRITICAL|HIGH|MEDIUM|LOW] [file:line]
   - 어떤 순서·동시 요청에서 상태가 어긋나는가 (구체적 시나리오)
   - expected rule (order-flow-guide §)
   - recommended fix

Transition Coverage:
- [상태] → [상태]: 진입 지점 / 테스트 유무

Untested Paths:
- ...
```
