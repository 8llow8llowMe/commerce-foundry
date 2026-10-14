# Security Guide

> 인증·인가(JWT 발급·검증, Resource Server, 오류 코드, 키 배포·교체)의 정본이다. 구현은 `core/security-core` 다.
> 보안 구조의 요지는 `architecture-guide.md` §6, 시크릿 보관 규칙은 `secret-management.md` 를 따른다.

## 1. 구성

| 역할 | 누가 | 무엇으로 |
|------|------|----------|
| 발급 | auth-service 만 | 개인키(`JWT_PRIVATE_KEY`) · `JwtIssuer` · `OpaqueTokenGenerator` |
| 검증 | 서비스 전부 · auth · api-gateway | 공개키(`JWT_PUBLIC_KEY`, 교체 중 `JWT_PUBLIC_KEY_PREVIOUS`) |

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
- TTL 기본 30분(`security.jwt.issuance.access-token-ttl`), 시계 오차 허용 기본 60초(`security.jwt.clock-skew`, 0 이상 5분 이하).
- 위반은 한 번에 모두 모아 판정한다 (§4-1).

### 2-3. refresh 토큰은 JWT 가 아니다

- `OpaqueTokenGenerator#generate()` — `SecureRandom` 32바이트를 base64url 무패딩(43자)으로. 뜻 없는 난수다.
- 서버 저장소에 있어야만 유효하므로 서명·claim 이 필요 없고, 탈취돼도 내용이 드러나지 않는다.
- 저장소에는 원문이 아니라 `OpaqueTokenGenerator#fingerprint(token)`(SHA-256, base64url 43자)을 키로 둔다.
  `RedisKey.of("auth", "refresh-token", fingerprint)` 처럼 그대로 쓸 수 있다.
- TTL 기본 14일(`security.jwt.issuance.refresh-token-ttl`). 저장·회전·재사용 감지·전달 방식(헤더 / httpOnly 쿠키)은 #4 에서 정한다.

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

## 4. 오류 코드

