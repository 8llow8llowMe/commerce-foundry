# 비회원 세션 기반 (#470 PR-B1a)

guestsession 컨텍스트는 비회원의 불투명 자격 증명을 소유한다. 회원 신원이나 권한 claim을 만들지 않는다.
내부 Java 진입은 `GuestSessionInternalUseCase → Facade → Processor → crypto/store port`다.
다른 컨텍스트는 architecture-guide §3에 따라 자기 Facade에서 GuestSessionProcessor만 호출한다.
HTTP 진입이 없어 coding-conventions §8-1의 ExceptionHandler 생략 예외를 적용한다.

## 발급과 저장

발급은 `(id, token, expiresAt)`을 최초 한 번 반환한다. 발급 Facade의 NOT_SUPPORTED는 호출자 트랜잭션을 잠시 중단하고,
Processor가 자체 트랜잭션을 커밋한 뒤 반환하게 한다. 호출자 트랜잭션이 나중에 롤백돼도 발급된 자격 증명은 남는다.
저장·커밋 실패 시 토큰을 반환하지 않는다. 응답 유실이나 호출자 롤백으로 접근하지 못하는 행은 절대 만료 정책을 따른다.
Processor를 다른 업무 트랜잭션에 직접 조합할 때는 호출자가 커밋 완료 후 외부에 토큰을 반환할 책임이 있다.

토큰은 난수 32바이트의 canonical base64url 무패딩 43자다. crypto adapter는 기존 security-core의
OpaqueTokenGenerator에 생성과 SHA-256 지문 생성을 위임한다. JWT 발급 설정이나 개인키는 가져오지 않는다.
DB에는 지문 43자만 저장한다. 토큰/지문을 가진 객체의 toString은 이를 가리며 로그에도 원문·지문을 싣지 않는다.

만료는 발급 시 한 번 읽은 UTC 시각에서 정확히 P7D다. business 시각은 DB와 같은 microsecond로 정규화한다.
P7D는 #470에서 확정한 자격 증명 protocol 상수이며 설정 외부화의 명시적 예외다. 사용으로 만료를 연장하지 않는다.

## 인증과 폐기

ACTIVE는 `revokedAt == null && now < expiresAt`이다. null·잘못된 형식·없는 토큰·만료·폐기는 모두
같은 401 GUEST_SESSION_001 고정 메시지로 거절한다. canonical 형식 검사는 해시 계산이나 조회보다 먼저 한다.
인증 결과는 `(id, expiresAt)`만 반환한다. privileged 내부 폐기는 도메인 전이와 saveAndFlush/@Version을 사용한다.
이미 폐기됐거나 만료된 행은 시각·버전을 바꾸지 않는다. 실제 동시 쓰기 패자는 409 GUEST_SESSION_002다.
지문 유니크 충돌은 409 GUEST_SESSION_003이며 실패한 트랜잭션 안에서 재시도하지 않는다.
그 밖의 저장소 오류는 전파한다. 제약 이름 판정은 PostgreSQL의 정확한 이름을 따르며 H2의 인덱스 접미사는 허용하지 않는다.

`support.support_guest_session`에는 양수 Snowflake id, 유니크 token_hash, expires_at, nullable revoked_at,
낙관적 락 version, BaseEntity 감사 시각만 둔다. 개인정보·회원 id·중복 status는 없다.

읽기 버전은 인증과 경합하는 폐기를 막지 못한다. 인증 결과는 조회 시점에만 유효하며 후속 대화 업무는 매 트랜잭션에서
세션 유효성과 현재 소유를 다시 검증해야 한다. 회원 연결은 대화 소유 승계와 자격 증명 폐기를 같은 트랜잭션으로 처리해야 한다.

## 후속 범위와 검증

HTTP·WebSocket·Redis·Gateway·요청 제한·회원 연결·대화 모델·AI 호출은 후속이다.
외부 발급 API는 PR-B2 요청 제한과 함께 제공한다. 7일 자격 증명 만료와 비회원 대화 30일 보존은 다른 정책이며,
물리 삭제·보존 스케줄러는 후속에서 대화 참조와 함께 설계한다. #470 전체는 OPEN을 유지한다.
H2와 PostgreSQL이 같은 영속성 계약을 실행한다. PG17 생성 스냅숏을 init script로 적용하고 validate하며,
발행 전에는 기존 지식 엔티티를 포함한 전체 엔티티 스캔으로 support-schema.sql을 다시 생성한다.
