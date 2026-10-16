package com.commercefoundry.security.resourceserver;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.ControllerAdviceBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스가 하는 그대로 {@link ResourceServerSecurityConfigurer} 와 {@link JwtVerificationPropertiesConfig} 를 import 한 컨텍스트에서
 * 토큰 없음 · 만료 · 위조 · 역할 부족이 {@code Response.fail} 봉투로 나가는지 본다.
 *
 * <p>서비스에 흔한 catch-all advice({@code Exception} → 500)를 일부러 두었다. 보안 예외가 그쪽으로 새면 403 이 500 이 된다.
 * 디코더는 Configurer 가 만든 실제 빈을 감싸, 정해 둔 두 토큰에만 검증 저장소 장애(#4 의 006)와 로그아웃 토큰(#4 의 004)을 흉내 낸다.
 * 공개키는 테스트 픽스처의 {@code SecurityTestPropertiesInitializer}(spring.factories)가 {@link TestKeys#CURRENT} 로 넣는다.
 */
@SpringBootTest(classes = ResourceServerSecurityIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class ResourceServerSecurityIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 디코더가 {@code JwtException}(Bad 아님)을 던진다 → Spring 이 {@code AuthenticationServiceException} 으로 감싼다. */
    private static final String STORE_DOWN_TOKEN = "verification-store-down";
    /** 디코더가 {@code OAuth2AuthenticationException(SECURITY_004)} 을 던진다 — #4 의 블랙리스트 확인 모양. */
    private static final String REVOKED_TOKEN = "revoked-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetCalls() {
        TestController.ADMIN_CALLS.set(0);
        TestController.INTERNAL_CALLS.set(0);
    }

    // ── 401 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 없이 인증 필요 API 에 오면 401 · SECURITY_001 · WWW-Authenticate: Bearer · 실패 봉투")
    void missingTokenOnAuthenticatedEndpoint() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/me"));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("만료된 토큰은 401 · SECURITY_002 — 보낸 토큰이 만료됐다")
    void expiredToken() throws Exception {
        String expired = token(Instant.now().minus(Duration.ofHours(2)), MemberRole.CUSTOMER);

        MockHttpServletResponse response = perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        assertEnvelope(response, SecurityErrorCode.TOKEN_EXPIRED);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer error=\"invalid_token\", error_description=");
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401 · SECURITY_003")
    void forgedSignature() throws Exception {
        String forged = TestTokens.sign(TestKeys.OTHER, TestTokens.header(TestKeys.CURRENT).build(), liveClaims(MemberRole.ADMIN));

        MockHttpServletResponse response = perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged));

        assertEnvelope(response, SecurityErrorCode.TOKEN_INVALID);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("공개 API 는 토큰 없이 200 이지만, 잘못된 토큰을 보내면 401 · SECURITY_003 이다 — 익명으로 넘어가지 않는다")
    void publicEndpointRejectsInvalidToken() throws Exception {
        assertThat(perform(get("/api/v1/public")).getStatus()).isEqualTo(200);

        assertEnvelope(perform(get("/api/v1/public").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")), SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("토큰 없이 어드민 경로에 오면 403 이 아니라 401 · SECURITY_001 — URL 규칙 · 애노테이션 모두")
    void missingTokenOnAdminEndpoints() throws Exception {
        assertEnvelope(perform(get("/api/v1/admin/x")), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get("/api/v1/annotated-admin")), SecurityErrorCode.UNAUTHORIZED);
        assertThat(TestController.ADMIN_CALLS).hasValue(0);
    }

    /** 컨트롤러에서 난 인증 예외는 필터가 아니라 SecurityExceptionAdvice#handleAuthentication 이 받는다. catch-all advice 보다 먼저다. */
    @Test
    @DisplayName("컨트롤러가 AuthenticationCredentialsNotFoundException 을 던지면 401 · SECURITY_001 · WWW-Authenticate: Bearer 봉투다")
    void authenticationExceptionFromControllerIsUnauthorized() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/credentials-missing").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    // ── 403 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER 가 /api/v1/admin/** 에 오면 403 · SECURITY_005 — 애노테이션이 없어도 URL 규칙이 막는다")
    void customerOnAdminPath() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/admin/x").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(TestController.ADMIN_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("CUSTOMER 가 hasRole('ADMIN') 메서드에 오면 catch-all advice 가 있어도 403 · SECURITY_005 다")
    void customerOnAnnotatedAdminMethod() throws Exception {
        assertEnvelope(perform(get("/api/v1/annotated-admin").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
        assertThat(TestController.ADMIN_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("보안 advice 는 @Bean 등록이어도 ControllerAdvice 로 인식되고 서비스 advice 보다 앞선다")
    void securityAdviceIsRegisteredFirst() {
        List<ControllerAdviceBean> advices = ControllerAdviceBean.findAnnotatedBeans(context);

        assertThat(advices).extracting(ControllerAdviceBean::getBeanType).containsSubsequence(SecurityExceptionAdvice.class, CatchAllAdvice.class);
    }

    // ── #4 경로: 006 · 004 ─────────────────────────────────────────

    @Test
    @DisplayName("토큰 검증 중 시스템 오류는 500 이 아니라 503 · SECURITY_006 봉투다 — 공개 API 여도 같다")
    void verificationFailureIsServiceUnavailable() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + STORE_DOWN_TOKEN));

        assertEnvelope(response, SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertEnvelope(perform(get("/api/v1/public").header(HttpHeaders.AUTHORIZATION, "Bearer " + STORE_DOWN_TOKEN)), SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("디코더가 OAuth2Error(SECURITY_004) 를 던지면 401 · SECURITY_004 — #4 의 로그아웃 토큰 경로")
    void revokedTokenIsTokenRevoked() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + REVOKED_TOKEN));

        assertEnvelope(response, SecurityErrorCode.TOKEN_REVOKED);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
    }

    // ── 200 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN 은 어드민 경로와 hasRole('ADMIN') 메서드를 통과한다")
    void adminPasses() throws Exception {
        assertThat(perform(get("/api/v1/admin/x").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(200);
        assertThat(perform(get("/api/v1/annotated-admin").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(200);
        assertThat(TestController.ADMIN_CALLS).hasValue(2);
    }

    @Test
    @DisplayName("@AuthenticationPrincipal MemberPrincipal 로 토큰의 memberId 를 받는다")
    void principalCarriesMemberId() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsByteArray()).get("dataBody").asString()).isEqualTo(String.valueOf(TestTokens.MEMBER_ID));
    }

    /**
     * 이 컨텍스트는 사용자 체인만 import 한다 — 서비스가 {@code InternalResourceServerConfigurer} 를 빠뜨린 모양이다.
     * 그래도 internal API 는 열리지 않는다(fail-closed). 서비스 토큰은 사용자 체인의 RS256 디코더를 통과하지 못해 003 이다.
     */
    @Test
    @DisplayName("internal 체인 없이 사용자 체인만 있으면 /internal/** 은 거부된다 — 무토큰 401 · 001, 로그인 403 · 005, 서비스 토큰 401 · 003")
    void internalEndpointIsDeniedWithoutInternalChain() throws Exception {
        String serviceToken = TestServiceTokens.bearer(TestServiceTokens.CALLER, TestServiceTokens.SERVICE_ID);

        assertEnvelope(perform(get("/internal/v1/x")), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get("/internal/v1/x").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))), SecurityErrorCode.FORBIDDEN);
        assertEnvelope(perform(get("/internal/v1/x").header(HttpHeaders.AUTHORIZATION, serviceToken)), SecurityErrorCode.TOKEN_INVALID);
        assertThat(TestController.INTERNAL_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("인증된 요청도 세션을 만들지 않는다 (STATELESS)")
    void createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    // ── 경로 우회 (#6 메모) ───────────────────────────────────────

    /**
     * 게이트웨이를 거치지 않고 서비스에 바로 닿아도, 정규화되면 어드민 경로가 되는 원문 경로로 URL 규칙을 우회하지 못한다.
     * Spring Security 의 방화벽(StrictHttpFirewall)이 필터 체인 앞에서 400 으로 끊는다 — 봉투 없는 응답이고 컨트롤러는 돌지 않는다.
     */
    @ParameterizedTest
    @MethodSource("com.commercefoundry.security.support.ResourceServerSmokeTestSupport#nonNormalizedAdminPaths")
    @DisplayName("정규화되면 어드민 경로가 되는 원문 경로는 CUSTOMER · 무토큰 모두 400 으로 끊기고 어드민 컨트롤러에 닿지 않는다")
    void pathTraversalDoesNotReachAdminController(String path) throws Exception {
        MockHttpServletResponse customer = perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));
        MockHttpServletResponse anonymous = perform(get(URI.create(path)));

        assertThat(customer.getStatus()).as(path).isEqualTo(400);
        assertThat(anonymous.getStatus()).as(path).isEqualTo(400);
        assertThat(TestController.ADMIN_CALLS).as(path).hasValue(0);
    }

    @Test
    @DisplayName("대소문자가 다른 경로(/API/v1/admin/x)는 어느 컨트롤러에도 매핑되지 않아 404 이고 어드민 컨트롤러에 닿지 않는다")
    void upperCasePathDoesNotReachAdminController() throws Exception {
        assertThat(perform(get("/API/v1/admin/x").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(404);
        assertThat(perform(get("/API/v1/admin/x")).getStatus()).isEqualTo(404);
        assertThat(TestController.ADMIN_CALLS).hasValue(0);
    }

    /**
     * 비예약 문자를 인코딩한 경로({@code %61} = {@code a})는 방화벽이 막지 않는다. 대신 URL 규칙과 MVC 가 <b>같은 디코딩</b>으로 본다 —
     * 보안 규칙에는 어드민 경로로 걸리고, 통과한 ADMIN 만 어드민 컨트롤러에 닿는다.
     */
    @Test
    @DisplayName("인코딩한 경로(/api/v1/%61dmin/x)도 어드민 URL 규칙에 걸린다 — CUSTOMER 403 · 무토큰 401, ADMIN 만 200")
    void encodedUnreservedCharacterStillMatchesAdminRule() throws Exception {
        URI encoded = URI.create("/api/v1/%61dmin/x");

        assertEnvelope(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
        assertEnvelope(perform(get(encoded)), SecurityErrorCode.UNAUTHORIZED);
        assertThat(TestController.ADMIN_CALLS).hasValue(0);

        assertThat(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(200);
        assertThat(TestController.ADMIN_CALLS).hasValue(1);
    }

    /** Spring Security 7 이 자동으로 켜는 DPoP 필터의 응답이다 — 우리 봉투가 아니다(security-guide.md 알려진 예외). */
    @Test
    @DisplayName("Authorization: DPoP 요청은 유효한 ADMIN 토큰이어도 봉투 없는 401 로 끝나고 컨트롤러에 닿지 않는다")
    void dpopRequestIsRejectedWithoutEnvelope() throws Exception {
        String adminToken = token(Instant.now(), MemberRole.ADMIN);

        MockHttpServletResponse response = perform(get("/api/v1/admin/x").header(HttpHeaders.AUTHORIZATION, "DPoP " + adminToken));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("DPoP");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(TestController.ADMIN_CALLS).hasValue(0);
    }

    /** Spring Security 7 의 Resource Server 가 자동으로 여는 메타데이터 엔드포인트다(security-guide.md 알려진 예외). */
    @Test
    @DisplayName("/.well-known/oauth-protected-resource 는 토큰 없이 200 으로 메타데이터를 내준다 — 게이트웨이가 라우팅하지 않는다")
    void protectedResourceMetadataIsPublic() throws Exception {
        MockHttpServletResponse response = perform(get("/.well-known/oauth-protected-resource"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsByteArray()).has("resource")).isTrue();
    }

    // ── actuator ─────────────────────────────────────────────────

    @Test
    @DisplayName("/actuator/health 는 토큰 없이 200 이다")
    void healthIsPublic() throws Exception {
        MockHttpServletResponse response = perform(get("/actuator/health"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsByteArray()).get("status").asString()).isEqualTo("UP");
    }

    /** 노출 설정이 1차 방어다. 노출하지 않은 엔드포인트는 actuator 규칙에 걸리지 않고 매핑이 없어 404 다. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/env", "/actuator/heapdump"})
    @DisplayName("노출하지 않은 actuator 엔드포인트는 무토큰 · ADMIN 모두 404 다")
    void unexposedEndpointIsNotFound(String path) throws Exception {
        assertThat(perform(get(path)).getStatus()).as(path).isEqualTo(404);
        assertThat(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).as(path).isEqualTo(404);
    }

    @Test
    @DisplayName("actuator 링크(/actuator)는 무토큰 401 · SECURITY_001, 로그인해도 403 · SECURITY_005 다")
    void actuatorLinksAreDenied() throws Exception {
        assertEnvelope(perform(get("/actuator")), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get("/actuator").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))), SecurityErrorCode.FORBIDDEN);
    }

    /** 바깥 클래스의 {@code mockMvc} 는 바깥 컨텍스트 것이라 쓰지 않는다. 노출 범위를 바꾼 컨텍스트의 것을 따로 받는다. */
    @Nested
    @TestPropertySource(properties = {"management.endpoints.web.exposure.include=health,info", "management.endpoint.health.show-components=always"})
    @DisplayName("actuator 노출을 health,info 로 넓혀도")
    class ExposedBeyondHealth {

        @Autowired
        private MockMvc mockMvc;

        /** 구성 요소 경로는 show-components 를 켜야 생긴다. 켠 상태에서 하위 경로도 health 규칙에 들어가는지 본다. */
        @Test
        @DisplayName("health 는 하위 경로(/actuator/health/ping)까지 그대로 열려 있다")
        void healthStaysPublic() throws Exception {
            assertThat(perform(mockMvc, get("/actuator/health")).getStatus()).isEqualTo(200);
            assertThat(perform(mockMvc, get("/actuator/health/ping")).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("health 가 아닌 노출 엔드포인트(/actuator/info)는 무토큰 401 · SECURITY_001, CUSTOMER · ADMIN 403 · SECURITY_005 다")
        void otherExposedEndpointIsDenied() throws Exception {
            assertEnvelope(perform(mockMvc, get("/actuator/info")), SecurityErrorCode.UNAUTHORIZED);
            assertEnvelope(perform(mockMvc, get("/actuator/info").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
            assertEnvelope(perform(mockMvc, get("/actuator/info").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))), SecurityErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("링크(/actuator)도 무토큰 401 · SECURITY_001 이다")
        void linksAreDenied() throws Exception {
            assertEnvelope(perform(mockMvc, get("/actuator")), SecurityErrorCode.UNAUTHORIZED);
        }
    }

    @Nested
    @TestPropertySource(properties = {"management.endpoints.web.exposure.include=health,info", "management.endpoints.web.base-path=/manage"})
    @DisplayName("management base-path 를 /manage 로 바꾸면 규칙이 따라간다")
    class CustomBasePath {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("/manage/health 는 열리고 /manage/info · /manage 는 막힌다")
        void rulesFollowBasePath() throws Exception {
            assertThat(perform(mockMvc, get("/manage/health")).getStatus()).isEqualTo(200);
            assertEnvelope(perform(mockMvc, get("/manage/info")), SecurityErrorCode.UNAUTHORIZED);
            assertEnvelope(perform(mockMvc, get("/manage/info").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))), SecurityErrorCode.FORBIDDEN);
            assertEnvelope(perform(mockMvc, get("/manage")), SecurityErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("옛 경로(/actuator/info)는 엔드포인트가 아니어서 404 다")
        void oldBasePathIsNotFound() throws Exception {
            assertThat(perform(mockMvc, get("/actuator/info")).getStatus()).isEqualTo(404);
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return perform(mockMvc, request);
    }

    private static MockHttpServletResponse perform(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + token(Instant.now(), role);
    }

    private static String token(Instant issuedAt, MemberRole role) {
        return TestTokens.issuer(issuedAt).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private static JWTClaimsSet liveClaims(MemberRole role) {
        Instant now = Instant.now();
        return TestTokens.claims().claim("role", role.name())
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofMinutes(30)))).build();
    }

    // ── 테스트 애플리케이션 ───────────────────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class, TestController.class, CatchAllAdvice.class})
    static class TestApplication {

        /** Configurer 의 실제 디코더 빈을 감싼다. 정해 둔 두 토큰 말고는 그대로 실제 디코더가 판정한다. */
        @Bean
        static BeanPostProcessor failureInjectingDecoder() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof JwtDecoder real)) {
                        return bean;
                    }
                    return (JwtDecoder) token -> switch (token) {
                        case STORE_DOWN_TOKEN -> throw new JwtException("verification store unavailable");
                        case REVOKED_TOKEN -> throw new OAuth2AuthenticationException(new OAuth2Error(SecurityErrorCode.TOKEN_REVOKED.getCode()));
                        default -> real.decode(token);
                    };
                }
            };
        }
    }

    @RestController
    static class TestController {

        static final AtomicInteger ADMIN_CALLS = new AtomicInteger();
        static final AtomicInteger INTERNAL_CALLS = new AtomicInteger();

        /** 공개 API 도 permitAll() 을 명시한다 — 빠뜨린 메서드가 리뷰에서 보이게 (security-guide.md §5-1). */
        @PreAuthorize("permitAll()")
        @GetMapping("/api/v1/public")
        ResponseEntity<Response<Void>> publicEndpoint() {
            return ResponseEntity.ok(Response.success());
        }

        @PreAuthorize("isAuthenticated()")
        @GetMapping("/api/v1/me")
        ResponseEntity<Response<String>> me(@AuthenticationPrincipal MemberPrincipal member) {
            return ResponseEntity.ok(Response.success(String.valueOf(member.memberId())));
        }

        /** 애노테이션 없이 URL 규칙({@code /api/v1/admin/**})만으로 막히는지 본다. */
        @GetMapping("/api/v1/admin/x")
        ResponseEntity<Response<Void>> admin() {
            ADMIN_CALLS.incrementAndGet();
            return ResponseEntity.ok(Response.success());
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/v1/annotated-admin")
        ResponseEntity<Response<Void>> annotatedAdmin() {
            ADMIN_CALLS.incrementAndGet();
            return ResponseEntity.ok(Response.success());
        }

        /** 사용자 체인의 {@code denyAll} 만으로 막히는지 본다 — 애노테이션이 있으면 그것이 막았는지 구분되지 않는다. */
        @GetMapping("/internal/v1/x")
        ResponseEntity<Response<Void>> internal() {
            INTERNAL_CALLS.incrementAndGet();
            return ResponseEntity.ok(Response.success());
        }

        /** 서비스 코드가 SecurityContext 를 직접 읽다 인증 정보를 못 찾은 경우를 흉내 낸다. */
        @PreAuthorize("permitAll()")
        @GetMapping("/api/v1/credentials-missing")
        ResponseEntity<Response<Void>> credentialsMissing() {
            throw new AuthenticationCredentialsNotFoundException("no credentials in security context");
        }
    }

    /**
     * 서비스에 흔한 "모든 예외 → 500" advice. 보안 예외가 여기로 오면 안 된다.
     * 도메인 advice 가 {@code basePackages} 로 범위를 좁히듯 테스트 컨트롤러로 좁힌다 — 매핑 없는 경로(404)까지 500 으로 바꾸지 않게.
     */
    @RestControllerAdvice(assignableTypes = TestController.class)
    static class CatchAllAdvice {

        @ExceptionHandler(Exception.class)
        ResponseEntity<Response<Void>> handle(Exception exception) {
            return ResponseEntity.internalServerError().body(Response.fail("TEST_500", "catch-all"));
        }
    }
}