| 코드 | enum | HTTP | 언제 | `WWW-Authenticate` |
|------|------|------|------|--------------------|
| `SECURITY_001` | `UNAUTHORIZED` | 401 | 토큰 없이 인증 필요 API · 어드민 경로에 왔다 | `Bearer` |
| `SECURITY_002` | `TOKEN_EXPIRED` | 401 | 만료 — **프론트가 재발급을 시도하는 유일한 신호** | `Bearer error="invalid_token", error_description="The access token expired"` |
| `SECURITY_003` | `TOKEN_INVALID` | 401 | 서명 · 형식 · alg · typ · iss · aud · claim 위반 | `Bearer error="invalid_token"` |
| `SECURITY_004` | `TOKEN_REVOKED` | 401 | 로그아웃된 토큰 (#4) | `Bearer error="invalid_token"` |
| `SECURITY_005` | `FORBIDDEN` | 403 | 로그인했지만 역할이 모자란다 | 없음 |
| `SECURITY_006` | `TOKEN_VERIFICATION_UNAVAILABLE` | 503 | 토큰 검증 저장소 장애 (#4) · 인증 처리 중 시스템 오류 | 없음 |

- 본문은 `Response.fail(code, message)` 봉투다 (`api-design-guide.md` §2-1). 서비스와 게이트웨이가 `SecurityErrorEnvelope` 로 같은 바이트를 쓴다.
- 002 이외의 401 은 재발급으로 풀리지 않는다 — 프론트는 다시 로그인시킨다.

### 4-1. 판정 규칙 (`SecurityErrorResolver`)

- 예외 타입과 `OAuth2Error` 의 오류 코드로만 판정한다. **예외 메시지 문자열을 비교하지 않는다.**
- 검증 위반이 **만료 하나뿐일 때만 002** 다. 만료에 다른 위반(aud 불일치 등)이 섞이면 003 — 재발급으로 풀리지 않는 토큰이다.
- 만료 판정은 security-core 의 validator 가 오류 코드 `SECURITY_002` 로 남긴다. Nimbus 의 기본 claim 검증은 꺼 두었다
  (켜 두면 Nimbus 가 먼저 "Expired JWT" 문구의 예외를 던져 만료를 문자열로 가려내야 한다).
- #4 가 004 · 006 을 낼 때는 `new OAuth2AuthenticationException(new OAuth2Error(SecurityErrorCode.TOKEN_REVOKED.getCode()))` 처럼
  오류 코드에 `SECURITY_xxx` 를 싣는다. 리졸버가 그 코드를 그대로 쓴다.
- 권한 거부는 익명이면 001, 로그인했으면 005 다.

## 5. Resource Server (서블릿 서비스)

### 5-1. 정책

| 경로 | 규칙 |
|------|------|
| `/api/v1/admin/**` | URL 규칙으로 `ADMIN` 을 요구한다. 컨트롤러의 `@PreAuthorize("hasRole('ADMIN')")` 와 이중이다. 토큰 없으면 401(001), CUSTOMER 면 403(005) |
| `/internal/**` | JWT 없이 호출된다. **internal 컨트롤러에 `@PreAuthorize` 를 달지 않는다.** 외부 차단은 게이트웨이 몫이다 (`architecture-guide.md` §6) |
| 그 밖의 `/api/v1/**` | URL 단계에서는 열어 두고, 인증이 필요한 메서드에 `@PreAuthorize("isAuthenticated()")` 를 단다. 비로그인 상품 조회 같은 API 가 있다 |

- **토큰을 보냈는데 틀리면 공개 API 여도 401 이다.** 틀린 토큰을 익명으로 취급하지 않는다. 프론트는 만료 토큰을 지우거나 재발급한 뒤 다시 보낸다.
- 세션을 만들지 않는다(STATELESS). CSRF · 폼 로그인 · Basic · 로그아웃을 끈다. CORS 는 게이트웨이가 처리하므로 서비스에서 끈다.
- 정규화되면 다른 경로가 되는 원문 경로(`..` · `%2e` · `;` · `//` · `%2f`)는 Spring Security 방화벽이 필터 체인 앞에서 **400(봉투 없음)** 으로 끊는다.
  게이트웨이도 같은 경로를 먼저 거절한다(`GATEWAY_004`). 서비스의 400 은 게이트웨이를 거치지 않은 요청에 대한 이중 방어다.

### 5-2. 도메인 advice 와의 관계

- `@PreAuthorize` 거부(`AuthorizationDeniedException`)는 필터가 아니라 **MVC 예외 처리**로 먼저 간다.
  security-core 의 `SecurityExceptionAdvice` 가 가장 높은 우선순위(`@Order(HIGHEST_PRECEDENCE)`)로 잡아 001 · 005 로 답한다.
- 도메인 `*ExceptionHandler` 는 `Exception` · `RuntimeException` 을 통째로 잡지 않는다 (`coding-conventions.md` §8-1). 잡으면 보안 예외를 가로챌 수 있다.

### 5-3. 서비스 구성

```groovy
// service/<svc>/build.gradle
implementation project(':core:security-core')
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
```

- security-core 는 서블릿 · Boot 보안 자동설정을 `compileOnly` 로만 둔다. 리액티브 게이트웨이에 서블릿 스택이 새지 않게 하려는 것이고,
  security-core 의 `verifyNoServletLeak` 태스크가 `check` 에서 이를 확인한다. 그래서 서블릿 서비스가 위 두 스타터를 직접 선언한다.

| 파일 | import |
|------|--------|
| `*BeansConfig` | `ResourceServerSecurityConfigurer` (전 서비스), `JwtIssuerConfigurer` (auth 만) |
| `*PropertiesConfig` | `JwtVerificationPropertiesConfig` (전 서비스), `JwtIssuerPropertiesConfig` (auth 만) |

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
```

기동을 멈추는 조건 — 메시지에는 프로퍼티·환경변수 이름만 있고 키 내용은 없다.

| 조건 | 메시지가 가리키는 것 |
|------|----------------------|
| 공개키가 비었거나 `${JWT_PUBLIC_KEY}` 가 풀리지 않았다 | `JWT_PUBLIC_KEY` |
| 키가 PEM 으로 읽히지 않는다 · 2048비트 미만이다 · PKCS#1 이다 · 공개키 자리에 개인키가 왔다 | 해당 환경변수 · 변환 명령 |
| `issuer` · `audience` 가 비었다, `clock-skew` 가 범위 밖이다, TTL 이 0 이하다 | 해당 프로퍼티 |
| (auth) 개인키가 `JWT_PUBLIC_KEY` 와 한 쌍이 아니다 | `JWT_PRIVATE_KEY` · `JWT_PUBLIC_KEY` (§6-3) |

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

auth 는 기동할 때 자기 개인키와 `JWT_PUBLIC_KEY` 가 한 쌍인지 확인한다. 아래 순서를 지키면 교체 중에도 401 이 나지 않는다.

1. **검증자에 새 공개키를 먼저 배포한다** — auth 를 뺀 서비스 전부와 게이트웨이에서 `JWT_PUBLIC_KEY_PREVIOUS` ← 지금 공개키,
   `JWT_PUBLIC_KEY` ← 새 공개키로 바꾸고 롤링 재기동한다. 이제 옛 키·새 키로 서명한 토큰을 모두 받는다.
2. **auth 의 키를 바꾼다** — `JWT_PRIVATE_KEY` ← 새 개인키, `JWT_PUBLIC_KEY` ← 새 공개키, `JWT_PUBLIC_KEY_PREVIOUS` ← 옛 공개키로 바꾸고 재기동한다.
   이때부터 새 키로 서명한다.
3. **access TTL + 시계 오차(기본 31분)가 지난 뒤** 모든 곳의 `JWT_PUBLIC_KEY_PREVIOUS` 를 비우고 롤링 재기동한다.

- 순서를 틀리면 auth 의 쌍 검사가 기동을 막는다 — ①에서 auth 까지 재기동하거나(개인키는 옛 키, 공개키는 새 키) ②를 ①보다 먼저 하면
  `JWT_PRIVATE_KEY 와 JWT_PUBLIC_KEY 가 한 쌍이 아닙니다` 로 멈춘다. 이미 떠 있는 auth 인스턴스는 옛 설정으로 계속 동작한다.
- 개인키가 유출됐으면 ③을 기다리지 않는다 — `JWT_PUBLIC_KEY_PREVIOUS` 를 바로 비워 옛 키로 서명한 토큰을 모두 무효로 한다 (`secret-management.md` §6).
- JWKS 엔드포인트는 두지 않는다. 키는 환경변수로만 배포한다.

## 7. 게이트웨이(#4)가 재사용하는 것

security-core 의 아래 클래스는 서블릿 의존이 없다. 게이트웨이는 `spring-boot-starter-security`(WebFlux 변형)를 직접 선언하고 이것들을 조합한다.

| 클래스 | 쓰임 |
|--------|------|
| `token.JwtVerificationPropertiesConfig` · `JwtVerificationProperties` | `security.jwt.*` 바인딩 |
| `token.reactive.ReactiveAccessTokenDecoderFactory` | `ReactiveJwtDecoder` — 서블릿과 같은 키 묶음 · validator |
| `principal.MemberJwtAuthenticationConverter` | `ReactiveJwtAuthenticationConverterAdapter` 로 감싸 쓴다 |
| `error.SecurityErrorResolver` · `SecurityErrorEnvelope` · `SecurityErrorCode` | JWT 거부 응답 — 서비스와 같은 코드 · 같은 바이트 · 같은 `WWW-Authenticate` |

- 게이트웨이의 JWT 거부도 `SECURITY_*` 를 쓴다 (`api-design-guide.md` §2-1). `GATEWAY_*` 는 라우팅 오류에만 쓴다.

## 8. 로그

- JWT 원문 · 키 · 예외 메시지(토큰 조각이 섞일 수 있다)를 남기지 않는다. 남기는 것은 코드 · 경로 · 예외 타입, 로그인한 요청이면 memberId · jti 다.
- `IssuedAccessToken` 과 `JwtIssuanceProperties` 의 `toString()` 은 토큰 · 개인키를 가린다.
