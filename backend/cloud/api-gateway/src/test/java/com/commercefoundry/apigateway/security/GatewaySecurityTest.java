package com.commercefoundry.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.support.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 보안 체인 — 실제 서버 + 실제 디코더(테스트 픽스처 키)로 본다 ({@code security-guide.md} §7).
 *
 * <p>서비스 대신 JDK {@link HttpServer} 하나를 서비스 전부의 인스턴스로 등록해(simple discovery) 하류에 닿았는지 · 넘어간 헤더를 기록한다.
 * 차단 목록 조회기는 스텁({@link StubRevocationChecker})으로 바꿔 차단 · 저장소 장애를 흉내 낸다. Redis 구현은 단위 테스트가 본다.
 *
 * <p>토큰은 지금 시각으로 만든다 — 게이트웨이 디코더는 시스템 시계를 쓴다. 테스트는 순차 실행을 가정한다 (스텁 상태 · 하류 기록을 테스트마다 비운다).
 */
@GatewayIntegrationTest
@Import(GatewaySecurityTest.StubRevocationConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewaySecurityTest {

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service", "operations-service");

    private static final String PUBLIC_PATH = "/api/v1/products/1";
    private static final String ADMIN_PATH = "/api/v1/admin/orders/1";
    private static final String ADMIN_MEMBERS_PATH = "/api/v1/admin/members";
    private static final String ADMIN_LOGIN_PATH = "/api/v1/auth/admin/login";
    private static final String ADMIN_JOBS_PATH = "/api/v1/admin/jobs/1";
    private static final String ADMIN_NOTIFICATIONS_PATH = "/api/v1/admin/notifications/1";

    private static final String BEARER = "Bearer ";
    private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\"";
    private static final String EXPIRED = "Bearer error=\"invalid_token\", error_description=\"The access token expired\"";

    /** 하류(가짜 서비스)가 받은 요청 — 경로와 헤더. */
    private static final Queue<UpstreamRequest> UPSTREAM_REQUESTS = new ConcurrentLinkedQueue<>();

    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private StubRevocationChecker revocationChecker;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerServiceInstances(DynamicPropertyRegistry registry) {
        String upstreamUri = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        for (String serviceId : SERVICE_IDS) {
            registry.add("spring.cloud.discovery.client.simple.instances." + serviceId + "[0].uri", () -> upstreamUri);
        }
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @BeforeEach
    void reset() {
        UPSTREAM_REQUESTS.clear();
        revocationChecker.reset();
    }

    // ── 토큰 없음 ─────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 없는 공개 경로 요청은 하류로 그대로 간다 — 판정은 서비스가 한다")
    void anonymousPublicRequestIsRouted() {
        webTestClient.get().uri(PUBLIC_PATH).exchange().expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo(PUBLIC_PATH);
            assertThat(request.header(HttpHeaders.AUTHORIZATION)).isNull();
        });
    }

    @Test
    @DisplayName("토큰 없는 어드민 요청은 401 + SECURITY_001 + WWW-Authenticate: Bearer 이고 하류에 닿지 않는다")
    void anonymousAdminRequestIsUnauthorized() {
        assertRejected(webTestClient.get().uri(ADMIN_PATH).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
    }

    @Test
    @DisplayName("허용 origin 의 실제 요청이 401 을 받아도 Access-Control-Allow-Origin 이 실린다 — 브라우저가 오류 본문을 읽을 수 있다")
    void securityErrorCarriesCorsHeaders() {
        webTestClient.get().uri(ADMIN_PATH)
            .header(HttpHeaders.ORIGIN, GatewayIntegrationTest.ALLOWED_ORIGIN)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, GatewayIntegrationTest.ALLOWED_ORIGIN);

        assertThat(UPSTREAM_REQUESTS).isEmpty();
    }

    // ── 역할 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER 토큰의 어드민 요청은 403 + SECURITY_005 이고 WWW-Authenticate 가 없다")
    void customerOnAdminPathIsForbidden() {
        assertRejected(get(ADMIN_PATH, token(MemberRole.CUSTOMER)), SecurityErrorCode.FORBIDDEN, null);
    }

    // ── 어드민 경로 변형 ───────────────────────────────────────────

    /**
     * 비예약 문자 인코딩({@code %61} = {@code a})과 끝 슬래시는 요청 가드를 지나 라우트에도 걸린다 — 인가 규칙도 같은 경로로 봐야 한다.
     * WebTestClient 의 문자열 URI 는 {@code %} 를 다시 인코딩하므로 원문 {@link URI} 로 보낸다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/%61dmin/orders/1", "/api/v1/admin/orders/1/"})
    @DisplayName("인코딩 · 끝 슬래시 변형 어드민 경로도 무토큰 401(001) · CUSTOMER 403(005)이고 하류에 닿지 않는다")
    void adminPathVariantsAreProtected(String rawPath) {
        assertRejected(webTestClient.get().uri(rawUri(rawPath)).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(webTestClient.get().uri(rawUri(rawPath)).header(HttpHeaders.AUTHORIZATION, BEARER + token(MemberRole.CUSTOMER)).exchange(),
            SecurityErrorCode.FORBIDDEN, null);
    }

    @Test
    @DisplayName("인코딩된 어드민 경로도 어드민으로 보아 차단 목록 장애 시 503(006)이다 — fail-open 으로 새지 않는다")
    void encodedAdminPathFailsClosed() {
        revocationChecker.failWith(new RedisConnectionFailureException("simulated outage"));

        assertRejected(webTestClient.get().uri(rawUri("/api/v1/%61dmin/orders/1")).header(HttpHeaders.AUTHORIZATION, BEARER + token(MemberRole.ADMIN)).exchange(),
            SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE, null);
    }

    @Test
    @DisplayName("대소문자만 다른 어드민 경로는 인가 규칙에도 라우트에도 걸리지 않아 404(GATEWAY_001)이고 하류에 닿지 않는다")
    void differentCaseAdminPathIsNotRouted() {
        webTestClient.get().uri("/api/v1/Admin/orders/1")
            .header(HttpHeaders.AUTHORIZATION, BEARER + token(MemberRole.CUSTOMER))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_001");

        assertThat(UPSTREAM_REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("ADMIN 토큰의 어드민 요청은 하류로 가고, Authorization 은 그대로 넘어가며 검증 결과 헤더를 만들지 않는다")
    void adminOnAdminPathIsRoutedWithOriginalAuthorization() {
        String adminToken = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();

        get(ADMIN_PATH, adminToken).expectStatus().isOk().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo(ADMIN_PATH);
            assertThat(request.header(HttpHeaders.AUTHORIZATION)).isEqualTo(BEARER + adminToken);
            assertThat(request.header("X-Member-Id")).isNull();
        });
    }

    // ── 어드민 회원 조회 · 관리자 로그인 (#6) ─────────────────────

    @Test
    @DisplayName("어드민 회원 조회(/api/v1/admin/members)는 무토큰 401(001) · CUSTOMER 403(005)이고 하류에 닿지 않는다")
    void adminMembersRejectsAnonymousAndCustomer() {
        assertRejected(webTestClient.get().uri(ADMIN_MEMBERS_PATH).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(get(ADMIN_MEMBERS_PATH, token(MemberRole.CUSTOMER)), SecurityErrorCode.FORBIDDEN, null);
    }

    @Test
    @DisplayName("ADMIN 토큰의 어드민 회원 조회는 auth-service 라우트로 쿼리 문자열과 함께 그대로 간다")
    void adminMembersIsRoutedForAdmin() {
        get(ADMIN_MEMBERS_PATH + "?page=0&size=20", token(MemberRole.ADMIN)).expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> assertThat(request.path()).isEqualTo(ADMIN_MEMBERS_PATH));
    }

    /**
     * 하류 Tomcat 이 정규화하면 {@code /api/v1/admin/members} 가 되는 원문 경로. 고객 라우트({@code /api/v1/members/**})에 걸려도 요청 가드가 라우팅 ·
     * 인가 전에 끊는다 — 어드민 판정이 원문 경로 접두어로 비껴가지 않는다 (#3 메모).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/members/../admin/members",
        "/api/v1/members/%2e%2e/admin/members",
        "/api/v1/members/%2E%2E/admin/members",
        "/api/v1/members/..;/admin/members",
        "/api/v1/admin;x=1/members",
        "/api/v1/members/me/..%2f..%2fadmin/members",
        "/api/v1/members/me/..%2F..%2Fadmin/members",
        "/api/v1/admin%2Fmembers",
        "/api/v1/members/%252e%252e/admin/members"
    })
    @DisplayName("어드민 회원 조회로 정규화되는 우회 경로(.. · %2e%2e · ; · %2f/%2F · 이중 인코딩 %252e)는 CUSTOMER · 무토큰 모두 400 + GATEWAY_004 이고 하류에 닿지 않는다")
    void adminMembersBypassPathsAreRejected(String rawPath) {
        for (String authorization : new String[] {null, BEARER + token(MemberRole.CUSTOMER)}) {
            WebTestClient.RequestHeadersSpec<?> request = webTestClient.get().uri(rawUri(rawPath));
            if (authorization != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            request.exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_004");
        }

        assertThat(UPSTREAM_REQUESTS).as("하류에 닿지 않는다").isEmpty();
    }

    @Test
    @DisplayName("인코딩한 어드민 회원 경로(/api/v1/%61dmin/members)도 어드민으로 본다 — 무토큰 401 · CUSTOMER 403")
    void encodedAdminMembersPathIsProtected() {
        assertRejected(webTestClient.get().uri(rawUri("/api/v1/%61dmin/members")).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(webTestClient.get().uri(rawUri("/api/v1/%61dmin/members")).header(HttpHeaders.AUTHORIZATION, BEARER + token(MemberRole.CUSTOMER)).exchange(),
            SecurityErrorCode.FORBIDDEN, null);
    }

    @Test
    @DisplayName("끝 슬래시 어드민 회원 경로(/api/v1/admin/members/)도 어드민으로 본다 — 무토큰 401 · CUSTOMER 403, 하류에 닿지 않는다")
    void trailingSlashAdminMembersPathIsProtected() {
        String trailing = ADMIN_MEMBERS_PATH + "/";

        assertRejected(webTestClient.get().uri(trailing).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(get(trailing, token(MemberRole.CUSTOMER)), SecurityErrorCode.FORBIDDEN, null);
    }

    @Test
    @DisplayName("관리자 로그인(/api/v1/auth/admin/login)은 어드민 패턴 밖이라 토큰 없이 하류로 간다 — 판정은 auth-service 가 한다")
    void adminLoginIsRoutedWithoutToken() {
        webTestClient.post().uri(ADMIN_LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo(ADMIN_LOGIN_PATH);
            assertThat(request.header(HttpHeaders.AUTHORIZATION)).isNull();
        });
    }

    // ── 어드민 작업 (#212) ─────────────────────────────────────────

    @Test
    @DisplayName("어드민 작업(/api/v1/admin/jobs/**)은 무토큰 401(001) · CUSTOMER 403(005)이고, ADMIN 은 operations-service 라우트로 그대로 간다")
    void adminJobsIsProtectedAndRoutedForAdmin() {
        assertRejected(webTestClient.get().uri(ADMIN_JOBS_PATH).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(get(ADMIN_JOBS_PATH, token(MemberRole.CUSTOMER)), SecurityErrorCode.FORBIDDEN, null);

        get(ADMIN_JOBS_PATH, token(MemberRole.ADMIN)).expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> assertThat(request.path()).isEqualTo(ADMIN_JOBS_PATH));
    }

    @Test
    @DisplayName("어드민 알림(/api/v1/admin/notifications/**)은 무토큰 401(001) · CUSTOMER 403(005)이고, ADMIN 은 operations-service 라우트로 그대로 간다")
    void adminNotificationsIsProtectedAndRoutedForAdmin() {
        assertRejected(webTestClient.get().uri(ADMIN_NOTIFICATIONS_PATH).exchange(), SecurityErrorCode.UNAUTHORIZED, "Bearer");
        assertRejected(get(ADMIN_NOTIFICATIONS_PATH, token(MemberRole.CUSTOMER)), SecurityErrorCode.FORBIDDEN, null);

        get(ADMIN_NOTIFICATIONS_PATH, token(MemberRole.ADMIN)).expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> assertThat(request.path()).isEqualTo(ADMIN_NOTIFICATIONS_PATH));
    }

    @Test
    @DisplayName("CUSTOMER 토큰의 공개 경로 요청은 Authorization 그대로 하류로 간다")
    void customerOnPublicPathIsRouted() {
        String customerToken = token(MemberRole.CUSTOMER);

        // 프록시한 응답에는 게이트웨이가 Cache-Control 을 붙이지 않는다 (하류가 보내지 않았다)
        get(PUBLIC_PATH, customerToken).expectStatus().isOk().expectHeader().doesNotExist(HttpHeaders.CACHE_CONTROL);

        assertThat(UPSTREAM_REQUESTS).singleElement()
            .satisfies(request -> assertThat(request.header(HttpHeaders.AUTHORIZATION)).isEqualTo(BEARER + customerToken));
    }

    // ── 토큰 잘못 ─────────────────────────────────────────────────

    @Test
    @DisplayName("공개 경로여도 틀린 서명 토큰은 401 + SECURITY_003 이다 — 틀린 토큰을 익명으로 취급하지 않는다")
    void wrongSignatureIsInvalid() {
        assertRejected(get(PUBLIC_PATH, token(TestKeys.OTHER, MemberRole.CUSTOMER, UUID.randomUUID().toString(), Instant.now())),
            SecurityErrorCode.TOKEN_INVALID, INVALID_TOKEN);
    }

    @Test
    @DisplayName("쉼표로 합친 Authorization 값 하나는 401 + SECURITY_003 이고 하류에 닿지 않는다 — 두 토큰 중 하나를 고르지 않는다")
    void commaJoinedAuthorizationIsInvalid() {
        String joined = BEARER + token(MemberRole.ADMIN) + ", " + BEARER + token(MemberRole.CUSTOMER);

        assertRejected(webTestClient.get().uri(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, joined).exchange(),
            SecurityErrorCode.TOKEN_INVALID, INVALID_TOKEN);
    }

    @Test
    @DisplayName("Bearer 뒤가 토큰 모양이 아니면 401 + SECURITY_003 이다")
    void malformedBearerIsInvalid() {
        assertRejected(webTestClient.get().uri(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not a token").exchange(),
            SecurityErrorCode.TOKEN_INVALID, INVALID_TOKEN);
    }

    @Test
    @DisplayName("만료 토큰은 401 + SECURITY_002 이고 WWW-Authenticate 에 만료 설명이 붙는다")
    void expiredTokenIsExpired() {
        String expired = token(TestKeys.CURRENT, MemberRole.CUSTOMER, UUID.randomUUID().toString(), Instant.now().minus(Duration.ofHours(2)));

        assertRejected(get(PUBLIC_PATH, expired), SecurityErrorCode.TOKEN_EXPIRED, EXPIRED);
    }

    // ── 로그아웃 차단 목록 ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_PATH, ADMIN_PATH})
    @DisplayName("차단 목록에 있는 jti 는 공개 · 어드민 경로 모두 401 + SECURITY_004 이다")
    void revokedTokenIsRejected(String path) {
        String jti = UUID.randomUUID().toString();
        revocationChecker.revoke(jti);

        assertRejected(get(path, token(TestKeys.CURRENT, MemberRole.ADMIN, jti, Instant.now())), SecurityErrorCode.TOKEN_REVOKED, INVALID_TOKEN);
        assertThat(revocationChecker.checkedJtis()).containsExactly(jti);
    }

    @Test
    @DisplayName("차단 목록 장애 시 공개 경로는 통과한다 (fail-open)")
    void storageFailureOnPublicPathFailsOpen() {
        revocationChecker.failWith(new RedisConnectionFailureException("simulated outage"));

        get(PUBLIC_PATH, token(MemberRole.CUSTOMER)).expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).hasSize(1);
    }

    @Test
    @DisplayName("차단 목록 장애 시 어드민 경로는 503 + SECURITY_006 봉투다 (fail-closed, 봉투 없는 500 이 아니다)")
    void storageFailureOnAdminPathFailsClosed() {
        revocationChecker.failWith(new RedisConnectionFailureException("simulated outage"));

        assertRejected(get(ADMIN_PATH, token(MemberRole.ADMIN)), SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE, null);
    }

    @Test
    @DisplayName("차단 목록 조회가 응답하지 않으면 상한 시간 뒤 어드민 경로는 503 + SECURITY_006 이다")
    void storageHangOnAdminPathFailsClosed() {
        revocationChecker.hang();

        assertRejected(get(ADMIN_PATH, token(MemberRole.ADMIN)), SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE, null);
    }

    @Test
    @DisplayName("서명이 틀린 토큰은 차단 목록을 조회하지 않는다 — 장애 중에도 003 이다")
    void invalidTokenSkipsRevocationLookup() {
        revocationChecker.failWith(new RedisConnectionFailureException("simulated outage"));

        assertRejected(get(ADMIN_PATH, token(TestKeys.OTHER, MemberRole.ADMIN, UUID.randomUUID().toString(), Instant.now())),
            SecurityErrorCode.TOKEN_INVALID, INVALID_TOKEN);
        assertThat(revocationChecker.checkedJtis()).isEmpty();
    }

    // ── 요청 가드 (GatewayRequestGuardFilter) ──────────────────────

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_PATH, ADMIN_PATH})
    @DisplayName("Authorization 이 두 개면 어느 토큰도 검증하지 않고 400 + GATEWAY_004 이며 하류에 닿지 않는다")
    void multipleAuthorizationHeadersAreRejected(String path) {
        String adminToken = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();

        webTestClient.get().uri(path)
            .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken, BEARER + token(MemberRole.CUSTOMER))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_004");

        assertThat(UPSTREAM_REQUESTS).as("하류에 닿지 않는다").isEmpty();
        assertThat(revocationChecker.checkedJtis()).as("보안 체인보다 앞이라 차단 목록을 조회하지 않는다").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/skus?skuIds=1", "/internal", "/INTERNAL/v1/skus", "/Internal/v1/orders/1", "/%69nternal/v1/skus"})
    @DisplayName("/internal 은 ADMIN 토큰이어도 토큰 검증 전에 404 + GATEWAY_001 이고 하류에 닿지 않는다 — 대소문자 · 인코딩 변형 포함")
    void internalPathIsRejectedBeforeAuthentication(String rawPath) {
        revocationChecker.failWith(new RedisConnectionFailureException("simulated outage"));
        String adminToken = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();

        webTestClient.get().uri(rawUri(rawPath))
            .header(HttpHeaders.AUTHORIZATION, BEARER + adminToken)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_001");

        assertThat(UPSTREAM_REQUESTS).as("하류에 닿지 않는다").isEmpty();
        assertThat(revocationChecker.checkedJtis()).as("차단 목록 장애 중에도 503 이 아니라 404 다 — 조회 전에 끝난다").isEmpty();
    }

    @Test
    @DisplayName("경로 중간에 internal 세그먼트가 있는 공개 경로는 막지 않고 하류로 간다 — 맨 앞 세그먼트만 본다")
    void internalSegmentInsidePublicPathIsRouted() {
        String path = "/api/v1/products/internal";

        webTestClient.get().uri(path).exchange().expectStatus().isOk();

        assertThat(UPSTREAM_REQUESTS).singleElement().satisfies(request -> assertThat(request.path()).isEqualTo(path));
    }

    // ── 구성 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("resolver 빈이 있어 Boot 가 생성 비밀번호 사용자(ReactiveUserDetailsService)를 만들지 않는다")
    void noGeneratedUserDetailsService() {
        assertThat(applicationContext.getBeanNamesForType(ReactiveAuthenticationManagerResolver.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ReactiveUserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("게이트웨이 헬스는 Redis 없이도 200 UP 이다 — Redis 장애로 LB 가 게이트웨이를 빼지 않는다 (fail-open 유지)")
    void healthIsUpWithoutRedis() {
        webTestClient.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private URI rawUri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }

    private WebTestClient.ResponseSpec get(String path, String token) {
        return webTestClient.get().uri(path).header(HttpHeaders.AUTHORIZATION, BEARER + token).exchange();
    }

    /** 상태 · 봉투 바이트(서비스와 같은 {@link SecurityErrorEnvelope}) · {@code WWW-Authenticate} · 세션 쿠키 없음 · 하류 미도달. */
    private void assertRejected(WebTestClient.ResponseSpec response, SecurityErrorCode errorCode, String wwwAuthenticate) {
        EntityExchangeResult<byte[]> result = response
            .expectStatus().isEqualTo(errorCode.getHttpStatus())
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
            .expectHeader().doesNotExist("Strict-Transport-Security")
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody().returnResult();

        assertThat(result.getResponseBody()).as("서비스와 같은 봉투 바이트").isEqualTo(new SecurityErrorEnvelope(jsonMapper).body(errorCode));
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).as(HttpHeaders.WWW_AUTHENTICATE).isEqualTo(wwwAuthenticate);
        assertThat(UPSTREAM_REQUESTS).as("하류에 닿지 않는다").isEmpty();
    }

    private static String token(MemberRole role) {
        return token(TestKeys.CURRENT, role, UUID.randomUUID().toString(), Instant.now());
    }

    private static String token(KeyPair keyPair, MemberRole role, String jti, Instant issuedAt) {
        return TestTokens.sign(keyPair, TestTokens.claims()
            .claim(MemberRole.CLAIM_NAME, role.name())
            .jwtID(jti)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(TestTokens.ACCESS_TOKEN_TTL)))
            .build());
    }

    /** 어떤 경로든 받은 경로 · 헤더를 기록하고 {@code {}} 로 답한다. */
    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_REQUESTS.add(new UpstreamRequest(exchange.getRequestURI().getRawPath(), Map.copyOf(exchange.getRequestHeaders())));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JDK {@code Headers} 의 키는 첫 글자만 대문자로 정규화된다. 대소문자 없이 찾는다. */
    private record UpstreamRequest(String path, Map<String, List<String>> headers) {

        String header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> String.join(",", entry.getValue()))
                .findFirst()
                .orElse(null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRevocationConfig {

        @Bean
        @Primary
        StubRevocationChecker stubRevocationChecker() {
            return new StubRevocationChecker();
        }
    }

    /** 차단 목록 · 장애를 흉내 내는 조회기. 조회된 jti 를 기록한다. */
    static final class StubRevocationChecker implements AccessTokenRevocationChecker {

        private final Set<String> revoked = ConcurrentHashMap.newKeySet();
        private final Queue<String> checked = new ConcurrentLinkedQueue<>();
        private volatile Mono<Boolean> override;

        @Override
        public Mono<Boolean> isRevoked(String jti) {
            checked.add(jti);
            Mono<Boolean> current = override;
            return current != null ? current : Mono.just(revoked.contains(jti));
        }

        void revoke(String jti) {
            revoked.add(jti);
        }

        void failWith(RuntimeException failure) {
            override = Mono.error(failure);
        }

        void hang() {
            override = Mono.never();
        }

        List<String> checkedJtis() {
            return List.copyOf(checked);
        }

        void reset() {
            revoked.clear();
            checked.clear();
            override = null;
        }
    }
}
