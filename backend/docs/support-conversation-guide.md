# 상담 대화·고객 메시지 내부 기반 (#470 PR-B1b/B1c)

`conversation`은 대화의 소유와 초기 상태를 관리한다. 공개 HTTP 계약은 없으며 내부 Java 진입은
`ConversationInternalUseCase → ConversationInternalFacade → ConversationProcessor → RepositoryPort`다.
HTTP가 없으므로 coding-conventions §8-1의 ExceptionHandler 생략 예외를 적용한다.

## 모델과 소유

대화는 양수 Snowflake `id`, nullable `memberId` 또는 `guestSessionId` 중 정확히 하나의 양수 소유자,
`locale=KO|EN`, `channel=WEB`, `status`, `lastSeq`, 낙관적 락 `version`과 BaseEntity 감사 시각을 가진다.
소유 XOR·양수·순번 비음수는 도메인과 DB check가 함께 강제한다. 외부 회원은 auth 소유 ID이며
JPA 관계나 서비스 간 물리 FK를 두지 않는다. 비회원 세션도 raw FK와 컬럼 주석으로 참조한다.
신규 상태는 `AI_ACTIVE`, 순번은 0이다. `WAITING_AGENT|HUMAN_ACTIVE|RESOLVED|CLOSED`는 향후 전이를 위한 값이며
이번 변경에는 상태 전이, 담당자, 이관, AI 실행, 해결 정보, 소유 변경 기능이 없다.

내부 memberId는 인증 경계에서 검증된 신뢰 가능한 양수 ID만 전달한다. 호출자가 임의의 회원 ID를 받는
공개 API로 직접 노출해서는 안 된다. Snowflake ID는 추측 방지 비밀이나 인증 수단이 아니다.
조회는 PK와 소유자를 함께 제한한다. 없는 ID, 타 회원, 타 비회원, 회원/비회원 교차 조회는 모두
404 `SUPPORT_010`의 같은 고정 메시지다. 잘못된 회원 ID와 누락 언어는 각각 400 `SUPPORT_110/111`이다.
Info는 `id/locale/channel/status/lastSeq`만 반환하며 소유 ID·토큰·지문을 포함하지 않는다.

## 트랜잭션과 비회원 인증

Facade가 트랜잭션을 소유한다. 회원 조회는 읽기 전용, 생성과 비회원 조회는 짧은 쓰기 트랜잭션이다.
다른 컨텍스트 조합은 Facade만 GuestSessionProcessor를 호출하고 반환된 domain `GuestSessionIdentity`를
자기 Processor로 넘긴다. 다른 컨텍스트 Info·Port·Repository·예외를 참조하거나 Facade에서 예외를 번역하지 않는다.

비회원 생성·조회는 매번 `resolveForUpdate`로 canonical 토큰 검사 → 지문 계산 → ID만 조회 → guest 행의
EntityManager refresh와 PESSIMISTIC_WRITE NOWAIT 획득 → Clock 읽기와 microsecond 정규화 → 만료·폐기 재검증을 수행한다.
엔티티 잠금 질의는 오래된 영속성 컨텍스트의 버전을 refresh 전에 거절할 수 있으므로 ID만 투영한다.
기존 영속성 컨텍스트에 읽힌 행이 있어도 refresh로 최신 폐기를 확인한다. ID 조회와 refresh 사이 폐기도 이때 재검증한다.
반환된 신원은 락을 유지하는 트랜잭션 안에서만 유효하다. 락 순서는 guest → conversation이며 외부 I/O는 없다.
`jakarta.persistence.lock.timeout=0`은 PostgreSQL NOWAIT다. 양수 timeout 설정은 PostgreSQL에서 대기 상한을 보장하지 않는다.
락 조회와 refresh의 잠금 실패는 SQL·자격 증명·원인 예외를 붙이지 않은 409 `GUEST_SESSION_004` 고정 메시지다.
같은 비회원의 동시에 수행된 조회도 409가 될 수 있다. 호출자는 완료 후 다시 요청할 수 있다.

