# HANDOFF — #17 B2a 반품 환불 · 복원 엔진 (2026-11-13 13:4x)

> 다음 세션이 이어 작업하기 위한 인계 메모다. **PR 올리기 전에 이 파일과 HANDOFF-17-B2-design.md 를 삭제**한다.

## 진행 상태

- 브랜치 `feature/be/17-return-approval-flow` — develop `c20876c4` 기준(B1 #718 · #693 PR A #719 포함). develop 이 그 뒤 #720 · #721 로 움직였다 → **이어서 하기 전에 rebase**(order-service 는 겹치지 않을 것, 문서 충돌 가능).
- 커밋은 "작업 중 저장" 1개(코드 20파일 + 이 메모 2개).
- 설계 정본: `HANDOFF-17-B2-design.md`(architect 11-13 + 사용자 결정 — 3분할, expectedRefundAmount 409, 부분 승인 미지원 · 후속 #722, 반품 상태 변경 tx 는 주문 FOR UPDATE 먼저). 결정은 #17 댓글(11-13 "B2 착수")에도 있다.
- **구현 완료(be-executor), 20파일**:
  - main 12: `Claim`(7개 전이의 requireCancellation 해제 · 유형별 환불 대기 `awaitingRefundStatus()` · `requiresRestoration()` · `Type#awaitingAttemptStatuses()`, `hasSameRefundRequest` 같은 유형만, `isRefundRejected` 유형 조건 제거, reopen · requireDecisionOn 은 취소 전용 유지 = B2b), `OrderStatusHistory`(사유 `RETURN_REFUNDED`), `OrderRepositoryPort` · `OrderRepositoryAdapter`(`findByIdForUpdate` PESSIMISTIC_WRITE NOWAIT), `StockRestorationPort`(`default restoreReturn` — 기본 UnsupportedOperationException) · `StockRestorationClientAdapter`(사유 인자 private restore), `ClaimRepositoryPort`(주석) · `ClaimRepository` · `ClaimRepositoryAdapter`(재시도 대상 `(CANCELLATION ∧ {REQUESTED, REFUNDED}) ∨ (RETURN ∧ {APPROVED, REFUNDED})` 한 JPQL, 상태 집합은 도메인이 줌), `ClaimEntity`(주석), `OrderCancellationProcessor`(대기 판정 2곳 `isAwaitingRefund()`, `findClaimById`, `applyRefunded` 유형 분기 — 취소 분기 그대로, 새 `applyReturnRefunded`), `OrderCancellationPipelineProcessor`(progress 판정, `reload()` 취소 = findClaim(orderId) / 반품 = findClaimById, restore 가 `requiresRestoration` false 면 호출 없이 완료, 반품 = restoreReturn).
  - test 5: `ClaimTest`(수정 1 + 신규 6), `StockReservationClientAdapterTest`(+1 `restoresReturn`), 신규 `support/ReturnRefundPipelineContract`(8건, H2 · PG 공용), 신규 `ReturnRefundPipelineH2Test`, 신규 `ReturnRefundPipelinePostgresTest`(계약 8 + 잠금 경합 1).
  - docs 3: order-flow-guide(§1-2 반품 잠금 규칙, B1 절 정리, 스케줄러 설명, §5 "반품 환불 · 복원 엔진 (#17 B2a)" 절), payment-guide §8(반품 부분 취소 호출 · PAYMENT_016 한계 · 이미 환불된 claimId 200 · LATE_APPROVED 대상 아님), deploy-guide(B2a 절: DDL 없음, 배포 순서 B2a → B2b → B2c, 게이트 공유, 승인 행 생긴 뒤 B2a 이전 롤백 금지).
- **설계와 다르게 한 점(executor 판단, 검토 때 확인할 것)**:
  1. 재조회를 전부 findById 로 통일하지 않음 — 기존 취소 시험이 findClaim(orderId) · findByOrderIdAndRequestKey 를 목으로 검증하므로 취소는 기존 조회, 반품만 아이디(유형 분기 2곳).
  2. 복원 포트를 reason 인자화하지 않고 `default restoreReturn` 추가(기존 시험 · `OrderPaymentCrashChild` 람다 구현 보존). 사유는 `claim.type()` 으로 메서드를 고르는 방식으로만.
  3. 반품 기록 중 `ReturnRefundPolicy.outcome` 예외(살아 있는 취소 · 수량 초과 등 데이터 이상)도 fail-closed → `RESTORATION_FAILED(ORDER_STATUS_UNEXPECTED)`(그냥 두면 롤백 → 503 → 무한 재시도).
  4. 파이프라인에 `requiresRestoration` 가드(restock=false 인 REFUNDED 반품은 복원 없이 COMPLETED).
  5. 로그 문구 "cancellation …" 를 반품도 그대로 사용(반품 전용은 "return refunded but …" 2줄). 운영 알림 규칙이 "cancellation claim failed, operator action required" 로 걸려 있으면 반품 실패도 잡힘 — 의도인지 확인.
- 바꾼 기존 시험: `ClaimTest.approvedReturnDoesNotUseCancellationTransitionsYet` 1건만(허용 예외 — B2a 에서 그 전이가 열려 삭제, 신규 시험으로 대체).

## 검증

- executor 반복 실행(모두 실패 0): ClaimTest 52 · OrderCancellationProcessorTest 34 · OrderCancellationPipelineProcessorTest 20 · ClaimAdminRecoveryPolicyTest 37 · ClaimRecoverySituationTest 28 · ClaimCancellationRetrySchedulerFacadeTest 5 · StockReservationClientAdapterTest 96 · ReturnRefundPipelineH2Test 8. PG 9건은 로컬 skip.
- **module check 통과**: `./gradlew :service:order-service:cleanTest :service:order-service:check --no-build-cache` → exit 0(13:27 시작 · 13:32 결과). 메인 세션이 결과 XML 을 직접 집계: 150 suite, **1880건 실행, 실패 0 · 오류 0 · skip 181**(Testcontainers PG · Kafka, 로컬 Docker 없음). 기존 취소 시험 무수정 통과 — ClaimCancellationH2Test 11 · OrderCancellationWebIntegrationTest 9 · ClaimAdminRetryWebIntegrationTest 10 · ClaimAdminDecisionWebIntegrationTest 6 · OrderCancellationProcessorTest 34 · OrderCancellationPipelineProcessorTest 20, 새 ClaimTest 52 · ReturnRefundPipelineH2Test 8 모두 skip 0.
- PG 전용(CI 에서 확인): ReturnRefundPipelinePostgresTest 9건(계약 8 + 주문 잠금 경합 1). rebase 뒤 다시 check 를 돌린다.

## 검토 — 아직 안 함

T3 라 다음을 병렬로: be-payment-reviewer(취소 동작 불변 · 반품 환불 기록 tx · 이중 환불/복원 · fail-closed), be-db-reviewer(재시도 OR 질의 · findByIdForUpdate NOWAIT · 잠금 순서 — recordConfirmedRefundWithLock 은 클레임 → 주문, 반품 기록은 주문 FOR UPDATE), be-hexagonal-reviewer(포트 default 메서드, 유형 분기 위치), 그 뒤 최종 reviewer. 보안 검토는 API · 권한 변경이 없어 생략 가능.

## 다음 세션에서 할 일 (순서)

1. 워크트리 준비
   ```bash
   git fetch origin
   git worktree add ../commerce-foundry-wt/17-return-approval-flow feature/be/17-return-approval-flow   # 없으면
   cd ../commerce-foundry-wt/17-return-approval-flow
   git rebase origin/develop
   ```
2. `./gradlew :service:order-service:cleanTest :service:order-service:check --no-build-cache` 직접 실행(10분 넘을 수 있음 — 백그라운드). 실패하면 원인부터.
3. 검토 4개(위) → CRITICAL/HIGH 만 be-executor 로 반영 → check 재실행.
4. HANDOFF 2개 삭제 → "작업 중 저장" 커밋을 [BE] 코드 · [DOCS] 문서 커밋으로 다시 만들기(force push 막히면 새 브랜치 이름, 이 원격 브랜치는 머지 뒤 삭제).
5. `/pr` — 제목 예 "[BE] feat: 반품 환불 · 재고 복원 엔진을 취소 파이프라인에 연결한다 (#17 B2a)", Refs #17, DDL 없음, 승인 API 가 없어 반품 승인 행은 B2c 전까지 생기지 않음, 리뷰어 seonghoho(API 변화 없음 — FE 영향 없음).
6. 이어서 B2b(수습 확장) → B2c(승인 · 거절 API). 설계 파일의 B2b · B2c 절과 아래 "넘길 것".

붙여 넣을 프롬프트:

```
/dev-orchestrator #17 B2a 이어서. 워크트리 17-return-approval-flow 의 HANDOFF-17-B2a.md · HANDOFF-17-B2-design.md 를 먼저 읽고,
rebase → order-service check 직접 실행 → 결제 · DB · 계층 검토 병렬 → HIGH 이상 반영 → 최종 reviewer → HANDOFF 삭제 · 커밋 정리 → /pr.
order-service · backend/docs 만 수정(payment · product · operations 금지).
```

## B2b · B2c 로 넘길 것

- B2b: `Claim.reopen` · `confirmRefundByAdmin` · `completeByAdmin`(requireDecisionOn) 반품 확장, `ClaimAdminRecoveryPolicy` 의 requireStoredCancellation · `type != CANCELLATION` 조건 확장(반품 종결은 주문 CANCELED 조건 안 걺, 반품 수동 확정은 원장 REJECTED 계열만), `sumRequestedReturnQuantities` → `sumReservedReturnQuantities` 개명, 반품 수동 확정 tx 도 `findByIdForUpdate` 먼저.
- B2c: 승인 · 거절 tx(`findByIdForUpdate` → 재조회 + 버전 → 클레임 목록), 커밋 뒤 Facade 가 tx 밖에서 `pipeline.progress`, 게이트 꺼짐 503 ORDER_040, 감사 detail fault · restock, expectedRefundAmount 409 ORDER_046, 상세 환불 미리보기, Swagger 상태 나열.
- 위험: 재시도 OR 질의의 PG 실행 계획 EXPLAIN 미실측(대상 적음).

## 다른 세션 상태 (11-13 13:4x)

- #720(#359 PR-2b-1) · #721(#693 PR B) 머지 · 정리 완료. #693 PR C(결제 어드민 목록 · 필터)는 **PR #723** 으로 올라감(1591건 실패 0, 검토 HIGH 0, DDL 없음, 리뷰어 seonghoho) — 머지되면 #693 의 payment 쪽(A · B · C) 끝, 정리(브랜치 · 워크트리 693-external-refund-admin-list · #693 작업 상세 5번 체크 · 댓글) 필요. #693 order 2단계(order 수동 확정 → payment external-refund 먼저 호출, RECORDED · ALREADY_REFUNDED 일 때만 확정, 응답 channel 정본 — #721 본문)는 B2 뒤.
- #359 2b-2 는 WIP 로 원격 `feature/be/359-product-draft-generation-register`(bdb29979) — 루트 `HANDOFF-359-2b-2.md`(영수증 영속성 · DDL · 플래그 완료, 등록 Facade · internal API 남음).
- 후속 이슈 #722(반품 거절 뒤 재접수 유예 또는 부분 승인).
- 보존: 원격 `feature/be/17-admin-claim-processing`(A4 · B 참고용).
