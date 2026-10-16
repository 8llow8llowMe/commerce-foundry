package com.commercefoundry.security.resourceserver;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertWwwAuthenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.internal.ServicePrincipal;
import com.commercefoundry.security.principal.MemberRole;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 서비스가 하는 그대로 {@link InternalResourceServerConfigurer} · {@link ResourceServerSecurityConfigurer} 와 두 프로퍼티 설정을 import 한 컨텍스트에서
 * 서비스 간 인증 체인을 본다 — 401 · 007 / 403 · 005 / 통과, 체인 순서, 사용자 체인과의 분리.
 *
 * <p>catch-all advice({@code Exception} → 500)를 일부러 두었다. 허용 목록 거부가 그쪽으로 새면 403 이 500 이 된다.
 * 서비스 토큰 비밀 · JWT 공개키는 테스트 픽스처의 {@code SecurityTestPropertiesInitializer} 가 넣는다.
 */
@SpringBootTest(
    classes = InternalResourceServerIntegrationTest.TestApplication.class,
    properties = "security.internal.service-id=" + InternalResourceServerIntegrationTest.SERVICE_ID)
@AutoConfigureMockMvc
class InternalResourceServerIntegrationTest {

    static final String SERVICE_ID = "product-service";
    private static final String ORDER = "order-service";
    private static final String PAYMENT = "payment-service";
    private static final String STOCK_PATH = "/internal/v1/stocks/reserve";
    private static final String ORDER_PATH = "/internal/v1/orders/x";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetCalls() {
        TestController.STOCK_CALLS.set(0);
    }

    // ── 401 · SECURITY_007 ────────────────────────────────────────

    @Test
    @DisplayName("서비스 토큰이 없으면 401 · SECURITY_007 · WWW-Authenticate: Bearer · 실패 봉투, 컨트롤러에 닿지 않는다")
    void missingToken() throws Exception {
        MockHttpServletResponse response = perform(get(STOCK_PATH));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
        assertThat(TestController.STOCK_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("매핑이 없는 internal 경로도 인증부터 한다 — 무토큰 401 · 007 (404 로 경로 존재를 알리지 않는다)")
    void unmappedInternalPathRequiresAuthenticationFirst() throws Exception {
        assertEnvelope(perform(get("/internal/v1/unknown")), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    @DisplayName("사용자 access 토큰(RS256 · at+jwt)은 401 · SECURITY_007 이다 — ADMIN 이어도 internal 을 부를 수 없다")
    void userAccessTokenIsRejected(MemberRole role) throws Exception {
        String userToken = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();

        assertEnvelope(perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertThat(TestController.STOCK_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("aud 가 다른 서비스면 401 · SECURITY_007 이다 — delivery-service 로 가는 토큰을 product-service 에 쓸 수 없다")
    void otherAudienceIsRejected() throws Exception {
        assertEnvelope(perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, "delivery-service"))),
            SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 비밀로 서명했거나 만료된 서비스 토큰은 401 · SECURITY_007 이다")
    void forgedOrExpiredIsRejected() throws Exception {
        Instant now = Instant.now();
        String forged = TestServiceTokens.sign(TestServiceTokens.OTHER_SECRET, liveClaims(ORDER, now).build());
        String expired = TestServiceTokens.sign(TestServiceTokens.SECRET, liveClaims(ORDER, now)
            .issueTime(Date.from(now.minus(Duration.ofMinutes(3)))).expirationTime(Date.from(now.minus(Duration.ofMinutes(2)))).build());

        assertEnvelope(perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertEnvelope(perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertThat(TestController.STOCK_CALLS).hasValue(0);
    }

    /** 문자열 템플릿은 {@code %} 를 다시 인코딩하므로 {@link URI} 로 보낸다. 체인 선택(securityMatcher)과 MVC 가 같은 디코딩으로 본다. */
    @Test
    @DisplayName("인코딩한 경로(/%69nternal/...)도 internal 체인에 걸린다 — 무토큰 401 · 007, 허용된 호출자만 200")
    void encodedInternalPathMatchesInternalChain() throws Exception {
        URI encoded = URI.create("/%69nternal/v1/stocks/reserve");

        assertEnvelope(perform(get(encoded)), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertThat(TestController.STOCK_CALLS).hasValue(0);
        assertThat(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, SERVICE_ID))).getStatus()).isEqualTo(200);
        assertThat(TestController.STOCK_CALLS).hasValue(1);
    }

    // ── 403 · SECURITY_005 ────────────────────────────────────────

    @Test
    @DisplayName("허용 목록에 없는 호출자는 catch-all advice 가 있어도 403 · SECURITY_005 · WWW-Authenticate 없음, 컨트롤러에 닿지 않는다")
    void callerOutsideAllowListIsForbidden() throws Exception {
        MockHttpServletResponse response = perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(PAYMENT, SERVICE_ID)));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertWwwAuthenticate(response, null);
        assertThat(TestController.STOCK_CALLS).hasValue(0);
    }

    // ── 통과 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("허용된 호출자는 통과하고 @AuthenticationPrincipal ServicePrincipal 로 호출 서비스를 받는다")
    void allowedCallerPasses() throws Exception {
        MockHttpServletResponse response = perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, SERVICE_ID)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"dataBody\":\"order-service\"");
        assertThat(TestController.STOCK_CALLS).hasValue(1);
    }

    @Test
    @DisplayName("허용 호출자가 여럿이면 각자 통과한다 — hasAnyAuthority")
    void anyOfAllowedCallersPasses() throws Exception {
        assertThat(perform(get(ORDER_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, SERVICE_ID))).getStatus()).isEqualTo(200);
        assertThat(perform(get(ORDER_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(PAYMENT, SERVICE_ID))).getStatus()).isEqualTo(200);
        assertEnvelope(perform(get(ORDER_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("delivery-service", SERVICE_ID))),
            SecurityErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("인증된 internal 요청도 세션을 만들지 않는다 (STATELESS)")
    void createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, SERVICE_ID))).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    // ── 사용자 체인과의 분리 ──────────────────────────────────────

