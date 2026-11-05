# #16 PR-2 설계안 — 결제 후(출고 전) 고객 취소 (architect, 2026-11-05 17:5x)

> 인계용. **PR 올리기 전에 삭제**하고 확정된 내용만 `backend/docs/*` 로 옮긴다.
> 경로 약어: `$O` = `backend/service/order-service/src/main/java/com/commercefoundry/domainlayer/order`, `$P` = `backend/service/payment-service/src/main/java/com/commercefoundry/domainlayer/payment`, `$D` = `backend/docs`. 라인은 develop 84eee730 기준.

## 1. 현재 상태 사실

- 고객 취소 API 는 **결제 전만** 된다. `$O/domain/model/Order.java:216-218` `isCancelableByCustomer()` = PENDING_PAYMENT 만, `:181-189` `cancelBeforePayment()`. `$O/application/service/processor/OrderHistoryProcessor.java:50-63` 이 이것을 부르고, PAID 는 409 `ORDER_020`. 컨트롤러 `$O/adapter/in/web/controller/OrderHistoryWebController.java:75-83` 는 `{reason}` 만 받고 `Idempotency-Key` 가 없다. `OrderMemberSummaryInfo.java:19-21` 가 같은 규칙을 따로 들고 있다(`cancelable` FE 플래그).
- 전이 표에는 PAID·MAKING·PREPARING → CANCEL_REQUESTED → CANCELED 가 **이미 있다** (`$O/domain/model/OrderStatus.java:66-74`). 도메인 메서드만 없다. `OrderStatusHistory.canceledByCustomer` (`:94-100`) 는 PENDING_PAYMENT 전용 가드다.
- payment 취소 internal API **존재**: `$P/adapter/in/internal/controller/PaymentInternalController.java:40-49` `POST /internal/v1/payments/{paymentId}/cancellations`, 헤더 `Idempotency-Key`, 본문 `{claimId, amount, currency}`, 200 `{paymentId, claimId, status, canceledAmount}`.
  - `PaymentCancellationFacade.java:24-41`: 키 ≠ claimId → 409 `PAYMENT_019`. PG 결과가 SUCCEEDED → 200, REJECTED → 409 `PAYMENT_016`, UNKNOWN → 503 `PAYMENT_018`(같은 키로 재시도).
  - `PaymentCancelProcessor.prepare:29-67`: DONE·PARTIAL_CANCELED·LATE_APPROVED 만 받는다. 누적 취소액이 승인액을 넘으면 409 `PAYMENT_017`. 결제당 PENDING 클레임은 하나. **키 유효 기간 14일**(넘으면 018). 이미 SUCCEEDED 인 클레임을 다시 요청하면 PG 호출 없이 200(멱등).
- **order → payment 취소 호출 경로는 없다.** `$O/adapter/out/client/feign/PaymentClient.java:24-26` 에는 `getPayment` 만 있고, 포트도 `PaymentQueryPort.findPayment` 뿐이다. 오류 분류 패턴은 `adapter/out/client/support/PaymentInternalResponseSupport.java:65-77`(UNAVAILABLE/UNAUTHORIZED/REJECTED/CONTRACT_VIOLATION).
- 재고 복원 경로 **존재**: `$O/application/port/out/StockRestorationPort.java` `restoreCancellation(claimId, reservationId, SortedMap)` + `OrderStockProcessor.restoreCanceledPayment:243-266`(NEVER. 027/028/029/003 종결 → `ORDER_017`, 나머지 → `ORDER_002`). 지금 호출자는 #308 C1 경합 하나뿐이다.
- 클레임: `Claim.java:14-45` Type `{RETURN}` · Status `{REQUESTED}` · 항목 1~50개, `ClaimItem.Reason` 3종.
  - DDL `src/test/resources/ddl/order-schema.sql:89-90` 이 `status in ('REQUESTED')`, `type in ('RETURN')` 으로, `:138` 이 reason 3종으로 **check 를 고정**한다 → 유형·상태를 추가하려면 DDL 을 바꿔야 한다.
  - `ClaimRepositoryAdapter.java:35` 반품 수량 합은 이미 `type=RETURN` 으로 거른다(취소 클레임을 추가해도 안전).
