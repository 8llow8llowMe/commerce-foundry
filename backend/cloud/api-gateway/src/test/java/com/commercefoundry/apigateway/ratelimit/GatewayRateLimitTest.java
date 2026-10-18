package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.security.AccessTokenRevocationChecker;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 회원 · 클라이언트 IP 단위 요청 제한을 실제 서버 · 보안 체인 · 라우팅으로 본다 ({@code security-guide.md} §7-3). 하류는 받은 경로를 기록하는 가짜 서비스다.
 * 제한기는 스텁({@link StubBucketRateLimiter})으로 바꿔 허용 · 거절을 정한다 — 실제 토큰 버킷은 {@link RedisBucketRateLimiterTest} 가 실제 Redis 로 본다.
 */
@GatewayIntegrationTest
@Import(GatewayRateLimitTest.StubConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayRateLimitTest {

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");
    private static final String CART_ITEMS = "/api/v1/carts/me/items";
    private static final Queue<String> UPSTREAM_PATHS = new ConcurrentLinkedQueue<>();
    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private StubBucketRateLimiter rateLimiter;

    @Autowired
    private ClientIpHasher clientIpHasher;

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
        UPSTREAM_PATHS.clear();
        rateLimiter.reset();
    }

    @Test
    @DisplayName("한도를 넘은 장바구니 쓰기는 429 + GATEWAY_006 + Retry-After 이고 하류에 닿지 않는다")
    void deniedCartWriteIsRejectedBeforeDownstream() {
        rateLimiter.deny = true;

        post(CART_ITEMS, BEARER + token())
            .expectStatus().isEqualTo(429)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.dataHeader.success").isEqualTo(false)
            .jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_006")
            .jsonPath("$.dataBody").isEmpty();

        assertThat(UPSTREAM_PATHS).as("하류에 닿지 않는다").isEmpty();
        assertThat(rateLimiter.calls).containsExactly("CART_WRITE:member:" + TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("허용 origin 의 429 에도 Access-Control-Allow-Origin 이 실린다 — 브라우저가 오류 본문을 읽을 수 있다")
    void deniedResponseCarriesCorsHeaders() {
        rateLimiter.deny = true;

        webTestClient.post().uri(CART_ITEMS)
            .header(HttpHeaders.AUTHORIZATION, BEARER + token())
            .header(HttpHeaders.ORIGIN, GatewayIntegrationTest.ALLOWED_ORIGIN)
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, GatewayIntegrationTest.ALLOWED_ORIGIN);
    }

    @Test
    @DisplayName("한도 안의 장바구니 쓰기는 하류로 간다")
    void allowedCartWriteIsRouted() {
        post(CART_ITEMS, BEARER + token()).expectStatus().isOk();

        assertThat(UPSTREAM_PATHS).containsExactly(CART_ITEMS);
    }

    @Test
    @DisplayName("토큰 없는 장바구니 쓰기는 클라이언트 IP 의 HMAC 으로 세고, 한도를 넘으면 429 이며 하류에 닿지 않는다")
    void anonymousCartWriteIsCountedByClientIp() {
        rateLimiter.deny = true;

        webTestClient.post().uri(CART_ITEMS).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
            .expectStatus().isEqualTo(429)
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_006");

        assertThat(rateLimiter.calls).containsExactly("CART_WRITE:ip:" + clientIpHasher.hash("127.0.0.1"));
        assertThat(UPSTREAM_PATHS).isEmpty();
    }

    @Test
    @DisplayName("신뢰 프록시가 없으면(기본) X-Forwarded-For 를 바꿔 보내도 같은 버킷이다 — 버킷을 우회할 수 없다")
    void spoofedForwardedForCannotEscapeBucket() {
        for (String spoofed : List.of("198.51.100.1", "198.51.100.2", "10.0.0.1, 198.51.100.3")) {
            webTestClient.post().uri(CART_ITEMS).header("X-Forwarded-For", spoofed)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        }

        assertThat(rateLimiter.calls).hasSize(3).containsOnly("CART_WRITE:ip:" + clientIpHasher.hash("127.0.0.1"));
        assertThat(rateLimiter.calls).allSatisfy(call -> assertThat(call).doesNotContain("127.0.0.1").doesNotContain("198.51.100"));
    }

    @Test
    @DisplayName("장바구니 조회는 세지 않는다")
    void cartReadIsNotCounted() {
        rateLimiter.deny = true;

        webTestClient.get().uri("/api/v1/carts/me").header(HttpHeaders.AUTHORIZATION, BEARER + token()).exchange().expectStatus().isOk();

        assertThat(rateLimiter.calls).isEmpty();
    }

    @Test
    @DisplayName("제한기 장애면 통과시킨다 (fail-open)")
    void limiterFailureFailsOpen() {
        rateLimiter.fail = true;

        post(CART_ITEMS, BEARER + token()).expectStatus().isOk();

        assertThat(UPSTREAM_PATHS).containsExactly(CART_ITEMS);
    }

    private static final String BEARER = "Bearer ";

    private WebTestClient.ResponseSpec post(String path, String authorization) {
        return webTestClient.post().uri(path)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange();
    }

    private static String token() {
        return TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                UPSTREAM_PATHS.add(exchange.getRequestURI().getRawPath());
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

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        @Primary
        StubBucketRateLimiter stubBucketRateLimiter() {
            return new StubBucketRateLimiter();
        }

        /** 실제 Redis 가 없으므로 차단 목록은 항상 비어 있다고 답한다 (차단 목록 동작은 GatewaySecurityTest 가 본다). */
        @Bean
        @Primary
        AccessTokenRevocationChecker emptyRevocationChecker() {
            return jti -> Mono.just(false);
        }
    }

    static final class StubBucketRateLimiter implements BucketRateLimiter {

        private final Queue<String> calls = new ConcurrentLinkedQueue<>();
        private volatile boolean deny;
        private volatile boolean fail;

        @Override
        public Mono<Boolean> tryAcquire(RateLimitPolicy policy, RateLimitSubject subject) {
            calls.add(policy + ":" + subject.value());
            if (fail) {
                return Mono.error(new IllegalStateException("simulated outage"));
            }
            return Mono.just(!deny);
        }

        void reset() {
            calls.clear();
            deny = false;
            fail = false;
        }
    }
}