    @Test
    @DisplayName("서비스 토큰으로 사용자 API 를 부르면 401 · SECURITY_003 이다 — 사용자 체인은 RS256 access 토큰만 받는다")
    void serviceTokenIsRejectedOnUserApi() throws Exception {
        assertEnvelope(perform(get("/api/v1/public").header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER, SERVICE_ID))),
            SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("사용자 체인은 그대로다 — 공개 API 무토큰 200, 어드민 경로 무토큰 401 · 001 · CUSTOMER 403 · 005")
    void userChainIsUnchanged() throws Exception {
        String customer = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        assertThat(perform(get("/api/v1/public")).getStatus()).isEqualTo(200);
        assertEnvelope(perform(get("/api/v1/admin/x")), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get("/api/v1/admin/x").header(HttpHeaders.AUTHORIZATION, customer)), SecurityErrorCode.FORBIDDEN);
    }

    /** 사용자 체인은 모든 요청에 걸리므로 마지막이어야 한다. internal 체인이 뒤에 있으면 /internal/** 이 사용자 체인의 denyAll 에 걸린다. */
    @Test
    @DisplayName("internal 체인이 사용자 체인보다 앞이고 /internal/** 에만 걸린다. JwtDecoder 빈은 사용자 체인의 것 하나다")
    void internalChainComesFirstAndOnlyMatchesInternalPaths() {
        List<SecurityFilterChain> chains = context.getBean(FilterChainProxy.class).getFilterChains();

        assertThat(chains).hasSize(2);
        assertThat(chains.getFirst().matches(request("/internal/v1/stocks/reserve"))).isTrue();
        assertThat(chains.getFirst().matches(request("/api/v1/products"))).isFalse();
        assertThat(chains.getFirst().matches(request("/actuator/health"))).isFalse();
        assertThat(chains.getLast().matches(request("/api/v1/products"))).isTrue();
        assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1).containsKey("accessTokenDecoder");
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static JWTClaimsSet.Builder liveClaims(String caller, Instant now) {
        return TestServiceTokens.claims(caller, SERVICE_ID).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)));
    }

    // ── 테스트 애플리케이션 ───────────────────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        InternalResourceServerConfigurer.class,
        InternalTokenPropertiesConfig.class,
        JwtVerificationPropertiesConfig.class,
        ResourceServerSecurityConfigurer.class,
        TestController.class,
        CatchAllAdvice.class
    })
    static class TestApplication {

    }

    @RestController
    static class TestController {

        static final AtomicInteger STOCK_CALLS = new AtomicInteger();

        /** 재고 선점처럼 order-service 만 부를 수 있는 internal API. */
        @PreAuthorize("hasAuthority('SERVICE_order-service')")
        @GetMapping(STOCK_PATH)
        ResponseEntity<Response<String>> reserve(@AuthenticationPrincipal ServicePrincipal caller) {
            STOCK_CALLS.incrementAndGet();
            return ResponseEntity.ok(Response.success(caller.serviceId()));
        }

        @PreAuthorize("hasAnyAuthority('SERVICE_order-service', 'SERVICE_payment-service')")
        @GetMapping(ORDER_PATH)
        ResponseEntity<Response<Void>> order() {
            return ResponseEntity.ok(Response.success());
        }

        @PreAuthorize("permitAll()")
        @GetMapping("/api/v1/public")
        ResponseEntity<Response<Void>> publicEndpoint() {
            return ResponseEntity.ok(Response.success());
        }

        @GetMapping("/api/v1/admin/x")
        ResponseEntity<Response<Void>> admin() {
            return ResponseEntity.ok(Response.success());
        }
    }

    /** 서비스에 흔한 "모든 예외 → 500" advice. 허용 목록 거부(AuthorizationDeniedException)가 여기로 오면 안 된다. */
    @RestControllerAdvice(assignableTypes = TestController.class)
    static class CatchAllAdvice {

        @ExceptionHandler(Exception.class)
        ResponseEntity<Response<Void>> handle(Exception exception) {
            return ResponseEntity.internalServerError().body(Response.fail("TEST_500", "catch-all"));
        }
    }
}