- #308 C 와의 관계: `OrderPaymentProcessor.inspect:57-64` 가 같은 paymentId 기록을 no-op 처리하고, `canProbe:94-105` 는 `findPaidByOrderId` 결과가 있으면 프로브·복원을 하지 않는다 → **PAID 뒤 취소한 주문에 승인이 재전달돼도 이중 복원 없음**. `OrderPaymentRepositoryPort.findPaidByOrderId(:34)` 로 환불 대상 paymentId 를 얻는다.
- 문서:
  - `$D/order-flow-guide.md:48-49` PR-2 범위(PAID·MAKING·PREPARING → CANCEL_REQUESTED)
  - `:61-62` "취소 거절 전이는 PR-2 에서 정한다"
  - `:546-551` 클레임 먼저 · 복원 대기 표시 · 스케줄러 재전송
  - `:874-882` §5
  - `:183-185` 배송비 재부과는 **주문 스냅숏 기준**
  - `payment-guide.md:489-493` §8: 환불액은 order 가 계산하고 payment 가 실행, Idempotency-Key = 클레임 ID
  - 배송비 차감 **산식은 어느 문서에도 없다**(이번에 정한다).

## 최소 의미 단위 — PR-2a "전체 취소" (부분 취소 API 는 PR-2b)

파일이 45개쯤이라 둘로 나눈다.
- **PR-2a-1 기반(~20개)**: 도메인·클레임 DDL·payment 취소 Feign/포트·환불 정책·설정 + 단위/슬라이스 테스트. API 동작은 바뀌지 않는다.
- **PR-2a-2 흐름(~20개)**: Processor/Facade·컨트롤러·`cancelable`·재시도 스케줄러·통합 테스트·문서.

## 2. 설계

**API** `POST /api/v1/orders/{orderId}/cancellation` `{reason}` — 요청 모양은 그대로 둔다.
- 응답은 200 `OrderDetailResponse`(기존).
- 결제 전 주문은 PR-1 경로 그대로 간다.
- 결제 뒤 주문은 환불이 완료되면 `status=CANCELED`, 환불이 미확정이거나 실패하면 `CANCEL_REQUESTED`(요청 접수, 200).
- 오류:
  - 404 `ORDER_001`: 주문 없음·타인 주문
  - 409 `ORDER_020`: SHIPPING 이후 · EXPIRED · RETURN_* · MAKING 정책 불허. 새 코드 없이 상태명 메시지로 처리
  - 409 `ORDER_025`: 동시 변경 뒤 다시 판정할 수 없음
  - 500 신규 `ORDER_033 CANCELLATION_PAYMENT_MISSING`: PAID 인데 `order_payment` 에 PAID 행이 없음(데이터 이상)
- `Idempotency-Key` 헤더는 받지 않는다. 전체 취소는 주문당 하나이므로 `request_key = UUID.nameUUIDFromBytes("cancellation:"+orderId)` 결정적 키를 쓰고, `uk_claim_order_id_request_key` 로 "주문당 전체 취소 클레임 하나"를 DB 가 보장한다. 고객 키는 부분 취소(PR-2b)에서 쓴다.