guest 락을 먼저 얻으면 기존 revoke의 UPDATE가 커밋까지 기다린다. revoke UPDATE가 먼저면 비회원 업무가
NOWAIT 409로 거절된다. revoke 커밋 뒤에는 401 `GUEST_SESSION_001`이다. 형식 오류·없는 자격·만료·폐기도 같은 401이다.
생성과 조회를 함께 묶은 호출자 트랜잭션이 롤백되면 flush된 대화도 남지 않는다.

생성은 **비멱등**이다. 같은 소유자와 언어로 두 번 부르면 두 대화가 생성된다. 커밋 결과 불명이나 응답 유실은
자동 재시도하지 않는다. 공개 생성 API의 요청 키와 재조회 전략은 후속에서 설계한다.

## 검증과 후속 경계

도메인 불변식과 H2/PG 공통 계약으로 소유 격리·초기 상태·비멱등 생성·DB check·롤백·만료·폐기를 검사한다.
실제 PostgreSQL 17에서는 NOWAIT 경합, 오래된 영속성 컨텍스트의 폐기 refresh, 양방향 revoke 경합을 검증한다.
테스트는 생성된 전체 support DDL 스냅숏으로 초기화하고 Hibernate validate를 적용한다.

이번 기반은 HTTP·요청 제한·Redis·WebSocket·AI·이관·이벤트·회원 연결을 제공하지 않는다.
공개 진입은 PR-B2의 인증 및 요청 제한과 함께 추가한다. 비회원 자격 만료 7일과 대화 보존 30일은 별도 정책이며,
보존 기간 삭제·회원 연결·소유 승계는 후속에서 참조 및 폐기 원자성을 설계한다. #470 전체 완료로 보지 않는다.

## 고객 메시지 추가와 재생

내부 Java 진입은 `ConversationMessageInternalUseCase → ConversationMessageInternalFacade →
ConversationMessageProcessor → ConversationRepositoryPort/MessageRepositoryPort`다. 메서드는
`appendForMember(conversationId, memberId, clientMessageId, rawContent)`와
`appendForGuest(conversationId, token, clientMessageId, rawContent)`다. 신뢰할 수 있는 양수 회원 ID만 전달한다.
비회원 Facade는 기존 GuestSessionProcessor의 resolveForUpdate를 먼저 호출하고 domain 신원을 넘긴다.
항상 guest → conversation 순서로 잠그며 Facade 트랜잭션 안의 MANDATORY Processor가 처리한다. 외부 I/O는 없다.

대화 잠금도 소유자로 제한한 ID 투영 → getReference → refresh(PESSIMISTIC_WRITE, timeout=0) 순서다.
refresh 뒤 소유자를 다시 검사하고 최신 상태·lastSeq·version을 사용한다. 없는 대화와 소유 불일치는 모두 SUPPORT_010이다.
소유 확인 뒤 키·본문을 검증한다. 키는 소문자 canonical UUID 36자이며 UUID 버전은 제한하지 않는다.
본문은 raw와 canonical 모두 null·blank를 금지하고 UTF-16 길이 1~2000을 검사한다.
PostgreSQL이 저장할 수 없는 NUL 문자도 SUPPORT_113으로 거절한다.

저장 본문은 ai-core의 `new PiiMasker(Map.of()).mask(raw, List.of())`로 NFKC 정규화 및 기본 개인정보 마스킹 후
strip한 결과다. AI Configurer를 import하지 않으며 AiClient 빈·AI 호출·계량은 없다.
raw 본문과 지문은 저장하지 않는다. 이름·비정형 주소·주문번호는 완전 탐지할 수 없으므로 개인정보 제거를 보장하지 않는다.
본문·키·토큰·원인 예외를 로그에 남기지 않으며 Message·MessageInfo의 toString도 본문·키를 숨긴다.
기본 마스킹 규칙은 현재 저장 정책으로 고정한다. 규칙 변경 시 기존 키 재생의 호환성과 정책 버전은 후속 설계 대상이다.

