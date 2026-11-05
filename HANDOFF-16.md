# HANDOFF — #16 결제 후 주문 취소 (2026-11-05 17:41)

> 이 파일은 집에서 이어 작업하기 위한 인계 메모다. **PR 올리기 전에 삭제**한다.

## 진행 상태

- 브랜치 `feature/be/16-paid-order-cancellation` — origin/develop `84eee730` 기준, **코드 변경 없음**.
- 분류: FEATURE / T3 (결제 환불 · 재고 복원 · 주문 상태 전이 · 멱등 · 서비스 간 정합성).
- architect 설계가 인계 직후 도착해 **[HANDOFF-16-design.md](HANDOFF-16-design.md)** 에 담았다(라인 근거는 develop 84eee730).
  집에서는 설계를 다시 돌리지 않는다. 먼저 §5 ★ 결정을 사용자에게 받은 다음 PR-2a-1(기반)부터 `be-executor` 로 구현한다.
  rebase 뒤 develop 이 많이 움직였으면 라인 근거만 `explorer` 로 다시 확인한다.

## 남은 범위 (#16 미완 체크박스)

- [ ] `POST /api/v1/orders/{orderId}/cancellation` — 출고 전 취소 → 환불 실행(#15) + 재고 복원
- [ ] 타인 주문은 404, 출고 후 취소는 거절된다
- [ ] 부분 취소 환불 금액(배송비 차감 포함)이 테스트된다

## 이미 develop 에 있는 것 (설계 때 재사용 확인)

- #16: PR #454 주문 내역·취소, #527 구매 확정, #564 고객 부분 반품 접수
- #17: PR #574 어드민 클레임 목록·상세 조회
- #15: PR #528 payment 취소, #559 REFUND_FAILED 필터, #563 웹훅
- #14/#308: C1 #584 · C2a #617 · C2b #623 (order_payment · restoration_claim_id · stock-restorations 복원 · CANCELED 경합), C3a-1 #627 · C3a-2 #633

## 겹치면 안 되는 다른 세션 (11-05 기준, `_coordination/BOARD.md`)

- Codex S2: #308 **C3b** — payment-service 회신 컨슈머 · PaymentCancelPolicy
- Codex S3: #20 B1b (product 가격) · S4: #163 R2a
- 다른 세션: #586 product-service 어드민 권한 (워크트리 `586-product-admin-permissions`)
- → **product-service 는 수정하지 않고** 기존 internal 재고 API 를 쓴다. payment-service 를 바꿔야 하면 C3b 와 겹치는지 먼저 확인한다.

## 다음 명령 (집에서)

```bash
git fetch origin
git worktree add ../commerce-foundry-wt/16-paid-order-cancellation feature/be/16-paid-order-cancellation   # 집 PC 에 없으면
cd ../commerce-foundry-wt/16-paid-order-cancellation
git rebase origin/develop
```

그다음 Claude Code 에서:

```
/dev-orchestrator #16 PR-2a-1. HANDOFF-16.md · HANDOFF-16-design.md 를 먼저 읽고 §5 ★ 결정을 받은 뒤 구현.
product-service · payment-service 수정 금지, #308 C3b(payment 회신 컨슈머)와 겹치지 않게.
```

architect 에게 받을 것: 현재 고객 취소 API 범위(결제 전만인지), payment 취소 internal 계약, order → payment 취소 호출 경로,
재고 복원 경로, 상태 전이 표(order-flow-guide.md), 배송비 차감 정책 문서 여부, 30파일 이내 PR 분할안, ★ 사용자 결정 항목.

## 결정 대기 항목

HANDOFF-16-design.md §5 의 ★ 6개 — 순서(환불 먼저), 자동 처리 범위(MAKING 플래그), 응답 환불액 블록, 부분 취소 API 분리,
PR 2분할, 사유 저장 방식. 추천안이 함께 적혀 있다. 열린 질문 3개(PREPARING 공지, 백오프 상한, 환불 알림)도 같이 묻는다.
