# 상담 대화 내부 기반 (#470 PR-B1b)

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

이번 기반은 HTTP·요청 제한·Redis·WebSocket·메시지·AI·이관·이벤트·회원 연결을 제공하지 않는다.
공개 진입은 PR-B2의 인증 및 요청 제한과 함께 추가한다. 비회원 자격 만료 7일과 대화 보존 30일은 별도 정책이며,
보존 기간 삭제·회원 연결·소유 승계는 후속에서 참조 및 폐기 원자성을 설계한다. #470 전체 완료로 보지 않는다.