멱등 범위는 `(conversationId, clientMessageId)`다. 같은 키와 같은 canonical masked 본문이면 기존 MessageInfo를 반환하고
부모 seq·version과 메시지 행을 바꾸지 않는다. 서로 다른 전화번호도 둘 다 `[PHONE]`이면 같은 본문으로 간주한다.
같은 키에 다른 canonical 본문은 SUPPORT_014다. 종료 상태에서도 기존 키 재생은 허용하지만 인증·소유 확인은 항상 선행한다.
새 키만 상태를 검사한다. 회원은 AI_ACTIVE·WAITING_AGENT·HUMAN_ACTIVE, 비회원은 AI_ACTIVE에서만 추가할 수 있다.
비회원 상담원 대화 허용 플래그는 후속 범위다.

새 메시지는 Math.addExact로 부모 순번을 1 증가시키고 부모 saveAndFlush 후 메시지 insertAndFlush한다.
둘은 하나의 트랜잭션이며 메시지 삽입 실패나 호출자 롤백 시 부모 seq·version도 함께 복구된다.
메시지는 양수 Snowflake id·대화 ID·seq, CUSTOMER 작성자, COMPLETED 상태를 가지며 append-only Immutable 엔티티다.
원 작성자는 잠근 대화의 소유자로부터 정하고 양수 memberId/guestSessionId XOR를 도메인·DB check로 강제한다.
후속 회원 연결이 생겨도 이 원 작성자 기록은 보존한다. DB unique는 대화별 seq 및 요청 키다. 물리 FK·JPA 관계는 없다.

고정 오류는 상태 409 SUPPORT_011, 잠금 409 SUPPORT_012, 낙관 충돌 409 SUPPORT_013,
키/메시지 unique 충돌 409 SUPPORT_014, 순번 소진 409 SUPPORT_015, 키 400 SUPPORT_112, 본문 400 SUPPORT_113이다.
예상 잠금·낙관·unique 충돌은 원인 예외 없이 반환한다. 이름이 일치하는 두 메시지 unique만 변환하고 다른 DB 장애는 전파한다.
HTTP·WebSocket·AI 응답·상태 전이·이벤트·회원 연결·삭제 보존은 제공하지 않는다.

메시지 검증은 domain/policy 단위와 H2/PostgreSQL 공통 계약으로 멱등·소유·상태·원자 롤백·DB check/unique를 검사한다.
부모를 flush한 뒤 실제 메시지 unique 실패로 트랜잭션 전체가 복구되는지 검사한다.
오래된 영속성 컨텍스트의 부모는 refresh로 최신 seq를 사용한다. PostgreSQL 전용 계약은 동일 대화 NOWAIT,
재시도 시 seq 1/2와 같은 키 단일 행, 다른 대화의 독립 진행, 양방향 비회원 폐기 경합을 검사한다.

## 소유자별 메시지 커서 조회 (#470 PR-B1e)

기존 내부 Java 진입에 `getForMember(conversationId, memberId, afterSeq, size)`와
`getForGuest(conversationId, token, afterSeq, size)`를 추가한다. 회원 ID는 신뢰할 수 있는 인증 경계에서 확정한 양수다.
비회원은 매 호출마다 Facade가 기존 `resolveForUpdate`로 인증하고 domain 신원만 Processor에 전달한다.
인증과 소유 확인은 커서·크기 검증보다 먼저 수행한다. 없는 대화와 다른 소유자·회원/비회원 교차 조회는 같은 404 SUPPORT_010이다.
잘못된 회원 ID는 400 SUPPORT_110, 비회원 만료·폐기·잘못된 토큰은 기존 401 GUEST_SESSION_001,
비회원 잠금 경합은 기존 409 GUEST_SESSION_004다. CLOSED·RESOLVED를 포함한 모든 대화 상태에서 읽을 수 있다.

