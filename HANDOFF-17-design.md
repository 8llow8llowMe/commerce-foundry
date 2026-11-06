# #17 설계안 — 클레임 승인·거절, 환불 실행, REFUND_FAILED 재시도 (architect, 2026-11-06, develop 2dd1b858)

경로 약어: `$O` = `backend/service/order-service/src/main/java/com/commercefoundry/domainlayer/order`, `$D` = `backend/docs`, `$T` = `backend/service/order-service/src/test`

## 1. 현재 상태 사실

- **주문 전이 표** (`$O/domain/model/OrderStatus.java:80-95`)
  - `CANCEL_REQUESTED → CANCELED`, `RETURN_REQUESTED → RETURNED` 뿐이다.
  - RETURNING·RETURN_REJECTED 같은 상태와 거절 전이(취소 요청 → 원상태, 반품 요청 → DELIVERED)가 없다.
  - `Order.changeStatus(:120-127)` 는 SHIPPING·DELIVERED 와 결제 전 CANCELED 만 막는다. 그래서 `RETURN_REQUESTED → RETURNED` 는 도메인이 허용한다.
  - `AdminStatusChangePolicy` MANUAL 표는 PAID→MAKING/PREPARING, MAKING→PREPARING, SHIPPING→DELIVERED 뿐이다. 취소·반품은 `ORDER_023`.
- **문서**
  - `$D/order-flow-guide.md:38-39`: `RETURN_REQUESTED → RETURNED` 배송 후 반품(회수 후 환불), 고객 요청 → 어드민.
  - `:59-60`: "취소 거절 전이는 #17 에서 정한다". `:75-76` 출고 쪽에도 같은 문구가 있다.
  - `:884-908` §5: 환불 계산은 order, 실행은 payment. REFUND_FAILED 는 어드민에 노출한다. "종결 실패 수습은 코드 경로 없음".
  - `:350·:442·:551-553`: 반품 복원은 "검수 뒤 어드민이 재고로 되돌림을 고른 경우만 reason=RETURN".
  - **반품 배송비·반품 환불 산식은 어느 문서에도 없다.** `payment-guide.md` §8 에는 "order 가 계산한다"는 원칙만 있다.
- **클레임** (`$O/domain/model/Claim.java`)
  - 칸: `paymentId, attemptCount, nextAttemptAt, failureCode`.
  - CANCELLATION 은 payment_id 와 환불액>0 이 필수다. **RETURN 은 payment_id 가 null 이어야 한다**(DB `ck_claim_payment_cancellation_only`).
  - 상태 메서드(`markRefunded/complete/failRefund/failRestoration/retryLater/holdUntil`)는 전부 `requireCancellation()` 을 거쳐서 RETURN 에는 못 쓴다.
  - `markRefunded` 는 `REFUND_FAILED(REFUND_UNCONFIRMED)` 에서만 늦은 성공을 받는다.
  - Status 는 `REQUESTED·REFUNDED·COMPLETED·REFUND_FAILED·RESTORATION_FAILED` 이고 DDL check 로 고정돼 있다. 그래서 승인·거절 상태를 더하려면 DDL 을 바꿔야 한다.
- **반품 접수** (`OrderReturnProcessor`)
  - `DELIVERED|RETURN_REQUESTED` 에서만 받는다. 첫 접수 때 `changeStatus(RETURN_REQUESTED)` 와 CUSTOMER 이력을 남긴다.
  - 클레임은 `RETURN/REQUESTED`, 환불액은 null 이다.
  - 수량 상한을 볼 때 `sumRequestedReturnQuantities` 가 **REQUESTED 상태만** 합산한다.
- **#16 파이프라인**
  - `OrderCancellationProcessor`: `request` 가 tx1. `recordRefund/recordRestoration` 이 있고, `applyRefunded` 는 주문을 `CANCEL_REQUESTED → CANCELED` 로 고정해서 바꾼다. 재시도·포기는 `retryOrGiveUp` 이 정한다.
  - `OrderCancellationPipelineProcessor.progress` 는 NEVER 다. `refund` 는 저장된 payment_id·금액만 보내고, `restore` 는 `restoreCancellation` 고정이다.
  - 재시도 Facade 는 hold(@Version lease) → progress 순서다.
  - `ClaimRepository` 의 재시도 대상 쿼리는 `type` 을 파라미터로 받지만, 어댑터가 `CANCELLATION` 으로 고정해서 넘긴다.
