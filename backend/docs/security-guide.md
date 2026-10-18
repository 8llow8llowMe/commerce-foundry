# Security Guide

> 인증·인가(JWT 발급·검증, Resource Server, 오류 코드, 키 배포·교체, 서비스 간 internal API 인증)의 정본이다. 구현은 `core/security-core` 다.
> 보안 구조의 요지는 `architecture-guide.md` §6, 시크릿 보관 규칙은 `secret-management.md` 를 따른다.

## 1. 구성

| 역할 | 누가 | 무엇으로 |
|------|------|----------|
| 발급 | auth-service 만 | 개인키(`JWT_PRIVATE_KEY`) · `JwtIssuer` · `OpaqueTokenGenerator` |
| 검증 | 서비스 전부 · auth · api-gateway | 공개키(`JWT_PUBLIC_KEY`, 교체 중 `JWT_PUBLIC_KEY_PREVIOUS`) |
| 서비스 토큰 (internal API) | 서비스 5종이 서로 — 게이트웨이 제외 | 공용 HS256 비밀(`INTERNAL_JWT_SECRET`, 교체 중 `INTERNAL_JWT_SECRET_PREVIOUS`) · `InternalTokenIssuer` · internal 체인 (§9) |

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
  로그아웃 블랙리스트(#4) 보관 기간이 늘어난다. 긴 로그인 유지는 refresh 토큰으로 한다.
- 시계 오차 허용 기본 60초(`security.jwt.clock-skew`, 0 이상 5분 이하).
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
  다시 던져 **봉투 없는 500** 을 만들기 때문에, 서블릿 Resource Server 는 다시 던지지 않는 처리기로 바꿔 두었다(`ResourceServerSecurityConfigurer`).
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
| `/actuator/**` | `health`(하위 경로 포함)만 `permitAll` 이다. 웹에 노출된 다른 엔드포인트와 링크(`/actuator`)는 `denyAll` — 토큰 없으면 401(001), 로그인했으면 403(005). 노출하지 않은 엔드포인트는 이 규칙에 걸리지 않고 404 다 — **노출 설정(`exposure.include: health`)이 1차, URL 규칙이 2차** 방어다. 경로는 Boot 의 `EndpointRequest` 로 잡아 management base-path 를 바꿔도 따라간다. **어느 서비스도 `heapdump` 를 노출하지 않는다** — 서비스 5종의 힙에 `INTERNAL_JWT_SECRET` 이 있고, auth 의 힙에는 개인키도 있다 |
| Swagger (`/v3/api-docs/**` · `/swagger-ui/**`) | 명시 `permitAll` 을 두지 않는다. URL 단계는 default-allow 로 열려 있고, springdoc 스위치(기본 닫힘, local · dev 만 켬 — `bootstrap-conventions.md`)가 닫는다 |

- health 는 익명에 열려 있으므로 `management.endpoint.health.show-details` · `show-components` 는 기본값 `never` 를 유지한다.
  상세가 필요하면 management 포트를 분리하고 roles 로 좁힌다. **management 포트 분리는 검증하지 않았다** — 분리할 때 security-core 에
  RANDOM_PORT + `management.server.port=0` 통합 테스트를 더해 actuator 규칙이 분리된 포트에서도 걸리는지 확인한다.
- **토큰을 보냈는데 틀리면 공개 API 여도 401 이다.** 틀린 토큰을 익명으로 취급하지 않는다. BFF 는 401 이면 재발급 후 한 번 재시도한다(§4).
- 세션을 만들지 않는다(STATELESS). CSRF · 폼 로그인 · Basic · 로그아웃을 끈다. CORS 는 게이트웨이가 처리하므로 서비스에서 끈다.
- 정규화되면 다른 경로가 되는 원문 경로(`..` · `%2e` · `;` · `//` · `%2f`)는 Spring Security 방화벽이 필터 체인 앞에서 **400(봉투 없음)** 으로 끊는다.
  게이트웨이도 같은 경로를 먼저 거절한다(`GATEWAY_004`). 서비스의 400 은 게이트웨이를 거치지 않은 요청에 대한 이중 방어다.
- 대소문자가 다른 경로(`/API/v1/admin/x`)는 어느 컨트롤러에도 매핑되지 않아 404 다. 비예약 문자를 인코딩한 경로(`/api/v1/%61dmin/x`)는
  URL 규칙과 MVC 가 같은 디코딩으로 보므로 어드민 규칙에 그대로 걸린다(CUSTOMER 403 · 무토큰 401). 둘 다 컨트롤러가 있는 security-core 통합 테스트로 고정했다.
  서비스 인가 스모크(§5-3)는 인코딩 경로가 규칙에 걸리는지와, 서비스 MVC 의 경로 매칭이 URL 규칙과 같은 대소문자 규칙(`PathPatternParser`, 대소문자 구분)인지 본다.

**default-allow 보완 규칙** — URL 단계가 열려 있으니 메서드 애노테이션이 빠지면 그대로 공개 API 가 된다.

- `adapter/in/web` · `adapter/in/admin` 의 **모든 핸들러 메서드에 `@PreAuthorize` 를 단다.** 공개 API 도 `@PreAuthorize("permitAll()")` 로 적어
  빠뜨린 메서드가 리뷰에서 보이게 한다. 로그인 필요는 `isAuthenticated()`, 어드민은 `hasRole('ADMIN')` 이다. ArchUnit 강제는 #42 에서 한다.

**`/internal/**` 사설망 전제의 강제 수단**

- 서비스 포트를 외부에 publish 하지 않는다. 운영은 네트워크 정책(보안 그룹 · NetworkPolicy)으로 서비스 간 통신만 허용한다.
- 게이트웨이(#3)가 막는 방식 — 정규화되지 않은 경로(`..` · `%2e` · `;` · `//` 등)는 `GatewayRequestGuardFilter` 가 라우팅 전에 `GATEWAY_004`(400)로 끊는다.
  그 뒤로는 원문 경로와 정규화 경로가 같으므로, `/internal/**` 은 어느 라우트(`ServiceRoute`)에도 없어 `GATEWAY_001`(404)이 된다.
- 그래서 규칙은 둘이다. 게이트웨이 라우트 predicate 는 `/internal` 을 포함할 수 있는 패턴(`/**` 같은 catch-all)을 쓰지 않는다.
  경로를 바꾸는 필터(`StripPrefix` · `RewritePath` · `SetPath`)는 **와일드카드 없는 고정 경로 라우트에만** 쓴다 — 바뀐 경로도 고정값이라
  `/internal` · `/api/v1/admin` 이 만들어질 수 없다. 예: local · dev 전용 `GatewayDevRouteConfig`(`/health/{service-id}` · `/docs/{service-id}/v3/api-docs/{group}`).
  접두어만 떼는 라우트(`/docs/{service-id}/**` + `StripPrefix`)는 만들지 않는다. 명시적 `/internal` 거부 필터는 #4 에서 검토한다.
- 서비스 간 인증은 §9 다(결정 #43, 구현 #51). 1단계는 공용 키라 호출자가 "선언" 이므로 사설망 전제를 걷어 내지 않는다.

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
  internal:                                # 서비스 간 인증 (§9-6) — 서비스 5종
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
| 정규화되지 않은 경로 (§5-1) | 봉투 없는 400 | 게이트웨이가 먼저 `GATEWAY_004` 로 거절한다 |

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

**필터 순서** — `GatewayRequestGuardFilter`(가장 앞, 비정규 경로 거절) → 보안 체인(`WebFilterChainProxy`, -100: CORS → Bearer 인증 → 인가) → 라우팅.
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
- **차단 목록은 게이트웨이만 본다.** 서비스 5종은 서명 · 만료만 본다(#4 결정 3). 사설망 안에서 서비스에 직접 붙는 요청은 로그아웃한 토큰도
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

## 8. 로그

- JWT 원문 · 키 · 예외 메시지(토큰 조각이 섞일 수 있다)를 남기지 않는다. 남기는 것은 코드 · 경로 · 예외 타입, 로그인한 요청이면 memberId · jti 다.
- `IssuedAccessToken` 과 `JwtIssuanceProperties` 의 `toString()` 은 토큰 · 개인키를 가린다. `InternalTokenProperties` 의 `toString()` 은 비밀을 가린다.
- 서비스 인증 실패(007)와 서비스의 허용 목록 거부(005)는 **warn** 이다 — 정상 배포에서는 일어나지 않고 키 · 설정 실수나 사설망 안의 위조를 드러낸다.
  거부 로그에는 호출 서비스 · jti 를 남긴다. 서비스 토큰 원문 · 비밀 · 비밀에서 계산한 값(지문)은 남기지 않는다.
- internal 체인의 006 은 **error** 다 — 서비스 토큰 검증은 메모리의 비밀만 써서 저장소 장애가 있을 수 없다. 나면 서버 결함 신호다(사용자 체인의 006 은 warn).
- 보안 거부 로그의 경로는 **앞 세 구간만** 남기고 나머지를 가린다 — `/internal/v1/orders/4611686018427387904` → `/internal/v1/orders/…`.
  internal · 고객 경로에 주문번호 같은 식별자가 들어가기 때문이다 (`SecurityErrorResponder#maskPath`, 게이트웨이는 `GatewaySecurityErrorResponder#maskPath` — 같은 규칙).
- 게이트웨이의 거부 로그 수준은 서비스와 같다 — 001 · 002 · 003 · 004 는 debug, 006 은 warn, 로그인한 회원의 005 는 info(memberId · jti).
- **차단 목록 fail-open 통과 로그**(§7-1)는 warn 이지만 **1분에 한 번만** warn 이고 나머지는 debug 다 — 장애 동안 토큰 붙은 요청마다 warn 을 쓰면 로그가 폭주한다.
  jti · 메서드 · 마스킹 경로 · 예외 타입만 남긴다(`revocation list unavailable, token passed (fail-open)`). 어드민 경로의 006 은 응답 로그(warn)로 남는다.

## 9. 서비스 간 인증 (internal API, 1단계)

결정은 #43(2026-10-16), 구현은 #51 이다. internal API(`/internal/v1/**`)는 **서비스 토큰**이 있어야 열리고, 엔드포인트마다 허용 호출자를 403 으로 강제한다.

### 9-1. 방식

- 서비스 5종이 **같은 HS256 비밀**(`INTERNAL_JWT_SECRET`)로 짧은 서비스 토큰에 서명하고, 받는 쪽이 같은 비밀로 검증한다. Nimbus(security-core)를 그대로 쓴다.
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

internal 컨트롤러의 **모든 핸들러 메서드**에 허용 호출자를 선언한다. 체인은 "서비스 토큰이 있다" 까지만 보므로, 선언이 없으면 서비스 5종 누구나 부를 수 있다.

```java
@PreAuthorize("hasAuthority('SERVICE_order-service')")
@PostMapping("/internal/v1/stocks/reservations")
public ResponseEntity<Response<StockReservationResponse>> reserve(@RequestBody @Valid StockReservationRequest request) {
    return ResponseEntity.ok().body(Response.success(stockInternalUseCase.reserve(request)));
}
```

- 여럿이면 `hasAnyAuthority('SERVICE_order-service', 'SERVICE_payment-service')`. `isAuthenticated()` · `permitAll()` 은 쓰지 않는다.
- 호출 서비스가 필요하면 `@AuthenticationPrincipal ServicePrincipal caller` 로 받는다(`caller.serviceId()`). application 계층으로 넘기지 않는다.
- 사용자 식별이 필요하면 memberId 를 요청 본문 · 파라미터로 받는다 — 호출 서비스가 사용자 JWT 로 이미 검증한 값이다. 사용자 토큰은 internal 로 오지 않는다.
- **빠뜨린 메서드는 서비스 인가 스모크가 잡는다**(§9-10) — 서비스의 실제 MVC 매핑을 모두 돌며, `/internal/` 핸들러의 `@PreAuthorize`(메서드, 없으면 클래스) 식이
  서비스 권한만 나열한 `hasAuthority('SERVICE_…')` · `hasAnyAuthority('SERVICE_…', ...)` 그 자체인지 본다. `or isAuthenticated()` 처럼 넓힌 식도 실패다.
  #42 의 ArchUnit 은 정적 규칙(전역 `RequestInterceptor` 빈, `RequestContextHolder` 로 사용자 토큰 꺼내기 등)을 맡는다.
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

- 값은 Vault 에 두고 서비스 5종이 **같은 값**을 받는다. 저장소 밖에서 만들고 터미널 기록에 남기지 않는다 (`secret-management.md`).

| 기동을 멈추는 조건 | 메시지가 가리키는 것 |
|--------------------|----------------------|
| 비밀이 비었거나 `${INTERNAL_JWT_SECRET}` 가 풀리지 않았다 | `INTERNAL_JWT_SECRET` |
| 비밀이 base64 가 아니다 · 디코드하면 32바이트 미만이다 | 해당 환경변수 · `openssl rand -base64 32` |
| `previous-secret` 을 채웠는데 위 규칙에 어긋난다 | `INTERNAL_JWT_SECRET_PREVIOUS` |
| `service-id` 가 비었다 · 플레이스홀더가 풀리지 않았다 · 소문자 kebab 이 아니다 | `security.internal.service-id` |
| `token-ttl` 이 0 이하이거나 5분을 넘는다 | `security.internal.token-ttl` |

메시지에는 프로퍼티 · 환경변수 이름만 있고 비밀 내용은 없다(바인딩 실패 진단의 `Value` 에도 비밀이 실리지 않는 것을 테스트가 본다).

### 9-7. 비밀 교체 런북

받는 쪽이 새 비밀을 **먼저** 받아들인 뒤 서명 비밀을 바꾼다. 서비스 5종 모두 같은 값을 쓴다.

| 단계 | 서비스 5종 | 결과 |
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
- 바꾸는 것은 검증기 · 발급기뿐이다. 주체(`ServicePrincipal`) · 권한 이름(`SERVICE_<service-id>`) · 허용 목록 선언 · Feign Capability 경계 · 오류 코드는 그대로 둔다.

### 9-10. 테스트

- 비밀은 커밋하지 않는다. `TestServiceTokens`(테스트 픽스처)가 실행 중 32바이트 난수를 만들고, `SecurityTestPropertiesInitializer` 가 컨텍스트에 넣는다(§5-3 테스트 키).
  `TestServiceTokens.bearer(caller, audience)` 로 서비스 토큰을 만든다.
- 서비스 스모크(`ResourceServerSmokeTestSupport`)는 테스트 전용 핸들러 `/internal/v1/__security-smoke`(허용 호출자 `smoke-allowed-caller` 고정)로 본다.
  이 핸들러는 픽스처의 `InternalSecuritySmokeContextCustomizerFactory`(spring.factories)가 **모든 서블릿 테스트 컨텍스트**에 등록한다 —
  모든 테스트에 같은 커스터마이저라 `*ApplicationTests` 와 스모크가 컨텍스트를 계속 같이 쓴다. 리액티브 컨텍스트에는 등록하지 않는다.
- **인가 선언 누락 가드** — 같은 스모크의 `handlersDeclareAuthorization` 이 서비스 컨텍스트의 `RequestMappingHandlerMapping` 을 모두 돈다.
  `/internal/` 핸들러는 서비스 권한만 나열한 `hasAuthority` · `hasAnyAuthority` 식, `/api/` 핸들러는 `@PreAuthorize` 존재(식은 자유)를 요구하고 위반 핸들러를 목록으로 알린다.
  스모크 핸들러가 검사 대상에 들어 있는지도 단언해, 실제 핸들러가 없는 지금도 빈 목록으로 통과하지 않는다. 정적 규칙은 #42 ArchUnit 이 맡는다.
- 호출 쪽은 `InternalServiceTokenCapabilityTest` 가 실제 Feign 파이프라인(`Feign.builder()` + 앞 · 뒤 인터셉터 + 다른 Capability + 요청을 붙잡는 Client)을
  왕복해 최종 요청의 `Authorization` 이 서비스 토큰 하나인지 본다.
- internal 컨트롤러가 생기면 그 서비스의 `*SecuritySmokeTest` 나 컨트롤러 테스트에서 실제 경로의 허용 · 거부 호출자를 하나씩 본다.
