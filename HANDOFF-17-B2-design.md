# #17 B2 반품 승인 → 환불 → 재고 복원 → 주문 정리 — 확정 설계 (architect 11-13 + 사용자 결정)

기준: develop c20876c4 (B1 #718, #693 PR A #719 포함). `O` = backend/service/order-service/src/main/java/com/commercefoundry/domainlayer/order, `DOCS` = backend/docs.

## 사용자 결정 (고정)
- 3분할: **B2a 엔진 → B2b 수습 확장 → B2c 승인 · 거절 API** (각 30파일 이내).
- 승인 · 거절 권한 ORDER_MANAGE, 환불 실패 수습은 SETTLEMENT_MANAGE(A3).
- 승인 = 검수 완료 = PG 환불 실행. 운영자 입력: restock, deductionAmount, fault(CUSTOMER|SELLER, 생략 시 Fault.defaultOf), **expectedRefundAmount(필수, 서버 계산과 다르면 409 ORDER_046)** — 상세 응답에 귀책별 환불 미리보기.
- 환불액 ≤ 0 승인 409, 같은 주문 APPROVED · REFUND_FAILED 반품 있으면 승인 409.
- 귀책은 클레임 단위 운영자 선택, 저장 칸 없음(감사 detail 에 fault · restock).
- REFUND_FAILED 반품 종결은 A3 수동 확정만(승인 철회는 A4 와 함께).
- 반품 수동 확정은 결제사 종결 거절(원장 REJECTED)만 먼저 허용, 결과 미확인(원장 PENDING)은 #693 PR B + order 호출 뒤.
- 거절 사유 필수(1~200자).
- 부분 승인 미지원 — "거절 후 재접수". 거절로 DELIVERED 복귀 뒤 즉시 구매 확정되는 문제는 후속 이슈 #722 로, 문서에 "거절 전 고객과 조율" 명시.
- 반품 다시 시도 14일 기한 = 승인 시각 기준(B1 구현됨).

## 코드 제약 (근거)
- Claim 전이 7개 `requireCancellation()` (Claim.java:190,204,325,336,344,357,381,399,438). 파이프라인 `status == REQUESTED` 로 환불 단계 판정(OrderCancellationPipelineProcessor.java:56) — 반품 REQUESTED 가 들어오면 paymentId null 로 환불 보냄. 재조회가 주문 아이디 + 취소 키(PipelineProcessor:87, OrderCancellationProcessor:168,219-222). `hasSameRefundRequest` type CANCELLATION 고정(Claim.java:213).
- 주문 잠금은 `findByIdAndMemberIdForUpdate`(NOWAIT)뿐(OrderRepositoryAdapter:68-71, OrderRepositoryPort:26). 어드민용 `findByIdForUpdate(orderId)` 없음.
- DB check `ck_claim_return_next_attempt`: 반품 APPROVED · REFUNDED ⇔ next_attempt not null → restock=false 반품은 같은 tx 에서 REFUNDED → COMPLETED.
- payment: 같은 결제 다른 claimId PENDING → 409 PAYMENT_016(payment-guide:637-640). 이미 환불된 claimId 는 PG 없이 200(:634-635).
- order 는 LATE_APPROVED 모름 → 사전 차단 불필요(문서 한 줄).
- product 복원: 같은 claimId 의 reason · 수량 다르면 PRODUCT_029, 주문 상태 안 봄.

## B2a 엔진 (이번 PR, ~22 파일, 호출부 = 스케줄러 · 파이프라인만 — 반품 APPROVED 행은 B2c 전까지 생기지 않음, 시드 행으로 검증)
1. **Claim 전이 반품 해제**: `markRefunded` · `markConfirmedRefund` · `complete` · `failRefund` · `failRestoration` · `retryLater` · `holdUntil` 의 requireCancellation 해제, "REQUESTED" 고정을 유형별 환불 대기 상태(`isAwaitingRefund()` 계열)로. `hasSameRefundRequest` 는 `other.type == type`, `isRefundRejected`(Claim.java:411) type 조건 제거. (`reopen` · `requireDecisionOn` 은 B2b.) restock=false 반품은 기록 tx 에서 REFUNDED 뒤 바로 COMPLETED.
2. **파이프라인 공유(분기 최소)** — 새 파이프라인 만들지 않음(2-CAS + 잠금 TX + 503 ORDER_036 복제 금지):
   - `progress`: `status == REQUESTED` → `isAwaitingRefund()`. 반품 REQUESTED 유입은 무동작.
   - `recordRefund` · `retryOrGiveUp` 의 REQUESTED 비교(OrderCancellationProcessor:136,250) → `isAwaitingRefund()`.
   - 재조회를 `findById(source.id())` 로(취소도 같은 행). `findClaim(orderId)` 는 고객 재생용으로 남김.
   - `applyRefunded`(OrderCancellationProcessor:228-244)를 type 으로 분기 — **취소 분기는 한 글자도 바꾸지 않음**. 반품 분기: 주문 FOR UPDATE → markConfirmedRefund(restock=false 면 complete) → update → 클레임 목록에서 이 행을 메모리 교체해 `ReturnRefundPolicy.outcome` → `settleReturn` → 바뀌었으면 `saveStatusChange`(SERVICE 이력, 사유 RETURN_REFUNDED). 주문이 RETURN_REQUESTED 아니면 RESTORATION_FAILED(ORDER_STATUS_UNEXPECTED), 복원 안 함.
   - `restore`: 포트를 `restore(claimId, reservationId, reason, quantities)` 로 인자화, reason 은 `claim.type()` 에서만 유도(취소 본문 reason "CANCELLATION" 불변).
3. **주문 잠금 규칙(신규)**: 반품 상태를 바꾸는 모든 tx(승인 · 거절(B2c) · 반품 환불 기록(B2a) · 반품 수동 확정(B2b))는 주문 FOR UPDATE(NOWAIT)를 먼저. write skew 방지(열린 반품 없는데 RETURN_REQUESTED 영구 정지 / 열린 REQUESTED 반품 있는데 DELIVERED). 새 `OrderRepositoryPort#findByIdForUpdate(orderId)`. 환불 기록에서 NOWAIT 지면 기존 바깥 catch → 503 ORDER_036, 클레임 APPROVED + lease → 스케줄러 재전송(payment 200, PG 없음). 취소 경로의 낙관적 방식은 그대로.
4. **스케줄러**: `findCancellationRetryTargets`(ClaimRepositoryAdapter:120-124)를 `(CANCELLATION ∧ {REQUESTED, REFUNDED}) ∨ (RETURN ∧ {APPROVED, REFUNDED})` 한 질의로(인덱스 idx_claim_type_next_attempt_at_id). batch · 시간 예산 공유, 설정 키 `order.cancellation.retry.*` 개명 안 함(문서에 "반품 포함").
5. 주문 정리 시점 = 환불 기록 tx 안. REFUNDED · RESTORATION_FAILED 반품 복원은 주문 상태와 무관(자동 구매 확정이 먼저 가도 복원 진행).
6. `OrderStatusHistory` 팩토리(SERVICE, RETURN_REFUNDED) 필요 시.

## 보존할 동작
- 취소 클레임의 상태 순서 · 실패 코드 · 횟수 · lease · 로그 문구("cancellation …"), 503 ORDER_036 조건, 복원 reason "CANCELLATION".
- A2 · A3 취소 결과와 오류 코드(038 · 039 · 040 · 041), 14일 기한, 거절 환불 재시도 제외.
- 기존 시험은 케이스를 고치지 않고 추가만(예외: ClaimTest 의 "반품은 이 전이를 못 한다" 류 — 목록 보고).

## B2a 시험
- ClaimTest: 반품 전이 전수(APPROVED → REFUNDED → COMPLETED, restock=false 직행, failRefund · failRestoration · retryLater · holdUntil).
- 프로세서/파이프라인(H2): 반품 REQUESTED 유입 무동작, reason 이 type 에서 유도, 재조회 id 기준(같은 주문 반품 2건), 환불 기록 → 주문 정리 3결과(RETURNED · DELIVERED · STILL_REQUESTED), restock=false 복원 미호출, 주문 PURCHASE_CONFIRMED 인 REFUNDED 반품도 복원, 주문이 RETURN_REQUESTED 아니면 ORDER_STATUS_UNEXPECTED.
- PG(Testcontainers, CI): 스케줄러가 두 유형을 함께 집음, 환불 기록 vs 고객 추가 접수 동시.
- 회귀: OrderCancellationProcessorTest · PipelineProcessorTest · CancellationRefundRecoveryPostgresTest · ClaimAdmin*Test 무수정 통과.

## Failure Table (B2 전체)
| 단계 | 남는 상태 | 복구 |
|---|---|---|
| 승인 커밋 뒤 PG 전 중단 | APPROVED + lease | 스케줄러 |
| PG 미확정 | APPROVED 횟수+1 → 상한 초과 REFUND_FAILED(UNCONFIRMED) | 자동 재시도 → A2(승인 +14일) → A3 |
| PG 종결 거절 | REFUND_FAILED(코드) | A3 수동 확정 |
| PG 성공 · 기록 tx 실패 | APPROVED + lease, 503 ORDER_036 | 스케줄러 재전송 → payment 200 |
| 기록 성공 | REFUNDED(restock=false 면 COMPLETED) + 주문 정리 + 이력 한 커밋 | — |
| 복원 미확정 | REFUNDED → RESTORATION_FAILED(UNCONFIRMED) | 스케줄러 → A2 |
| 복원 종결 거절 | RESTORATION_FAILED(코드) | 재고 조정 뒤 A3 종결(주문 상태 무관) |
| 기록 시 주문 RETURN_REQUESTED 아님 | RESTORATION_FAILED(ORDER_STATUS_UNEXPECTED) | 운영자 조사(잠금 규칙상 도달 불가, fail-closed) |
| 반품 수동 확정 뒤 payment PENDING | 다음 반품 PAYMENT_016 연쇄 | #693 PR B + order 호출 |

## DDL: 없음 (B1 check 가 B2 조합 허용). 거절 사유 DB check 는 A4 DDL 에.

## 문서 (B2a 분)
- order-flow-guide §5 B2 절(상태표 다음 칸, 주문 FOR UPDATE 규칙, 정리 시점, 복원의 주문 상태 무관, 귀책 미저장), §1-2 동시성에 반품 잠금 규칙, 942행 "B2 다" 문장 정리.
- payment-guide §8: 반품 부분 취소 호출(본문 · 키 동일), 다른 claimId PENDING → 016 한계, LATE_APPROVED 대상 아님.
- deploy-guide: DDL 없음, 배포 순서 B2a → B2b → B2c, 반품도 `order.cancellation.retry.enabled` 게이트, APPROVED 행 생성 뒤 B2a 이전 롤백 금지.

## B2b 수습 (다음 PR, ~16): ClaimAdminRecoveryProcessor · Policy · ClaimRecoverySituation · Claim(reopen · confirmRefundByAdmin · completeByAdmin) 반품 확장(반품 종결은 주문 CANCELED 조건 안 걺, 반품 수동 확정은 원장 REJECTED 계열만), `sumRequestedReturnQuantities` → `sumReservedReturnQuantities` 개명, B1 LOW 정리.
## B2c API (마지막, ~26): `POST /api/v1/admin/claims/{claimId}/return-approval` {expectedVersion, restock, deductionAmount, fault?, expectedRefundAmount}, `POST .../return-rejection` {expectedVersion, reason}, 오류 ORDER_042(결정 불가) · 043(진행 중 반품) · 044(환불액 ≤ 0) · 045(계산 불가, log.error) · 046(금액 변경), 재사용 032 · 037 · 025 · 040 · 100, 감사 CLAIM_RETURN_APPROVE · CLAIM_RETURN_REJECT, 승인 tx(게이트 → 클레임 → 주문 FOR UPDATE → 재조회 + 버전 → 목록 → 042 · 043 → 결제 총액 대조 → Settled.of → refund.withDeduction → 044 · 046 → approveReturn → update, 커밋 뒤 Facade 가 pipeline.progress), 상세 미리보기, Swagger 상태 나열, rejectReturn(Decision).