- **payment** (`PaymentCancelProcessor.prepare`)
  - 같은 claimId 가 REJECTED 면 **영구 409 `PAYMENT_016`** 이다. 같은 결제에 다른 PENDING 클레임이 있어도 `016` 이다.
  - 누적 환불액이 승인액을 넘으면 `017`, PENDING 이 14일을 넘으면 `018` 이다. LATE_APPROVED 는 전액 취소만 된다.
  - SUCCEEDED 를 다시 요청하면 PG 를 부르지 않고 200 이다. 부분 취소(PARTIAL_CANCELED)는 허용한다.
  - **클레임별 취소 상태를 조회하는 API 는 없다.**
- **product**
  - `StockRestorationRequest.reason` 은 이미 `CANCELLATION|RETURN` 을 받는다.
  - 선점이 CONFIRMED(출고 뒤에도 유지)면, 선점 수량에서 이미 복원한 수량을 뺀 한도 안에서 복원한다.
  - 멱등은 (reservationId, reason, 수량)이 같은지로 판단한다. 부분 복원을 여러 번 할 수 있다.
  - **order 쪽** `StockRestorationPort`·`StockRestorationClientAdapter`(reason "CANCELLATION" 하드코딩)만 넓히면 된다 → **product-service·payment-service 수정은 필요 없다.**
- **어드민**
  - 권한은 `AdminAuthority` `ORDER_READ/ORDER_MANAGE` 가 있다(CLAIM_* 는 없음).
  - `ClaimAdminController` 는 GET 2개(ORDER_READ)뿐이다. `ClaimAdminResponse/Item` 에는 payment_id·failure_code·attempt 가 없다.
  - 감사는 `ClaimAdminAuditProcessor`(CLAIM_SEARCH/VIEW)가 한다.
    - `OrderAdminFacade.changeStatus` 는 실패도 false 로 기록한 뒤 다시 던진다.
    - 버전 충돌일 때 같은 목표 상태면 200, 아니면 `ORDER_025` 다.
  - `OrderAdminProcessor.changeStatus` 는 `findById` + @Version 으로 처리한다. 비관 잠금 조회는 회원 전용만 있다.
  - `ClaimAdminQueryPort.findById` 는 있고, `ClaimRepositoryPort` 에는 `findById` 가 없다.
  - 마지막 오류 코드는 `ORDER_035`, 마지막 검증 코드는 `ORDER_168` 이다.

## 2. 범위 · PR 분할 (순서 A → B1 → B2)

A 를 먼저 한다. 운영 결제를 여는 조건(deploy-guide ⑧)이 "취소 종결 실패 수습 경로"이기 때문이다.

| PR | 규모 | 내용 |
|---|---|---|
| **PR-A 취소 수습** | ~22 파일, DDL 1 | 어드민 응답 보강, 재시도, 취소 거절(주문 원상태 복귀), 수동 환불 확정, 복원 생략 종결, 고객 재취소 차단 `ORDER_037` |
| **PR-B1 반품 기반** | ~18 파일, DDL 1 | 클레임 RETURN 수명, `ReturnRefundPolicy`, `Order.rejectReturn/concludeReturn`, 전이 `RETURN_REQUESTED → DELIVERED`, `StockRestorationPort.restoreReturn`, 재시도 대상 type 확장. API 변화 없음 |
| **PR-B2 반품 흐름** | ~22 파일 | 승인·거절 API, 파이프라인 type 분기, 스케줄러가 RETURN 도 집음, 통합 테스트, 문서 |

## 3. 설계

### 3-1. PR-A API

