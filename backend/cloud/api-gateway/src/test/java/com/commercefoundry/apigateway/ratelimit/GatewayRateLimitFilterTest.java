package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GatewayRateLimitFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long MEMBER_ID = 4611686018427387904L;
    private static final String CART_ITEMS = "/api/v1/carts/me/items";
    private static final GatewayClientIpProperties CLIENT_IP = new GatewayClientIpProperties(true, List.of(), GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY);
    /** 운영 기본값 — 비로그인 IP 계수 끔, 키 없음. */
    private static final GatewayClientIpProperties CLIENT_IP_DISABLED = new GatewayClientIpProperties(false, List.of(), null);
    private static final ClientIpResolver RESOLVER = new ClientIpResolver(CLIENT_IP);
    private static final ClientIpHasher HASHER = new ClientIpHasher(CLIENT_IP);

    private final StubLimiter limiter = new StubLimiter();
    private final GatewayRateLimitFilter filter = filter(true);

    @Test
    @DisplayName("한도 안이면 하류로 보낸다 — 정책과 회원 id 로 센다")
    void allowedRequestIsRouted() {
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS), member(), routed);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).containsExactly(RateLimitPolicy.CART_WRITE + ":member:" + MEMBER_ID);
    }

    @Test
    @DisplayName("한도를 넘으면 429 + GATEWAY_006 + Retry-After 이고 하류로 보내지 않는다")
    void deniedRequestIsRejected() {
        limiter.answer = Mono.just(false);
        AtomicBoolean routed = new AtomicBoolean();
        MockServerWebExchange exchange = post("/api/v1/carts/me/merge");

        run(exchange, member(), routed);

        assertThat(routed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(exchange.getResponse().getBodyAsString().block(TIMEOUT));
        assertThat(body.path("dataHeader").path("success").asBoolean(true)).isFalse();
        assertThat(body.path("dataHeader").path("resultCode").asString()).isEqualTo("GATEWAY_006");
        assertThat(body.path("dataBody").isNull()).isTrue();
    }

    @Test
    @DisplayName("토큰 없는 요청은 클라이언트 IP 의 HMAC 으로 센다 — 키에 IP 원문이 없다")
    void anonymousRequestIsCountedByClientIpHash() {
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS, "203.0.113.7"), null, routed);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).containsExactly(RateLimitPolicy.CART_WRITE + ":ip:" + HASHER.hash("203.0.113.7"));
        assertThat(limiter.calls.getFirst()).doesNotContain("203.0.113.7");
    }

    @Test
    @DisplayName("신뢰 프록시가 없으면 X-Forwarded-For 를 바꿔 보내도 같은 버킷이다 — 클라이언트가 버킷을 고를 수 없다")
    void spoofedForwardedForDoesNotChangeBucket() {
        MockServerWebExchange first = MockServerWebExchange.from(MockServerHttpRequest.post(CART_ITEMS)
            .remoteAddress(new InetSocketAddress("203.0.113.7", 40000)).header("X-Forwarded-For", "198.51.100.1"));
        MockServerWebExchange second = MockServerWebExchange.from(MockServerHttpRequest.post(CART_ITEMS)
            .remoteAddress(new InetSocketAddress("203.0.113.7", 40001)).header("X-Forwarded-For", "198.51.100.2"));

        run(first, null, new AtomicBoolean());
        run(second, null, new AtomicBoolean());

        assertThat(limiter.calls).hasSize(2).containsOnly(RateLimitPolicy.CART_WRITE + ":ip:" + HASHER.hash("203.0.113.7"));
    }

    @Test
    @DisplayName("비로그인 IP 계수가 꺼져 있으면(기본) 토큰 없는 요청은 제한기를 부르지 않고 보낸다 — BFF 뒤 공용 버킷이 비어도 401 재발급 경로를 막지 않는다")
    void anonymousRequestIsNotCountedWhenClientIpDisabled() {
        limiter.answer = Mono.just(false);
        AtomicBoolean routed = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(CART_ITEMS)
            .remoteAddress(new InetSocketAddress("203.0.113.7", 40000)).header("X-Forwarded-For", "198.51.100.1"));

        // 끈 상태의 해시기는 키가 없어 부르면 던진다 — IP 를 해석 · 해시하지 않아야 통과한다
        run(filter(true, CLIENT_IP_DISABLED), exchange, null, routed);

        assertThat(routed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(limiter.calls).isEmpty();
    }

    @Test
    @DisplayName("비로그인 IP 계수가 꺼져 있어도 로그인한 요청은 회원 단위로 센다")
    void memberRequestIsCountedWhenClientIpDisabled() {
        limiter.answer = Mono.just(false);
        AtomicBoolean routed = new AtomicBoolean();
        MockServerWebExchange exchange = post(CART_ITEMS, "203.0.113.7");

        run(filter(true, CLIENT_IP_DISABLED), exchange, member(), routed);

        assertThat(routed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limiter.calls).containsExactly(RateLimitPolicy.CART_WRITE + ":member:" + MEMBER_ID);
    }

    @Test
    @DisplayName("원격 주소조차 모르는 비로그인 요청은 세지 않고 통과시킨다")
    void unknownClientAddressIsNotCounted() {
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS), null, routed);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).isEmpty();
    }

    @Test
    @DisplayName("대상이 아닌 요청(조회)은 세지 않는다")
    void nonPolicyRequestIsNotCounted() {
        AtomicBoolean routed = new AtomicBoolean();

        run(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/carts/me")), member(), routed);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).isEmpty();
    }

    @Test
    @DisplayName("끄면 어떤 요청도 세지 않는다")
    void disabledFilterPassesEverything() {
        limiter.answer = Mono.just(false);
        AtomicBoolean routed = new AtomicBoolean();

        filter(false).filter(post(CART_ITEMS), chain(routed))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(member()))
            .block(TIMEOUT);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).isEmpty();
    }

    @Test
    @DisplayName("저장소 장애면 통과시킨다 (fail-open) — 남용 완화 수단이 쇼핑 장애가 되지 않게")
    void storageFailureFailsOpen() {
        limiter.answer = Mono.error(new IllegalStateException("simulated outage"));
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS), member(), routed);

        assertThat(routed).isTrue();
    }

    @Test
    @DisplayName("저장소가 응답하지 않으면 상한 시간 뒤 통과시킨다")
    void storageHangFailsOpenAfterTimeout() {
        limiter.answer = Mono.never();
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS), member(), routed);

        assertThat(routed).isTrue();
    }

    @Test
    @DisplayName("fail-open warn 은 간격마다 한 번만 남긴다")
    void failOpenWarnIsThrottled() {
        GatewayRateLimitFilter fixedClockFilter = new GatewayRateLimitFilter(limiter, RESOLVER, HASHER, properties(true), CLIENT_IP, new GatewayErrorResponseWriter(JSON),
            Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));

        assertThat(fixedClockFilter.claimWarnSlot()).isTrue();
        assertThat(fixedClockFilter.claimWarnSlot()).isFalse();
    }

    private void run(ServerWebExchange exchange, Authentication authentication, AtomicBoolean routed) {
        run(filter, exchange, authentication, routed);
    }

    private static void run(GatewayRateLimitFilter target, ServerWebExchange exchange, Authentication authentication, AtomicBoolean routed) {
        Mono<Void> result = target.filter(exchange, chain(routed));
        if (authentication != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }
        result.block(TIMEOUT);
    }

    private GatewayRateLimitFilter filter(boolean enabled) {
        return new GatewayRateLimitFilter(limiter, RESOLVER, HASHER, properties(enabled), CLIENT_IP, new GatewayErrorResponseWriter(JSON));
    }

    /** 운영처럼 해석기 · 해시기 · 필터가 같은 클라이언트 IP 설정을 받는다. */
    private GatewayRateLimitFilter filter(boolean enabled, GatewayClientIpProperties clientIp) {
        return new GatewayRateLimitFilter(limiter, new ClientIpResolver(clientIp), new ClientIpHasher(clientIp), properties(enabled), clientIp,
            new GatewayErrorResponseWriter(JSON));
    }

    private static GatewayRateLimitProperties properties(boolean enabled) {
        return new GatewayRateLimitProperties(enabled, null, null, null);
    }

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    private static MockServerWebExchange post(String path, String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).remoteAddress(new InetSocketAddress(remoteIp, 40000)));
    }

    private static Authentication member() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(new MemberPrincipal(MEMBER_ID, MemberRole.CUSTOMER, "jti-1"), null, "ROLE_CUSTOMER");
        authentication.setAuthenticated(true);
        return authentication;
    }

    private static GatewayFilterChain chain(AtomicBoolean routed) {
        return exchange -> {
            routed.set(true);
            return Mono.empty();
        };
    }

    private static final class StubLimiter implements BucketRateLimiter {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile Mono<Boolean> answer = Mono.just(true);

        @Override
        public Mono<Boolean> tryAcquire(RateLimitPolicy policy, RateLimitSubject subject) {
            calls.add(policy + ":" + subject.value());
            return answer;
        }
    }
}
