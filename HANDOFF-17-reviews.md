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

### 결제 정합성(be-payment-reviewer) — CRITICAL 0 · **HIGH 3** · MEDIUM 3 (17:3x 도착)
- **HIGH-1 이미 환불된 결제도 거절 가능**(ClaimAdminActionProcessor:103-107, Claim:258): payment `PAYMENT_016` 은 "결제가 이미 CANCELED/콘솔 취소" · "다른 PENDING 있음" · "PG 성공 뒤 complete 에서 이미 REJECTED" 까지 섞여 있고, `PAYMENT_017` 은 이미 일부 환불됐다는 뜻. 지금은 REFUND_UNCONFIRMED 만 거절 금지라, 016/017 로 닫힌 건을 거절하면 돈은 환불됐는데 주문은 PAID 로 돌아가 출고된다. → 최소: 016 · 017 은 거절 금지(409 ORDER_038)로, 거절은 PG 거절 계열(PAYMENT_019 · 001 · 400 검증 등 돈이 확실히 안 움직인 코드)만. 더 나아가 payment 조회 포트(#308)로 결제 DONE · 이 claimId SUCCEEDED 없음을 확인 후 허용, 또는 payment 016 세분화(후속 이슈).
- **HIGH-2 대기 상태 재시도가 lease 를 무시**(ClaimAdminActionProcessor:74, Claim.holdUntil:213-219): 진행 중인 원격 호출과 겹쳐 PG 취소 동시 2회 → 한쪽 016 → HIGH-1 경로. → REQUESTED · REFUNDED 이고 nextAttemptAt > now 면 409 ORDER_025("진행 중"). DB 검토 MEDIUM 과 같은 지적. 동시성 시험 추가.
- **HIGH-3 completion 이 RESTORATION_UNCONFIRMED 도 받음**(ClaimAdminActionProcessor:177): product 는 실제로 복원했는데 응답만 유실된 경우, 운영자가 재고 조정 후 completion 하면 이중 복원. → RESTORATION_UNCONFIRMED 는 completion 금지(재시도 강제 — product claimId 멱등이라 안전), completion 은 종결 코드(027 · 028 · 029 · 003 · RESTORATION_DATA_INVALID · ORDER_STATUS_UNEXPECTED)만.
- MEDIUM-4: 수동 확정 · 거절 근거가 사라짐(failureCode null 처리, Decision 덮어쓰기, CANCELED 분기 ADMIN 이력 없음, 감사 줄에 사유 없음) → 종결 시 실패 코드 보존(last_failure_code 등), 결정 이력 append-only 검토, 감사에 이전 상태 · 실패 코드.
- MEDIUM-5: 거절 뒤 늦게 온 성공이 warn 로그로만 버려짐(Pipeline:75-77, :61-62) → REJECTED 재읽기 + DONE 이면 error 로그 + 알림/표시, 시험.
- MEDIUM-6: 미확정을 "환불 안 됨"으로 확정할 경로가 없음(payment 018 고리) → deploy-guide ⑧ 에 한계 명시, payment 클레임별 취소 조회 API 후속 이슈.
- LOW: 수동 확정 · completion 에 paidCancellationOpen 게이트 없음(스케줄러 꺼져 있으면 REFUNDED 방치 알림). 거절 원상태 판정 · 전이 표 확장 · ORDER_STATUS_UNEXPECTED 재시도 금지 · 수동 확정 뒤 복원 멱등은 정상.

### 최종 reviewer — 마감까지 미도착 → 집에서 다시 돌린다.
- HIGH: HANDOFF-17*.md 3개가 커밋돼 public 원격에 있음 → PR 전에 빼고(이력 정리는 새 브랜치 이름으로), 남길 내용은 이슈 댓글로. (계획대로)
- HIGH: security-guide.md:250 규칙과 ORDER_MANAGE 권한 모순(보안 HIGH 와 같음, ★ 결정).
- MEDIUM: 재시도가 lease 무시(결제 HIGH-2 · DB 와 같음).
- MEDIUM: completion 이 ORDER_STATUS_UNEXPECTED 에서 주문 상태를 안 봄 → 환불된 살아 있는 주문이 실패 목록에서 사라지고 출고될 수 있음 → 409 로 막거나 주문 CANCELED 확인, 또는 "주문을 먼저 손으로 맞춘 뒤 completion" 절차 문서화.
- LOW: 거절 · 종결 뒤 failureCode null(화면에서 PG 거절 코드 사라짐 — 결제 MEDIUM-4 와 같음), 결정 덮어쓰기(수용 가능), api-design-guide.md:1254 빈 코드 스팬 오타, 통합 시험의 "PAYMENT_016 재시도 성공"은 운영에서 불가능한 경로 → REFUND_UNCONFIRMED 로 바꾸고 016 재시도는 "다시 REFUND_FAILED 200", 본문 없는 요청은 ORDER_169 가 아니라 공통 400(문서 문구 좁히기), retries 는 PG 재거절이어도 200 이니 FE 는 status 를 읽어야 함을 문서에.
- 13개 판단 대부분 타당(거절 전제는 결과적으로 설계와 같다고 판단 — 단 결제 HIGH-1 은 016 · 017 의 의미 자체가 섞여 있다는 지적이라 별개로 반영).
- 시험 공백: RESTORATION_FAILED 재시도 통합, PATCH 상태 변경 CANCEL_REQUESTED → PAID 409 ORDER_023 웹 시험, 활성 lease 중 재시도, ORDER_STATUS_UNEXPECTED completion, PAID · MAKING 원상태 거절 통합, completion 2회 200, 본문 없는 400.