공통: 권한은 전부 `AdminAuthority.ORDER_MANAGE`, 응답은 200 = 보강된 `ClaimAdminResponse`. 클레임 없음 404 `ORDER_032`, 아이디 형식 오류 400 `ORDER_166`.

| API | 전제 | 동작 | 오류 |
|---|---|---|---|
| `POST /api/v1/admin/claims/{claimId}/retries` (본문 없음) | 대기 상태(REQUESTED·REFUNDED·APPROVED) 또는 실패 상태(REFUND_FAILED·RESTORATION_FAILED) | 실패면 `reopen`: 직전 대기 상태로, attempt 0, failure null, lease. 대기면 `hold`(lease, @Version). 그 뒤 `progress` 를 인라인으로 돌리고 현재 상세를 반환. COMPLETED 는 변경 없이 200 | REJECTED 409 `ORDER_036 CLAIM_STATUS_INVALID`, 선점 패배 409 `ORDER_025`, 스케줄러 꺼짐 503 `ORDER_035` |
| `POST …/rejection` `{reason}` (필수 1~200자) | CANCELLATION + REFUND_FAILED + failure_code 가 **종결 거절 코드**(PAYMENT_*·400) | 한 tx 에서 클레임 REJECTED(결정 칸 기록), 주문 `CANCEL_REQUESTED → 원상태`(이력에서 `to=CANCEL_REQUESTED` 인 마지막 행의 from), ADMIN 이력(reason) | `REFUND_UNCONFIRMED` 면 409 `ORDER_038 REFUND_UNVERIFIED`, 원상태를 못 찾으면 500 `ORDER_041`, 이미 REJECTED 면 200, 그 밖 409 `ORDER_036` |
| `POST …/refund-confirmation` `{reason}` | CANCELLATION + REFUND_FAILED (코드 무관) | tx 에서 `confirmRefundByAdmin` → REFUNDED + 주문 CANCELED + ADMIN 이력. 그 뒤 tx 밖에서 복원 → tx3 (파이프라인의 REFUNDED 단계를 다시 씀) | 이미 REFUNDED 이후면 변경 없이 200, 그 밖 409 `ORDER_036` |
| `POST …/completion` `{reason}` | RESTORATION_FAILED | tx 에서 COMPLETED(결정 칸 기록). 재고는 운영자가 #86 재고 조정으로 먼저 맞춘다 | 그 밖 409 `ORDER_036` |

- **멱등**: 헤더는 쓰지 않는다. 상태 전제를 보고, 같은 결과면 변경 없이 200, 모순이면 409 `ORDER_036`, 동시 변경이면 `ORDER_025`.
- **경합**
  - (a) 스케줄러와 어드민 재시도가 겹치면 hold 의 @Version 때문에 한쪽만 이긴다.
  - (b) 거절과 재시도가 겹칠 때: 재시도가 먼저 reopen 하면 거절은 409 다. 거절 뒤에는 스케줄러도, 늦게 온 성공 반영도 거부된다. 그래서 `REFUND_UNCONFIRMED` 상태에서는 거절을 막는다.
  - (c) 고객이 다시 취소하면: 결정적 키 클레임이 REJECTED 면 409 `ORDER_037 CANCELLATION_REJECTED` 다.
  - (d) 출고: 원상태로 돌아간 뒤에는 ship 이 정상 동작한다.
- **감사**: `CLAIM_RETRY · CLAIM_REJECT · CLAIM_REFUND_CONFIRM · CLAIM_COMPLETE`. 실패도 false 로 기록한다.
- **DDL-A**
  - status check 에 `APPROVED`, `REJECTED` 를 더한다.
  - 칸 `decision_reason varchar(200)`, `decided_by_member_id bigint`, `decided_at timestamptz` 를 더한다.
  - check: REJECTED 면 decided_* 가 not null.
- **도메인**
  - `Order.rejectCancellation(restored)` 를 더한다. restored 는 PAID·MAKING·PREPARING 중 하나다.
  - 전이 표에 `CANCEL_REQUESTED → PAID·MAKING·PREPARING` 을 더한다. MANUAL 정책에는 넣지 않는다.

