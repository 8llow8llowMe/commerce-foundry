package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
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

    private final StubLimiter limiter = new StubLimiter();
    private final GatewayRateLimitFilter filter = filter(true);

    @Test
    @DisplayName("한도 안이면 하류로 보낸다 — 정책과 회원 id 로 센다")
    void allowedRequestIsRouted() {
        AtomicBoolean routed = new AtomicBoolean();

        run(post(CART_ITEMS), member(), routed);

        assertThat(routed).isTrue();
        assertThat(limiter.calls).containsExactly(RateLimitPolicy.CART_WRITE + ":" + MEMBER_ID);
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
    @DisplayName("토큰 없는 요청은 세지 않는다 — 대상 API 가 로그인 필수라 서비스가 401 로 끊는다")
    void anonymousRequestIsNotCounted() {
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
        GatewayRateLimitFilter fixedClockFilter = new GatewayRateLimitFilter(limiter, properties(true), new GatewayErrorResponseWriter(JSON),
            Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));

        assertThat(fixedClockFilter.claimWarnSlot()).isTrue();
        assertThat(fixedClockFilter.claimWarnSlot()).isFalse();
    }

    private void run(ServerWebExchange exchange, Authentication authentication, AtomicBoolean routed) {
        Mono<Void> result = filter.filter(exchange, chain(routed));
        if (authentication != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }
        result.block(TIMEOUT);
    }

    private GatewayRateLimitFilter filter(boolean enabled) {
        return new GatewayRateLimitFilter(limiter, properties(enabled), new GatewayErrorResponseWriter(JSON));
    }

    private static GatewayRateLimitProperties properties(boolean enabled) {
        return new GatewayRateLimitProperties(enabled, null, null, null);
    }

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
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

    private static final class StubLimiter implements MemberRateLimiter {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile Mono<Boolean> answer = Mono.just(true);

        @Override
        public Mono<Boolean> tryAcquire(RateLimitPolicy policy, long memberId) {
            calls.add(policy + ":" + memberId);
            return answer;
        }
    }
}
