## 도착한 검토 결과 (11-06 17:3x 기준)

### 보안(be-security-reviewer) — CRITICAL 0 · HIGH 1 · MEDIUM 1 · LOW 3
- **HIGH(★ 사용자 결정 필요) — 권한 수준이 security-guide 의 기존 규칙과 충돌**: `security-guide.md:250` 은 "클레임 승인 · 환불 실행 · REFUND_FAILED 재시도 핸들러는 ORDER_MANAGE 에 그대로 얹지 말고 CS_MANAGE · SETTLEMENT_MANAGE 와 함께 따로 정한다" 인데, 4개 API 모두 ORDER_MANAGE(MANAGER 허용)다. 특히 refund-confirmation 은 PG 재조회 없이 "환불 완료" 기록 + 재고 복원.
  - 안 (a): refund-confirmation = SETTLEMENT_MANAGE(OWNER·ADMIN 만), retries · rejection = CS_MANAGE(+ORDER_MANAGE), completion = ORDER_MANAGE. OrderAdminAuthorizationMappingTest · security-guide 표 함께 수정.
  - 안 (b): ORDER_MANAGE 유지하고 :250 문장을 명시적 결정으로 교체(근거: PG 가 정본, 수동 확정은 order 기록).
  - 후속 후보: 수동 확정 때 payment internal 로 "PG 에서 취소됨" 확인(지금은 운영자 말만 믿음).
- MEDIUM: decisionReason 자유 텍스트를 STAFF 도 읽음(ORDER_READ) — 사유에 고객 개인정보·계좌를 쓰지 말라는 문구를 security-guide 개인정보 절과 FE 입력란에.
- LOW: 멱등 200 도 감사 SUCCESS 로 남음(문서 명시 또는 target 에 changed=false), 400·403 은 수습 감사 FAILURE 에 안 남음(문서 범위 좁히기), @AdminAudited 와 감사 행위 문자열 일치 시험 없음(기존 패턴, 후속).
- 확인됨: 결정자는 JWT 주체, reason 검증(169/148/151), 로그에 reason 없음, 게이트웨이 어드민 경로·OTP 적용, 고객 037 은 소유 확인 뒤라 정보 노출 없음, 비밀 없음.

### Hexagonal(be-hexagonal-reviewer) — CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 4
- MEDIUM: 고객 cancelable 판정이 Processor 와 OrderHistoryWebFacade(:45, 54-59, 146-149 — PAID_BEFORE_SHIPMENT + findRejectedOrderIds + notCancelable 덮어쓰기)로 갈렸다. → OrderHistoryProcessor 에 ClaimRepositoryPort 주입, findSlice 같은 readOnly tx 에서 거절 주문 아이디 조회해 Info 에 반영, 상세도 Processor 가 판정, Facade 는 한 줄로 복귀.
- MEDIUM: PAID_BEFORE_SHIPMENT 가 3곳 중복(OrderHistoryWebFacade:45 · ClaimAdminActionProcessor:39 · Order:50) → OrderStatus#isPaidBeforeShipment() 로 통합.
- LOW: isRefundedOrLater 를 Claim.Status 로, ORDER_STATUS_UNEXPECTED 상수를 Claim 쪽으로, 설계와 다른 Facade 구성(기존 ClaimAdminFacade 확장 — 규칙상 OK)은 PR 본문에 한 줄, Processor 의 사전 가드와 도메인 가드 중복은 주석 정도.
- 확인됨: Controller → UseCase 만, admin DTO 분리, Presenter 변환만, 포트 반환 타입, 상태 전이 도메인 메서드, 감사 패턴, H12.

### DB(be-db-reviewer) — CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 4
- 확인됨: 운영 DDL 은 nullable 칸 3개 + check 교체라 정리 단계 불필요, 스냅숏·엔티티 일치, 기존 check 와 새 상태 정합, 업그레이드 시험 2개 모두 새 스크립트 연결(c2a 기반 시험은 이 둘뿐), IN 조회 1회로 N+1 없음, findByIdForUpdate 없이 두 행 @Version 으로 충분.
- MEDIUM: ClaimAdminActionProcessor:74 prepareRetry 가 lease 가 살아 있는 REQUESTED·REFUNDED 도 hold 로 다시 선점 → 진행 중인 스케줄러/고객 쪽과 원격 호출 중복, 진 쪽이 실패 1회로 세어져 UNCONFIRMED 상한에 일찍 닿을 수 있음. → nextAttemptAt > now 면 원격 없이 현재 상세(또는 409).
- MEDIUM: 롤링 배포 · 롤백 위험을 문서에 명시 — 구 앱은 REJECTED 를 못 읽어 그 행이 든 어드민 목록 페이지 500, 구 앱은 거절된 주문 고객 재취소를 막지 않음(uk 충돌 롤백). "REJECTED 행이 생긴 뒤 PR-A 이전 앱으로 롤백 금지, 필요하면 거절 API 먼저 차단", 롤링 중 거절 API 막는 수단(게이트웨이/플래그/절차).
- LOW: ADD CHECK 에 NOT VALID 없음(작은 테이블이라 OK, 주석), preflight 에 새 칸 존재 여부 SELECT 추가(dev ddl-auto 대비), 마지막 결정이 덮어써 수동 확정 사유가 audit 에만 남는 경우(감사 실패 경보 또는 문서화), findOrderIds 는 uk 앞 열 사용으로 충분.