소유자 조건을 포함한 scalar `lastSeq` projection으로 현재 트랜잭션에서 관측한 상한을 한 번 고정한다.
0은 정상적인 빈 대화이며 조회 결과 없음과 구분한다. 영속성 컨텍스트의 오래된 부모 엔티티 값을 사용하지 않는다.
메시지는 `conversationId`와 `afterSeq < seq <= lastSeq`로 한정하고 seq 오름차순으로 DB에서 최대 `size + 1`행만 읽는다.
기존 `(conversation_id, seq)` unique 인덱스를 사용하며 count 쿼리·전체 조회·새 인덱스·DDL 변경은 없다.
`0 <= afterSeq <= lastSeq`, `1 <= size <= 100`이며 잘못된 커서는 400 SUPPORT_114, 잘못된 크기는 400 SUPPORT_115다.
`afterSeq == lastSeq`이면 빈 목록, hasNext=false, nextCursor=afterSeq다.

반환은 `MessagePageInfo(messages, hasNext, nextCursor, lastSeq)`다. 목록은 방어 복사해 수정할 수 없고
기존 MessageInfo만 사용하므로 원 작성자 ID·요청 키·토큰을 노출하지 않는다. 저장한 canonical 본문을 재마스킹하지 않는다.
기본 마스킹 뒤에도 이름·주소 등의 개인정보가 남을 수 있어 본문 접근 인가가 계속 필요하다.
MessagePageInfo의 toString은 메시지 수와 커서만 남기며 본문은 포함하지 않는다.

hasNext는 이번 호출의 관측 상한 안에서 다음 행이 존재한다는 뜻이다. nextCursor는 실제 반환한 마지막 seq이며
빈 목록이면 입력 afterSeq다. lastSeq는 관측 상한이므로 다음 커서로 사용하면 안 된다.
다음 호출에는 nextCursor를 전달한다. 매 호출마다 상한을 다시 읽으므로 페이지 사이에 커밋한 새 append도 순서대로 받을 수 있다.
두 SELECT 사이에 append가 커밋해도 현재 페이지는 처음 관측한 상한까지만 반환한다.
hasNext=false는 영구 종료가 아니며 같은 nextCursor로 나중에 폴링할 수 있다. 별도 snapshotUpper 입력은 없다.

Facade는 기존 REQUIRED를 유지한다. 회원 조회만 readOnly=true이며, 비회원 조회는 인증 잠금을 위한 짧은 쓰기 트랜잭션이다.
Processor는 MANDATORY다. 호출자 트랜잭션의 미커밋 append도 읽고, 호출자가 롤백하면 부모 순번과 메시지가 함께 복구된다.
기본 READ COMMITTED에서는 각 호출이 당시 커밋된 상한을 관측한다. 더 강한 격리의 외부 트랜잭션에 참여하면 그 스냅숏을 따른다.
부모에는 추가 잠금을 잡지 않는다. 비회원 잠금은 참여한 트랜잭션 종료까지 유지되어 먼저 인증한 조회는 revoke를 기다리게 하고,
revoke가 먼저 잠근 경우 NOWAIT 409, 폐기 커밋 뒤에는 401이다. 외부 I/O는 수행하지 않는다.

이 조회는 현재 소유자가 바뀌지 않고 append와 부모 순번 증가가 원자적이며 메시지를 물리 삭제하지 않는 현재 모델을 전제로 한다.
향후 소유 이전·보존 삭제를 구현할 때 이 보장을 다시 설계해야 한다. 후속 HTTP·WebSocket 진입도 인증 우선순위,
소유자 범위, 실제 반환 커서, DB 조회 상한, 본문 비로깅 계약을 보존해야 한다. 공개 HTTP·WebSocket 계약은 이번 범위에 없다.

별도 H2/PostgreSQL 읽기 계약으로 소유·인증 우선순위, 커서·크기 경계, 모든 상태 읽기, 오름차순 무누락 페이지,
DB의 size+1 제한과 count 부재, 두 SELECT 사이 append, 오래된 부모의 scalar 상한, 호출자 자기 쓰기·롤백을 검증한다.
PostgreSQL 17 전용 검증은 양방향 비회원 조회·폐기 잠금 경합을 확인하며 기존 support DDL 스냅숏과 Hibernate validate를 사용한다.

## 고객 REST 진입 (#470 PR-B2a)

