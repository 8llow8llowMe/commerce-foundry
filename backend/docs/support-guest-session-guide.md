# 비회원 세션 기반 (#470 PR-B1a)

guestsession 컨텍스트는 비회원의 불투명 자격 증명을 소유한다. 회원 신원이나 권한 claim을 만들지 않는다.
내부 Java 진입은 `GuestSessionInternalUseCase → Facade → Processor → crypto/store port`다.
다른 컨텍스트는 architecture-guide §3에 따라 자기 Facade에서 GuestSessionProcessor만 호출한다.
공개 HTTP 진입은 발급 하나다(`GuestSessionWebController → GuestSessionWebUseCase → GuestSessionWebFacade → GuestSessionProcessor`,
아래 PR-B2b-1 절). 오류 봉투는 guestsession 웹 어댑터 패키지 한정 `GuestSessionExceptionHandler`가 만든다.

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

PR-B1b의 대화 내부 기반은 [support-conversation-guide.md](support-conversation-guide.md)를 따른다.
`resolveForUpdate`는 MANDATORY 쓰기 트랜잭션에서 NOWAIT guest 행 락과 명시적 refresh를 완료한 뒤
Clock을 읽어 만료·폐기를 판정하며, domain `GuestSessionIdentity(id, expiresAt)`만 반환한다.
락 조회·refresh 잠금 실패는 원인 예외를 보존하지 않은 409 GUEST_SESSION_004다.
기존 resolve의 시점 인증과 revoke의 낙관적 UPDATE 계약은 유지한다.
공개 REST(B2a 사용 · B2b-1 발급)와 게이트웨이 필수 요청 제한은 아래 절을 따른다. WebSocket·회원 연결·공개 폐기·AI 호출은 후속이다.
7일 자격 증명 만료와 비회원 대화 30일 보존은 다른 정책이며,
물리 삭제·보존 스케줄러는 후속에서 대화 참조와 함께 설계한다. #470 전체는 OPEN을 유지한다.
H2와 PostgreSQL이 같은 영속성 계약을 실행한다. PG17 생성 스냅숏을 init script로 적용하고 validate하며,
발행 전에는 기존 지식 엔티티를 포함한 전체 엔티티 스캔으로 support-schema.sql을 다시 생성한다.

## 기존 자격 증명의 REST 사용 (#470 PR-B2a)

기존 비회원은 X-Support-Guest-Token으로 본인 대화 상세·메시지 커서 조회·메시지 POST만 호출할 수 있다.
공개 발급은 아래 B2b-1 절, 대화 생성은 B2b-2 범위다. 매 요청 resolveForUpdate와 기존 소유 검증을 같은 트랜잭션으로 묶는다.
유효한 CUSTOMER JWT가 함께 있으면 회원 신원이 우선하고 guest 헤더는 무시한다. 잘못된 bearer는 401이며 guest로 대체하지 않는다.
운영자는 403이다. 자격 누락·오류·폐기·만료는 동일한 GUEST_SESSION_001이며 토큰은 응답·로그에 남기지 않는다.
상세 HTTP 계약과 필수 게이트웨이 쓰기 제한은 support-conversation-guide.md를 따른다.

## 비회원 자격 공개 발급 (#470 PR-B2b-1)

| 메서드·경로 | 입력 | 성공 | dataBody |
|---|---|---|---|
| POST /api/v1/support/guest-sessions | 본문 없음 | 201 | token(43자), expiresAt(UTC ISO-8601, 발급 + P7D) |

- 세션 id는 응답에 넣지 않는다. 쿠키(`cf_gs`)는 BFF가 세팅하며 서비스는 Set-Cookie를 내지 않는다.
- `@PreAuthorize("isAnonymous()")` — bearer가 없을 때만 발급한다. 유효한 회원·운영자 JWT는 403, 잘못되거나 만료된 bearer는 401이다.
  회원에게 비회원 자격은 쓸모가 없고, access가 만료된 회원이 비회원 자격을 받는 오발급을 막는다.
  **BFF는 인증 쿠키(access·refresh)가 전혀 없을 때만 발급을 호출한다**(FE #223 계약).
- WebFacade는 트랜잭션을 걸지 않고(NOT_SUPPORTED) Processor의 자체 트랜잭션 커밋 뒤 토큰을 반환한다 — 위 발급 계약 그대로다.
  커밋 실패면 토큰이 나가지 않고, 지문 충돌은 409 GUEST_SESSION_003이다.
- 응답은 `/api/v1/support/**` 전체에 걸리는 서비스 `SupportCacheControlFilter`(global/web, 보안 필터 앞)와 게이트웨이 필터로 no-store다.
  인증 실패 401·403도 같다. 응답 record의 toString은 토큰을 가리며 토큰·지문은 로그·예외에 남기지 않는다.
- 게이트웨이 필수 정책 `support-guest-session`(기본 분당 6 · burst 3, IP HMAC 버킷)으로 센다. 실제 초과만 429 GATEWAY_006 + Retry-After,
  전역 제한 OFF·client-ip OFF(기본)·주체 불명·Redis 오류/타임아웃은 503 GATEWAY_002다(닫힌 기본값) — security-guide §15.
- 발급한 토큰은 기존 B2a 진입(X-Support-Guest-Token)에 그대로 쓴다. 대화 생성(B2b-2)·폐기/회원 연결 공개 API는 이 범위가 아니다.
  발급 요청에 기존 X-Support-Guest-Token이 붙어 와도 무시하고 새로 발급하며(201), 기존 토큰은 그대로 유효하다.
- **운영 관문** — 운영에서 client-ip를 켜기 전 전제는 서비스 자체 계수기(#470-C)와 만료 비회원 행 정리(보존 스케줄러)다.
  지금은 주체당 버킷(IPv4 주소 1개 · IPv6 /64)만 있어 분산된 주소로 발급 행을 늘릴 수 있다. 전역 발급 버킷은 두지 않았다(PR 결정 항목).
  support-service(8087) 직접 접근은 게이트웨이 제한을 거치지 않아 사설망 전제(security-guide §5-1)에 기댄다.
