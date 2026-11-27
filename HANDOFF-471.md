# HANDOFF — #471 AI CS 상담원 이관 · 배정 상태 머신 (2026-11-27 17:2x)

## 현재 상태

- 브랜치 `feature/be/471-cs-handoff-state-machine`, base `origin/develop` 844b86ef. 워크트리 `commerce-foundry-wt/471-cs-handoff`.
- **코드 변경 없음.** 이 커밋은 이 문서 하나다. architect 설계를 저장소 코드와 대조해 확정했고, 구현은 시작하지 않았다.
- 검증 · 리뷰: 아직 없음.
- 설계 정본: #470 댓글 6569351436(상태 5개 · outbox 알림), #471 본문 · 댓글, 아래 "확정 설계". PR 전에 이 파일을 지운다.

## 확인한 사실 (근거 파일:줄, 경로는 `backend/service/support-service/src/main/java/com/commercefoundry/domainlayer/conversation` 기준)

- `support_conversation` 칸은 status · last_seq · version 뿐이다(`adapter/out/persistence/entity/ConversationEntity.java:23-58`, 테스트 스냅숏 `src/test/resources/ddl/support-schema.sql:117-130`).
  - 없는 칸: assignee, handoff 사유 · 요약, active_ai_message_id, resolution_kind.
  - `support_conversation_event` 테이블도 없다.
- AI 턴 코드가 없다. 작성자는 CUSTOMER, 상태는 COMPLETED 뿐이다(`support-schema.sql:208,213`).
- 고객 메시지를 쓸 때마다 대화 version 이 오른다(`application/service/processor/ConversationMessageProcessor.java:99`).
  - 그래서 어드민이 expectedVersion 을 보내는 방식은 쓰지 않는다. 대기 중 고객 글 하나에 수락이 헛되이 실패한다.
- 메시지 쓰기는 대화 행을 PESSIMISTIC_WRITE NOWAIT 로 잠근 뒤 @Version 으로 저장한다(`adapter/out/persistence/ConversationRepositoryAdapter.java:38-45,67-85`).
- 오류 코드 `SUPPORT_020`~ 는 아직 안 쓴다. `016` 은 #470 B2b-2 예약(#470 댓글 7209013730).
- 상태 전이 규칙: 도메인 전이 → merge(@Version) 만 쓰고 bulk UPDATE 는 금지(`backend/docs/coding-conventions.md:260-261,374`).
- 지식 어드민 권한은 조회 CS_READ · 쓰기 CS_MANAGE 다(`knowledge/.../KnowledgeAdminController.java:48,66`, `security-guide.md:310-311`).
- 사용자 결정(11-27): #470 B2b-2 "소유자당 열린 대화 1개" 확정.

## 확정 설계 (architect, 메인 세션이 저장소와 대조)

### 상태 전이 표
표 밖 전이는 도메인 `ConversationTransitionException` 이고, Processor 가 409 `SUPPORT_020` 으로 바꾼다.