기존 대화에 대한 공개 진입은 Controller → ConversationWebUseCase → ConversationWebFacade → 기존 Processor → Presenter다.
WebFacade는 InternalUseCase/Facade를 호출하지 않는다. 회원·비회원 분기는 검증된 JWT memberId의 존재로 정하고
회원일 때 guest 헤더는 무시한다. 비회원 인증 잠금을 유지하기 위해 Facade의 짧은 쓰기 트랜잭션 안에서
GuestSessionProcessor.resolveForUpdate → Conversation/MessageProcessor 순서로 조합한다. 외부 I/O는 없다.

| 메서드·경로 | 입력 | 성공 dataBody |
|---|---|---|
| GET /api/v1/support/conversations/{id} | 인증 헤더 | id(String), locale, channel, status, lastSeq |
| GET /api/v1/support/conversations/{id}/messages | cursor=0, size=20 기본 | messages, hasNext, nextCursor, lastSeq |
| POST /api/v1/support/conversations/{id}/messages | Idempotency-Key, {content} | id(String), conversationId(String), seq, authorType, status, content |

모두 Response 봉투와 Cache-Control: no-store를 사용한다. POST 신규·동일 키 재생은 모두 200이며
저장된 마스킹 본문만 반환한다. 소문자 canonical UUID, 본문 2000 UTF-16 상한·NUL 거절·소유 우선 검증은
기존 Processor가 담당한다. 형식이 유효한 다른 소유자·없는 대화는 같은 404 SUPPORT_010이다.
잘못된 JSON·숫자 바인딩은 400 SUPPORT_100이며 본문 값과 자격 증명을 오류에 싣지 않는다.

유효한 CUSTOMER bearer는 JWT memberId만 사용하고 비회원 헤더를 무시한다. 잘못되거나 만료된 bearer는
공개 경로에서도 security-core가 먼저 401로 거절하며 비회원으로 대체하지 않는다. 운영자는 403이다.
bearer가 없으면 기존 X-Support-Guest-Token이 필요하며 누락·형식 오류·미등록·폐기·만료는 동일한 401 GUEST_SESSION_001이다.
인증 오류도 캐시하지 않는다. 문법상 잘못된 JSON·숫자는 MVC 바인딩에서 먼저 400이 될 수 있다.

POST 메시지는 게이트웨이 support-write 필수 정책을 통과해야 한다. 제한기/IP 해석 비활성·주체 부재·Redis 장애/타임아웃은
503 GATEWAY_002, 실제 한도 초과만 429 GATEWAY_006 + Retry-After다. 조회는 이 쓰기 정책으로 세지 않는다.
배포는 기존 신뢰 프록시/IP HMAC 설정과 Redis가 필요하다. 전역 IP 해석의 기본 OFF에서는 상담 쓰기를 열지 않는다.

FE #223은 재시도 시 같은 키와 본문을 유지하고 다음 조회에 nextCursor를 사용한다. hasNext=false 후에도 같은 커서로 폴링할 수 있다.
계약 검토자는 seonghoho다. 이 PR에는 비회원 발급·대화 생성 HTTP(B2b), WS·티켓(C), AI·이벤트·새 DDL이 없다.


B2a의 locale/channel/status/authorType/message status는 coding-conventions §11의 제어 코드 예외다.
FE는 코드 자체를 문구로 표시하지 않고 언어 선택, 채널 처리, 입력·종료 동작, 말풍선 배치·완료 렌더링에만 사용한다.
표시가 필요하면 FE의 코드별 ko/en 문구로 변환한다. wire 값은 locale=KO|EN, channel=WEB,
대화 status=AI_ACTIVE|WAITING_AGENT|HUMAN_ACTIVE|RESOLVED|CLOSED, authorType=CUSTOMER,
메시지 status=COMPLETED로 고정하며 각 응답 필드의 Swagger allowableValues에 명시한다.
명칭 변경·삭제는 계약 변경이고, 새 작성자·상태는 후속 API 계약과 FE 처리를 함께 확장한다.
게이트웨이의 /api/v1/support/** 응답도 초기 WebFilter와 커밋 직전 헤더 적용으로 no-store를 보장한다.
서비스에 닿지 않은 401·429·503에도 같은 계약을 적용하며 다른 서비스 경로의 캐시 정책은 바꾸지 않는다.