### 3-2. PR-B 반품

- **클레임 RETURN 수명**
  - `REQUESTED` → [승인] `APPROVED`(payment_id·환불액·restock·deduction 확정, lease) → 환불 200 → `REFUNDED` → restock 이면 복원 후, 아니면 바로 `COMPLETED`.
  - 거절하면 `REJECTED`. 실패와 수습은 PR-A API 를 그대로 쓴다.
  - 환불액이 0 이면 payment 를 부르지 않고 바로 REFUNDED.
- **승인 API** `POST …/approval` `{restock(필수), deduction?, customerFault?, reason?}`
  - 전제: 클레임 RETURN·REQUESTED, 주문 RETURN_REQUESTED.
  - 이미 승인된 뒤 같은 입력이면 200, 다른 입력이면 409 `ORDER_036`.
  - 같은 주문에 환불 진행 중인 반품이 있으면 409 `ORDER_039`. 승인은 주문 행 잠금으로 직렬화하고, 이를 위해 `findByIdForUpdate(orderId)` 를 새로 둔다. 이것으로 payment 의 016 "결제당 PENDING 하나" 경합을 order 쪽에서 막는다.
  - deduction 이 산정액보다 크면 `ORDER_040`.
- **환불 산식** `ReturnRefundPolicy`
  - `kept_before` = 주문 수량 − 이미 환불된 반품 수량, `kept_after` = kept_before − 이 클레임 수량.
  - 고객 귀책: `policy = due(kept_before) − due(kept_after)`. 무료배송 기준 미달이면 배송비를 다시 부과한다.
  - 판매자 귀책(모든 항목이 DEFECTIVE·WRONG_ITEM 이거나 `customerFault=false`): 재부과 없이 `policy = 반품 항목 금액`.
  - 최종 `refund = policy − deduction`, `0 ≤ deduction ≤ policy`.
  - 누적 환불이 승인액을 넘지 않는지 order 가 먼저 검사한다.
- **주문 종결**
  - 환불된 수량 합이 주문 전 수량이면 `RETURNED`.
  - 아니고 진행 중인 반품이 없으면 `DELIVERED` 로 되돌린다.
  - 그 밖이면 `RETURN_REQUESTED` 를 유지한다.
  - 전이 표에 `RETURN_REQUESTED → DELIVERED` 를 더한다.
- **재고**: `restoreReturn(claimId, reservationId, qty)` 를 reason `RETURN` 으로 부른다. 승인 때 restock=true 면 호출하고, false 면 호출하지 않는다.
- **수량 합산**: `sumRequestedReturnQuantities` 를 REQUESTED·APPROVED·REFUNDED·COMPLETED 로 넓힌다. 거절·실패분만 다시 접수할 수 있게 된다.
- **DDL-B1**
  - 취소 전용 check 를 type·status 를 보는 형태로 바꾼다.
  - 칸 `restock boolean`, `deduction_amount bigint` 를 더한다.
  - 스케줄러는 type in (CANCELLATION, RETURN) 을 집는다.

### 3-3. 실패 표

| 단계 | 남는 상태 | 복구 |
|---|---|---|
| A 재시도 tx1 실패 | 바뀐 것 없음 | 다시 호출 |
| 원격 호출 중단 | 대기 상태 + lease | 스케줄러 |
| 원격 200 뒤 tx 실패 | 대기 상태 | 다시 호출하면 멱등 200 |
| 수동 확정 뒤 복원 실패 | REFUNDED, 주문 CANCELED | 재시도, 종결 실패면 completion |
| B 승인 뒤 미확정 | APPROVED + 백오프 | 스케줄러, 소진되면 UNCONFIRMED → 수습 |
| 복원 종결 409 | RESTORATION_FAILED | #86 재고 조정 + completion |
| payment PENDING 14일 초과 | 영구 018 | **범위 밖** — payment 쪽 복구가 필요하다 |

## 4. 파일 · 검증

