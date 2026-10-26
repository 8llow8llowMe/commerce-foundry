# Security Guide

> 인증·인가(JWT 발급·검증, Resource Server, 오류 코드, 키 배포·교체, 서비스 간 internal API 인증)의 정본이다. 구현은 `core/security-core` 다.
> 보안 구조의 요지는 `architecture-guide.md` §6, 시크릿 보관 규칙은 `secret-management.md` 를 따른다.

## 1. 구성

| 역할 | 누가 | 무엇으로 |
|------|------|----------|
| 발급 | auth-service 만 | 개인키(`JWT_PRIVATE_KEY`) · `JwtIssuer` · `OpaqueTokenGenerator` |
| 검증 | 서비스 전부 · auth · api-gateway | 공개키(`JWT_PUBLIC_KEY`, 교체 중 `JWT_PUBLIC_KEY_PREVIOUS`) |
| 서비스 토큰 (internal API) | 서비스 전부가 서로 — 게이트웨이 제외 | 공용 HS256 비밀(`INTERNAL_JWT_SECRET`, 교체 중 `INTERNAL_JWT_SECRET_PREVIOUS`) · `InternalTokenIssuer` · internal 체인 (§9) |

- 서명은 **RS256**(RSA 2048비트 이상)이다. 공개키가 새도 토큰을 만들 수 없다 — 검증하는 곳마다 서명 키를 나눠 줄 필요가 없다.
- 라이브러리는 Spring Security JOSE(Nimbus `JwtEncoder` / `JwtDecoder`)만 쓴다. jjwt 를 쓰지 않는다.
- **서비스는 게이트웨이가 넘긴 헤더를 믿지 않고 JWT 를 직접 검증한다.** 게이트웨이가 검증 결과를 `X-Member-Id` 같은 헤더로 넘기면,
  게이트웨이를 거치지 않은 요청(사설망 내부, 설정 실수)이 그 헤더를 위조해 아무 회원이 될 수 있다.

## 2. access 토큰

### 2-1. 헤더

| 필드 | 값 | 비고 |
|------|----|------|
| `alg` | `RS256` | 다른 값(`none`, `HS256` 포함)은 003 |
| `typ` | `at+jwt` | RFC 9068. `application/at+jwt` 도 같은 값으로 본다. 없거나 `JWT` 면 003 — 다른 용도의 JWT 가 access 토큰으로 쓰이지 않게 |
| `kid` | 공개키의 JWK 썸프린트(RFC 7638) | 같은 키면 어디서 계산해도 같다. `kid` 가 없는 토큰은 등록된 키를 모두 시도한다 |

### 2-2. claim

