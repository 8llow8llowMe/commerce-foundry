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
목록·페이징·HTTP·WebSocket·AI 응답·상태 전이·이벤트·회원 연결·삭제 보존은 제공하지 않는다.

메시지 검증은 domain/policy 단위와 H2/PostgreSQL 공통 계약으로 멱등·소유·상태·원자 롤백·DB check/unique를 검사한다.
부모를 flush한 뒤 실제 메시지 unique 실패로 트랜잭션 전체가 복구되는지 검사한다.
오래된 영속성 컨텍스트의 부모는 refresh로 최신 seq를 사용한다. PostgreSQL 전용 계약은 동일 대화 NOWAIT,
재시도 시 seq 1/2와 같은 키 단일 행, 다른 대화의 독립 진행, 양방향 비회원 폐기 경합을 검사한다.