| 전이 | 행위자 |
|------|--------|
| AI_ACTIVE → WAITING_AGENT | 고객(회원, 비회원은 `store.support.guest-handoff` on 일 때) · 시스템(#222-D, 메서드만) |
| WAITING_AGENT → HUMAN_ACTIVE | 어드민 CS_MANAGE, assignee = 수락자 |
| WAITING_AGENT → CLOSED | 어드민 (스팸 · 중복) |
| HUMAN_ACTIVE → RESOLVED, CLOSED | 어드민 |
| RESOLVED → CLOSED | 시스템 (표에만 둔다) |
| RESOLVED · CLOSED → 그 밖 | 없음 |

### 열린 대화 1개와의 관계
- 재개 전이는 두지 않는다. RESOLVED · CLOSED 는 열린 칸을 비우므로, 재문의는 `POST /conversations` 로 새 대화를 만든다.
  - 재개 전이를 두면 그사이 생긴 새 열린 대화와 유니크가 충돌한다.
- B2b-2 쪽에 권할 것: `open_*` 칸은 매퍼가 status 에서 파생하게 한다. 그러면 #471 종료 전이가 `open_*` 를 몰라도 된다.

### API
- **고객 `POST /api/v1/support/conversations/{id}/handoff`**
  - 본문 · 멱등 키 없음, 기존 인가식(`ConversationWebController.java:35`), 200 으로 기존 Detail 을 돌려준다.
  - 판정 순서:
    1. 인증
    2. 소유 확인 — 남의 대화면 404 `SUPPORT_010`
    3. 이미 WAITING · HUMAN 이면 200, 아무것도 바꾸지 않음
    4. 종료 상태면 409 `SUPPORT_020`
    5. 비회원이고 플래그 off 면 409 `SUPPORT_022`
    6. 전이
  - 게이트웨이는 기존 support-write 정책이 덮는다.
- **어드민 `GET /api/v1/admin/support/conversations?status=&assignee=ME&page=&size=`** (CS_READ)
  - PageResponse, 기본 필터 WAITING_AGENT, handoff_requested_at 오름차순
- **어드민 `GET .../{id}`** (CS_READ) — 요약 + events[]. 메시지 본문은 넣지 않는다.
- **어드민 `POST .../{id}/assign`** (CS_MANAGE, 감사 CONVERSATION_ASSIGN)
  - 본문 없음, 본인 수락만
  - 본인이 이미 담당이면 200, 그 밖의 비대기 상태면 409 `SUPPORT_021`
- **어드민 `PATCH .../{id}/status {status: RESOLVED|CLOSED}`** (CS_MANAGE, 감사 CONVERSATION_STATUS_CHANGE)
  - 같은 상태를 다시 요청하면 200, 표 밖이면 020
- 검증 메시지: 새 `ConversationValidationMessage` 의 `SUPPORT_116`~`119`.
  - 110~114 는 지식 검증과 대화 오류가 이미 겹쳐 있다(`KnowledgeValidationMessage.java:14-18` vs `ConversationErrorCode.java:17-21`).

### 동시성 · 트랜잭션
- **동시 수락(권장안 A):**
  - 잠금 없이 조회 → 도메인 전이 → @Version 저장
  - Facade 가 시도마다 새 트랜잭션을 열어 최대 3회 재시도(선례 operations `JobAdminFacade.java:69-78`)
  - 재조회에서 WAITING_AGENT 가 아니면 021
  - 쓰지 않는 안: 조건부 UPDATE(B)는 규칙 위반, 어드민 NOWAIT 잠금(C)은 패자 코드가 갈린다.
- **고객 이관 트랜잭션:** 기존 ForUpdate 포트(guest → conversation 순) → 전이 저장 → 이벤트 INSERT 를 Facade 한 트랜잭션으로 묶는다(`ConversationWebFacade.java:15`).
- **어드민 트랜잭션:** Facade 는 트랜잭션 없이, Processor 가 자기 트랜잭션을 연다. 최종 결과가 나온 뒤 감사를 남긴다(KnowledgeAdminFacade 패턴).
- **이벤트 행:** 전이와 같은 트랜잭션에 쓰고, append-only 다.
- **비회원 쓰기 규칙 변경:** "플래그는 진입만 막고, WAITING · HUMAN 대화는 소유자 누구나 쓴다"로 바꾼다(`ConversationMessageProcessor.java:87-89`).
  - 바꾸지 않으면 플래그 on 인 비회원이 이관 뒤 글도 못 쓰고 새 대화도 못 만든다.

### DDL — 필요 (dev 도 수동 적용, ddl-auto=update 는 ck 를 만들지 않음 `deploy-guide.md:487`)
- `support_conversation` 에 더할 것:
  - `assignee_member_id`
  - `handoff_reason`(CUSTOMER_REQUEST|AI_DECISION|AI_FAILURE)
  - `handoff_requested_at`
  - check 는 별도 이름 `ck_support_conversation_handoff` — B2b-2 의 state check 교체와 부딪히지 않게 한다.
  - 인덱스 `(status, handoff_requested_at, id)`
- 새 테이블 `support_conversation_event`: kind · from/to_status · actor_type · actor_member_id · assignee_member_id · reason · occurred_at.
- 요약 · resolution_kind · active_ai_message_id 는 그것을 쓰는 PR(#222 · #472)로 미룬다.

### 분할
- **A1 (약 28파일, DDL 전부):** 도메인 + 영속 + 고객 이관 + StoreProperties(`store.support.guest-handoff`).
- **A2 (약 24파일, DDL 없음):** 어드민 큐 · 상세 · 수락 · 상태 + 감사.
- 이후 PR: #471-B 이관 알림(support outbox → `support.conversation-handoff-requested` → operations `CS_HANDOFF`).
  - operations notification 패키지는 다른 세션(#229)이 작업 중이라 그 뒤에 한다.
- 범위 밖: AI 스트림 취소 · 부재 · 영업시간 · 타임아웃 재알림.

### 도메인 먼저 커밋할 최소 단위 (7파일, DDL · Spring 없음)
`Conversation.java`
- 전이 메서드를 더한다.
- 기존 8인자 생성자는 위임으로 남겨 매퍼 · 생성 경로를 고치지 않는다.

새 파일
- `ConversationTransitionException`
- `ConversationEventKind`
- `ConversationActorType`
- `HandoffReason`
- `ConversationEvent`
- `ConversationTest`(전이 표 전수)

### 테스트 계획
- 도메인: 5상태 × 행위 전수.
- H2 계약: 전이 + 이벤트가 함께 롤백되는지.
- Postgres: 동시 수락 1승 1패(021), 수락 대 고객 글 경합.
- 웹: 소유 404 · 플래그 off 022 · 멱등.
- `SupportAdminAuthorizationMappingTest` 에 4행을 더한다.
- **주의 — 기존 시험 · 코드 두 곳:**
  - `KnowledgeAdminFacadeTest` 는 감사 enum 전체를 knowledge 로 가정한다(84-91, 124-128). 새 감사 값이 들어가면 깨지므로 `KNOWLEDGE_` 접두로 거른다.
  - `SupportAdminAuditProcessor.java:36` 은 target 을 "knowledge" 로 고정해 두었다. 행위별 target 으로 바꾼다.

### 실패 표
| 상황 | 결과 상태 | 회복 |
|------|-----------|------|
| 이관 저장 후 이벤트 INSERT 실패 | 전체 롤백, AI_ACTIVE | 재요청 |
| 이관 커밋 후 응답 유실 | WAITING_AGENT | 재요청 200 무변경 |
| 이관 후 아무도 수락 안 함 | 고객이 열린 칸에 묶임 | 어드민 CLOSE 뿐 → 결정 1 |
| 수락 버전 충돌 | 변경 없음 | Facade 재시도, 패자 021 + FAILURE 감사 |
| 수락 커밋 후 응답 유실 | HUMAN_ACTIVE | 본인 재요청 200 |
| 종료 전이인데 open_* 미정리 | check 거절 · 롤백 | 매퍼 파생 + 계약 테스트 |
| 플래그 on → off | 이관된 비회원 대화 남음 | 위 쓰기 규칙으로 계속 진행 |

### 고칠 문서
- `support-conversation-guide.md`
  - 78-79행 비회원 규칙
  - 전이 표
  - #222-B 계약 "AI 확정은 대화 행을 잠그고 AI_ACTIVE 를 확인"
- `api-design-guide.md`
- `security-guide.md`(§3-1 표 · §11-4 행위)
- `deploy-guide.md`(DDL 절)
- `service-inventory.md`

## 사용자 결정 필요 (구현 전에)
1. 고객의 이관 취소(WAITING_AGENT → AI_ACTIVE)를 둘지. 타임아웃이 범위 밖이라 지금은 대기에서 빠져나갈 길이 없다.
2. 어드민 메시지 읽기 · 쓰기(AGENT 작성자, support_message check 변경)를 어느 PR 에 둘지. 없으면 HUMAN_ACTIVE 에서 운영자가 답할 수단이 없다.
3. 다른 운영자를 지정하는 배정은 auth 운영자 조회가 필요하다. v1 은 본인 수락만으로 갈지.
4. STAFF 는 CS 조회 권한만 있어서 큐는 보지만 수락하지 못한다. 의도가 맞는지.
5. 이관 사유를 진입 경로 3종으로 고정하고, AI 세부 사유는 #222-D 로 넘길지.
6. A1 과 #470 B2b-2 의 머지 순서. Conversation · Entity · Mapper · 스냅숏 4파일이 겹친다.

## 남은 일 순서
1. 위 결정 1~6 을 사용자에게 받는다. 6 은 B2b-2 담당 세션 · 감독과 맞춘다.
2. 도메인 최소 단위 7파일을 구현하고 ConversationTest 를 돌린다 → A1 의 나머지(영속 · DDL · 고객 이관 · 플래그 · 비회원 쓰기 규칙 · 문서) 순서로 간다.
3. support 모듈 check(`./gradlew :service:support-service:cleanTest :service:support-service:check --no-build-cache`)를 돌린다. 메모리가 부족하면 PR CI 로 확인한다.
4. 리뷰를 한 메시지에서 병렬로 돌린다(be-security · be-db · be-hexagonal · reviewer) + `/backend-api-check`. HIGH 이상을 고친다 → 이 파일을 지우고 `/pr`.
   - PR 본문에 운영 DDL 과 dev 1회 수동 적용을 적는다.
5. A2(어드민 큐 · 수락 · 상태 · 감사)를 같은 방식으로 → #471-B 알림.

## 막힌 것
- 결정 1~6 대기.
- 로컬 Docker 가 없어 Postgres 동시 수락 테스트는 CI 에서만 돈다.
- 여러 세션이 동시에 빌드하면 module check 가 메모리 부족으로 끊길 수 있다.

## 다음 세션 프롬프트

```text
#471 이어서. 브랜치 feature/be/471-cs-handoff-state-machine 의 HANDOFF-471.md 를 먼저 읽어라(확정 설계 · 근거 · 결정 대기 6개).
결정 1~6 을 사용자에게 받은 뒤 /dev-orchestrator 로 #471-A1(도메인 + 영속 + DDL + 고객 이관)을 구현한다.
등급은 핵심, 검토는 be-security · be-db · be-hexagonal · reviewer 를 한 메시지에서 병렬로, /backend-api-check 포함.
Gradle --stop · 루트 check 금지, 반복은 바꾼 테스트 클래스, PR 직전 support 모듈 check.
30파일 넘으면 분할하고 #471 에 댓글. PR 전에 HANDOFF-471.md 삭제. 대화 생성 경로는 #470 B2b-2 와 겹치니 머지 순서를 확인한다.
```