| claim | 값 | 검증 |
|-------|----|------|
| `iss` | `security.jwt.issuer` (기본 `commerce-foundry-auth`) | 일치 |
| `aud` | `security.jwt.audience` (기본 `commerce-foundry-api`) | 포함 |
| `sub` | memberId **문자열** (`"4611686018427387904"`) | 양의 정수, `String.valueOf(long)` 모양만 (`007` · `+1` 거부) |
| `role` | `CUSTOMER` 또는 `ADMIN` 문자열 하나 | `MemberRole` 이름과 대소문자까지 같아야 한다 |
| `iat` | 발급 시각(초) | — |
| `exp` | 발급 시각 + access TTL | 없으면 003, 지났으면 002 (시계 오차 허용 적용) |
| `jti` | UUID | 있어야 한다. 로그아웃 블랙리스트(#4)의 키 |

- `nbf` 는 넣지 않는다. iss 가 URL 이 아니므로 `Jwt#getIssuer()`(URL 변환)를 쓰지 않는다.
- TTL 기본 30분, **최대 1시간**(`security.jwt.issuance.access-token-ttl`). access 토큰은 발급 뒤 거둘 수 없어 길수록 탈취 시 악용 창과
  로그아웃 블랙리스트(#4) 보관 기간이 늘어난다. 긴 로그인 유지는 refresh 토큰으로 한다. 관리자 토큰도 같은 TTL 이고 refresh 가 없다(§11-1).
- 시계 오차 허용 기본 60초(`security.jwt.clock-skew`, 0 이상 5분 이하).
- 위반은 한 번에 모두 모아 판정한다 (§4-1).

### 2-3. refresh 토큰은 JWT 가 아니다

- `OpaqueTokenGenerator#generate()` — `SecureRandom` 32바이트를 base64url 무패딩(43자)으로. 뜻 없는 난수다.
- 서버 저장소에 있어야만 유효하므로 서명·claim 이 필요 없고, 탈취돼도 내용이 드러나지 않는다.
- 저장소에는 원문이 아니라 `OpaqueTokenGenerator#fingerprint(token)`(SHA-256, base64url 43자)을 키로 둔다.
  `RedisKey.of("auth", "refresh-token", fingerprint)` 처럼 그대로 쓸 수 있다.
- TTL 기본 14일(`security.jwt.issuance.refresh-token-ttl`).
- **전달은 응답 본문이다**(#4 결정 1) — 로그인 · 재발급 응답 `{accessToken, accessTokenExpiresIn, refreshToken, refreshTokenExpiresIn}`(수명은 초),
  `Cache-Control: no-store`. 쿠키는 BFF 가 만든다(게이트웨이 CORS `allowCredentials(false)` 유지).

#### 저장 구조 (auth-service `adapter/out/redis`, `StringRedisTemplate` + Lua)

| 키 | 타입 · 값 | TTL |
|----|-----------|-----|
| `auth:refresh-token:{fingerprint}` | HASH `{memberId, familyId, rotatedAt?, siblings?}` — `rotatedAt` 은 회전한 시각(epoch ms), `siblings` 는 유예 안 형제 발급 수 | `min(refresh TTL, 상한까지 남은 시간)` |
| `auth:refresh-family:{familyId}` | STRING **최초 로그인 시각(epoch ms)** — 있으면 그 family 의 토큰이 살아 있다. 회전해도 값을 다시 쓰지 않는다 | `min(refresh TTL, 상한까지 남은 시간)`, 회전마다 다시 건다 |

- family 는 로그인 한 번에서 회전으로 이어진 토큰 묶음이다(`familyId` = UUID). **폐기 = family 키 DEL** 하나로 그 family 의 토큰이 모두 무효가 된다
  (토큰 HASH 는 TTL 까지 남지만 family 가 없으면 읽기 · 회전 모두 무효다).
- 키는 auth 안에서만 쓰므로 auth 의 키 함수(`RefreshTokenRedisKeys`) 하나로 만든다. HASH 와 TTL 은 같은 Lua 스크립트에서 쓴다 (`coding-conventions.md` §9-7).
- 로그인은 새 family 와 첫 토큰을 한 스크립트로 저장한다. family 값은 로그인 시각(애플리케이션 `Clock`)이고 TTL 은 refresh TTL 그대로다
  (절대 수명 ≥ refresh TTL 이라 로그인 시점에는 줄어들 일이 없다).

#### 재발급 (`POST /api/v1/auth/token/reissue`)

1. **모양 검사가 먼저다** — base64url 43자가 아니면(없음 · 빈 값 포함 — 필드 검증 400 을 두지 않는다) 지문을 계산하거나 저장소를 부르지 않고 401 `AUTH_010`.
2. 지문으로 HMGET(`memberId` · `familyId`) → family 키 존재 확인. 없으면 401 `AUTH_011`(만료 · 로그아웃 · 폐기 · 절대 수명 초과를 구분하지 않는다).
3. DB 에서 회원(역할)을 읽는다. 없거나 고객이 아니면 family 를 폐기하고 401 `AUTH_011`. **회원 조회를 회전보다 먼저 한다** —
   회전 뒤 DB 가 실패하면 "옛 토큰은 회전됐는데 새 토큰을 못 받은" 상태가 남기 때문이다.
4. Lua 회전(원자, 지금 시각은 애플리케이션 `Clock` 을 ARGV 로 넘긴다):

| 옛 토큰 상태 | 처리 | 결과 |
|--------------|------|------|
| 없음 · 읽은 memberId/familyId 와 다름 · family 없음 | 아무것도 쓰지 않는다 | 401 `AUTH_011` |
| family 값(로그인 시각)이 손상 — 정수 문자열(`^%d+$`)이 아님 · 무한대로 읽힘(숫자 309자리 이상) · **지금 + 허용 시계 오차(60초)보다 미래** (#143). **재사용 판정보다 먼저 본다** | 회전하지 않고 family DEL | 401 `AUTH_011` + warn 로그 |
| 절대 수명(기본 30일)까지 남은 시간(경과 음수는 0) < 1초 — **재사용 판정보다 먼저 본다** | 회전하지 않고 family DEL | 401 `AUTH_011` + info 로그 |
| `rotatedAt` 없음 (처음 쓰임) | `rotatedAt`=지금, 새 토큰 저장(HSET + PEXPIRE), family TTL 다시 걸기 | 200 새 쌍 |
| `rotatedAt` 있고 지금 − `rotatedAt` ≤ 유예(기본 30초, 음수는 0) · 형제 상한 안 | `siblings` 를 HINCRBY 하고 새 토큰(형제) 저장, family TTL 다시 걸기. **옛 토큰의 `rotatedAt` 은 그대로** — 유예는 첫 회전부터 재는 창이라 재시도가 창을 늘리지 못한다 | 200 새 쌍 + info 로그 |
| 유예 밖 재사용 · 형제 상한(기본 3) 초과 · `rotatedAt` 이 손상(family 값과 같은 규칙 — 정수 문자열 아님 · 무한대 · 지금 + 60초보다 미래) | family DEL — 형제 · 새 토큰까지 모두 무효 | 401 `AUTH_012` + warn 로그 |

- 새 토큰 · family 의 TTL 은 `min(refresh TTL, 로그인 시각 + 절대 수명 − 지금)` 이다(정상 회전 · 형제 발급 모두).
  **남은 시간이 1초 미만이면 회전하지 않고 만료로 본다**(`RefreshTokenRotationPolicy.MIN_REMAINING_LIFETIME`, Lua `1000`) — 응답 `refreshTokenExpiresIn` 이
  0초로 버려져 BFF 쿠키가 바로 지워지는 토큰을 내주지 않는다.
- 저장된 시각(family 값 · `rotatedAt`)은 Lua `epochMillis` 하나로 읽고 세 가지를 모두 통과해야 받는다 (#143).
  1. `^%d+$` 정수 문자열 — Lua `tonumber` 는 strtod 라 `"nan"` · `"inf"` · `"0x10"` · `"1e3"` 도 숫자로 읽는다. 이 검사가 막는 것은 이런 **문자**뿐이다.
  2. 유한 — 숫자만으로 된 값도 309자리를 넘으면 `tonumber` 가 무한대(`math.huge`)로 읽는다. 무한대는 3번(미래 판정)에서도 걸리므로
     이 검사는 **방어용 중복**이다 — 실제로 긴 숫자열을 막는 것은 3번이다. NaN 은 1번을 통과한 숫자열에서 나올 수 없으므로 **1번 패턴 검사가 `tonumber` 보다 먼저여야 한다**.
  3. 지금 + 허용 시계 오차 이하 — 미래 값을 받으면 경과가 0 으로 눌려 절대 수명 상한이 사실상 사라진다(아래 판정 시각).
- 스크립트가 판정과 같은 자리에서 한 번만 계산해 `{결과, TTL ms}` 로 돌려주고, **재발급 응답의 `refreshTokenExpiresIn` 은 이 값(초, 버림)이다** —
  BFF 쿠키 만료가 서버 키 TTL 과 맞는다. 로그인 응답은 refresh TTL 그대로다.
- 옛 토큰 HASH 의 TTL 은 회전해도 그대로다 — 남은 수명 동안 재사용 감지에 쓰인다.
- 키 타입이 어긋나 스크립트가 실패하면(WRONGTYPE 등) 저장소 장애와 같이 503 `AUTH_013` 이다.

5. access 토큰을 발급해 로그인과 같은 모양으로 답한다.

- 유예는 `auth.token.refresh-reuse-grace`(기본 30초, 0 이상 2분 이하 — #4 결정 6, FE `REISSUE_GRACE_MS` 와 같다)다. BFF 의 동시 재발급 · 응답 유실 재시도를 받아 준다.
  유예 안에서는 회전한 토큰을 훔친 쪽도 형제 토큰을 받는다 — 유예를 길게 두지 않고, 형제 수도 토큰당 `auth.token.refresh-reuse-grace-max-siblings`
  (기본 3, 0 이상 10 이하)로 묶는다. 넘으면 재사용 감지와 같이 family 를 폐기한다.
- 형제 발급(info) · 재사용 감지(warn) 로그는 memberId · familyId · 지문 앞 8자만 남긴다. 토큰 원문 · 지문 전체를 남기지 않는다.
  family 만료(info) · family 손상(warn) 로그는 memberId · familyId 만 남긴다 — family 의 일이라 토큰을 가리킬 필요가 없다.
- **family 절대 수명은 최초 로그인 기준 30일이다 (#67).** `auth.token.refresh-family-max-lifetime`(기본 `30d`) — refresh TTL 14일의 약 두 배로,
  자주 쓰는 사용자도 한 달에 한 번은 다시 로그인한다. 회전마다 TTL 이 다시 걸려도(슬라이딩) 이 시각을 넘겨 이어지지 않고, 가까워지면 TTL 을 남은 시간으로 줄인다.
  넘기면 재발급이 401 `AUTH_011` 이고 BFF 는 다른 4xx 와 같이 쿠키를 지우고 로그인 화면으로 보낸다(FE 변경 없음).
  - 0 이하 · **90일 초과**(`AuthTokenProperties.MAX_REFRESH_FAMILY_MAX_LIFETIME`, #143)이거나 `security.jwt.issuance.refresh-token-ttl` 보다 짧으면 기동하지 않는다.
    짧으면 로그인 직후부터 TTL 이 줄어 refresh TTL 설정이 뜻을 잃는다. 두 설정을 함께 보는 검사는 회전 정책 빈(`AuthTokenBeansConfig#refreshTokenRotationPolicy`)을 만들 때 한다.
  - 90일 상한 — 이 값이 탈취한 refresh 토큰을 회전으로 이어 쓸 수 있는 가장 긴 시간이라 재로그인 주기를 한 분기보다 늘리지 않는다. refresh TTL 은 이 값 이하여야 하므로
    함께 묶인다. 회전마다 밀리초로 바꿔 Lua 에 넘기는 값이라 터무니없이 큰 값(`Duration.toMillis()` 오버플로)이 재발급마다 500 이 되지 않고 기동에서 드러나게 하는 목적도 있다.
  - 응답 코드는 새로 만들지 않고 `AUTH_011`("로그인이 만료되었습니다") 을 쓴다 — 사용자에게는 만료와 같은 일이고, `AUTH_012`(재사용 · 탈취 신호, warn)와는
    로그 수준(info, memberId · familyId 만)으로 나눈다.
  - family 값 손상도 응답은 `AUTH_011` 이지만 결과는 따로 낸다(`RefreshTokenRotation.FAMILY_CORRUPTED`, #143) — 정상 흐름에는 없고 저장소를 직접 고쳤거나
    시계가 크게 앞선 인스턴스가 로그인을 받은 신호라 **warn**(memberId · familyId 만)으로 드러낸다. 그냥 `INVALID` 로 답하면 로그가 남지 않는다.
  - family 손상과 이미 회전한 토큰의 재사용이 겹치면 **손상만 남는다**(손상 판정이 먼저다). 어느 쪽이든 family 를 지우므로 보호 효과는 같고,
    family 값은 서버 `Clock` 으로만 쓰여 공격자가 손상을 일으켜 재사용을 숨길 수는 없다.
- 판정 시각은 애플리케이션 `Clock` 이다(Redis `TIME` 이 아니다). **인스턴스 간 시계는 NTP 로 맞춘다는 전제다** — 어긋나 지금이 `rotatedAt` · 로그인 시각보다 이르면
  경과를 0 으로 본다. 단 **허용 시계 오차 60초**(`RefreshTokenRotationPolicy.CLOCK_SKEW`, Lua `now + 60000` — `RefreshTokenRedisAdapterArgumentsTest` 가 묶는다, #143)를
  넘게 미래면 손상으로 보고 거절한다(로그인 시각은 `FAMILY_CORRUPTED`, `rotatedAt` 은 재사용 감지).
  - 60초는 access 토큰 만료 판정의 기본 시계 오차(`security.jwt.clock-skew`)와 같은 폭이다 — 같은 인스턴스들의 시계를 같은 크기로 본다.
    상한을 늘려 주는 폭이 많아야 60초라 절대 수명(일 단위)에 비해 무시할 만하다.
  - 받아 주면 시계가 앞선 인스턴스가 쓴 로그인 시각이나 `^%d+$` 를 통과하는 먼 미래 값(309자리 이상이면 무한대)이 상한을 사실상 없앤다.
    반대로 시계가 60초 넘게 앞선 인스턴스가 받은 로그인은 (앞선 폭 − 60초) 안에 정상 인스턴스에서 재발급하면 끝난다(재로그인). 재발급은 보통 access 수명
    (기본 30분)이 지나야 하므로 시계가 30분 넘게 어긋나야 사용자에게 보인다 — 상한을 지키고 시계 고장을 warn 으로 드러내는 쪽을 택했다.
  - **`rotatedAt` 은 60초만 넘어도 드러난다.** 회전을 기록한 인스턴스의 시계가 다른 인스턴스보다 60초 넘게 앞서면, 같은 refresh 로 온 동시 재발급
    (BFF 가 병렬 401 마다 재발급을 부른다)이 다른 인스턴스에서 `AUTH_012`(재사용 감지 · family 폐기 · "reuse detected" warn)가 된다 — 정상 사용자가 로그아웃되고
    시계 고장이 탈취 경보와 같은 문구로 보인다. 반대 방향(기록한 쪽이 뒤처짐)은 #143 이전과 같이 유예(30초) 안에서만 형제로 받는다.
    앞선 인스턴스가 유예를 무한히 늘리던 구멍을 60초로 막는 대가이며, NTP 동기화가 깨지면 `AUTH_012` warn 이 늘어나는 것으로 먼저 보인다.
- 스크립트는 standalone 전제다. **Redis Cluster 로 바꾸면** 한 스크립트의 세 키(옛 토큰 · family · 새 토큰)가 같은 슬롯에 있어야 해(CROSSSLOT)
  키에 같은 hash tag(예: `{familyId}`)를 넣어야 한다.
- 저장소(Redis) 장애 · 타임아웃은 503 `AUTH_013` 이다. BFF 는 재발급 401(4xx)이면 쿠키를 지우고, 503 이면 쿠키를 둔다.
- Redis 헬스는 끈다(`management.health.redis.enabled: false`) — 인스턴스가 모두 같이 쓰는 의존이라 DOWN 이면 모든 인스턴스가 함께 빠지고
  Redis 와 무관한 회원가입 · 내 정보까지 끊긴다. 재시작으로 낫는 장애도 아니다. 장애는 503 `AUTH_013` 응답과 어댑터의 warn 로그(예외 타입만, 1분에 한 번 — §8)로 드러난다.

#### 로그아웃 (`POST /api/v1/auth/logout`, `isAuthenticated()`)

1. 본문의 refresh 토큰이 모양이 맞고 · 저장소에 있고 · **이 회원의 것**이면 그 family 를 DEL 한다. 본문 없음 · 토큰 없음 · 모양 오류 · 모르는 토큰 · 남의 토큰은
   건드리지 않고 넘어간다(200 멱등) — 남의 refresh 를 손에 넣은 사람이 자기 로그아웃으로 남의 세션을 끊지 못하게.
2. access 토큰의 jti 를 차단 목록에 SET(값 `"1"`, TTL `RevokedAccessTokenKeys.ttl(clockSkew)` — 같은 명령) (§7-1). 쓰기는 `AccessTokenRevocationPort` 뒤
   `AccessTokenRevocationRedisAdapter` 가 하고 키 · TTL 도 어댑터가 정한다.

- **순서는 family 폐기 → 차단 등록이다. 사용자 확정 결정 7 은 차단 → family 순서였다.** 이 PR 은 둘째 단계가 실패했을 때 남는 것을
  14일짜리 refresh 대신 ≤61분짜리 access 로 줄이려고 순서를 바꿨고, PR 에서 사용자 확인을 받는다.
  확인을 받지 못해 되돌리면 `AuthTokenProcessor#logout` 안의 두 줄(`revokeOwnFamily` · `accessTokenRevocationPort.revoke`)의 순서만 바꾸면 된다.
- 어느 쪽이든 Redis 장애면 503 `AUTH_013` 이고, 다시 부르면 같은 결과가 된다.
- **FE 후속 메모** — BFF 는 로그아웃이 401 이면(access 만료) 재발급한 뒤 한 번 재시도한다. 그러지 않으면 access 가 만료된 뒤 로그아웃할 때
  refresh family 가 지워지지 않고 남는다.
- auth-service 도 차단 목록을 보지 않는다 — 서비스는 서명 · 만료만 본다(#4 결정 3, §7-1).

## 3. 인증 주체

검증을 통과한 토큰은 `MemberPrincipal(long memberId, MemberRole role, String tokenId)` 이 된다.
권한 이름은 `ROLE_CUSTOMER` · `ROLE_ADMIN` 이라 `hasRole('ADMIN')` 이 동작한다.

```java
@PreAuthorize("isAuthenticated()")
@GetMapping("/api/v1/orders/{orderId}")
public ResponseEntity<Response<OrderDetailResponse>> getOrder(
    @AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId
) {
    return ResponseEntity.ok().body(Response.success(orderWebUseCase.getOrder(member.memberId(), orderId)));
}
```

- 컨트롤러는 `member.memberId()` 만 UseCase 로 넘긴다. `MemberPrincipal` 을 application 계층으로 넘기지 않는다.
- 소유자 판정은 JWT 의 memberId 로만 한다. 요청 본문·파라미터의 memberId 를 믿지 않는다 (`api-design-guide.md` §5).
  internal API 는 예외다 — 사용자 JWT 가 오지 않으므로 호출 서비스가 검증한 memberId 를 본문 · 파라미터로 받는다 (§9-4).

## 4. 오류 코드

| 코드 | enum | HTTP | 언제 | `WWW-Authenticate` |
|------|------|------|------|--------------------|
| `SECURITY_001` | `UNAUTHORIZED` | 401 | 토큰 없이 인증 필요 API · 어드민 경로에 왔다 (access 쿠키가 만료로 사라져 Authorization 없이 온 요청도 여기다) | `Bearer` |
| `SECURITY_002` | `TOKEN_EXPIRED` | 401 | 보낸 access 토큰이 만료됐다 | `Bearer error="invalid_token", error_description="The access token expired"` |
| `SECURITY_003` | `TOKEN_INVALID` | 401 | 서명 · 형식 · alg · typ · iss · aud · claim 위반 | `Bearer error="invalid_token"` |
| `SECURITY_004` | `TOKEN_REVOKED` | 401 | 로그아웃된 토큰 (#4) | `Bearer error="invalid_token"` |
| `SECURITY_005` | `FORBIDDEN` | 403 | 로그인했지만 역할이 모자란다 | 없음 |
| `SECURITY_006` | `TOKEN_VERIFICATION_UNAVAILABLE` | 503 | 토큰 검증 저장소 장애 (#4) · 검증 중 시스템 오류(`AuthenticationServiceException`) | 없음 |
| `SECURITY_007` | `SERVICE_UNAUTHORIZED` | 401 | internal API(`/internal/**`)의 서비스 인증 실패 — 토큰 없음 · 서명 · 만료 · `typ` · `aud` 불일치 · 사용자 access 토큰 (§9). 프론트는 보지 않는다 | `Bearer` |

- 본문은 `Response.fail(code, message)` 봉투다 (`api-design-guide.md` §2-1). 서비스와 게이트웨이가 `SecurityErrorEnvelope` 로 같은 바이트를 쓴다.
- `SECURITY_*` 는 access 토큰 판정뿐이다. refresh 토큰 거절(재발급)은 auth 도메인 코드 401 `AUTH_010` · `AUTH_011` · `AUTH_012`, 토큰 저장소 장애는 503 `AUTH_013` 이다 (§2-3).
  로그인 실패는 401 이 아니라 400 `AUTH_001` 이다 — 401 은 토큰 거절에만 쓴다 (#4 결정 5).
- **프론트(BFF)는 재발급 여부를 코드로 가르지 않는다.** 401 이면 refresh 쿠키로 한 번 재발급하고 원 요청을 한 번 재시도한다.
  재발급이 명시적으로 거절되면 로그인, 일시 장애면 쿠키를 둔 채 `BFF_002` 503 이다. 006(503)으로는 로그아웃시키지 않는다
  ([frontend/docs/architecture.md](../../frontend/docs/architecture.md) §2 · §3, `api-design-guide.md` §2-1). 코드는 원인을 알리는 용도다(로그 · 모니터링 · 화면 문구).

### 4-1. 판정 규칙 (`SecurityErrorResolver`)

- 예외 타입과 `OAuth2Error` 의 오류 코드로만 판정한다. **예외 메시지 문자열을 비교하지 않는다.**
- 검증 위반이 **만료 하나뿐일 때만 002** 다. 만료에 다른 위반(aud 불일치 등)이 섞이면 003 — 단순 만료가 아니라 잘못된 토큰이라는 원인을 가리지 않는다.
- 만료 판정은 security-core 의 validator 가 오류 코드 `SECURITY_002` 로 남긴다. Nimbus 의 기본 claim 검증은 꺼 두었다
  (켜 두면 Nimbus 가 먼저 "Expired JWT" 문구의 예외를 던져 만료를 문자열로 가려내야 한다).
- **#4 가 004 · 006 을 내는 방법** — 디코더 래퍼(서블릿 `JwtDecoder` · 리액티브 `ReactiveJwtDecoder`)나 `AuthenticationManager` 에서
  `new OAuth2AuthenticationException(new OAuth2Error(SecurityErrorCode.TOKEN_REVOKED.getCode()))` 를 던진다.
  리졸버는 원인 사슬에 실려 온 코드 중 **004 · 006 만** 따르고 001 · 002 · 003 · 005 는 무시한다 — 만료가 아닌 토큰이 002 로 보고되어 원인이 가려지지 않게.
  **실제로 내는 곳은 게이트웨이뿐이다**(서비스는 차단 목록을 보지 않는다, §7). 게이트웨이의 `RevocationCheckingAuthenticationManager` 가
  서명 · 만료 검증(`JwtReactiveAuthenticationManager`)을 **통과한 뒤에만** jti 로 차단 목록을 조회해, 차단이면 004 를, 조회 실패 · 타임아웃이면
  어드민 경로에서만 `new OAuth2AuthenticationException(new OAuth2Error("SECURITY_006"), cause)` 를 던진다. 틀린 토큰은 조회 전에 003 · 002 로 끝난다.
  jti 가 키 규칙에 맞지 않으면 저장소 장애가 아니라 003 이다 — fail-open 경로에서도 통과시키지 않는다.
- **validator 에 넣지 않는다.** validator 의 오류는 `JwtValidationException` 안에 들어가 003 이 된다.
- 저장소 장애를 `JwtException`(Bad 아님)이나 `AuthenticationServiceException` 으로 던져도 006 이다. Spring 의 기본 실패 처리기는 이 예외를
  다시 던져 필터 밖 500(지금은 `/error` 의 `COMMON_000`, #81 — 원인 · 재인증 분기를 잃는다)을 만들기 때문에, 서블릿 Resource Server 는 다시 던지지 않는 처리기로 바꿔 두었다(`ResourceServerSecurityConfigurer`).
- 권한 거부는 익명이면 001, 로그인했으면 005 다. 인증된 서비스가 허용 목록(§9-4) 밖이어도 005 다.
- **internal 체인은 `resolveServiceAuthentication` 을 쓴다** — 인증 실패는 원인과 무관하게 007, 검증 중 시스템 오류만 006 이다.
  호출자는 다른 서비스라 원인별로 다르게 처리할 것이 없고, 어느 검증에서 걸렸는지 응답으로 알리지 않는다(원인은 수신 쪽 로그의 예외 타입).

## 5. Resource Server (서블릿 서비스)

### 5-1. 정책

| 경로 | 규칙 |
|------|------|
| `/api/v1/admin/**` | URL 규칙으로 `ADMIN` 을 요구한다. 컨트롤러의 `@PreAuthorize("hasRole('ADMIN')")` 와 이중이다. 토큰 없으면 401(001), CUSTOMER 면 403(005) |
| `/internal/**` | 이 체인에서는 **`denyAll`**(익명 001 · 로그인 005)이다. 앞 순서의 서비스 인증 체인(§9-3)만 서비스 토큰으로 연다 — 서비스가 그 Configurer 를 빠뜨려도 internal API 가 열리지 않는다(fail-closed). **internal 컨트롤러 메서드마다 허용 호출자를 `@PreAuthorize("hasAuthority('SERVICE_<service-id>')")` 로 선언한다**(§9-4). 사설망 전제(게이트웨이가 라우팅하지 않고 서비스 포트를 외부에 열지 않는다, 아래 강제 수단)는 그대로 둔다 — 1단계 서비스 토큰은 그 위의 두 번째 방어다 |
| 그 밖의 `/api/v1/**` | URL 단계에서는 열어 두고(default-allow) 메서드의 `@PreAuthorize` 가 정한다. 비로그인 상품 조회 같은 API 가 있다 (아래 보완 규칙) |
| `/actuator/**` | `health`(하위 경로 포함)만 `permitAll` 이다. 웹에 노출된 다른 엔드포인트와 링크(`/actuator`)는 `denyAll` — 토큰 없으면 401(001), 로그인했으면 403(005). 노출하지 않은 엔드포인트는 이 규칙에 걸리지 않고 404 다 — **노출 설정(`exposure.include: health`)이 1차, URL 규칙이 2차** 방어다. 경로는 Boot 의 `EndpointRequest` 로 잡아 management base-path 를 바꿔도 따라간다. **어느 서비스도 `heapdump` 를 노출하지 않는다** — 서비스 전부의 힙에 `INTERNAL_JWT_SECRET` 이 있고, auth 의 힙에는 개인키도 있다 |
| Swagger (`/v3/api-docs/**` · `/swagger-ui/**`) | 명시 `permitAll` 을 두지 않는다. URL 단계는 default-allow 로 열려 있고, springdoc 스위치(기본 닫힘, local · dev 만 켬 — `bootstrap-conventions.md`)가 닫는다 |

- health 는 익명에 열려 있으므로 `management.endpoint.health.show-details` · `show-components` 는 기본값 `never` 를 유지한다.
  상세가 필요하면 management 포트를 분리하고 roles 로 좁힌다. **management 포트 분리는 검증하지 않았다** — 분리할 때 security-core 에
  RANDOM_PORT + `management.server.port=0` 통합 테스트를 더해 actuator 규칙이 분리된 포트에서도 걸리는지 확인한다.
- **토큰을 보냈는데 틀리면 공개 API 여도 401 이다.** 틀린 토큰을 익명으로 취급하지 않는다. BFF 는 401 이면 재발급 후 한 번 재시도한다(§4).
- 세션을 만들지 않는다(STATELESS). CSRF · 폼 로그인 · Basic · 로그아웃을 끈다. CORS 는 게이트웨이가 처리하므로 서비스에서 끈다.
- 정규화되면 다른 경로가 되는 원문 경로(`..` · `%2e` · `;` · `//`)는 Spring Security 방화벽이 필터 체인 앞에서 **400** 으로 끊는다(`%2f` 는 그보다 앞의 Tomcat 이 끊는다 — 아래).
  게이트웨이도 같은 경로를 먼저 거절한다(`GATEWAY_004`). 서비스의 400 은 게이트웨이를 거치지 않은 요청에 대한 이중 방어다.
  방화벽은 기본 처리기대로 `sendError(400)` 만 하고(`RequestRejectedHandler` 를 두지 않는다), 실제 컨테이너에서는 `/error` ERROR 디스패치를 거쳐
  common-core `FallbackErrorController` 가 `COMMON_004` 봉투를 쓴다(#81, `api-design-guide.md` §2-1). MockMvc 는 재전송하지 않아 본문이 비어 있다.
  사용자 체인의 `anyRequest().permitAll()` 이 `/error` 디스패치를 열고, Bearer 필터는 ERROR 디스패치에서 돌지 않는다(`OncePerRequestFilter` 기본값) —
  **`/error` 의 ERROR 디스패치를 인증 · 거부하는 어떤 규칙도 두지 않는다**(URL 규칙 · `dispatcherTypeMatchers(ERROR)` · 컨트롤러 메서드 보안 · Bearer 필터를 ERROR 에도 돌리기).
  두면 MVC 밖 오류가 401(익명) · 403 `SECURITY_005`(인증)로 덮인다. 서비스 `*ErrorPageEnvelopeSmokeTest` 가 무토큰 · 고객 토큰 · 틀린 토큰으로 이것을 본다.
  `/internal/**` 원 요청의 방화벽 거부도 400 `COMMON_004` 다 — `/error` 는 internal 체인(`securityMatcher` `/internal/**`)이 아니라 사용자 체인을 탄다.
  핸들러를 실행하지 않고 봉투만 쓰므로 internal 의 "인증 전 단계 `SECURITY_007`" 보다 앞서는 것이 위험은 아니다.
  `%2f` 는 Tomcat 이 Spring 전에 400 HTML 로 끊는다.
- 대소문자가 다른 경로(`/API/v1/admin/x`)는 어느 컨트롤러에도 매핑되지 않아 404 다. 비예약 문자를 인코딩한 경로(`/api/v1/%61dmin/x`)는
  URL 규칙과 MVC 가 같은 디코딩으로 보므로 어드민 규칙에 그대로 걸린다(CUSTOMER 403 · 무토큰 401). 둘 다 컨트롤러가 있는 security-core 통합 테스트로 고정했다.
  서비스 인가 스모크(§5-3)는 인코딩 경로가 규칙에 걸리는지와, 서비스 MVC 의 경로 매칭이 URL 규칙과 같은 대소문자 규칙(`PathPatternParser`, 대소문자 구분)인지 본다.

**default-allow 보완 규칙** — URL 단계가 열려 있으니 메서드 애노테이션이 빠지면 그대로 공개 API 가 된다.

- `adapter/in/web` · `adapter/in/admin` 의 **모든 핸들러 메서드에 `@PreAuthorize` 를 단다.** 공개 API 도 `@PreAuthorize("permitAll()")` 로 적어
  빠뜨린 메서드가 리뷰에서 보이게 한다. 로그인 필요는 `isAuthenticated()`, 어드민은 `hasRole('ADMIN')` 이다.
  빠뜨리면 **빌드가 실패한다** — 경로 기준 런타임 가드(§9-10 스모크)와 패키지 기준 정적 가드(§9-11 ArchUnit)가 둘 다 본다.

**`/internal/**` 사설망 전제의 강제 수단**

- 서비스 포트를 외부에 publish 하지 않는다. 운영은 네트워크 정책(보안 그룹 · NetworkPolicy)으로 서비스 간 통신만 허용한다.
- 게이트웨이(#3)가 막는 방식 — 정규화되지 않은 경로(`..` · `%2e` · `;` · `//` 등)는 `GatewayRequestGuardFilter` 가 라우팅 전에 `GATEWAY_004`(400)로 끊는다.
  그 뒤로는 원문 경로와 정규화 경로가 같다. `/internal` 과 그 아래는 **같은 필터가 명시적으로 404 `GATEWAY_001`** 로 끊는다(#65) —
  매핑 없는 경로와 같은 응답이라 존재를 드러내지 않고, 토큰 검증 · 차단 목록 조회 전에 끝나며, 대소문자 · 비예약 문자 인코딩(`/%69nternal`)도 같은 경로로 본다.
  라우트(`ServiceRoute`)에도 `/internal` 은 없다 — 라우트 부재가 두 번째 방어다.
  가드는 CORS 필터보다 앞이라 **가드가 거절한 응답(400 · 405 · 이 404)에는 CORS 헤더가 없다** — preflight 도 같다. 정상 프론트는 이런 요청을 만들지 않는다.
- 그래서 규칙은 둘이다. 게이트웨이 라우트 predicate 는 `/internal` 을 포함할 수 있는 패턴(`/**` 같은 catch-all)을 쓰지 않는다.
  경로를 바꾸는 필터(`StripPrefix` · `RewritePath` · `SetPath`)는 **와일드카드 없는 고정 경로 라우트에만** 쓴다 — 바뀐 경로도 고정값이라
  `/internal` · `/api/v1/admin` 이 만들어질 수 없다. 예: local · dev 전용 `GatewayDevRouteConfig`(`/health/{service-id}` · `/docs/{service-id}/v3/api-docs/{group}`).
  접두어만 떼는 라우트(`/docs/{service-id}/**` + `StripPrefix`)는 만들지 않는다.
- 서비스 간 인증은 §9 다(결정 #43, 구현 #51). 1단계는 공용 키라 호출자가 "선언" 이므로 사설망 전제를 걷어 내지 않는다.
- **게이트웨이가 만드는 하류 헤더(`X-Client-Subject`, §7-3)도 이 전제 위에 있다.** 게이트웨이는 클라이언트가 보낸 같은 이름 헤더를 늘 지우고 자기 값을 싣지만,
  게이트웨이를 거치지 않은 요청(사설망 직접 호출)은 누구든 그 헤더를 붙일 수 있다. 서비스는 출처를 구별할 수 없으므로 그 값을 인증 · 인가 · 차단의 근거로 쓰지 않고 남용 완화(시도 계수)의 차원으로만 쓴다.
  서비스 간 호출(internal)은 게이트웨이를 거치지 않으므로 이 헤더를 Feign 으로 전파하지 않고 internal 핸들러에서 읽지 않는다.

### 5-2. 도메인 advice 와의 관계

- `@PreAuthorize` 거부(`AuthorizationDeniedException`)는 필터가 아니라 **MVC 예외 처리**로 먼저 간다.
  security-core 의 `SecurityExceptionAdvice` 가 가장 높은 우선순위(`@Order(HIGHEST_PRECEDENCE)`)로 잡아 001 · 005 로 답한다.
- 도메인 `*ExceptionHandler` 는 `Exception` · `RuntimeException` 을 통째로 잡지 않는다 (`coding-conventions.md` §8-1). 잡으면 보안 예외를 가로챌 수 있다.
- 남는 오류는 common-core 의 `FallbackErrorResolver` 가 `COMMON_00x` 로 받는다. advice 가 아니라 MVC 예외 처리 사슬에서 모든 advice **뒤**에 선
  처리기라 `SecurityExceptionAdvice` · 도메인 advice 보다 늦다 — 보안 오류 응답은 바뀌지 않는다 (`api-design-guide.md` §2-1 공통 오류 코드).

### 5-3. 서비스 구성

```groovy
// service/<svc>/build.gradle
implementation project(':core:security-core')
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
testImplementation testFixtures(project(':core:security-core'))   // 테스트 키 · 토큰 · 인가 스모크 (아래 "테스트 키")
```

- security-core 는 서블릿 · Boot 보안 자동설정을 `compileOnly` 로만 둔다. 리액티브 게이트웨이에 서블릿 스택이 새지 않게 하려는 것이고,
  security-core 의 `verifyNoServletLeak` 태스크가 `check` 에서 이를 확인한다. 그래서 서블릿 서비스가 위 두 스타터를 직접 선언한다.
- **구성 실패 모드** — `spring-boot-starter-security` 와 `ResourceServerSecurityConfigurer` import 를 **둘 다** 빠뜨리면 오류 없이 기동하고,
  `@PreAuthorize` 가 무시되어 모든 API 가 열린다(스타터만 있으면 Boot 기본 체인이 모든 요청을 막고, Configurer 만 있으면 spring-security-config 클래스가 없어 기동이 실패한다).
  그래서 서비스마다 **인가 스모크 `*SecuritySmokeTest extends ResourceServerSmokeTestSupport {}`**(security-core 테스트 픽스처)를 둔다.
  서비스 컨트롤러에 기대지 않고 매핑 없는 경로(`/api/v1/admin/smoke` · `/api/v1/smoke`)로 URL 규칙과 토큰 필터를 본다 —
  무토큰 어드민 401(001, `WWW-Authenticate: Bearer`) · CUSTOMER 403(005) · ADMIN 통과(404) · `%61` 인코딩 경로 401 · 403 · 정규화 우회 400 ·
  MVC 경로 매칭의 대소문자 구분 · 공개 경로의 틀린 토큰 003 · 만료 002 · 세션 없음. 구성도 본다 — actuator 웹 노출이 정확히 `health` 하나(바인딩된 최종 값),
  `application.yml` 원문의 `${JWT_PUBLIC_KEY}` · `${JWT_PUBLIC_KEY_PREVIOUS:}`(테스트 키가 실제 값을 가리므로 원문을 본다), 발급기(`JwtIssuer`) 빈은
  발급하는 서비스에만(`issuerExpected()` — #4 에서 auth 만 `true`). 004 · 006 은 디코더 장애를 흉내 내야 해 security-core 통합 테스트에만 둔다.
  서비스 간 인증(§9-10)도 같은 스모크가 본다 — 서비스 토큰 없음 · 사용자 access 토큰 · `aud` 불일치 401 · 007, 허용 목록 밖 호출자 403 · 005, 허용된 호출자 200,
  `application.yml` 원문의 `${INTERNAL_JWT_SECRET}` · `${INTERNAL_JWT_SECRET_PREVIOUS:}` · `${spring.application.name}`.
  인가 선언 누락 가드(§9-10)도 같은 스모크에 있다 — 서비스의 모든 `/api/` · `/internal/` 핸들러가 `@PreAuthorize` 를 선언했는지 본다.
  **`@PreAuthorize("isAuthenticated()")` 익명 401 · `SECURITY_001` 케이스는 #4 또는 서비스에 첫 `isAuthenticated()` 핸들러가 생기는 이슈에서 서비스의 `*SecuritySmokeTest` 에
  메서드로 더한다** — `perform` · `bearer` · `SecurityEnvelopeAssertions.assertEnvelope` 를 쓰고 같은 컨텍스트를 공유한다.
  `@WebMvcTest` 같은 슬라이스로 쓸 때는 `*BeansConfig` 가 빠지므로 `ResourceServerSecurityConfigurer` · `JwtVerificationPropertiesConfig` 를 `@Import` 한다
  (안 하면 Boot 기본 체인이 Basic 401 을 내서 우리 봉투를 보지 못한다). internal 컨트롤러 슬라이스는 `InternalResourceServerConfigurer` ·
  `InternalTokenPropertiesConfig` 도 더한다.

| 파일 | import |
|------|--------|
| `*BeansConfig` | `ResourceServerSecurityConfigurer` · `InternalResourceServerConfigurer` (전 서비스), `JwtIssuerConfigurer` (auth 만), `InternalTokenIssuerConfigurer` (internal Feign 클라이언트가 있는 서비스, §9-5) |
| `*PropertiesConfig` | `JwtVerificationPropertiesConfig` · `InternalTokenPropertiesConfig` (전 서비스), `JwtIssuerPropertiesConfig` (auth 만) |

```yaml
security:
  jwt:
    issuer: commerce-foundry-auth          # 기본값과 같으면 생략해도 된다
    audience: commerce-foundry-api
    clock-skew: 60s                        # 0 이상 5분 이하
    public-key: ${JWT_PUBLIC_KEY}
    previous-public-key: ${JWT_PUBLIC_KEY_PREVIOUS:}
    issuance:                              # auth-service 만
      private-key: ${JWT_PRIVATE_KEY}
      access-token-ttl: 30m
      refresh-token-ttl: 14d
  internal:                                # 서비스 간 인증 (§9-6) — 서비스 전부
    service-id: ${spring.application.name}
    secret: ${INTERNAL_JWT_SECRET}
    previous-secret: ${INTERNAL_JWT_SECRET_PREVIOUS:}
```

기동을 멈추는 조건 — 메시지에는 프로퍼티·환경변수 이름만 있고 키 내용은 없다.

| 조건 | 메시지가 가리키는 것 |
|------|----------------------|
| 공개키가 비었거나 `${JWT_PUBLIC_KEY}` 가 풀리지 않았다 | `JWT_PUBLIC_KEY` |
| 키가 PEM 으로 읽히지 않는다 · 2048비트 미만이다 · PKCS#1 이다 · 공개키 자리에 개인키가 왔다 | 해당 환경변수 · 변환 명령 |
| `issuer` · `audience` 가 비었다, `clock-skew` 가 범위 밖이다, TTL 이 0 이하다 | 해당 프로퍼티 |
| (auth) `access-token-ttl` 이 1시간을 넘는다 | `security.jwt.issuance.access-token-ttl` · 이유(탈취 창 · 블랙리스트) |
| (auth) 개인키가 `JWT_PUBLIC_KEY` 와 한 쌍이 아니다 | `JWT_PRIVATE_KEY` · `JWT_PUBLIC_KEY` (§6-3) |
| 서비스 토큰 비밀 · 서비스 ID · 수명이 어긋났다 | `INTERNAL_JWT_SECRET` · `security.internal.*` (§9-6) |

#### 테스트 키

- 키를 커밋하지 않는다. security-core 테스트 픽스처(`src/testFixtures`)의 `TestKeys` 가 실행 중 RSA 2048 쌍을 만들고,
  `SecurityTestPropertiesInitializer` 가 `@SpringBootTest` 컨텍스트에 `security.jwt.public-key`(현재 공개키) · `previous-public-key`(빈 값) ·
  `issuance.private-key`(현재 개인키 — auth 가 발급기를 붙이는 #4 대비, 발급기를 import 하지 않으면 무해)를 넣는다. `application-test.yml` 에 키를 적지 않는다.
  서비스 토큰 비밀도 같다 — `TestServiceTokens` 가 실행 중 32바이트 난수를 만들고 초기화기가 `security.internal.secret`(현재) · `previous-secret`(빈 값)을 넣는다.
- 초기화기는 픽스처의 `META-INF/spring.factories` 로 등록돼 픽스처를 받은 모듈의 `SpringApplication` 마다 돈다(Boot 4 에는 `context.initializer.classes` 프로퍼티가 없다).
  속성 소스를 맨 앞에 두어 개발 PC 의 OS 환경변수 `JWT_PUBLIC_KEY` 보다 우선한다. 그래서 `@TestPropertySource` 로도 바꿀 수 없다 —
  다른 키로 기동 실패를 보는 테스트는 `ApplicationContextRunner` 로 쓴다(이 초기화기가 돌지 않는다).
- 테스트 클래스패스로 앱을 띄우는 `bootTestRun` 에서도 이 초기화기가 돌아 `.env` 의 키를 테스트 키로 덮는다. 로컬 실행은 `bootRun` 만 쓴다 (`local-run-guide.md` §6).
- 토큰은 `TestTokens.issuer(Instant.now()).issueAccessToken(...)` 로 만든다 — 컨텍스트의 공개키와 한 쌍이다. 봉투 단언은 `SecurityEnvelopeAssertions` 다.
- 픽스처는 `testImplementation testFixtures(...)` 로만 받는다 — `runtimeClasspath` 와 `bootJar` 에 들어가지 않는다. 픽스처의 서블릿 · MockMvc 의존은
  `compileOnly` 라 리액티브 게이트웨이(#4)도 키 · 토큰 픽스처를 받을 수 있고, `verifyNoServletLeak` 가 `testFixturesRuntimeClasspath` 도 본다.

### 5-4. 알려진 예외 — 우리 봉투가 아닌 응답

Spring Security 7 이 Resource Server 설정에 자동으로 더하는 것과 방화벽 거절이다. 정상 클라이언트는 만나지 않는다.

| 요청 | 응답 | 비고 |
|------|------|------|
| `GET /.well-known/oauth-protected-resource` | 200 + 메타데이터(무인증) | 비밀은 아니다. 게이트웨이가 라우팅하지 않는다 |
| `Authorization: DPoP ...` | 봉투 없는 401 + `WWW-Authenticate: DPoP ...` | 우리 토큰은 DPoP 바인딩이 없어 항상 거부된다. 프론트는 `Bearer` 만 쓴다 |
| 인코딩된 슬래시 `%2f` 경로 (§5-1) | Tomcat 의 400 HTML (서버 버전 없음) | 애플리케이션 전에 끊긴다. 게이트웨이가 먼저 `GATEWAY_004` 로 거절한다. 그 밖의 비정규 경로는 `COMMON_004` 봉투다(#81). 서버 버전이 나가지 않게 운영 프로필에서 Tomcat 오류 페이지(`ErrorReportValve` showServerInfo · showReport)와 `server.server-header` 를 바꾸지 않는다 |

## 6. 키

### 6-1. 만들기

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem   # PKCS#8 (BEGIN PRIVATE KEY)
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem                         # X.509 (BEGIN PUBLIC KEY)
```

- PKCS#1(`BEGIN RSA PRIVATE KEY`)은 받지 않는다. 있다면 `openssl pkcs8 -topk8 -nocrypt -in old.pem -out jwt-private.pem` 으로 바꾼다.
- 키 파일은 저장소 밖에서 만든다. `*.pem` 은 `.gitignore` 가 막지만, 값을 Vault(로컬은 `.env`)에 넣은 뒤 개인키 파일은 지운다.

### 6-2. 환경변수 값 — PEM 본문 한 줄

```bash
grep -v '^-----' jwt-private.pem | tr -d '\r\n'   # JWT_PRIVATE_KEY (auth 만)
grep -v '^-----' jwt-public.pem  | tr -d '\r\n'   # JWT_PUBLIC_KEY  (전원)
```

- 정본은 아머(`-----BEGIN ...-----`) 없는 본문 한 줄이다. 아머가 붙어 있거나, 개행이 섞이거나, 개행이 리터럴 `\n` 두 글자로 들어와도 읽는다.
- 공개키는 비밀이 아니지만 교체를 Vault 한 곳에서 하려고 환경변수로 받는다. 개인키는 auth-service 에만 둔다.

### 6-3. 교체 런북

auth 는 기동할 때 자기 `JWT_PRIVATE_KEY` 와 `JWT_PUBLIC_KEY` 가 한 쌍인지 확인한다(`JWT_PUBLIC_KEY_PREVIOUS` 는 보지 않는다).
아래 순서를 지키면 auth 가 여러 인스턴스여도 교체 중에 401 이 나지 않는다.

| 단계 | auth 를 뺀 서비스 · 게이트웨이 | auth | 결과 |
|------|------------------------------|------|------|
| ① 검증 키 추가 | `PREVIOUS` ← 옛 공개키, `PUBLIC` ← 새 공개키 | `PREVIOUS` ← **새** 공개키만. 개인키 · `PUBLIC` 은 그대로라 쌍 검사를 통과한다 | 모두 롤링 재기동. 어디서나 옛 키 · 새 키 토큰을 받는다 |
| ② 서명 키 교체 | 그대로 | `PRIVATE` ← 새 개인키, `PUBLIC` ← 새 공개키, `PREVIOUS` ← 옛 공개키 | auth 롤링 재기동. 옛 · 새 인스턴스가 서로의 토큰을 받는다 |
| ③ 정리 | `PREVIOUS` 비움 | `PREVIOUS` 비움 | ② 완료 후 access TTL + 시계 오차(기본 31분, 최대 61분)가 지나면 롤링 재기동 |

- ②를 ①보다 먼저 하면 아직 새 공개키가 없는 서비스가 새 토큰을 003 으로 거부한다. ①에서 auth 의 `PUBLIC` 까지 바꾸거나 ②에서 개인키만 바꾸면
  쌍 검사가 `JWT_PRIVATE_KEY 와 JWT_PUBLIC_KEY 가 한 쌍이 아닙니다` 로 기동을 막는다. 이미 떠 있는 인스턴스는 옛 설정으로 계속 동작한다.
- `PREVIOUS` 가 채워져 있는 동안 기동 로그에 `jwt previous public key slot is in use. previousKid=... currentKid=...` WARN 이 한 줄 남는다.
  ③을 잊은 곳을 찾는 신호다.
- ①에서 auth 와 다른 서비스의 `PUBLIC` · `PREVIOUS` 값이 다르다. Vault 경로는 서비스별로 두어 따로 바꿀 수 있어야 한다 (#32).
- 개인키가 유출됐으면 ③을 기다리지 않는다 — `JWT_PUBLIC_KEY_PREVIOUS` 를 바로 비워 옛 키로 서명한 토큰을 모두 무효로 한다 (`secret-management.md` §6).
- JWKS 엔드포인트는 두지 않는다. 키는 환경변수로만 배포한다.

## 7. 게이트웨이 (#4)

구현은 `cloud/api-gateway` 의 `security` 패키지다. 라우트 필터가 아니라 **전역 `SecurityWebFilterChain`**(`GatewaySecurityConfig`) 이다 —
한 라우트(`ServiceRoute`)가 고객 · 어드민 패턴을 섞어 갖고, dev 라우트(`/health/**` · `/docs/**`)도 덮어야 한다.

**필터 순서** — `GatewayCorrelationIdFilter`(가장 앞, 상관 id, #284) → `GatewayRequestGuardFilter`(비정규 경로 거절) → `GatewayRequestBodySizeFilter`(본문 크기 상한, §7-4) →
보안 체인(`WebFilterChainProxy`, -100: CORS → Bearer 인증 → 인가) → 라우트 전역 필터(`GatewayRateLimitFilter` HIGHEST+100 요청 제한 →
`ClientSubjectHeaderFilter` HIGHEST+200 하류 `X-Client-Subject`, §7-3) → 라우팅.
정규화되지 않은 경로로 인가 판정을 하지 않는다.

| 요청 | 게이트웨이 판정 |
|------|-----------------|
| 토큰 없음 · 공개 경로 | 그대로 하류로 간다. 인증 필요 여부는 서비스가 판정한다 (`architecture-guide.md` §6) |
| 토큰 없음 · `/api/v1/admin/**` | 401 `SECURITY_001` |
| 토큰 있음 (경로 무관) | 서명 · 만료 · claim 검증 → 차단 목록 조회. 틀리면 공개 경로여도 401(002 · 003 · 004) — 틀린 토큰을 익명으로 취급하지 않는다 |
| CUSTOMER · `/api/v1/admin/**` | 403 `SECURITY_005` |
| 토큰 없는 preflight(`OPTIONS`) | 보안 체인 안의 CORS 필터가 인증보다 먼저 답한다 — 어드민 경로도 401 이 되지 않는다 |

- 어드민 패턴(`GatewaySecurityConfig.ADMIN_PATH_PATTERN`)은 서비스 체인(§5-1)과 같은 `/api/v1/admin/**` 다. 경로 판정은 `PathPattern` 이라
  비예약 문자 인코딩(`/api/v1/%61dmin/x`)도 같은 경로로 본다.
- **`Authorization` 헤더는 그대로 하류로 넘긴다.** 서비스가 같은 토큰을 다시 검증한다. 검증 결과를 `X-Member-Id` 같은 헤더로 만들지 않는다 (§1).
  **`Authorization` 이 둘 이상인 요청은 `GatewayRequestGuardFilter` 가 보안 체인 전에 400 `GATEWAY_004` 로 끊는다**(#65) — 게이트웨이가 검증한 토큰과
  서비스가 쓰는 토큰이 하류 구성에 따라 갈라질 수 있어서다. 어느 토큰도 검증하지 않고, 헤더 값은 로그에 남기지 않는다.
- 매니저는 `oauth2ResourceServer().authenticationManagerResolver(...)` 로 경로마다 고른다(`GatewayAuthenticationManagerResolver`).
  Spring Security 7 은 resolver 와 `jwt()` DSL 을 같이 쓸 수 없어(`validateConfiguration`) 디코더 · 변환기를 resolver 안에서 조합한다.
  두 매니저(fail-open · fail-closed)는 기동 때 한 번 만들고 디코더를 나눠 쓴다. resolver 를 빈으로 내보내 Boot 가 생성 비밀번호 사용자를 만들지 않는다.
- 토큰 없는 어드민 요청(001)은 `exceptionHandling` 의 진입점이, 토큰이 붙은 요청의 실패는 resource server 의 실패 처리기가 같은
  `GatewaySecurityErrorResponder` 로 답한다.
- CORS 는 `GatewayCorsConfig` 의 `CorsConfigurationSource` 빈을 보안 체인의 `cors(...)` 에 **직접** 넘긴다. 따로 둔 `CorsWebFilter` 빈은 보안 체인 뒤에 돌아
  토큰 없는 어드민 preflight 가 401 이 된다. `withDefaults()` 는 `CorsConfigurationSource` 빈이 둘 이상이면 조용히 CORS 를 빼므로 쓰지 않는다.
- 세션 · CSRF · Basic · 폼 로그인 · 로그아웃 · request cache 를 끄고 `NoOpServerSecurityContextRepository` 를 쓴다(SESSION 쿠키 없음).
  **보안 헤더(`headers`)도 끈다** — 프록시한 서비스 응답에는 서비스 체인의 헤더가 이미 있고, 게이트웨이가 HSTS(includeSubDomains) · `Cache-Control` 을
  덧붙이면 서비스가 정한 값과 겹치거나 바뀐다. 게이트웨이가 직접 만드는 응답은 오류 봉투뿐이고, 그중 보안 오류(401 · 403 · 503)에만
  `Cache-Control: no-store` 를 붙인다(`GatewaySecurityErrorResponder`).

### 7-1. 로그아웃 차단 목록

- 키 `auth:revoked-access-token:{jti}` — `security-core` 의 `RevokedAccessTokenKeys` 하나로만 만든다. auth 가 로그아웃 때 쓰고(TTL = access 수명 상한 1시간 + 시계 오차),
  게이트웨이가 존재 여부만 읽는다 (`coding-conventions.md` §9-7).
- **차단 목록은 게이트웨이만 본다.** 서비스 전부는 서명 · 만료만 본다(#4 결정 3). 사설망 안에서 서비스에 직접 붙는 요청은 로그아웃한 토큰도
  만료까지(`access-token-ttl` + `clock-skew` — 기본 31분, 상한 61분) 통과한다 — 서비스 포트를 외부에 열지 않는 사설망 전제(§5-1)가 이 잔여 위험을 막는 수단이다.
- 조회 상한은 Redis 명령 타임아웃(`spring.data.redis.timeout`, 1s)이고, 게이트웨이 쪽 뒤쪽 방어로 2초 상한(`RevocationCheckingAuthenticationManager.CHECK_TIMEOUT`)이 하나 더 있다.
- **Redis 에 닿지 못하는 동안은 토큰이 붙은 요청마다 최대 약 2초 지연된다.** 연결 거부(포트 닫힘)는 바로 실패하지만, 응답 없는 호스트는 연결 타임아웃
  (`connect-timeout`, 2s)까지 기다리고, 끊긴 연결에서는 Lettuce 가 재연결을 기다리며 명령을 쌓아 둔다. 어느 쪽이든 `CHECK_TIMEOUT`(2s)이 먼저 끊어
  fail-open(통과) · fail-closed(503)로 넘긴다. 토큰 없는 요청은 조회하지 않으므로 지연이 없다. 두 값을 바꿀 때는 `CHECK_TIMEOUT` ≥ `timeout` 을 지킨다.

| 차단 목록 저장소 장애 · 타임아웃 시 | 동작 | 근거 |
|------------------------------------|------|------|
| 그 밖의 경로 | **통과(fail-open)** + warn 로그(§8) | 저장소 장애가 쇼핑 전체 장애가 되지 않게 (#4 결정 2) |
| `/api/v1/admin/**` | **503 `SECURITY_006`** (fail-closed) | 로그아웃한 관리자 토큰이 통과하면 피해가 크다 |

- fail-open 의 대가 — **장애 동안 로그아웃한 access 토큰은 만료까지 유효하다 — `access-token-ttl` + `clock-skew` (기본 31분 = 30분 + 1분, 상한 61분 = 1시간 + 1분).**
- 게이트웨이 헬스에 Redis 를 넣지 않는다(`management.health.redis.enabled: false`). 넣으면 Redis 장애에 LB 가 게이트웨이를 빼 fail-open 이 무의미해진다.

### 7-2. 재사용하는 security-core 클래스

security-core 의 아래 클래스는 서블릿 의존이 없다. 게이트웨이는 `spring-boot-starter-security`(WebFlux 변형)를 직접 선언하고 이것들을 조합한다.

| 클래스 | 쓰임 |
|--------|------|
| `token.JwtVerificationPropertiesConfig` · `JwtVerificationProperties` | `security.jwt.*` 바인딩 |
| `token.reactive.ReactiveAccessTokenDecoderFactory` | `ReactiveJwtDecoder` — 서블릿과 같은 키 묶음 · validator |
| `principal.MemberJwtAuthenticationConverter` | `ReactiveJwtAuthenticationConverterAdapter` 로 감싸 쓴다 |
| `error.SecurityErrorResolver` · `SecurityErrorEnvelope` · `SecurityErrorCode` | JWT 거부 응답 — 서비스와 같은 코드 · 같은 바이트 · 같은 `WWW-Authenticate` |
| `token.revocation.RevokedAccessTokenKeys` | 차단 목록 키 · TTL (auth 와 공유). redis-core 는 security-core 에서 `compileOnly` 다 |

- 게이트웨이의 JWT 거부도 `SECURITY_*` 를 쓴다 (`api-design-guide.md` §2-1). `GATEWAY_*` 는 라우팅 오류에만 쓴다.
- 인증 실패 처리기는 `ServerAuthenticationEntryPointFailureHandler` 를 `setRethrowAuthenticationServiceException(false)` 로 설정해 쓴다.
  기본(true)이면 006 이 될 `AuthenticationServiceException` 이 봉투 없는 500 이 된다 — 서블릿 쪽과 같은 이유다 (§4-1).

### 7-3. 고객 쓰기 API 요청 제한 (#79)

로그인한 회원 한 명이 쓰기 요청을 몰아 보내 DB 를 키우는 남용을 게이트웨이에서 줄인다(장바구니 병합 1회에 맞춤 제작 입력 약 50만 자, #12 보안 검토).
구현은 `cloud/api-gateway` 의 `ratelimit` 패키지다 — 라우트 전역 필터(`GatewayRateLimitFilter`)가 보안 체인 **뒤**, 하류 전송 **앞**에서 센다.

| 정책 (`RateLimitPolicy`) | 대상 | 기본 한도 (`gateway.rate-limit.*`) |
|--------------------------|------|-------------------------------------|
| `cart-merge` | `POST /api/v1/carts/*/merge` | 분당 6회 · 순간 3회 |
| `cart-write` | 그 밖의 `/api/v1/carts/**` 쓰기 (POST · PUT · PATCH · DELETE) | 분당 60회 · 순간 20회 |
| `order-quote` | `POST /api/v1/orders/quotes` (주문서 미리보기) | 분당 60회 · 순간 20회 |
| `order-write` | 그 밖의 `/api/v1/orders/**` 쓰기 (POST · PUT · PATCH · DELETE) | 분당 12회 · 순간 5회 |

- **표 순서가 곧 우선순위다** — 위에서 처음 맞는 정책 하나만 센다. 그래서 병합은 `cart-write` 앞, 미리보기는 `order-write` 앞이다.
  미리보기는 주문서에서 수량 · 배송지를 바꿀 때마다 나가므로 주문 생성과 한도를 나눈다 — 나누지 않으면 몇 번 바꾼 뒤 "주문하기" 가 429 를 받는다(#158).
  미리보기 한도는 `POST /api/v1/orders/quotes` 에만 쓴다. 같은 경로의 다른 메서드 · 끝 슬래시는 `order-write` 로 센다(더 엄격한 `order-write` 로 세므로 우회가 아니다).
  **전제: 미리보기 핸들러(#13)는 로그인한 회원(`CUSTOMER`)만 받는다** — 토큰 없는 요청은 IP 계수(`gateway.client-ip.enabled`)가 꺼진 기본값에서 세지 않으므로,
  미리보기를 비로그인으로 열면 하류 product · delivery 호출이 한도 없이 나간다. 비로그인으로 열 때는 IP 계수를 먼저 켠다(아래 항목).

- **주체는 둘이다** — 로그인한 요청은 검증된 회원 id, 토큰 없는 요청은 클라이언트 IP 의 HMAC(#89). 같은 정책 한도를 쓰되 버킷이 따로다.
  **토큰 없는 요청은 `gateway.client-ip.enabled` 를 켰을 때만 센다 — 기본은 꺼짐이다**(바로 아래 항목).
  키는 `request_rate_limiter.{<정책>.<정책>:member:<memberId>}` · `request_rate_limiter.{<정책>.<정책>:ip:<HMAC>}` 의 `.tokens` · `.timestamp`(Spring Cloud Gateway `RedisRateLimiter` 의 토큰 버킷 —
  프레임워크 키 `{<routeId>.<id>}` 의 routeId 자리에 정책 id 를 넘긴다, `coding-conventions.md` §9-7). 내부 Snowflake id 또는 IP 의 HMAC 만 들어가고 **이메일 · IP 원문 같은 개인정보는 넣지 않는다.** 요청 하나는 처음 맞는 정책 하나에만 걸린다.
  조회(GET) · 어드민 경로 · preflight 는 세지 않는다.
- **비로그인 IP 계수는 `gateway.client-ip.enabled` 로 켜고, 기본은 꺼짐이다.** 끄면 토큰 없는 요청은 세지 않는다(IP 해석 · 해시도 하지 않는다 — #89 이전과 같다).
  - **꺼 둔 이유는 BFF 구조다.** 스토어프론트 브라우저는 게이트웨이를 직접 부르지 않고 Next BFF 를 거치는데, BFF 는 `X-Forwarded-For` 를 넘기지 않는다
    (`frontend/packages/api-client/src/server/proxy.ts`). 그래서 게이트웨이가 보는 원격 주소는 늘 BFF 이고 **비로그인 요청 전체가 버킷 하나**가 된다.
    access 쿠키가 만료된 회원은 BFF 가 첫 요청을 토큰 없이 보내 401 을 받아야 재발급 · 재시도하는데, 그 공용 버킷이 비어 있으면 401 대신 429 `GATEWAY_006` 을 받아
    재발급 경로를 타지 못한다 — 익명 요청 몇 건으로 회원의 쓰기를 막을 수 있다.
  - **켜는 전제는 둘이다 (#134).** (1) BFF 는 브라우저가 보낸 `X-Forwarded-For` 를 버리고, 앞단(LB · 호스팅 플랫폼)이 정한 클라이언트 IP
    **한 값으로 게이트웨이행 `X-Forwarded-For` 를 덮어쓴다** — 게이트웨이를 부르는 BFF 의 모든 경로(중계 · 로그인 · 회원가입 · 재발급 · 로그아웃).
    그다음 BFF 의 송신 주소를 `GATEWAY_TRUSTED_PROXIES` 에 넣는다. 브라우저 값을 그대로 넘기면 누구나 버킷을 고르거나 남의 버킷을 소진시킨다.
    (2) BFF 가 access 쿠키는 없고 refresh 쿠키만 있으면 요청 전에 먼저 재발급한다 — 로그인한 회원의 요청이 토큰 없이 와 IP 버킷을 쓰지 않게.
    **켜는 스위치는 `GATEWAY_CLIENT_IP_ENABLED`(기본 `false`)다(#135).** 순서는 dev 먼저 — `GATEWAY_TRUSTED_PROXIES` · `GATEWAY_CLIENT_IP_HASH_KEY`(Vault)를 채운 뒤 켠다.
    운영은 BFF · LB 송신 주소가 정해진 뒤 켠다(배포 환경 결정). 불리언이 아니거나 빈 값이면 기동하지 않는다.
    켰는데 `trusted-proxies` 가 비면 기동 때 WARN 을 한 줄 남긴다(`ClientIpResolver`) — 프록시 없이 직접 받는 배포도 있을 수 있어 기동은 막지 않는다.
  - 켜도 정책은 최소다. 지금은 비로그인 쓰기 API 가 없어 새 경로를 열지 않고, 위 정책을 토큰 없는 요청에도 IP 로 적용하는 것만 한다 —
    대상 API 가 모두 `isAuthenticated()` 라 401 을 노리는 익명 폭주가 서비스에 닿는 양만 줄인다.
    **비로그인으로 쓰는 API(비회원 주문 등)를 만들면 그 경로 · 한도를 `RateLimitPolicy` 에 더한다.** 한 IP 뒤 여러 사용자(회사 · 통신사 NAT)를 고려해 한도를 정한다.
- **클라이언트 IP 는 클라이언트가 바꿀 수 없는 값으로만 정한다** (`ClientIpResolver`).
  - 신뢰 프록시(`gateway.client-ip.trusted-proxies` — `GATEWAY_TRUSTED_PROXIES`, 주소 · CIDR 콤마 목록)가 **비면(기본) `X-Forwarded-For` 를 무시하고 소켓의 원격 주소**를 쓴다.
    BFF · LB 뒤에서 비워 두면 비로그인 요청 전체가 버킷 하나다(위 항목).
  - BFF · LB · CDN 구성(`service-inventory.md` §5)이 정해지면 **우리가 운영하는 BFF · LB 의 송신 주소 대역만** 넣는다. 넣을 수 있는 것은
    **접속 주소를 `X-Forwarded-For` 에 덧붙이는(append) L7 프록시**와 **위 (1) 처럼 브라우저 값을 버리고 한 값으로 덮어쓰는 BFF** 뿐이다. `X-Forwarded-For` 를 덧붙이지 않는 L4 패스스루 LB(맨 오른쪽이 클라이언트가 쓴 값이 된다)나
    `ip:port` 로 붙이는 프록시(주소가 아니라 프록시 주소로 떨어져 한 버킷이 된다)는 넣지 않는다.
  - 공용 CDN 처럼 남도 쓰는 대역을 넣으면 그 안의 누구나 `X-Forwarded-For` 를 정해 버킷을 바꾸거나 남의 버킷을 소진시킨다.
    IPv4 /8 · IPv6 /32 보다 넓은 대역은 기동에서 거부하지만 **이 하한은 오타 · 실수 방지용**이다 — 공용 CDN 대역(예: /13 · /32)을 넣는 위험은 막지 못하므로 우리 프록시 대역만 넣는다.
  - 원격 주소가 신뢰 프록시면 `X-Forwarded-For` 를 **오른쪽부터** 읽어 신뢰 프록시를 건너뛴 첫 주소를 쓴다. 맨 왼쪽은 클라이언트가 마음대로 넣는 값이라 쓰지 않는다.
    헤더가 없거나 · 전부 신뢰 프록시이거나 · 주소가 아닌 값(호스트 이름 · 포트 · 대괄호)을 만나면 프록시 주소를 쓴다(그 프록시 뒤가 한 버킷 — 닫힌 쪽). 이름은 DNS 로 찾지 않는다.
  - 프레임워크가 전달 헤더로 원격 주소를 바꾸지 않게 `server.forward-headers-strategy: none` 을 명시한다(클라우드 플랫폼을 감지하면 Boot 기본값이 바뀐다). `Forwarded`(RFC 7239)는 보지 않는다.
- **IP 는 HMAC-SHA256 으로 바꿔 키에 넣는다** (`ClientIpHasher`, 앞 16바이트 base64url 22자). 키는 `GATEWAY_CLIENT_IP_HASH_KEY`(Vault, base64 32바이트 이상,
  게이트웨이 인스턴스 전부 같은 값) — 키 없는 해시는 IPv4 공간을 전수 대입하면 되돌릴 수 있다. **켤 때만 필수**이고, 켰는데 비었거나 짧거나 풀리지 않으면 기동하지 않는다
  (끈 기본값에서는 비워도 뜬다).
  키를 바꾸면 진행 중인 비로그인 버킷이 새로 시작될 뿐 다른 영향은 없다 — 롤링 배포 중에는 인스턴스마다 키가 달라 한도가 잠시 최대 두 배가 될 수 있다.
  로그에도 IP 원문 대신 HMAC 을 남긴다. **IPv6 는 /64 로 묶어** 센다 — 한 가입자가 보통 /64 를 받아 그 안에서 주소를 바꿔 가며 새 버킷을 얻을 수 있어서다.
  다만 /56 · /48 을 위임받는 가입자는 /64 를 바꿔 가며 버킷을 늘릴 수 있다. 완화 수단일 뿐이며, 익명 쓰기 API 가 생기면 더 넓은 묶음을 검토한다.
- **로그인 · 회원가입 시도 제한은 auth-service 가 맡는다(#66)** — 계정 잠금처럼 회원 상태와 묶여 있어 게이트웨이가 아니라 소유 서비스가 판정한다.
  게이트웨이 정책에 `/api/v1/auth/**` 를 넣지 않는다. 그 시도 제한이 IP 를 쓰게 되면 같은 신뢰 프록시 규칙(이 절)을 따르고, **신뢰 설정을 두 곳으로 가르지 않는다.**
  - Spring Cloud Gateway 의 `spring.cloud.gateway.server.webflux.trusted-proxies` 는 설정하지 않는다. 미설정이면 SCG 가 하류로 가는 `X-Forwarded-*` · `Forwarded` 를 지운다
    (`RemoveXForwardedHeadersFilter` · `RemoveForwardedHeadersFilter`) — 클라이언트가 보낸 값이 서비스에 닿지 않는다.
    **SCG 는 지운 뒤 다시 쓰지도 않는다** — `XForwardedHeadersFilter` 는 소켓 피어가 SCG 신뢰 프록시가 아니면 지우고 끝난다(5.0.3, 게이트웨이 신뢰 프록시
    `gateway.client-ip.trusted-proxies` 와 무관). 그래서 지금 하류 요청에는 `X-Forwarded-*` · `Forwarded` 가 없다 — 실제 Netty 테스트(`ClientSubject*Test`)가 고정한다.
    SCG `x-forwarded` 설정은 건드리지 않는다. 하류가 전달 헤더를 받더라도(설정이 바뀌어 생기더라도) **신뢰하지 않는 정보성 값**이고, 클라이언트 식별은 아래 `X-Client-Subject` 로만 한다.
  - 하류에 넘기는 것은 **IP 원문이 아니라 게이트웨이가 만든 HMAC** 이다(#135) — 하류 서비스에 IP 원문이 들어오지 않아 로그 · Redis 키 규칙(원문 금지, `coding-conventions.md` §9-7)이 단순해지고,
    해시 키를 서비스마다 둘 필요가 없으며, 신뢰 프록시 해석 · IPv6 묶음이 게이트웨이 한 곳에서 끝난다. 원문 IP 를 감사 로그에 넣을지는 #286 에서 정했다 — 넣지 않는다(§11-4 접속기록 범위).

**하류 헤더 계약 — `X-Client-Subject` (#135, `ClientSubjectHeaderFilter`)**

| 항목 | 값 |
|------|----|
| 이름 | `X-Client-Subject` (`ClientSubjectHeaderFilter.HEADER`) |
| 값 | 게이트웨이 HMAC-SHA256 앞 16바이트 base64url(패딩 없음, 22자). IPv6 는 /64 로 묶은 뒤 해시. **비로그인 버킷 키(`ip:<HMAC>`)와 같은 값**(`ClientSubjectResolver`). 불투명 id — 하류는 해석하지 않는다 |
| 언제 | `gateway.client-ip.enabled=true` 이고 클라이언트 IP 를 정할 수 있을 때, 게이트웨이를 지나는 모든 라우트 요청(로그인 요청 포함). 한 개만 싣는다 |
| 위조 | 클라이언트가 보낸 같은 이름 헤더는 게이트웨이가 **늘 지운다** — 꺼져 있어도, 토큰이 있어도, 대소문자가 달라도, 여러 개여도 |
| 하류의 신뢰 | 하류는 값의 출처(게이트웨이 · 사설망 직접 호출)를 구별할 수 없다. 그래서 **인증 · 인가 · 차단의 근거로 쓰지 않고** 남용 완화(시도 계수)에만 쓴다 |
| 직접 호출 | 사설망 직접 호출자는 헤더를 빼 IP 차원을 건너뛰거나, 남의 값을 넣어 그 버킷을 소진시킬 수 있다 — 사설망 전제(§5-1), 호출자 증명은 §9 2단계(§9-9) |
| 로그 | 하류도 값을 로그에 그대로 남기지 않는다(필요하면 앞 8자). Redis 키 id 로는 그대로 쓴다 — 원문이 아니고 id 문자 집합 안이다(`coding-conventions.md` §9-7) |
| 없을 때 | 하류는 IP 차원을 건너뛴다 (꺼짐 · 원격 주소 모름) |

- **이 표를 #66(auth 로그인 · 가입 시도 제한의 IP 차원)이 읽는다.** 행을 바꾸면 #66 쪽 구현 · 문서도 같이 본다.

- 라우트 전역 필터(`GlobalFilter`, HIGHEST+200)다 — 요청 제한(HIGHEST+100) 뒤라 제한에 걸린 요청은 꾸미지 않고, SCG 라우팅이 읽는 요청을 바꾼다. 이 필터는 헤더 값 · IP 원문을 로그에 남기지 않는다.
- 계수를 켜도 신뢰 프록시가 비어 있으면 BFF · LB 뒤의 모든 요청이 같은 값이다(위 "켜는 전제"). 하류는 이 값으로 회원을 막을 때 그 경우를 고려한다(#66).
- 한도를 넘으면 하류로 보내지 않고 **429 `GATEWAY_006`** + `Retry-After`(초, 요청 한 번만큼 버킷이 다시 차는 시간의 근사)로 답한다. CORS 헤더는 실린다(보안 체인 뒤라서).
  프레임워크의 `X-RateLimit-*` 헤더는 내보내지 않는다.
- **Redis 장애 · 응답 없음이면 통과(fail-open)** 한다 — 제한은 남용 완화 수단이라 장애가 쇼핑 장애가 되지 않게 한다(차단 목록 일반 경로와 같은 판단, §7-1).
  `RedisRateLimiter` 는 명령 오류를 스스로 허용으로 바꾸고 남은 토큰을 `-1` 로 알린다 — `RedisBucketRateLimiter` 가 그 신호를 장애로 바꾸고, 응답 없는 명령은
  2초 상한(`GatewayRateLimitFilter.CHECK_TIMEOUT`)이 끊는다. warn 은 1분에 한 번만 남기고, 프레임워크의 요청마다 쌓이는 error 로그는 yml 에서 끈다.
  응답 없는 Redis 에서는 대상 요청마다 최대 약 1~2초 늦어진다(차단 목록 조회와 같다). 장애 동안은 회원 한 명의 쓰기가 제한 없이 서비스에 닿는다.
- 한도는 yml 로만 바꾼다(`requests-per-minute` 는 60 의 약수 또는 배수, `burst` ≥ 1 — 어긋나면 기동하지 않는다). 대상 경로를 바꾸려면 `RateLimitPolicy` 를 고친다.
- **JSON 본문 크기 상한은 이 제한에 없다.** 게이트웨이는 본문을 스트리밍으로 넘기고 Tomcat 의 폼 상한은 JSON 에 걸리지 않는다 — §7-4 (#90) 가 맡는다.

### 7-4. 요청 본문 크기 상한 (#90)

서비스(Tomcat)의 `server.tomcat.max-http-form-post-size` 는 폼에만 걸리고 JSON 에는 걸리지 않는다. 요청 수 제한만으로는 한 요청에 큰 본문을 싣는 남용을 막지 못해
게이트웨이가 하류로 넘기기 전에 끊는다(`GatewayRequestBodySizeFilter`). 서비스 쪽 상한은 두지 않는다 — 게이트웨이를 거치지 않는 요청은 사설망 전제(§5-1)가 막는다.

| 설정 (`gateway.request-body.*`) | 기본 | 넘으면 | 근거 |
|--------------------------------|------|--------|------|
| `max-size` | 2MB | 413 `GATEWAY_007` | 가장 큰 정상 요청인 장바구니 병합(50줄 × 입력 20개 × 500자)이 한글 UTF-8 로 약 1.5MB 다. 이 검증 한도를 키우면 상한도 같이 본다 |
| `buffering-budget` | 32MB | 411 `GATEWAY_004` | chunked 본문을 동시에 모으는 총량 — 상한 2MB 면 동시 16개 |
| `body-read-timeout` | 10초 | 408 `GATEWAY_004` | chunked 본문을 다 받기까지의 시간 |
| `discard-max-size` | 4MB (`max-size` 이상 100MB 이하) | 배출하지 않고 바로 끊음 | 상한을 넘은 본문을 413 전에 읽어 버리는 전체 크기 상한 (#145) — Tomcat `maxSwallowSize` 와 같은 역할 |
| `discard-timeout` | 5초 (0 초과 60초 이하) | 그때 413 을 쓰고 끊음 | 배출에 쓰는 시간 상한 (chunked 는 상한을 넘은 때부터 세되 `body-read-timeout` 을 넘지 않는다) |

- **판정 순서** — chunked 인지 먼저 본다. `Content-Length` 와 chunked 가 함께 오면 chunked 가 경계를 정하므로(RFC 9112 §6.3) 길이를 믿지 않고 실제 바이트를 센다
  (하위 계층이 이때 `Content-Length` 를 지우는지에 기대지 않는다). chunked 가 아닐 때만 `Content-Length` 로 정한다.
- **`Content-Length` 가 있으면** 그 값만 보고 정한다. 넘으면 **413 `GATEWAY_007`** 로 끊는다 — 하류에는 넘기지 않고, 413 전에 본문을 버리는 방식은 아래 "큰 본문 거절 시 클라이언트가 받는 것" 이다.
- **chunked 면** 상한까지만 메모리에 모은다. 넘으면 버리고 413, 넘지 않으면 모은 본문을 `Content-Length` 와 함께 넘긴다(`Transfer-Encoding` 은 지운다).
  스트리밍으로 세면서 넘기지 않는다 — 그러면 상한을 넘기 전까지의 앞부분이 이미 서비스에 닿는다. **두 경우 모두 하류에는 한 바이트도 가지 않는다.**
- **둘 다 없으면** HTTP/1.1 에서 본문이 없는 요청이다(RFC 9112 §6.3). 모으지 않고 헤더도 바꾸지 않는다. **HTTP/2 를 켜면 길이 없는 본문이 생기므로 이 판단을 다시 본다**(지금은 켜지 않았다).
- **천천히 보내는 공격(slowloris)** — 인증 없는 연결 여러 개가 chunked 본문을 상한 직전까지 천천히 보내 메모리를 채우지 못하게, 동시에 모으는 총량(넘으면 411 — `Content-Length` 를 붙여 다시 보내라)과
  수신 시간(넘으면 408)을 함께 건다. chunked 본문은 조각마다 배열을 두지 않고 하나의 연속 배열에 이어 쓴다(두 배씩 늘리되 상한에서 멈춘다) —
  1바이트 청크로 보내도 요청당 상주 메모리는 상한 이하이고 늘리는 순간에만 상한의 2배 미만이다. 그래서 chunked 로 쓸 수 있는 메모리는 총량 × 2배 미만이 상한이다.
  하류로 넘길 때 다시 복사하지 않는다.
  정상 클라이언트(브라우저 fetch · BFF)는 `Content-Length` 를 보내 이 경로를 타지 않는다.
- **업로드 특례를 전역으로 두지 않는다.** `Content-Type: multipart/form-data` 만 바꿔 상한을 올릴 수 없게 한다.
  상품 이미지는 presigned PUT 으로 브라우저가 스토리지에 직접 올린다(#9 D1) — 게이트웨이를 지나지 않아 라우트 예외가 없다.
  교차 출처 허용은 버킷 CORS 가 맡는다(`bootstrap-conventions.md` Storage).
- 요청 가드 바로 뒤, CORS · 보안 체인보다 앞이다 — 큰 본문은 토큰 검증 전에 끊는다. 그래서 이 거절에는 CORS 헤더가 없다(요청 가드의 400 · 405 와 같다). BFF 는 서버에서 부르므로 영향이 없다.
- **큰 본문 거절 시 클라이언트가 받는 것 (#145).** 거절 응답은 `Connection: close` 로 닫는다. Reactor Netty 는 응답을 다 쓰자마자 채널을 닫는데,
  아직 들어오는 본문이 수신 버퍼에 남아 있으면 커널이 FIN 대신 RST 를 보내 클라이언트는 413 대신 연결 리셋(`PrematureCloseException` 등)을 본다 — BFF 는 이것을 `BFF_002` 503 으로 바꾼다.
  - 그래서 전체 본문이 `discard-max-size` 이하면 `discard-timeout` 안에서 끝까지 읽어 버린 뒤(받는 즉시 해제 — 메모리를 잡지 않는다) 413 을 쓴다.
    **이 범위의 클라이언트는 항상 413 `GATEWAY_007` 을 받는다.** 응답을 먼저 쓰고 배출하는 방식은 Reactor Netty 가 응답 직후 채널을 닫아 효과가 없다.
  - chunked 는 상한을 넘는 순간 모은 본문을 버리고 동시 수신 총량(`buffering-budget`) 자리를 바로 돌려준 뒤 같은 상한까지 세기만 한다 — 배출 중인 연결이 총량을 잡아 다른 요청을 411 로 막지 않게.
    배출 시간은 상한을 넘은 때부터 세지만, 요청 전체에 걸린 `body-read-timeout` 이 먼저 끝나면 배출도 거기서 잘린다 — 이미 상한을 넘었으므로 그때도 408 이 아니라 413 이다.
  - `discard-max-size` 를 넘거나 배출 시간이 지나면 바로 끊는다 — 남용 쪽이므로 **클라이언트는 413 을 읽거나 연결 오류를 본다(타이밍에 달렸다).** 하류에는 어느 쪽이든 닿지 않는다.
  - `Expect: 100-continue` 요청은 배출하지 않는다(본문을 구독하면 100 이 나간다) — 본문이 오기 전에 413 으로 끝난다.
    `Expect` 값 중 하나라도 100-continue 면(대소문자 무시) 그렇게 본다 — Reactor Netty 가 100 을 보낼지 정하는 판정과 맞춘다.
  - 배출 중 클라이언트가 끊어 읽기가 실패하면 413 을 쓰지 못해도 오류로 올리지 않는다(debug 로그만).
  - 배출 중인 연결은 최대 `discard-timeout` · `discard-max-size` 만큼 대역폭을 쓰지만 메모리는 잡지 않는다. 느린 연결 수 자체의 상한은 LB 의 몫이다(아래).
  - 상한(`max-size`)을 올리면 `discard-max-size` 도 함께 본다.
- 413 · 408 · 411 처리는 본문을 모으는 단계에만 건다 — 하류(코덱 등)에서 난 같은 종류의 오류를 이 코드로 바꾸지 않는다.
- 상한과 배출 상한은 100MB 이하, 총량과 배출 상한은 상한 이상, 수신 시간은 0 초과, 배출 시간은 0 초과 60초 이하여야 한다(어긋나면 기동하지 않는다).
- **정책 밖 전역 익명 요청 폭주는 LB 에서 막는다(#135 결정).** 게이트웨이 IP 단위 제한은 기본 꺼짐이고, 켜도 `RateLimitPolicy` 의 쓰기 경로만 센다(§7-3).
  조회 · `/api/v1/auth/**` 를 포함한 경로 전체의 익명 폭주와 느린 연결 수는 앞단 LB(L7 rate limit · WAF · 연결 수 상한)가 맡고, 게이트웨이 정책은 더하지 않는다.
  로그인 · 회원가입 시도는 auth 의 이메일 카운터와 `X-Client-Subject` IP 차원(#66, §7-3 하류 헤더 계약)이 맡는다.
  LB 설정은 배포 환경(`service-inventory.md` §5)이 정해질 때 함께 정한다.

## 8. 로그

- JWT 원문 · 키 · 예외 메시지(토큰 조각이 섞일 수 있다)를 남기지 않는다. 남기는 것은 코드 · 경로 · 예외 타입, 로그인한 요청이면 memberId · jti 다.
- `IssuedAccessToken` 과 `JwtIssuanceProperties` 의 `toString()` 은 토큰 · 개인키를 가린다. `InternalTokenProperties` 의 `toString()` 은 비밀을 가린다.
- 서비스 인증 실패(007)와 서비스의 허용 목록 거부(005)는 **warn** 이다 — 정상 배포에서는 일어나지 않고 키 · 설정 실수나 사설망 안의 위조를 드러낸다.
  거부 로그에는 호출 서비스 · jti 를 남긴다. 서비스 토큰 원문 · 비밀 · 비밀에서 계산한 값(지문)은 남기지 않는다.
- internal 체인의 006 은 **error** 다 — 서비스 토큰 검증은 메모리의 비밀만 써서 저장소 장애가 있을 수 없다. 나면 서버 결함 신호다(사용자 체인의 006 은 warn).
- 보안 거부 로그의 경로는 **앞 세 구간만** 남기고 나머지를 가린다 — `/internal/v1/orders/4611686018427387904` → `/internal/v1/orders/…`.
  internal · 고객 경로에 주문번호 같은 식별자가 들어가기 때문이다. 구현은 common-core `LogPathMask` 이고 `SecurityErrorResponder#maskPath` · 게이트웨이가 같이 쓴다 — 남기는 구간의 `;` 도 `;…` 다(#291).
- 게이트웨이의 거부 로그 수준은 서비스와 같다 — 001 · 002 · 003 · 004 는 debug, 006 은 warn, 로그인한 회원의 005 는 info(memberId · jti).
- **차단 목록 fail-open 통과 로그**(§7-1)는 warn 이지만 **1분에 한 번만** warn 이고 나머지는 debug 다 — 장애 동안 토큰 붙은 요청마다 warn 을 쓰면 로그가 폭주한다.
  jti · 메서드 · 마스킹 경로 · 예외 타입만 남긴다(`revocation list unavailable, token passed (fail-open)`). 어드민 경로의 006 은 응답 로그(warn)로 남는다.
- **auth 토큰 저장소(Redis) 장애 로그**(§2-3 · 503 `AUTH_013`)도 **1분에 한 번만** warn 이고 나머지는 debug 다(#234) — 로그인 · 가입 · 재발급 · 로그아웃이 모두
  Redis 를 불러 장애 동안 요청 하나가 warn 한 줄이 되기 때문이다. refresh 토큰 · 차단 목록 쓰기 · 시도 계수 세 어댑터가 `TokenStoreRedisSupport` 빈 하나를
  같이 써서 간격을 공유한다(같은 Redis 의 공통 장애라 어댑터별로 세지 않는다). 간격은 CAS 로 잡아 동시에 실패해도 간격당 warn 은 한 건이다.
  예외 타입만 남긴다 — warn `token store unavailable: <예외 타입>, nextWarnIn=PT1M`, debug `token store unavailable: <예외 타입>`.
  예외 메시지 · 키 · 이메일 · 지문은 어느 레벨에도 남기지 않는다(메시지에 키가 섞일 수 있다).

## 9. 서비스 간 인증 (internal API, 1단계)

결정은 #43(2026-10-16), 구현은 #51 이다. internal API(`/internal/v1/**`)는 **서비스 토큰**이 있어야 열리고, 엔드포인트마다 허용 호출자를 403 으로 강제한다.

### 9-1. 방식

- 서비스 전부가 **같은 HS256 비밀**(`INTERNAL_JWT_SECRET`)로 짧은 서비스 토큰에 서명하고, 받는 쪽이 같은 비밀로 검증한다. Nimbus(security-core)를 그대로 쓴다.
- auth-service 가 서비스 토큰을 발급하는 방식은 쓰지 않는다 — auth 가 멈추면 주문까지 막힌다. mTLS 를 직접 운영하는 방식도 쓰지 않는다 — 인증서 만료 관리 부담.
- 게이트웨이는 internal API 를 부르지도 받지도 않으므로 비밀을 갖지 않는다.

### 9-2. 토큰 형식

| 자리 | 값 | 받는 쪽 검증 (`InternalTokenDecoderFactory`) |
|------|----|------------------------------------------|
| `alg` | `HS256` | HS256 만. RS256 사용자 access 토큰 · `alg=none` · 같은 비밀의 HS384/HS512 는 서명 단계에서 거부 |
| `typ` | `svc+jwt` | 같은 값(또는 `application/svc+jwt`)만. 없거나 `JWT` · `at+jwt` 면 거부 — access 토큰과 서로 섞이지 않는다 |
| `kid` | 없음 | 현재 · 이전 비밀을 모두 시도한다. 비밀에서 계산한 값을 헤더에 싣지 않는다 |
| `iss` | 호출 서비스 ID (`order-service`) | 있어야 하고 서비스 ID 모양(소문자 kebab)이어야 한다. 권한 `SERVICE_<iss>` 가 된다 |
| `aud` | 대상 서비스 ID (`product-service`) | **받는 서비스의 ID 가 들어 있어야 한다.** 다른 서비스로 가는 토큰을 쓸 수 없다 |
| `iat` · `exp` | 발급 시각 · +60초 (`security.internal.token-ttl`) | 둘 다 있어야 한다. 만료 · `iat` 가 미래인지는 시계 오차 **30초**로 판정, 수명(`exp − iat`)은 **5분 이하** |
| `jti` | 요청마다 새 UUID | 있어야 한다. 재사용 검사는 하지 않는다 (§9-8) |

- 서비스 ID 는 Eureka 서비스 ID = `spring.application.name` 이다. 호출 쪽 `aud` 는 Feign 대상 이름에서 오므로 받는 쪽의 `spring.application.name` 과 같아야 한다.
- 수명 상한은 발급 설정과 무관하게 받는 쪽이 건다 — 비밀을 가진 쪽이 `exp` 를 멀리 잡아 서명해도 받지 않는다.

### 9-3. 받는 쪽 — internal 체인

`InternalResourceServerConfigurer`(서비스 `*BeansConfig` 가 import)가 `securityMatcher("/internal/**")` 전용 체인을 만든다.

- 사용자 체인보다 **앞 순서**(`@Order(HIGHEST_PRECEDENCE)`)다. 사용자 체인은 모든 요청에 걸리므로 마지막이어야 한다 — 뒤집히면 Spring Security 가 기동을 멈춘다.
- 모든 요청이 인증을 요구한다(`anyRequest().authenticated()`). 매핑 없는 internal 경로도 인증부터 한다.
- 인증 실패는 전부 **401 · `SECURITY_007`**, 허용 목록 거부는 **403 · `SECURITY_005`**, 검증 중 시스템 오류만 503 · 006 이다. 봉투는 사용자 체인과 같다(`SecurityErrorEnvelope`).
  006 은 메모리 비밀만 쓰는 검증에서 나므로 서버 결함 신호로 보고 error 로그를 남긴다(§8). Bearer 필터의 실패 처리기는 다시 던지지 않는다 — 봉투 없는 500 이 되지 않게.
- STATELESS. CSRF · 폼 로그인 · Basic · 로그아웃 · CORS · 요청 캐시를 끈다.
- 디코더는 체인 안에서만 만든다. **두 번째 `JwtDecoder` 빈을 두지 않는다** — 사용자 체인의 `accessTokenDecoder` 주입과 Boot 자동설정이 빈 하나를 전제로 한다.
- 사용자 체인은 `/internal/**` 을 `denyAll` 한다(§5-1). 이 Configurer 를 빠뜨려도 internal 이 열리지 않고 001 · 005 로 닫힌다. 거꾸로 이 Configurer 만 import 하면
  `ResourceServerSecurityConfigurer` 를 함께 가져온다 — internal 밖의 경로가 체인 없이 열리지 않게.
- 서비스 토큰은 사용자 체인의 RS256 디코더를 통과하지 못한다 — `/api/**` 에 서비스 토큰을 보내면 003 이다.

### 9-4. 호출자 허용 목록 (필수)

internal 컨트롤러의 **모든 핸들러 메서드**에 허용 호출자를 선언한다. 체인은 "서비스 토큰이 있다" 까지만 보므로, 선언이 없으면 어느 서비스나 부를 수 있다.

```java
@PreAuthorize("hasAuthority('SERVICE_order-service')")
@PostMapping("/internal/v1/stock-reservations")
public ResponseEntity<Response<StockReservationResponse>> reserve(@RequestBody @Valid StockReservationRequest request) {
    return ResponseEntity.ok().body(Response.success(stockInternalUseCase.reserve(request.toCommand())));
}
```

- 여럿이면 `hasAnyAuthority('SERVICE_order-service', 'SERVICE_payment-service')`. `isAuthenticated()` · `permitAll()` 은 쓰지 않는다.
- 호출 서비스가 필요하면 `@AuthenticationPrincipal ServicePrincipal caller` 로 받는다(`caller.serviceId()`). application 계층으로 넘기지 않는다.
- 사용자 식별이 필요하면 memberId 를 요청 본문 · 파라미터로 받는다 — 호출 서비스가 사용자 JWT 로 이미 검증한 값이다. 사용자 토큰은 internal 로 오지 않는다.
- **빠뜨린 메서드는 서비스 인가 스모크가 잡는다**(§9-10) — 서비스의 실제 MVC 매핑을 모두 돌며, `/internal/` 핸들러의 `@PreAuthorize`(메서드, 없으면 클래스) 식이
  서비스 권한만 나열한 `hasAuthority('SERVICE_…')` · `hasAnyAuthority('SERVICE_…', ...)` 그 자체인지 본다. `or isAuthenticated()` 처럼 넓힌 식도 실패다.
  ArchUnit(§9-11)은 패키지 기준으로 같은 식을 보고, 정적 규칙(전역 `RequestInterceptor` · `Capability` 빈, `RequestContextHolder` 로 사용자 토큰 꺼내기)을 맡는다.
- **1단계에서 호출자는 "선언" 이다.** 비밀이 하나라 비밀을 가진 서비스는 어느 `iss` 로도 서명할 수 있다. 허용 목록은 설정 실수 · 버그로 엉뚱한 서비스가
  부르는 것을 막고, 2단계(§9-9)에서 같은 선언이 "증명" 이 된다.

### 9-5. 호출 쪽 — Feign Capability

```java
@FeignClient(
    name = "${feign-client.target-services.product-service:product-service}",
    contextId = "productStockClient",
    configuration = InternalFeignClientConfiguration.class)
public interface ProductStockClient { ... }
```

- `InternalFeignClientConfiguration`(security-core `internal.client`)이 그 클라이언트의 자식 컨텍스트에 Feign `Capability`(`InternalServiceTokenCapability`)
  빈을 둔다. Spring Cloud OpenFeign 의 `FeignClientFactoryBean#configureUsingConfiguration` 이 클라이언트 컨텍스트의 `Capability` 빈을 정렬해
  `Feign.Builder#addCapability` 한다. 서비스 `*BeansConfig` 는 `InternalTokenIssuerConfigurer` 를 import 한다(발급기 빈 — 빠뜨리면 클라이언트를 만들 때 기동이 멈춘다).
- **정규화는 최종 요청 단계에서 한다.** Capability 가 감싼 `Client` 는 인터셉터 · 대상 URL 적용이 모두 끝난 `Request` 를 받아 `Authorization` 을
  대소문자와 무관하게 **전부 지우고** `Bearer <서비스 토큰>` 하나만 싣는다. 요청 인터셉터로 하면 자기보다 뒤에 붙은 인터셉터가 넣은 값을 지우지 못해
  헤더가 둘이 되고, 받는 쪽은 첫 헤더만 읽어 사용자 토큰 · 외부 키가 internal 로 새고도 조용히 성공할 수 있다.
  Capability 는 `HIGHEST_PRECEDENCE` 라 가장 먼저 더해져 실제 Client(로드밸런서)를 바로 감싼다 — 다른 Capability 가 Client 단계에서 더한 값까지 정리한다.
- `aud` 는 Feign 대상 이름(`request.requestTemplate().feignTarget().name()`)이다. 비면 요청을 보내지 않고 실패하고, 서비스 ID 모양(소문자 kebab)이 아니면 발급기가 거부한다.
  **kebab 이름의 외부 클라이언트(`name = "toss-payments", url = ...`)에 붙이면 막지 못한다** — URL 검사는 로컬 url override 를 막으므로 두지 않는다. 리뷰가 본다.
- **모든 클라이언트에 붙는 설정을 쓰지 않는다** — 전역 `RequestInterceptor` · `Capability` 빈, `@EnableFeignClients(defaultConfiguration = ...)`,
  `spring.cloud.openfeign.client.config.default.request-interceptors`. 외부 API(토스 등)에 사용자 토큰 · 서비스 토큰이 실리거나 그쪽 `Authorization` 을 덮어쓴다.
  internal 클라이언트에 붙은 값은 위 정규화가 지우지만 외부 클라이언트에는 정규화가 없다. 외부 클라이언트에 `InternalFeignClientConfiguration` 을 붙이지 않는다.
- **요청마다 새로 서명한다.** 캐시하지 않고 401 을 받아도 다시 발급해 재시도하지 않는다 — HS256 서명은 마이크로초 단위라 캐시 이득이 없고,
  캐시가 없으면 만료 경계 · 키 교체 직후의 낡은 토큰 문제가 사라진다. 받는 쪽 401 · 007 은 설정 · 키 문제이므로 재시도로 풀리지 않는다.
- 사용자 토큰을 `RequestContextHolder` 로 꺼내 internal 요청에 싣지 않는다 (`coding-conventions.md` §10).
- 같은 최종 요청에서 `X-Correlation-Id` 도 갈아 끼운다 (#284). 요청에 있던 값은 지우고, 호출 스레드 MDC 의 검증된 상관 id 만 하나 싣는다. MDC 가 없으면 헤더를 만들지 않는다.
  사용자 토큰 · 외부 키를 이 헤더에 담지 않는다. 받는 서비스는 형식이 틀린 헤더를 버리고 새로 만든다 (`coding-conventions.md` §8).
- Feign 은 security-core 가 `compileOnly` 로만 둔다. Feign 클라이언트가 생기는 서비스가 openfeign 스타터를 직접 선언한다(#12 · #13).

### 9-6. 설정 · 비밀 만들기

```yaml
security:
  internal:
    service-id: ${spring.application.name}            # 보내는 토큰의 iss, 받는 토큰의 aud
    secret: ${INTERNAL_JWT_SECRET}                    # 기본값 없음 — 비면 기동하지 않는다
    previous-secret: ${INTERNAL_JWT_SECRET_PREVIOUS:} # 교체 중에만
    # token-ttl: 60s                                  # 기본 60초, 0 초과 5분 이하
```

```bash
openssl rand -base64 32    # INTERNAL_JWT_SECRET — 표준 base64, 디코드하면 32바이트
```

- 값은 Vault 에 두고 서비스 전부가 **같은 값**을 받는다. 저장소 밖에서 만들고 터미널 기록에 남기지 않는다 (`secret-management.md`).

| 기동을 멈추는 조건 | 메시지가 가리키는 것 |
|--------------------|----------------------|
| 비밀이 비었거나 `${INTERNAL_JWT_SECRET}` 가 풀리지 않았다 | `INTERNAL_JWT_SECRET` |
| 비밀이 base64 가 아니다 · 디코드하면 32바이트 미만이다 | 해당 환경변수 · `openssl rand -base64 32` |
| `previous-secret` 을 채웠는데 위 규칙에 어긋난다 | `INTERNAL_JWT_SECRET_PREVIOUS` |
| `service-id` 가 비었다 · 플레이스홀더가 풀리지 않았다 · 소문자 kebab 이 아니다 | `security.internal.service-id` |
| `token-ttl` 이 0 이하이거나 5분을 넘는다 | `security.internal.token-ttl` |

메시지에는 프로퍼티 · 환경변수 이름만 있고 비밀 내용은 없다(바인딩 실패 진단의 `Value` 에도 비밀이 실리지 않는 것을 테스트가 본다).

### 9-7. 비밀 교체 런북

받는 쪽이 새 비밀을 **먼저** 받아들인 뒤 서명 비밀을 바꾼다. 서비스 전부가 같은 값을 쓴다.

| 단계 | 서비스 전부 | 결과 |
|------|-----------|------|
| ① 새 비밀 수락 | `PREVIOUS` ← **새** 비밀. `SECRET` 은 그대로(옛 비밀로 계속 서명) | 롤링 재기동. 모두가 옛 · 새 비밀 토큰을 받는다 |
| ② 서명 비밀 교체 | `SECRET` ← 새 비밀, `PREVIOUS` ← 옛 비밀 | 롤링 재기동. 옛 · 새 인스턴스가 서로의 토큰을 받는다 |
| ③ 정리 | `PREVIOUS` 비움 | ② 완료 후 토큰 수명 + 시계 오차(기본 90초)가 지나면 롤링 재기동 |

- 롤링 중 401 이 나지 않는 이유: 어느 단계에서든 서명에 쓰이는 비밀(①은 옛 것, ②는 새 것)을 그 시점에 떠 있는 모든 인스턴스가 이미 받아들이고 있다.
- ①을 건너뛰고 ②를 하면, 아직 새 비밀이 없는 인스턴스가 새 비밀 토큰을 007 로 거부한다.
- `PREVIOUS` 가 채워져 있는 동안 기동 로그에 `internal service token previous secret slot is in use` WARN 이 한 줄 남는다. ③을 잊은 곳을 찾는 신호다.
- 비밀이 유출됐으면 단계를 기다리지 않는다 — 새 비밀로 `SECRET` 을 바꾸고 `PREVIOUS` 를 비운 채 전 서비스를 바로 재기동한다(교체 중 잠시 007 을 감수한다).

### 9-8. 1단계의 한계

- **호출자는 선언이다** (§9-4). 비밀 하나가 새면 어느 서비스로든 위장할 수 있다 — 비밀은 Vault 에만 두고, 사설망 전제(§5-1)를 유지한다.
- **`jti` 재사용 검사를 하지 않는다.** 수명(60초 + 오차) 안에서 가로챈 토큰을 다시 보낼 수 있다. 사설망 전제와, 상태를 바꾸는 internal API 의
  멱등 처리(재고 선점 키 · 결제 완료 재전송)로 막는다. 필요해지면 Redis 에 `jti` 를 수명만큼 두는 방식을 2단계와 함께 본다.
- 토큰은 요청 본문에 묶이지 않는다(서명 범위는 헤더 · claim 뿐).

### 9-9. 2단계 (예정)

- 배포 환경이 정해지면(`service-inventory.md` §5) **서비스별 RS256 키** 또는 **플랫폼 mTLS** 로 바꾼다. 그때 호출자가 "증명" 된다.
  Eureka 레지스트리 인증(§12, 공유 비밀번호 Basic)도 이때 서비스별 신원 · mTLS 로 함께 본다.
- 바꾸는 것은 검증기 · 발급기뿐이다. 주체(`ServicePrincipal`) · 권한 이름(`SERVICE_<service-id>`) · 허용 목록 선언 · Feign Capability 경계 · 오류 코드는 그대로 둔다.

### 9-10. 테스트

- 비밀은 커밋하지 않는다. `TestServiceTokens`(테스트 픽스처)가 실행 중 32바이트 난수를 만들고, `SecurityTestPropertiesInitializer` 가 컨텍스트에 넣는다(§5-3 테스트 키).
  `TestServiceTokens.bearer(caller, audience)` 로 서비스 토큰을 만든다.
- 서비스 스모크(`ResourceServerSmokeTestSupport`)는 테스트 전용 핸들러 `/internal/v1/__security-smoke`(허용 호출자 `smoke-allowed-caller` 고정)로 본다.
  이 핸들러는 픽스처의 `InternalSecuritySmokeContextCustomizerFactory`(spring.factories)가 **모든 서블릿 테스트 컨텍스트**에 등록한다 —
  모든 테스트에 같은 커스터마이저라 `*ApplicationTests` 와 스모크가 컨텍스트를 계속 같이 쓴다. 리액티브 컨텍스트에는 등록하지 않는다.
- **인가 선언 누락 가드** — 같은 스모크의 `handlersDeclareAuthorization` 이 서비스 컨텍스트의 `RequestMappingHandlerMapping` 을 모두 돈다.
  `/internal/` 핸들러는 서비스 권한만 나열한 `hasAuthority` · `hasAnyAuthority` 식, `/api/v1/admin` 과 그 아래 핸들러는 `hasRole('ADMIN')` 필요조건, 그 밖의 `/api/` 핸들러는 `@PreAuthorize` 존재(식은 자유)를 요구하고 위반 핸들러를 고치는 법과 함께 목록으로 알린다.
  스모크 핸들러가 검사 대상에 들어 있는지도 단언해, 실제 핸들러가 없는 지금도 빈 목록으로 통과하지 않는다. 허용 호출자 식 판정은 `AuthorizationExpressions` 하나를 ArchUnit(§9-11)과 같이 쓴다.
- 호출 쪽은 `InternalServiceTokenCapabilityTest` 가 실제 Feign 파이프라인(`Feign.builder()` + 앞 · 뒤 인터셉터 + 다른 Capability + 요청을 붙잡는 Client)을
  왕복해 최종 요청의 `Authorization` 이 서비스 토큰 하나인지 본다.
- internal 컨트롤러가 생기면 그 서비스의 `*SecuritySmokeTest` 나 컨트롤러 테스트에서 실제 경로의 허용 · 거부 호출자를 하나씩 본다.

### 9-11. 정적 규칙 (ArchUnit, #42)

서비스마다 `src/test/java/com/commercefoundry/<Svc>ArchitectureTest.java` 한 파일이 security-core 테스트 픽스처의
`ControllerAuthorizationArchTestSupport` 를 상속하고, common-core 테스트 픽스처의 계층 규칙 `HexagonalArchitectureRules`(`architecture-guide.md` §3)를 implements 하며,
`applicationClass()` 하나를 **public** 으로 구현한다(인터페이스 메서드라 public 이어야 한다 — `implements` 를 빠뜨리면 계층 규칙만 조용히 꺼진다). 서비스의 **main 클래스 위치만** 읽어 검사하고(core jar · 테스트 클래스 제외),
Spring 컨텍스트를 띄우지 않는다. `./gradlew check` 에서 돈다. 새 서비스를 만들면 이 한 파일을 더한다. api-gateway 는 MVC 컨트롤러가 없는 리액티브라 적용하지 않는다.

| 규칙 | 대상 | 위반하면 이렇게 고친다 |
|------|------|------------------------|
| R0 컨트롤러 위치 | `@Controller`(메타 포함, `@RestController` 포함 · `@(Rest)ControllerAdvice` 제외) 클래스 | `adapter.in.web`(고객) · `adapter.in.admin`(어드민) · `adapter.in.internal`(서비스 간) · `adapter.in.webhook`(외부 콜백) 중 하나로 옮긴다 |
| R7 매핑은 컨트롤러에 직접 | web · admin · internal 컨트롤러의 상위 클래스 · 인터페이스 | springdoc `XxxApi` 인터페이스나 추상 기반 컨트롤러에 매핑을 두지 않는다. 매핑 · `@PreAuthorize` · Swagger 애노테이션을 컨트롤러 메서드에 직접 둔다 (상속받은 핸들러는 R1~R3 이 보지 못한다) |
| R1 핸들러 인가 선언 | `..adapter.in.web..` · `..adapter.in.admin..` 의 `@Controller`(·`@RestController`) 매핑 메서드 | 보고된 `클래스#메서드` **메서드에** `@PreAuthorize` 를 단다 — 공개 `permitAll()`, 로그인 `isAuthenticated()`, 어드민 `hasRole('ADMIN')`. 클래스 선언은 대신하지 않는다 |
| R2 어드민은 ADMIN 필요 | `..adapter.in.admin..` 핸들러 | `hasRole('ADMIN')` 또는 `hasRole('ADMIN') and <추가 조건>`. `permitAll()` · `isAuthenticated()` · 괄호 밖 `or` · `\|\|` · 삼항은 실패다. 고객도 부르는 API 면 admin 이 아니라 web 패키지에 따로 만든다(DTO 공유 금지) |
| R3 internal 허용 호출자 | `..adapter.in.internal..` 핸들러(메서드, 없으면 클래스) | `hasAuthority('SERVICE_<호출 서비스>')` 또는 `hasAnyAuthority('SERVICE_a', 'SERVICE_b')` **그 자체**. `isAuthenticated()` · `or` 금지 (§9-4) |
| R4 `RequestContextHolder` 금지 | 서비스 main 전체 | 사용자 토큰 · 헤더를 꺼내 넘기지 않는다. memberId 를 본문 · 파라미터로 넘긴다 (§9-4, `coding-conventions.md` §10) |
| R5 Feign 전역 빈 금지 | `feign.RequestInterceptor` · `feign.Capability`(구현 포함)를 반환하는 `@Bean`, 그리고 그 둘을 구현한 `@Component`(메타 포함 — `@Configuration` · `@Service` 등) 클래스 | 전역 빈 · 스캔되는 구현을 지운다(OpenFeign 은 컨텍스트의 인터셉터를 모든 클라이언트에 붙인다). internal 클라이언트는 `@FeignClient(configuration = InternalFeignClientConfiguration.class)` 로 붙이고, 그 configuration 클래스에도 `@Configuration` 을 달지 않는다 (§9-5) |
| R6 기본 설정 금지 | `@EnableFeignClients(defaultConfiguration = ...)` — 메타(합성) 애노테이션 포함 | 지우고 클라이언트마다 `configuration` 으로 붙인다 |

- 런타임 가드(§9-10 스모크, **경로 기준**)와 정적 가드(ArchUnit, **패키지 기준**)는 서로를 대신하지 않는다.
  스모크는 실제 매핑(상속 · 인터페이스로 받은 것 포함)을 보고 `/internal/` 은 허용 호출자 식 그 자체, **`/api/v1/admin` 과 그 아래는 `hasRole('ADMIN')` 필요조건(R2 와 같은 판정)**,
  그 밖의 `/api/` 는 선언 존재를 요구한다 — `adapter.in.web.admin` 처럼 패키지로는 R2 를 비켜 간 어드민 핸들러를 여기서 잡는다.
  ArchUnit 은 `/internal/` 이 아닌 경로로 매핑된 internal 패키지 컨트롤러, 어드민 경로가 아닌 admin 패키지 핸들러를 잡는다.
- 식은 SpEL 을 파싱하지 않고 문자열 모양으로 본다. 애매한 모양은 실패 쪽으로 판정한다 — **식에 큰따옴표 문자열과 `{...}` 템플릿 자리표시자를 쓰지 않는다**(문자열은 작은따옴표).
  막히면 식을 위 표의 표준 형태로 고친다.
- `adapter.in.webhook` 은 R1~R3 · R7 대상이 아니다. 경로가 `/api/` 면 런타임 가드가 `@PreAuthorize` 를 요구하므로 공개 수신은 `permitAll()` 을 적는다.
- 알려진 한계
  - R4 는 `RequestContextHolder` 만 막는다. `SecurityContextHolder` 의 토큰 값 · `@RequestHeader(AUTHORIZATION)` 를 Feign 파라미터로 넘기는 경로는 리뷰로 본다(§9-4).
  - yml 의 `spring.cloud.openfeign.client.config.default.*` 는 보지 않는다(리뷰로 본다).
  - 외부 API(PG 등) 클라이언트 전용 인터셉터도 서비스에 `@Bean` 으로 두면 R5 에 걸린다 — 외부 클라이언트 이슈에서 예외 방식을 정한다.
  - 정적 규칙은 서비스 모듈만 읽는다. 컨트롤러는 core 모듈에 두지 않는다.
- 헥사고날 계층 의존 규칙은 #77 에서 다룬다 — 같은 지원 클래스에 더할지, 이름에 맞게 별도 클래스로 나눌지는 그 이슈에서 정한다.

## 10. 계정 존재 노출 (회원가입 · 로그인, #4)

| 경로 | 응답 | 판단 |
|------|------|------|
| 회원가입 `POST /api/v1/auth/sign-up` | 이미 가입된 이메일이면 409 `AUTH_002` | **계정 존재를 드러낸다 — UX 를 위해 감수하는 위험이다.** "이미 가입된 이메일" 을 알려 주지 않으면 사용자가 왜 가입이 안 되는지 모른다. 그 대신 이메일로 가입 여부를 하나씩 확인(열거)할 수 있다 |
| 로그인 `POST /api/v1/auth/login` (#4 A3) | 이메일 없음 · 비밀번호 틀림 · 관리자 계정 모두 400 `AUTH_001` 같은 문구 | 숨긴다. 이메일이 없을 때도 더미 해시(기동 때 같은 인코더로 만든 BCrypt)로 비교하고, 관리자 계정도 비교한 뒤 거절해 응답 시간 차이를 없앤다 (`MemberLoginProcessor`) |
| 관리자 로그인 `POST /api/v1/auth/admin/login` (#6) | 이메일 없음 · 비밀번호 틀림 · 고객 계정 모두 400 `AUTH_030` 같은 문구 | 숨긴다. 고객 로그인의 거울이다 — 더미 해시로 비교하고 고객 계정도 비교한 뒤 거절한다(`MemberLoginProcessor#authenticateAdmin`) |

- 회원가입은 비밀번호 해시를 중복 확인보다 **먼저** 한다(`MemberWebFacade#signUp`) — 409 응답이 해시를 건너뛰어 빨리 끝나면 응답 시간으로도 존재가 드러나기 때문이다.
  409 자체가 존재를 알리므로 이것은 시간 차이를 더하지 않게 하는 것뿐이다.
- 열거 · 무차별 대입 완화(가입 · 로그인 요청 제한, 계정 잠금)는 후속 #66 에서 다룬다. 그때까지 가입 409 로 가입 여부를 대량으로 확인할 수 있다.
  관리자 로그인(#6)도 #66 의 대상이다 — 그때까지는 관리자 계정을 노린 실패가 감사 로그(§11-4 `target=member:<id>`)로만 드러난다.
- 타인 리소스는 반대로 **존재를 숨긴다** — 배송지(#7)는 (주소 아이디, JWT memberId) 로만 찾고, 없거나 남의 주소면 GET · PUT · DELETE · 기본 지정 모두 같은 404 `AUTH_020` 이다.
  배송지의 받는 사람 · 연락처 · 주소는 `toString` · 로그 · 예외 메시지에 싣지 않는다 (§8).

## 11. 관리자 계정 (#6)

### 11-1. 로그인 · 토큰

- 경로는 `POST /api/v1/auth/admin/login`(`permitAll()`, `adapter/in/web` 의 `AdminAuthWebController`)이다. `/api/v1/admin/**` 아래에 두지 않는다 —
  게이트웨이(§7) · 서비스(§5-1) URL 규칙이 그 경로에 ADMIN 토큰을 요구해 토큰을 받으러 오는 익명 요청이 닿지 못한다 (`api-design-guide.md` §1-2).
  소셜 로그인은 없다. 실패는 §10 과 같은 규칙으로 400 `AUTH_030` 하나다. 64자를 넘는 비밀번호도 필드 검증이 아니라 같은 `AUTH_030` 이다
  (비교는 자리값으로 한 번 해 시간도 같다 — `MemberLoginProcessor#authenticateAdmin`).
- **알려진 위험 — 관리자 로그인에 요청 제한 · 계정 잠금이 없다.** 게이트웨이 요청 제한(§7-3)은 로그인 경로를 세지 않고, 로그인 시도 제한은 #66 이 맡는다 —
  **#66 에서 관리자 로그인(`/api/v1/auth/admin/login`)도 대상으로 한다.** 그때까지는 대입 시도가 감사 로그(§11-4, `target=member:<id> result=FAILURE`)로만 드러나므로
  운영은 그 줄의 빈도를 경보로 건다. 관리자 비밀번호를 12자 이상으로 둔 것(§11-3)이 그동안의 완화다.
- **access 토큰만 낸다(refresh 없음).** 관리자 세션은 access 수명까지이고, 만료되면 다시 로그인한다 — 재발급 경로(`/token/reissue`)는 고객만 받으므로
  refresh family 를 만들어도 쓰지 못할 키만 남는다. 로그아웃은 고객과 같은 `POST /api/v1/auth/logout` 을 본문 없이 부른다(jti 차단, §7-1).
- **TTL 은 고객과 같은 `security.jwt.issuance.access-token-ttl`(기본 30분, 최대 1시간)이다 — 관리자 전용으로 줄이지 않았다.** 발급기(security-core
  `JwtIssuer`)가 역할별 TTL 을 받지 않아 줄이려면 core 를 바꿔야 하고, refresh 가 없어 30분이 곧 관리자 세션 상한이라 이미 고객(14일 refresh)보다 짧다.
  더 줄이려면(예: 15분) 발급기에 역할별 TTL 을 더하는 후속 이슈로 한다.
- 고객 로그인(`/api/v1/auth/login`)은 관리자 계정을 `AUTH_001` 로, 관리자 로그인은 고객 계정을 `AUTH_030` 으로 거절한다 — 한 계정이 두 종류 토큰을 받지 않는다.

### 11-2. 식별자 공간 (`sub`)

- 관리자와 고객은 **같은 `auth.member` 테이블 · 같은 Snowflake 아이디 공간**이고 `role` 칸(`CUSTOMER` · `ADMIN`)으로만 나뉜다 (#4 결정 4).
  access 토큰의 `sub` 은 어느 쪽이든 `member.id` 이고 `role` claim 이 같은 행의 값이다 — 관리자 테이블을 따로 두지 않으므로 `sub` 이 겹칠 일이 없다.
- 이메일 유니크(`uk_member_email`)도 공유한다. 한 이메일은 고객이거나 관리자이고 둘 다일 수 없다. 역할을 바꾸는 API 는 없다.
- 감사 · 이력에 남는 "조정한 관리자"(재고 조정 `adminMemberId` 등)도 같은 `member.id` 다.

### 11-3. 초기 관리자 시드

| 설정 (`auth.admin-seed.*`) | 환경변수 | 규칙 |
|-----------------------------|----------|------|
| `email` | `ADMIN_SEED_EMAIL` | 비면 시드하지 않는다. 254자 이하 · 도메인에 점이 있는 형식, 소문자로 저장 |
| `password` | `ADMIN_SEED_PASSWORD` | 12~64자, 영문 · 숫자를 1자 이상씩 포함한 공백 없는 ASCII(가입은 8자 — 관리자는 탈취 비용이 커 길게) |
| `name` | `ADMIN_SEED_NAME` | 비면 `관리자`, 앞뒤 공백 제거 후 50자 이하 |

- 값은 Vault 에서 받는다(`secret-management.md`). yml 은 `${ADMIN_SEED_EMAIL:}` 플레이스홀더뿐이고 실제 값을 저장소에 쓰지 않는다.
- 기동 직후 `adapter/in/bootstrap/AdminSeedRunner`(ApplicationRunner)가 한 번 부른다. **멱등이다** — 같은 이메일의 관리자가 있으면 아무것도 바꾸지 않는다(비밀번호를
  덮어쓰지 않는다). 그래서 첫 배포 뒤 값을 지워도 되고, 남겨 둬도 매 기동이 같은 결과다. 동시에 뜬 다른 인스턴스가 먼저 넣었으면(유니크 키) 건너뛴다.
- **설정이 틀리면 기동을 멈춘다** — 이메일 · 비밀번호 중 하나만 있음, 비밀번호 · 이메일 · 이름 규칙 위반. 예외 메시지에 값을 싣지 않는다.
- **같은 이메일의 고객이 있으면 승격하지 않고 warn 만 남긴 채 기동한다(`CUSTOMER_EXISTS`).** 기동을 멈추지 않는 이유 — 이것은 설정이 아니라 데이터 상태이고,
  관리자 이메일을 아는 누군가 먼저 고객 가입을 해 두는 것만으로 auth-service(고객 로그인 포함) 기동을 막을 수 있게 되기 때문이다. 다른 이메일로 다시 시드한다.
- 로그는 진입점(`AdminSeedRunner`)이 남기는 결과 한 줄뿐이다 — `admin seed result=CREATED email=ad***@e***.com`(`CUSTOMER_EXISTS` 만 warn).
  이메일은 마스킹(`MemberMasking`, `api-design-guide.md` §2-4)하고 비밀번호 · 이름은 남기지 않는다. `AdminSeedProcessor` 는 memberId 를 debug 로만 남긴다.
- 규칙 판정은 `AdminSeedProcessor#validate`, 순서(판정 → 트랜잭션 밖 BCrypt → 저장 트랜잭션)는 `AdminSeedFacade` 다.
- **운영 주의**
  - 시드 이메일은 외부에 알려지지 않은 주소로 한다 — 대표 메일 · CS 주소처럼 공개된 주소는 남이 먼저 고객으로 가입해 시드를 막거나(`CUSTOMER_EXISTS`) 대입 대상이 된다.
  - **`CUSTOMER_EXISTS` warn 은 경보로 받는다.** 정상 배포에서는 나지 않고, 나면 관리자 계정이 만들어지지 않은 채 기동한 것이다(누군가 그 이메일을 선점했을 수 있다).
- 관리자 추가 · 비활성화 · 본인 비밀번호 변경은 #193 이다 (`POST /api/v1/admin/admins`, `PATCH /api/v1/admin/admins/{memberId}`, `PATCH /api/v1/admin/me/password`).
  어드민은 상품을 파는 사업자의 직원 계정이다. 고객 이메일은 관리자로 승격하지 않는다. 마지막 활성 관리자는 끄지 못한다(409 `AUTH_031`).
  비밀번호 변경 · 비활성화는 Redis `auth:admin-access-cutoff:{memberId}` 에 다음 초를 쓰고, 게이트웨이가 관리자 토큰의 `iat` 가 그 시각보다 이르면 004 로 끊는다.
  본인 요청의 `jti` 는 로그아웃 차단 목록에도 넣는다. 다른 관리자를 끌 때는 그 사람의 jti 를 저장하지 않으므로 컷오프만 쓴다.
  컷오프는 로그아웃 차단과 같이 게이트웨이만 본다. 서비스로 직접 붙는 호출은 만료까지 옛 토큰이 남는다. 보관 TTL 은 access 수명 상한 + 시계 오차(기본 61분)다.
  컷오프 쓰기가 실패하면 계정 변경은 반영된 채 503 `AUTH_013` 이다.

### 11-4. 관리자 감사 로그 (공통 방식 · auth 영속화 #192)

관리자 행위는 **구조화된 감사 로그 한 줄**로 남긴다. auth-service 는 같은 네 칸을 DB(`auth.admin_audit_log`)에도 남기고 조회 API 를 연다 (#192, 아래 "영속화").

```text
action=ADMIN_LOGIN actorMemberId=- target=member:4611686018427387904 result=FAILURE
action=MEMBER_SEARCH actorMemberId=4611686018427387904 target=members?role=ALL&page=0&size=20 result=SUCCESS
```

- **로거 이름은 `admin.audit`, 수준은 INFO** 다 — 로그 수집기가 로거 이름으로 따로 모아 보관 기간을 앱 로그와 다르게 둘 수 있게 나눈다.
- 칸은 언제나 `action · actorMemberId · target · result` 넷이고 이 순서다. 모르는 칸은 `-`, 값에 공백을 넣지 않는다.
  - `action` — 대문자 스네이크(`ADMIN_LOGIN` · `MEMBER_SEARCH`). 로그 검색 키라 한번 정한 이름을 바꾸지 않는다.
  - `actorMemberId` — 행위한 관리자의 `member.id`(access 토큰의 `sub`). 요청 본문 값을 쓰지 않는다.
  - `target` — 대상 리소스 · 조건(`member:<id>` · `members?role=..&page=..&size=..`).
  - `result` — `SUCCESS` · `FAILURE`. 실패 이유(회원 없음 · 비밀번호 틀림 · 고객 계정)는 나누지 않는다.
- **개인정보 · 비밀을 싣지 않는다** — 이메일 · 이름 · 전화 원문, 비밀번호 · 토큰, 검색어 원문. 관리자 로그인 실패는 이메일 원문 대신 그 이메일이 관리자 계정일 때만
  `target=member:<id>` 를 남긴다(고객 memberId 는 남기지 않는다) — 한 관리자 계정에 몰리는 대입을 찾을 수 있게.
- 쓰는 방식 — 컨텍스트의 out-port(`AdminAuditPort`)에 행위별 메서드를 두고, **Facade 는 포트를 직접 주입하지 않고 `AdminAuditProcessor` 를 부른다**
  (ArchUnit H11 — Facade 는 out-port 를 쓰지 않는다). 업무가 끝난 뒤(트랜잭션 밖) 부르고, 실패 로그의 대상 선택 같은 감사 정책도 Processor 에 둔다.
  어댑터는 `adapter/out/log/*AuditLogAdapter` 다.
  다른 서비스도 어드민 쓰기 API 를 만들 때 같은 로거 이름 · 칸으로 남긴다. 업무 이력이 이미 DB 에 있는 행위(재고 조정 이력 #86 등)는 그 이력이 감사의 정본이다.
- **영속화 (auth-service, #192)** — `AdminAuditPort` 계약은 그대로이고 구현이 `adapter/out/persistence/AdminAuditLogRepositoryAdapter` 다.
  - 순서: `admin.audit` 로그 한 줄(`AdminAuditLogAdapter`)을 **먼저** 남기고 `auth.admin_audit_log` 에 한 행을 더한다. 칸 값은 `AdminAuditRecord` 팩토리 한 곳에서 만든다 —
    로그 줄과 행이 같은 값이다. 로그 어댑터는 지우지 않는다(수집기 형식 · 다른 서비스와 같은 줄 · DB 장애 때의 증거).
  - DB 쓰기는 `REQUIRES_NEW` 짧은 트랜잭션이고 예외는 그 바깥에서 삼킨다 — 감사 저장 실패가 로그인 · 조회 응답을 바꾸지 않는다(로그인 실패 응답이 달라지면
    계정 존재가 드러난다). 실패하면 error 한 줄(행위 · 관리자만)을 남기고 그 기간은 로그 줄이 정본이다. 뒷날 업무 트랜잭션 안에서 불려도 업무를 rollback-only 로
    만들지 않고, 업무가 롤백돼도 감사 행은 남는다. 단 업무 트랜잭션 안에서 부르면 스레드가 커넥션 2개를 잡는다 — 지금 호출자는 모두 트랜잭션 밖이다.
  - **행위자도 대상도 없는 기록은 행을 만들지 않는다** — 없는 이메일 · 고객 계정으로 관리자 로그인에 실패하면 로그 한 줄(`target=-`)만 남는다.
    인증 전 요청이라 이메일을 바꿔 가며 반복하면 행이 끝없이 쌓이기 때문이다. 노린 관리자 계정이 있는 실패(`target=member:<id>`)는 행으로 남는다.
  - 테이블은 append-only 이고 개인정보가 없다 — 행위 · 관리자 memberId(모르면 null) · 대상 · 결과 · 시각. 엔티티는 `@Immutable`, 조회 · 추가 리포지토리는 `JpaRepository` 가 아니라
    `Repository` 를 상속해 `saveAndFlush` 와 조회만 연다. DELETE 문장은 보관 정리 리포지토리(`AdminAuditLogRetentionRepository`)만 연다. 행위는 문자열 칼럼이다
    (행위를 더할 때 check 제약 DDL 이 생기지 않게). 결과만 check 로 막는다.
  - **보관 기간은 기본 2년**이다 (`auth.admin-audit.retention.period`, `P2Y`). 개인정보의 안전성 확보조치 기준 접속기록 **최소** 보관(1년, 대규모 · 고유식별 · 민감정보는 2년)을 덮는다.
    1년 미만이면 기동하지 않는다(`AdminAuditRetentionProperties` — 평년 1월 1일 기준으로 달력 1년 미만을 거절한다. 365일은 통과하고 364일은 막힌다).
    정리 스케줄러 `AdminAuditLogRetentionScheduler` → `AdminAuditLogRetentionSchedulerUseCase` → `AdminAuditLogRetentionSchedulerFacade`(배치마다 트랜잭션) → Processor 가 `id < 컷오프 아이디` 인 행만 배치로 지운다.
    컷오프는 `SnowflakeIdGenerator.lowestIdAt(지금 - 보관 기간)` 이라 그 밀리초의 행은 남고, 더 이전 행만 지워진다. `created_at` 인덱스는 두지 않는다.
    한 번에 `batch-size`(기본 1000)건, 한 실행은 `max-batches-per-run`(기본 50)까지다. 삭제는 멱등이라 인스턴스가 여럿이어도 분산 락을 두지 않는다.
    한 배치가 실패하면 그 실행은 멈추고 다음 주기가 남은 행을 다시 집는다.
  - **운영 DB 권한 (#286).** 이 테이블만 스키마 공통 DML(`service-inventory.md` §4-2)에서 좁힌다. UPDATE 는 주지 않는다.
    DELETE 문장은 정리 배치 한 곳이다. 배포 DB 로그인은 아직 서비스·용도별로 나뉘지 않는다(#82, 분리는 #258)라 정리 배치도 앱과 같은 로그인을 쓴다.
    그래서 그 로그인에 이 테이블의 **INSERT · SELECT · DELETE** 를 준다. "정리 배치에만 DELETE" 는 코드 경로로 지킨다 — 요청 처리 리포지토리에는 삭제 메서드가 없다.
    앱 로그인을 INSERT · SELECT 만 두고 DELETE 를 다른 로그인에 주려면 DataSource 가 둘이어야 한다. #258 에서 계정을 나눌 때 이 테이블의 DELETE 를 정리 배치 로그인으로 옮긴다.
    그 전까지 로그인 둘을 만들지 않는다.
  - **접속기록 범위 (#286).** 안전성 확보조치 기준의 접속기록 항목은 식별자 · 접속일시 · 접속지 · 처리한 정보주체 · 수행업무다.
    이 테이블이 맡는 것은 **식별자(`actor_member_id`) · 접속일시(`created_at`, Snowflake 아이디) · 수행업무(`action`) · 대상 리소스(`target`) · 결과** 다.
    - **접속지(IP) 칸은 넣지 않는다.** 게이트웨이는 하류에 IP 원문을 넘기지 않고 HMAC 만 넘긴다(#135, §8). 원문을 행에 넣으면 하류가 개인정보(접속지)를 저장하고,
      "행에 개인정보가 없어 이 조회는 감사하지 않는다" 는 전제가 깨진다. 조회마다 행이 늘어나는 순환도 생긴다. HMAC 은 법정 접속지를 대신하지 않는다.
    - **처리한 정보주체 칸도 새로 두지 않는다.** 로그인 실패의 `target=member:<id>` 는 노린 관리자 계정이지 열람한 정보주체가 아니다.
      회원 목록 조회의 `target` 은 조건 문자열이라 정보주체 식별자가 아니다.
    - 그래서 이 테이블은 법정 접속기록을 **혼자 채우지 않는다.** 범위는 관리자 행위 감사다. 접속지와 정보주체 열람 기록은 이 테이블 밖이다.
  - **남은 위험** — 관리자 로그인에는 아직 시도 제한이 없다(#66). 노린 관리자 계정이 있는 실패는 반복하면 행이 늘어난다. 보관 정리가 기간이 지난 행을 지우므로 무한히 쌓이지는 않는다.
    #66 이 관리자 로그인을 대상으로 삼을 때 IP 단위 제한은 게이트웨이 시도 제한(#135)을 보고, 이 테이블에 IP 를 넣지 않는다.
  - 조회는 `GET /api/v1/admin/audit-logs`(ADMIN, `api-design-guide.md` §2-4). 조건은 `action` · `actorMemberId` · `target`(완전 일치) · `result` 다.
    로그인 실패는 행위자가 null 이라 `actorMemberId` 만으로는 한 계정을 노린 실패를 못 고른다. `action=ADMIN_LOGIN&target=member:<id>&result=FAILURE` 로 고르고,
    `(target, id)` 인덱스를 둔다. 이 조회 자체는 감사하지 않는다 — 행에 개인정보가 없고, 남기면 조회마다 행이 늘어난다.
    응답의 `actorDisplayName` · `targetDisplayName` 은 저장된 행이 아니라 조회 시 회원 이름을 마스킹해 붙인다 (#322, `api-design-guide.md` §2-4). 회원 목록 API 는 쓰지 않는다.
  - 다른 서비스(operations `JOB_CANCEL` 등)는 지금 로그 한 줄만 남긴다. DB 영속화를 서비스마다 어떻게 둘지(서비스별 같은 모양 테이블 · core 공유 안 함 권고)는 #163 에서 정한다.
- **서비스 전용 이력 테이블 사례 — delivery-service 배송비 정책 교체 이력 (#69).** 어드민 공통 감사 로그(DB)가 아직 없어 `delivery.shipping_policy_history`
  (+ 구간 스냅숏 `shipping_policy_history_area`)에 교체 전 · 후 값 · 교체한 관리자(`admin_member_id` — access 토큰의 memberId, 본문 값을 쓰지 않는다) · 시각을 남긴다.
  교체와 **같은 트랜잭션**이라 이력 저장이 실패하면 교체도 롤백된다 — 기록 없는 변경이 생기지 않는다(로그 한 줄 방식은 트랜잭션 밖이라 이 보장이 없다).
  조회는 `GET /api/v1/admin/shipping-policies/domestic/histories`(ADMIN, `api-design-guide.md` §6-2). append-only 라 고치거나 지우는 API 가 없다.
  `adminDisplayName` 은 auth-service 표시 이름 벌크 조회가 마스킹해 준 값이다 (#322). 원문 이름은 delivery 에 들어오지 않는다.
  공통 감사 로그(#163 · #192)가 생기면 이 행위를 그쪽에도 함께 남길지 재검토한다.
- 적용 대상 (#6): 관리자 로그인 성공 · 실패(`AdminAuthWebFacade`), 어드민 회원 목록 조회(`MemberAdminFacade` — 마스킹했어도 개인정보를 읽는 행위다).
- 적용 대상 (#212): 어드민 작업 취소 성공 · 실패(`JobAdminFacade` — `action=JOB_CANCEL target=job:<id>`, operations-service `JobAdminAuditLogAdapter`). 작업 목록 · 상세 조회는 남기지 않는다 —
  payload · 결과 · 오류에 개인정보 원문을 두지 않는 규칙(`architecture-guide.md` §10)이라 읽기가 개인정보 열람이 아니다. 결과 파일 링크 발급(#212 PR-E)은 남긴다.

### 11-5. 이중 방어 확인

CUSTOMER 토큰으로 어드민 경로에 오면 게이트웨이와 서비스가 각각 거부한다. 회귀 테스트는 실제 회원 경로(`/api/v1/admin/members`)로 둔다.

| 층 | 테스트 | 보는 것 |
|----|--------|---------|
| 게이트웨이 | `GatewaySecurityTest` (`adminMembers*` · `*AdminMembersPath*`) | 무토큰 401 · CUSTOMER 403 · ADMIN 은 auth 라우트로. `..` · `%2e%2e` · `..;` · `;` · `%2f`/`%2F` · `%252e` 는 400 `GATEWAY_004`, `%61dmin` · 끝 슬래시는 401 · 403, 모두 하류 미도달 |
| 서비스 | `AuthServiceSecuritySmokeTest` (`adminMembers*` · `bypassPaths*` · `*AdminMembersPath`) | 무토큰 401 · CUSTOMER 403 · ADMIN 200. 우회 경로(위와 같음)는 방화벽 400, `%61dmin` 은 401 · 403, 끝 슬래시는 401 · 403 · ADMIN 404 |
| 서비스 슬라이스 · 흐름 | `MemberAdminControllerTest` · `AdminFlowIntegrationTest` · `AuthTokenFlowIntegrationTest#adminLogoutRevokesJtiOnly` | `hasRole('ADMIN')` · 시드 → 로그인 → 조회 · 로그아웃은 jti 차단(서비스 직접 호출은 200 — 차단 목록은 게이트웨이만 본다) |

- 서비스 스모크의 "컨트롤러 미도달" 은 감사 로그(`action=MEMBER_SEARCH`)가 stdout 에 없는 것으로 판정한다 — 로깅 설정이 그 로거를 콘솔에서 빼면 거짓 통과하므로,
  같은 테스트의 ADMIN 200 케이스가 그 줄이 잡히는 것(`contains`)을 함께 단언한다.

## 12. Eureka 레지스트리 인증 (#248)

같은 docker 네트워크에 붙은 컨테이너면 누구나 Eureka 에 아무 서비스 이름으로 인스턴스를 등록할 수 있었다. 가짜 `PAYMENT-SERVICE` 가 등록되면
게이트웨이 `lb://` 가 사용자 요청 일부를 그쪽으로 보내고, 그 인스턴스가 `Authorization` 토큰과 요청 본문을 받는다. 네트워크 분리(`deploy-guide.md` §6)에 더해 레지스트리를 인증으로 닫는다.

### 12-1. 방식 — HTTP Basic

| 방식 | 판단 |
|------|------|
| **HTTP Basic** (채택) | Spring Cloud 가 공식 지원하는 경로다. 클라이언트는 `defaultZone` URL 의 userinfo 를 Basic 헤더로 바꿔 싣고(userinfo 를 뗀 URL 로 요청), 코드가 없다. 서버는 보안 체인 하나와 기동 가드다. 새 시크릿은 하나다 |
| mTLS | CA · 서비스별 인증서 발급 · 갱신을 2인이 운영해야 하고, 게이트웨이 → 서비스 · 서비스 간 호출까지 TLS 가 아니면 반쪽이다. §9-9(2단계)와 함께 본다 |
| 네트워크 정책만 | 이미 적용돼 있고(#196) 잘못 붙은 컨테이너 · 호스트 안 프로세스를 막지 못한다. 테스트로 증명할 수 없다 |

- 평문 HTTP 위 Basic 이라 도청에는 무력하다 — 사설망 평문 HTTP 전제(`architecture-guide.md` §6)와 같은 한계다.
- 틀린 자격 증명마다 BCrypt 를 돌리므로 같은 네트워크 안에서는 Eureka CPU 를 소모시킬 수 있다. 정상 부하(heartbeat · 조회 약 0.5 rps)는 문제가 없고, 네트워크 분리가 1차 방어다.
- **계정은 하나다** — 사용자 `eureka`(고정, 비밀 아님), 비밀번호 `EUREKA_PASSWORD`. service-discovery · 게이트웨이 · 서비스 전부 같은 값, 환경(dev · prod)마다 다른 값이다.
  Eureka 는 계정과 서비스 이름을 묶지 않아 서비스별 계정은 효과가 없다.

### 12-2. 서버 (`cloud/service-discovery`)

- `DiscoverySecurityConfig` — 세션 · CSRF · 폼 로그인을 끄고 HTTP Basic 을 켠다. `/actuator/health` 만 익명(compose healthcheck), 다른 actuator 는 막고, 나머지
  (`/eureka/**` 등록 · 조회 · heartbeat, 대시보드)는 인증이 필요하다. 무자격 · 틀린 자격 증명은 401 + `WWW-Authenticate: Basic` 이다.
- 비밀번호는 BCrypt 로 해시해 메모리에 둔다. `spring.security.user.*` 는 쓰지 않는다(`{noop}` 평문 저장).
- **대시보드는 기본 닫힘**(`eureka.dashboard.enabled: false`) — 브라우저가 Basic 자격 증명을 기억하는 유일한 경로다. 로컬만 연다.
  배포 환경은 ssh 터널로 `curl -u eureka:<비밀번호> http://127.0.0.1:<호스트 포트>/eureka/apps` 를 본다.
- **기동 가드**(`DiscoveryPasswordStartupGuard`) — `EUREKA_PASSWORD` 가 비었거나 · 플레이스홀더가 풀리지 않았거나 · 32자 미만이거나 ·
  영문 · 숫자 · `. _ ~ -` 밖의 문자가 있으면 뜨지 않는다. 메시지에 값을 싣지 않는다. 인증을 끄는 스위치 · 기본값은 없다.

### 12-3. 클라이언트 (게이트웨이 · 서비스 전부)

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka:${EUREKA_PASSWORD}@${EUREKA_HOST:localhost}:8761/eureka/
    should-enforce-registration-at-init: true
```

- URL 은 yml 이 조립한다. env 는 호스트(`EUREKA_HOST`)와 비밀번호만 준다 — 비밀번호를 URL 째 Vault 에 두면 서버용과 두 벌이 돼 교체 때 어긋난다.
- **비밀번호 문자 규칙이 필수인 이유** — Eureka 클라이언트는 userinfo 를 디코딩해 `:` 로 나누고, 두 조각이 아니거나 URL 이 파싱되지 않으면
  **예외 없이 인증 없이** 요청한다. `:` 는 `%3A` 로도 안 된다. 인코딩을 풀지 않고 `openssl rand -hex 32` 로 문제 자체를 없앤다. 서버 가드가 같은 규칙을 강제해
  잘못된 값은 Eureka 배포 단계에서 먼저 드러난다.
- `should-enforce-registration-at-init` — 기동 때 등록이 실패하면(401 · 연결 불가) 클라이언트가 뜨지 않는다. 비밀번호가 틀린 채 "등록 안 된 UP" 으로 숨지 않는다.
  그래서 **Eureka 가 먼저 떠 있어야 한다**(`deploy-guide.md` §5 기동 순서). 테스트는 `eureka.client.enabled=false` 라 영향이 없다.
- `EUREKA_PASSWORD` 는 기본값이 없다. 로컬도 `backend/.env` 에 넣는다(`local-run-guide.md` §2).
- **로그에 비밀번호가 나오지 않게 한다** — Eureka 에 닿지 않으면 `RedirectingEurekaHttpClient` 가 INFO · WARN 으로 `defaultZone` 원문(userinfo 포함)을 남긴다.
  7개 모듈 yml 이 그 로거를 `ERROR` 로 둔다(연결 실패 신호는 `RetryableEurekaHttpClient` 의 WARN 이 남긴다). `com.netflix.discovery` 를 DEBUG 로 올리지 않는다 —
  `ConfigClusterResolver` 등이 같은 URL 을 찍는다. 요청 자체는 userinfo 를 뗀 URL 로 나가 예외 메시지에는 실리지 않는다.

### 12-4. 비밀번호 교체 런북

이전 값을 함께 받는 `_PREVIOUS` 는 두지 않았다. 교체 중 잠깐의 등록 실패를 감수한다.

1. 새 값을 만든다 — `openssl rand -hex 32`. 그 환경 Vault secret 의 `EUREKA_PASSWORD` 를 바꾼다.
2. service-discovery 를 다시 배포한다. 레지스트리는 메모리라 비워지고, 이때부터 옛 비밀번호의 heartbeat 는 401 이다.
3. 게이트웨이 → 서비스 순서로 다시 배포한다. 새 값으로 30초 안에 다시 등록된다.
- 2~3 사이에는 게이트웨이가 마지막으로 받아 둔 레지스트리로 **살아 있는** 옛 인스턴스에 계속 라우팅한다. 새로 뜨는 인스턴스는 3 이 끝나야 보인다.
- 비밀번호가 새면 같은 절차를 바로 밟는다. 새 값이 들어가기 전까지 그 값을 아는 컨테이너는 인스턴스를 등록할 수 있다 — 네트워크 분리가 1차 방어다.

### 12-5. 테스트

| 위치 | 테스트 | 고정하는 것 |
|------|--------|-------------|
| service-discovery | `DiscoverySecurityTest` (실제 포트 HTTP) | 무자격 조회 401 + `WWW-Authenticate: Basic` · 무자격 / 틀린 비밀번호 / 다른 사용자 등록 401 이고 레지스트리에 없음 · 올바른 자격 증명 등록 204 · 조회 200 · health 익명 UP · 다른 actuator 401 · 대시보드 익명 401 · 인증해도 기본 꺼짐 404 |
| service-discovery | `DiscoveryPasswordStartupGuardTest` | hex 64자 통과 · 없음 · 공백 · 풀리지 않은 플레이스홀더 · 31자 · `:` `@` `%3A` 공백 포함 시 기동 실패, 메시지에 값 없음 |
| service-discovery | `EurekaClientCredentialTest` | 클라이언트가 `http://eureka:<비밀번호>@localhost:<port>/eureka/` userinfo 를 Basic 으로 실어 등록한다 · 틀린 비밀번호는 Basic 으로 실려도 등록되지 않는다 · userinfo 에 `:` 가 더 있으면 등록되지 않고 클라이언트가 뜨지 않는다 · 연결 거부 로그에 비밀번호가 없다(`RedirectingEurekaHttpClient` ERROR, 7개 모듈 yml 과 같은 설정) |
