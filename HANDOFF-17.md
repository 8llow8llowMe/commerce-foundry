# HANDOFF — #17 PR-A 결제 후 취소 수습 (2026-11-06 17:3x)

> 집에서 이어 작업하기 위한 인계 메모다. **PR 올리기 전에 이 파일과 HANDOFF-17-design.md, HANDOFF-17-reviews.md 를 삭제**한다.

## 진행 상태

- 브랜치 `feature/be/17-admin-claim-processing` — develop `2dd1b858` 기준. **develop 이 `3bea6eeb`(#236 등)로 움직였다 → 이어서 하기 전에 rebase.**
- 커밋은 "작업 중 저장" 1개(코드 + 문서, 48개 파일). **CI 를 통과하지 못하는 상태**다(아래 실패 시험).
- 설계 정본: `HANDOFF-17-design.md`(= `_tmp/17-design.md` 사본, 맨 아래 "확정 결정" 8개). PR 순서 A(지금) → B1(반품 기반) → B2(반품 흐름).
- PR-A 범위(구현됨): 어드민 API 4개 `POST /api/v1/admin/claims/{claimId}/retries · rejection · refund-confirmation · completion`, 어드민 응답 칸 보강(paymentId · failureCode · attemptCount · nextAttemptAt · decisionReason · decidedById · decidedAt), 고객 재취소 409 `ORDER_037`, 오류 코드 036 · 037 · 038 · 041 · 검증 169, 운영 DDL `backend/service/order-service/src/test/resources/ddl/claim-admin-decision.sql`(배포 전 1단계만), 업그레이드 시험 2개에 연결, 문서 5개.

## 막힌 것 — 실패 시험 (먼저 고칠 것)

- 내 `./gradlew :service:order-service:cleanTest :service:order-service:check --no-build-cache` → 1664건 중 **1 실패**(구현 에이전트는 "실패 0"이라고 보고했지만 틀렸다).
- `OrderQuoteRemoteFailureWebTest` > "배송비 견적 5xx · read timeout · 서킷 열림은 모두 503 ORDER_011 이다" — expected `ORDER_011` but was `ORDER_002` (`OrderQuoteWebControllerTest.assertFailure:71`, `OrderQuoteRemoteFailureWebTest.java:161`).
- 단독 실행으로도 재현(4건 중 1 실패) → 시험 순서 문제 아님. **깨끗한 develop(3bea6eeb)에서는 통과** → PR-A 변경이 원인. diff 에 quote 파일은 없다 → 공용 설정·예외 처리·오류 코드 매핑·시험 지원 클래스(`OrderReturnTestConfig` 등)·MockitoBean·컨텍스트 공유 쪽을 의심. **시험 기대값을 바꾸지 말고 근본 원인을 찾는다.**
- 원인 조사를 맡긴 구현 에이전트는 17:35 마감 때 멈췄다. 조사 중 일부 파일을 손댔을 수 있으니 `git diff` 로 의도와 다른 변경이 없는지 먼저 본다.
- 재현: `cd backend && ./gradlew :service:order-service:test --tests '*OrderQuoteRemoteFailureWebTest'`

## 검토 결과 (HANDOFF-17-reviews.md 에 전문)

- 보안: **HIGH 1(★ 결정 필요)** — 권한 수준이 `security-guide.md:250` 규칙과 충돌. 4 API 모두 `ORDER_MANAGE`(MANAGER 허용). 내 추천 (a) refund-confirmation = `SETTLEMENT_MANAGE`, retries · rejection = `CS_MANAGE`(+ORDER_MANAGE), completion = `ORDER_MANAGE`. (b) 유지하고 :250 을 명시적 결정으로 교체.
- DB: HIGH 0. MEDIUM 2 — 재시도가 lease 가 살아 있는 클레임을 다시 선점(원격 중복·실패 횟수 오집계), 롤링 배포 · 롤백 위험 문서화(REJECTED 행 생긴 뒤 이전 앱 롤백 금지).
- Hexagonal: HIGH 0. MEDIUM 2 — 고객 cancelable 판정이 Facade 로 새어 나감(Processor 로 모으기), `PAID_BEFORE_SHIPMENT` 3곳 중복(`OrderStatus#isPaidBeforeShipment()`).
- 결제 정합성: **HIGH 3** — (1) PAYMENT_016 · 017 로 닫힌(이미 환불됐을 수 있는) 건도 거절 가능 → 거절은 돈이 확실히 안 움직인 코드만, (2) 재시도가 lease 무시 → 원격 중복 호출, (3) completion 이 RESTORATION_UNCONFIRMED 도 받아 이중 복원 가능. MEDIUM 3.
- 최종 reviewer: REQUEST_CHANGES — 위 HIGH 들과 같은 지적 + completion 이 ORDER_STATUS_UNEXPECTED 에서 주문 상태 미검사(MEDIUM), 시험 공백 7개. 전문은 reviews 파일.

## 집에서 할 일 (순서)

1. 워크트리 준비 + rebase
   ```bash
   git fetch origin
   git worktree add ../commerce-foundry-wt/17-admin-claim-processing feature/be/17-admin-claim-processing   # 집 PC 에 없으면
   cd ../commerce-foundry-wt/17-admin-claim-processing
   git rebase origin/develop
   ```
2. 실패 시험 근본 원인 수정 → 위 재현 명령으로 확인.
3. ★ 권한 결정(보안 HIGH) 받기.
4. 검토 지적 반영(결제 HIGH 3 · 보안 HIGH 1 · DB MEDIUM 2 · Hexagonal MEDIUM 2 + 결제 MEDIUM 3 + LOW 들) — 한 번에 be-executor 로.
5. 반영 뒤 결제 정합성 · 최종 reviewer 검토를 다시 돌린다(be-payment-reviewer · reviewer) — 이번 결과는 reviews 파일에 다 있다.
6. `./gradlew :service:order-service:cleanTest :service:order-service:check --no-build-cache` 를 직접 돌려 통과 확인.
7. HANDOFF 파일 3개 삭제 → "작업 중 저장" 커밋과 함께 정리(코드 커밋 · 문서 커밋으로 나눠 다시 만들기 — 원격 이력 덮어쓰기가 필요하면 새 브랜치 이름으로 올린다. 이 PC 의 권한 정책은 force push 를 막는다).
8. `/pr` — 제목은 이슈 제목 "[BE] feat: 어드민 주문 관리 API 구현 (목록·상세·상태 변경·취소/반품 처리)" 확인 후 사용, 본문 첫 줄 "#17 PR-A: 결제 후 취소 수습", 운영 DDL 절에 `claim-admin-decision.sql` 전문, FE 영향(어드민 버튼 4개 · 응답 칸 · 고객 ORDER_037) → reviewer seonghoho.

Claude Code 에 붙여 넣을 프롬프트:

```
/dev-orchestrator #17 PR-A 이어서. 워크트리 17-admin-claim-processing 의 HANDOFF-17.md · HANDOFF-17-design.md · HANDOFF-17-reviews.md 를 먼저 읽고,
실패 시험 OrderQuoteRemoteFailureWebTest 근본 원인부터. 그다음 ★ 권한 결정 → 검토 반영 → 결제 · 최종 검토 → check → /pr.
product-service · payment-service 수정 금지.
```

## 결정 대기

- ★ 어드민 수습 API 권한 수준(보안 HIGH, 위 (a)/(b)).
- 열린 질문(설계): USD/PayPal 부분 환불 수수료 처리, 수동 확정 때 payment internal 로 "PG 에서 취소됨" 확인(후속 이슈 후보).
