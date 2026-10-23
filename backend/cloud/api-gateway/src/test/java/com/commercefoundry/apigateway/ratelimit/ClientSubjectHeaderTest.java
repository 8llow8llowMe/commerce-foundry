package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.security.AccessTokenRevocationChecker;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 하류 헤더 계약 {@code X-Client-Subject} 를 실제 서버 · 보안 체인 · 라우팅(Netty)으로 본다 ({@code security-guide.md} §7-3, #135).
 * 하류는 받은 헤더를 그대로 기록하는 가짜 서비스다.
 *
 * <p>테스트 클라이언트의 주소({@code 127.0.0.1})를 신뢰 프록시로 두어, {@code X-Forwarded-For} 로 BFF 뒤의 서로 다른 클라이언트를 흉내 낸다.
 * 신뢰하지 않는 피어 · 끈 기본값은 {@link ClientSubjectUntrustedPeerTest} · {@link ClientSubjectHeaderDisabledTest} 가 본다.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = {
    GatewayIntegrationTest.CLIENT_IP_ENABLED, GatewayIntegrationTest.CLIENT_IP_HASH_KEY, "gateway.client-ip.trusted-proxies=127.0.0.1",
    // dev 라우트(/health/{service-id})도 같은 전역 필터를 타는지 본다
    "gateway.dev-routes.enabled=true"
})
@Import(ClientSubjectHeaderTest.StubConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientSubjectHeaderTest {

    static final String PRODUCT = "/api/v1/products/1";
    /** base64url(패딩 없음) 22자 — HMAC-SHA256 앞 16바이트. */
    static final String SUBJECT_FORMAT = "[A-Za-z0-9_-]{22}";

    private static final UpstreamHeaderRecorder UPSTREAM = new UpstreamHeaderRecorder();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ClientIpHasher clientIpHasher;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerServiceInstances(DynamicPropertyRegistry registry) {
        UPSTREAM.register(registry);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    /** 켜면 스텁 제한기가 모든 요청을 거절한다. */
    private static final AtomicBoolean DENY = new AtomicBoolean();

    @BeforeEach
    void reset() {
        UPSTREAM.clear();
        DENY.set(false);
    }

    @Test
    @DisplayName("dev 라우트(/health/{service-id})도 위조 X-Client-Subject 를 지우고 게이트웨이 값 한 개만 싣는다")
    void devRouteAlsoReplacesForgedSubject() {
        String status = UpstreamHeaderRecorder.rawGet(port, "/health/product-service", List.of(
            "x-client-subject: forged-lower", "X-CLIENT-SUBJECT: forged-upper", "X-Client-Subject: forged-1"));

        assertThat(status).contains(" 200 ");
        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER)).containsExactly(subjectOf("127.0.0.1"));
    }

    @Test
    @DisplayName("요청 제한에 걸린 요청(429)은 하류에 한 건도 닿지 않는다 — 헤더를 꾸미기 전에 끊긴다")
    void rateLimitedRequestNeverReachesDownstream() {
        DENY.set(true);

        webTestClient.post().uri("/api/v1/carts/me/items")
            .header("X-Forwarded-For", "198.51.100.7")
            .header(ClientSubjectHeaderFilter.HEADER, "forged")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(429);

        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    @DisplayName("클라이언트가 보낸 X-Client-Subject 는 대소문자 · 개수와 무관하게 지우고, 게이트웨이 값 한 개만 하류에 닿는다")
    void forgedSubjectHeadersAreReplacedWithOne() {
        String status = UpstreamHeaderRecorder.rawGet(port, PRODUCT, List.of(
            "x-client-subject: forged-lower", "X-CLIENT-SUBJECT: forged-upper", "X-Client-Subject: forged-1", "X-Client-Subject: forged-2"));

        assertThat(status).contains(" 200 ");
        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER))
            .as("XFF 가 없으면 신뢰 프록시 자신이 클라이언트다")
            .containsExactly(subjectOf("127.0.0.1"));
    }

    @Test
    @DisplayName("값은 클라이언트 IP 의 HMAC(base64url 22자) 하나이고 IP 원문이 없다")
    void subjectIsSingleOpaqueHmac() {
        get("198.51.100.7");

        List<String> values = UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER);
        assertThat(values).hasSize(1);
        assertThat(values.getFirst()).matches(SUBJECT_FORMAT).doesNotContain("198.51.100").isEqualTo(subjectOf("198.51.100.7"));
    }

    @Test
    @DisplayName("서로 다른 클라이언트는 다른 값, 같은 클라이언트는 같은 값이다 — 비로그인 버킷 키와 같은 값")
    void subjectDistinguishesClients() {
        String first = subjectFor("198.51.100.7");
        String again = subjectFor("198.51.100.7");
        String other = subjectFor("198.51.100.8");

        assertThat(first).isEqualTo(again).isEqualTo(clientIpHasher.hash(ClientIpResolver.bucketOf("198.51.100.7")));
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    @DisplayName("IPv6 는 /64 로 묶는다 — 같은 /64 안의 다른 주소는 같은 값, 다른 /64 는 다른 값이다")
    void ipv6SubjectIsGroupedBySlash64() {
        String first = subjectFor("2001:db8:1:2::1");
        String sameNetwork = subjectFor("2001:db8:1:2:ffff:ffff:ffff:ffff");
        String otherNetwork = subjectFor("2001:db8:1:3::1");

        assertThat(first).isEqualTo(sameNetwork).isEqualTo(subjectOf("2001:db8:1:2::1"));
        assertThat(otherNetwork).isNotEqualTo(first);
    }

    @Test
    @DisplayName("XFF 를 오른쪽부터 읽는다 — 클라이언트가 왼쪽에 넣은 값은 쓰이지 않는다")
    void forwardedForIsReadFromRight() {
        assertThat(subjectFor("203.0.113.99, 198.51.100.7")).isEqualTo(subjectOf("198.51.100.7"));
    }

    @Test
    @DisplayName("토큰 있는 요청에도 싣는다 — 하류가 로그인 여부와 무관하게 IP 차원을 쓴다. 위조 값은 지운다")
    void authenticatedRequestAlsoCarriesSubject() {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        webTestClient.get().uri(PRODUCT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("X-Forwarded-For", "198.51.100.7")
            .header(ClientSubjectHeaderFilter.HEADER, "forged")
            .exchange()
            .expectStatus().isOk();

        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER)).containsExactly(subjectOf("198.51.100.7"));
    }

    @Test
    @DisplayName("하류에는 X-Forwarded-* · Forwarded 가 없다 — SCG(자체 trusted-proxies 미설정)가 지우고 다시 쓰지 않는다. 게이트웨이 신뢰 프록시와 무관하다")
    void downstreamHasNoForwardingHeaders() {
        webTestClient.get().uri(PRODUCT).headers(UpstreamHeaderRecorder::spoofForwarding).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.onlyRequestHeaderNames()).noneMatch(UpstreamHeaderRecorder::isForwardingHeader);
        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER)).containsExactly(subjectOf("198.51.100.7"));
    }

    private void get(String forwardedFor) {
        webTestClient.get().uri(PRODUCT).header("X-Forwarded-For", forwardedFor).exchange().expectStatus().isOk();
    }

    private String subjectFor(String forwardedFor) {
        UPSTREAM.clear();
        get(forwardedFor);
        List<String> values = UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER);
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    private String subjectOf(String clientIp) {
        return clientIpHasher.hash(ClientIpResolver.bucketOf(clientIp));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        /** 실제 Redis 가 없으므로 차단 목록은 항상 비어 있다고 답한다 (차단 목록 동작은 GatewaySecurityTest 가 본다). */
        @Bean
        @Primary
        AccessTokenRevocationChecker emptyRevocationChecker() {
            return jti -> Mono.just(false);
        }

        /** 실제 Redis 대신 {@link #DENY} 로 허용 · 거절을 정한다 (실제 토큰 버킷은 RedisBucketRateLimiterTest 가 본다). */
        @Bean
        @Primary
        BucketRateLimiter switchableRateLimiter() {
            return (policy, subject) -> Mono.just(!DENY.get());
        }
    }
}