**상태 전이**
- `Order#requestCancellation()`: PAID·MAKING·PREPARING → CANCEL_REQUESTED. 이미 CANCEL_REQUESTED/CANCELED 면 그대로 반환한다.
- `Order#completeCancellation()`: CANCEL_REQUESTED → CANCELED.
- `isCancelableByCustomer(boolean allowWhileMaking)` = PENDING_PAYMENT·PAID·PREPARING(+MAKING 은 플래그). 설정은 `order.cancellation.allow-while-making`(기본 false). `OrderAutoConfirmationProperties` 패턴으로 `OrderCancellationProperties` 를 만든다.
- 취소 거절 전이(CANCEL_REQUESTED → 원래 상태)는 **이번에도 표에 넣지 않는다**(REFUND_FAILED 어드민 처리와 함께 #17/PR-2b 에서).

**클레임 수명**
- Type 에 `CANCELLATION` 추가.
- Status: `REQUESTED`(환불 대기) → `REFUNDED`(복원 대기 = 문서의 "재고 복원 대기") → `COMPLETED`. 종결 실패는 `REFUND_FAILED`·`RESTORATION_FAILED`.
- 추가 컬럼: `payment_id`, `attempt_count`, `next_attempt_at`, `failure_code`.
- 항목은 주문의 전 항목·전 수량이다(복원할 SKU 수량이자, 나중에 부분 취소의 "이미 취소된 수량" 근거).
- 사유는 `ClaimItem.Reason` 에 `ORDER_MISTAKE·PAYMENT_ISSUE·OTHER` 를 더하고 `OrderCancelReason` 과 이름으로 매핑한다.

**트랜잭션 경계** (Facade `NEVER`, 외부 I/O 는 전부 트랜잭션 밖. `OrderStockProcessor` 와 같은 패턴)
1. tx1(5s, `findByIdAndMemberIdForUpdate`): 소유·상태 검증 → `findPaidByOrderId` → 환불액 계산 → claim(REQUESTED, 환불액, payment_id) + 주문 CANCEL_REQUESTED + CUSTOMER 이력(사유 코드)을 원자 저장.
2. ext1: `PaymentCancelPort.cancel(paymentId, claimId, amount, currency)`, 헤더 `Idempotency-Key = claimId`.
   - 분류: 200 → SUCCEEDED. `PAYMENT_016/017/019/001` → REJECTED(종결). `PAYMENT_018`·타임아웃·5xx·서킷·401/403 → UNKNOWN(재시도).
   - 별도 Feign `contextId=paymentCancelClient` 로 read timeout 을 PG 취소에 맞게 늘린다(기본 3s 는 짧다).
3. tx2:
   - SUCCEEDED → claim REFUNDED + 주문 CANCELED + SERVICE 이력 `CANCELLATION_REFUNDED`
   - REJECTED → claim REFUND_FAILED(failure_code), 주문은 CANCEL_REQUESTED 유지
   - UNKNOWN → attempt+1, next_attempt_at(지수 백오프)
4. ext2(REFUNDED 일 때): `StockRestorationPort.restoreCancellation(claimId, order.stockReservationId, 스냅숏 SKU 수량합)`.
5. tx3: 200 → COMPLETED. 재시도성 실패 → next_attempt_at. 종결 409 → RESTORATION_FAILED(failure_code, 어드민 노출).
- 스케줄러 `ClaimCancellationRetryScheduler`:
  - 설정 `order.cancellation.retry.enabled/interval/batch-size/max-attempts`. ShedLock 없이 `@Version` + `next_attempt_at` 으로 선점한다.
  - type=CANCELLATION, status in (REQUESTED, REFUNDED), next_attempt_at ≤ now 인 건을 집어 2~5 단계를 같은 claimId·같은 본문으로 다시 실행한다.
  - max-attempts 를 넘으면(14일 키 유효 기간 안) REFUND_FAILED.
- **환불 → 복원 순서**: 복원은 되돌릴 수 없으므로(§3-4), 환불이 종결 거절되면 복원하지 않고 어드민이 취소를 거절할 길을 남긴다. 주문 `CANCELED` 는 "환불 확정"(고객에게 보이는 사실)이고, 복원 여부는 클레임 플래그로 본다.

**멱등·경합**
- 더블클릭: tx1 에서 진 쪽은 다시 조회해 CANCEL_REQUESTED/CANCELED 면 현재 상세를 200 으로 돌려준다. 파이프라인은 다시 돌리지 않고 완료는 스케줄러가 보장한다.
- 출고(`ship` internal)와의 경합: 주문 `@Version` 으로 하나만 커밋된다. 취소가 이기면 delivery 가 `ORDER_020` 을 받는다(기존 규칙 `:61`).
- 승인 재전달·이중 결제: `inspect`/`canProbe` 가드 때문에 이 주문에는 복원 의도가 생기지 않는다. C2a `existsRestorationIntent` 는 `order_payment` 전용이라 손대지 않는다(클레임 복원은 PAID 뒤에만 생긴다).
- 웹훅으로 결제가 이미 CANCELED 인 주문을 고객이 취소하면 `PAYMENT_016` → REFUND_FAILED 로 어드민에 보인다.

**부분 취소·배송비 산식** (도메인 `CancellationRefundPolicy`. 2a 에 포함하고 테스트한다)
- `refund = due(before) − due(after)`, `due(S) = items(S) + ship(S)`, `ship(∅)=0`
- 원주문이 무료배송이 아니면 스냅숏 배송비를 그대로 쓴다.
- 무료배송이 적용됐고 `items(S) < freeShippingThreshold` 이면 스냅숏 배송비 + `baseFee` 를 다시 부과한다(해외는 `+ weightFee`).
- 도서산간 추가금은 다시 부과하지 않는다(`:185`).
- 전체 취소는 총액 전액(배송비 포함)을 환불한다. 부분 취소를 순서대로 다 하면 합이 결제액과 같아야 한다.
- 음수(소액 항목을 취소해서 재부과액이 환불액보다 큰 경우)는 PR-2b 에서 정한다.

## 3. 파일 / DDL

- PR-2a-1:
  - 도메인: `Claim`·`ClaimItem`·`Order`·`OrderStatusHistory`, 신규 `CancellationRefundPolicy`
  - 영속: `ClaimEntity`·`ClaimRepository(Port/Adapter)`·`ClaimMapper`·`ClaimAdminFilter`(enum 미러)
  - payment 호출: 신규 `PaymentCancelPort`·`PaymentCancelResult`, `PaymentClient`(+cancel, 또는 별도 `PaymentCancelClient`), DTO 2개, `PaymentCancelClientAdapter`
  - 설정·DDL: `OrderCancellationProperties`·`application.yml`, DDL
- PR-2a-2:
  - 흐름: 신규 `OrderCancellationProcessor`·`OrderCancellationFacade`(또는 `OrderHistoryWebFacade.cancel` 분기)·`OrderCancellationRetryUseCase/Facade`·`ClaimCancellationRetryScheduler`
  - 웹: `OrderHistoryWebController`(Operation)·`OrderHistoryWebPresenter`·`OrderMemberSummaryInfo`
  - 기타: `OrderErrorCode`(+033), 문서
- **DDL 변경 있음**:
  - `claim`: check 2개 교체(type/status), 컬럼 4개 추가, `idx_claim_type_status_next_attempt_at`, `ck_claim_cancellation_payment`(CANCELLATION 이면 payment_id·환불액 not null)
  - `claim_item.reason` check 교체
  - 운영 스크립트 `service/order-service/src/test/resources/ddl/claim-cancellation.sql`(c2b 스크립트처럼 한 트랜잭션) + 스냅숏 `order-schema.sql` 재생성, `deploy-guide.md` 절 추가
- product-service·payment-service 는 수정하지 않는다(Codex C3b·#586 과 겹치지 않음). 클레임 id 는 Snowflake 라 C1 `restoration_claim_id` 와 충돌하지 않는다.

## 4. 검증

- 단위:
  - `CancellationRefundPolicyTest`: 전체 취소 / 임계 이상 / 임계 미달 재부과 / 순차 부분 취소 합 = 결제액 / 해외 weightFee / 무료배송 아닌 원주문
  - `OrderTest`(전이·cancelable 플래그), `ClaimTest`
- Mockito:
  - `OrderCancellationProcessorTest`
  - `OrderCancellationFacadeTest`: SUCCEEDED/REJECTED/UNKNOWN × 복원 200/재시도/종결, 낙관락에서 진 쪽, 결제 기록 없음 500
- Feign: `PaymentCancelClientAdapterTest`(`PaymentClientTest` 방식. 200/016/017/018/019/001/401/5xx/timeout 분류)
- H2/PG: `OrderCancellationPersistenceContract` + `OrderCancellationH2Test` + `OrderCancellationPostgresTest`(`OrderReturn*Test` 를 본뜸). 결정적 키 유니크, due 조회·선점, 취소 vs ship `@Version`, 새 check 제약
- 웹: `OrderHistoryWebControllerTest`(404/409/200), `OrderCancellationWebIntegrationTest`(`OrderReturnWebIntegrationTest` 방식, 포트 스텁)
- 스케줄러: `ClaimCancellationRetrySchedulerConditionTest`
- 명령: 반복은 `./gradlew :service:order-service:test --tests '*Cancellation*'`, PR 직전은 `:service:order-service:cleanTest :service:order-service:check --no-build-cache`

## 5. 사용자 결정 ★ (집에서 먼저 정한다)

- ★ 환불 → 복원 순서 — 추천: 환불 먼저(위 이유)
- ★ 자동 처리 범위 — 추천: PAID·PREPARING 은 즉시 자동, MAKING 은 플래그(기본 불허). 2인 운영이라 어드민 승인 단계 없이 자동으로 하고, 실패 건만 어드민으로 넘긴다
- ★ 응답에 환불액 블록(`cancellation{claimId,status,refundAmount}`) 추가 여부 — 추천: PR-2b 로 미루고 지금은 `OrderDetailResponse` 유지(FE 영향은 `cancelable` 의미 확장뿐, seonghoho 리뷰)
- ★ 부분 취소 API 를 이 이슈에서 할지 — 추천: 정책·테스트만 2a 에, API 는 PR-2b(음수 환불·고객 키 멱등 포함)
- ★ PR 2분할(2a-1/2a-2) vs 단일 PR — 추천: 분할
- ★ 사유 저장 방식 — 추천: `ClaimItem.Reason` 확장(다른 안: `claim.cancel_reason` 컬럼)

## 6. 범위 밖

- 부분 취소 API·음수 환불 규칙
- 어드민 취소 승인/거절 전이·환불 재시도·REFUND_FAILED 처리 화면(#17)
- PG 웹훅/콘솔 취소 → 주문 동기화(#563 후속)
- 반품 승인·환불·복원
- `payment.enabled` 운영 개방
- `order_payment` 가 없는 legacy PAID 주문
- 결제 전 취소 경로 변경
- FE

## 실패 표

| 단계 | 남는 상태 | 복구 |
|------|-----------|------|
| tx1 실패 | 없음(롤백) | 고객 재요청 |
| tx1 뒤 종료 | CANCEL_REQUESTED + claim REQUESTED | 스케줄러가 같은 claimId 로 환불 호출 |
| 환불 UNKNOWN | 위와 같음 + attempt/next_attempt_at | 같은 키로 재시도, 상한을 넘으면 REFUND_FAILED → 어드민 |
| 환불 REJECTED | claim REFUND_FAILED, 주문 CANCEL_REQUESTED | 어드민(#17) 재시도 또는 취소 거절(PR-2b) |
| 환불 200 뒤 tx2 실패 | PG 는 취소됨, DB 는 REQUESTED | 재호출 → payment 가 기존 SUCCEEDED 취소로 200(Facade:29-31) → tx2 |
| 복원 재시도성 실패 | claim REFUNDED, 주문 CANCELED | 스케줄러가 같은 claimId·본문으로 재호출(product 멱등 200) |
| 복원 종결 409 | RESTORATION_FAILED + failure_code | 어드민 노출, 재고 조정(#86) 수동 |
| 복원 200 뒤 tx3 실패 | product 는 복원됨, claim REFUNDED | 재호출 멱등 200 → tx3 |
| 더블클릭/출고 경합 | 하나만 커밋 | 진 쪽은 다시 조회해 현재 상태 200 / delivery 는 ORDER_020 |

## 바꿀 문서

- `order-flow-guide.md`
  - §1-1(:37 "어드민/자동 승인" → 환불 성공 시 자동, 거절 전이 보류 명시, cancelable 규칙)
  - §5(클레임 상태·재시도 컬럼·산식)
- `api-design-guide.md:1257` 행과 `cancelable` 의 뜻
- `payment-guide.md` §8(호출 경로·오류 분기·Feign 타임아웃)
- `deploy-guide.md`(claim-cancellation.sql)
- `service-inventory.md` §2(order → payment cancellations 행)

## 열린 질문

- PREPARING 상태에서 자동 취소할 때 포장 중인 건에 대한 운영 공지가 필요한지
- 재시도 백오프 상한값
- 환불 성공 알림(#477 알림 기반 재사용)을 후속으로 할지