- **PR-A**
  - 도메인: `Claim`, `Order.rejectCancellation`, `OrderStatus` 표
  - 영속: `ClaimEntity/Mapper`, `ClaimRepositoryPort.findById`, 이력 원상태 조회, `OrderRepositoryPort.findByIdForUpdate`
  - 처리: `OrderCancellationProcessor` 확장, `ClaimAdminProcessingFacade/UseCase`
  - 웹: 컨트롤러 +4, DTO, 응답·Presenter
  - 그 밖: 감사, `OrderErrorCode` 036·037·038·041, DDL + 업그레이드 SQL, 테스트 6~8, 문서 4
- **PR-B1·B2**: 3-2 에 나온 대로.
- **검증**
  - 단위 시험
  - Processor/Facade 모킹 시험(동시성 포함)
  - H2/PG 계약 + `SchemaUpgradePostgresTest` 단계 추가
  - `@SpringBootTest` 통합(MockitoBean 포트)
  - `:service:order-service:check`

## 5. 사용자 결정 ★

1. 반품 배송비 — 추천: 어드민이 승인 때 `deduction` 을 입력(0 허용). 판매자 귀책은 사유로 추론하고 `customerFault` 로 덮어쓸 수 있게 한다.
2. 반품 재고 복원 시점 — 추천: 승인(검수 완료) 때 `restock` 플래그로.
3. 수동 환불 확정 — 추천: 허용. 사유 필수, 감사, ADMIN 이력.
4. 취소 거절 원상태 — 추천: 이력의 직전 상태로 자동 복귀. 거절 뒤 고객이 다시 취소하면 409 `ORDER_037`.
5. 반품 승인 단계 — 추천: 한 번(검수 완료 = 환불 실행).
6. 부분 반품 뒤 주문 상태 — 추천: 전량 반품이면 RETURNED, 아니면 DELIVERED 로 복귀.
7. 권한 — 추천: 전부 `ORDER_MANAGE`.
8. `completion` 을 PR-A 에 넣을지 — 추천: 넣는다.

## 6. 범위 밖

교환, 회수 송장, 고객 알림, 부분 취소 API, payment 14일 PENDING 복구·클레임별 취소 조회 API, 반품 철회, FE, 대사(#10).

## 바꿀 문서

- `order-flow-guide.md`: §1 표, 거절 전이 문구, §5 클레임 표·산식·수습 API
- `api-design-guide.md`: §2-16 어드민 클레임
- `payment-guide.md`: §8 반품 환불·016 직렬화
- `deploy-guide.md`: ⑧ 과 DDL

## 열린 질문

- 어드민 상세 `nextStatuses[]` 가 전이 표를 그대로 내보내면 CANCEL_REQUESTED 에 PAID 등이 노출된다. FE 와 합의가 필요하다.
- USD/PayPal 부분 환불 수수료를 어떻게 다룰지.
- PR-A DDL 에 `APPROVED` 를 미리 넣을지 — 추천: 넣는다.

## 확정 결정 (사용자, 2026-11-06)
1. 반품 배송비: 승인 때 어드민이 deduction 입력(0 허용), 불량·오배송은 판매자 귀책(재부과 없음, customerFault 로 덮어쓰기), 고객 변심은 무료배송 기준 미달 재부과.
2. 반품 재고: 승인(검수 완료) 때 restock 플래그로 복원.
3. 수동 환불 확정(refund-confirmation): 허용 — 사유 필수 · 감사 · ADMIN 이력, 확정 뒤 재고 복원 자동 진행.
4. 취소 거절: 이력의 직전 상태로 자동 복귀, REFUND_UNCONFIRMED 는 거절 금지, 거절 뒤 고객 재취소 409 ORDER_037.
5. 반품 승인 한 단계(검수 완료 = 환불 실행).
6. 부분 반품: 전량이면 RETURNED, 아니면 DELIVERED 복귀.
7. 권한 전부 ORDER_MANAGE.
8. completion 은 PR-A 에 포함. DDL-A 에 APPROVED 도 미리 넣는다.
PR 순서: PR-A → PR-B1 → PR-B2.
