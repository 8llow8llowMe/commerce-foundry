package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

/** 프레임워크 응답 해석과 정책 등록을 본다. 실제 토큰 버킷은 {@link RedisBucketRateLimiterTest} 가 실제 Redis 로 본다. */
class RedisBucketRateLimiterUnitTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long MEMBER_ID = 42L;
    private static final String CLIENT_IP = "203.0.113.7";
    private static final ClientIpHasher HASHER =
        new ClientIpHasher(new GatewayClientIpProperties(true, List.of(), GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY));

    private final RedisRateLimiter redisRateLimiter = mock(RedisRateLimiter.class);
    private final Map<String, RedisRateLimiter.Config> registered = new HashMap<>();

    @Test
    @DisplayName("정책마다 토큰 버킷 설정을 정책 id 로 등록한다 — 분당 횟수 · 순간 허용을 바꾼 값")
    void registersPolicyConfigs() {
        limiter();

        assertThat(registered).containsOnlyKeys("cart-merge", "cart-write", "order-quote", "order-write");
        RedisRateLimiter.Config merge = registered.get("cart-merge");
        assertThat(merge.getReplenishRate()).isEqualTo(1);
        assertThat(merge.getRequestedTokens()).isEqualTo(10);
        assertThat(merge.getBurstCapacity()).isEqualTo(30);
    }

    @Test
    @DisplayName("버킷 id 는 정책 · 주체(회원 id 또는 IP 의 HMAC)로 만든다 — 프레임워크 키에는 이 값만 들어간다")
    void bucketIdSeparatesPolicies() {
        String hash = HASHER.hash(CLIENT_IP);

        assertThat(RedisBucketRateLimiter.bucketId(RateLimitPolicy.CART_WRITE, RateLimitSubject.member(MEMBER_ID))).isEqualTo("cart-write:member:42");
        assertThat(RedisBucketRateLimiter.bucketId(RateLimitPolicy.ORDER_WRITE, RateLimitSubject.member(MEMBER_ID))).isEqualTo("order-write:member:42");
        assertThat(RedisBucketRateLimiter.bucketId(RateLimitPolicy.CART_WRITE, RateLimitSubject.client(hash)))
            .isEqualTo("cart-write:ip:" + hash)
            .doesNotContain(CLIENT_IP);
    }

    @Test
    @DisplayName("비로그인 주체는 프레임워크에 (정책 id, <정책>:ip:<HMAC>) 로 넘긴다 — Redis 키가 되는 값에 IP 원문이 없다")
    void clientSubjectReachesFrameworkWithoutRawIp() {
        RedisBucketRateLimiter limiter = limiter();
        String hash = HASHER.hash(CLIENT_IP);
        given(redisRateLimiter.isAllowed(anyString(), anyString()))
            .willReturn(Mono.just(new RateLimiter.Response(true, Map.of(RedisRateLimiter.REMAINING_HEADER, "19"))));

        assertThat(limiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.client(hash)).block(TIMEOUT)).isTrue();

        ArgumentCaptor<String> routeId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        then(redisRateLimiter).should().isAllowed(routeId.capture(), id.capture());
        assertThat(routeId.getValue()).isEqualTo("cart-write");
        assertThat(id.getValue()).isEqualTo("cart-write:ip:" + hash).doesNotContain(CLIENT_IP);
    }

    @Test
    @DisplayName("허용 · 거절은 프레임워크 응답을 그대로 따른다")
    void followsFrameworkDecision() {
        RedisBucketRateLimiter limiter = limiter();
        answer(new RateLimiter.Response(false, Map.of(RedisRateLimiter.REMAINING_HEADER, "0")));

        assertThat(limiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.member(MEMBER_ID)).block(TIMEOUT)).isFalse();
    }

    @Test
    @DisplayName("프레임워크가 Redis 오류를 삼킨 응답(남은 토큰 -1)은 저장소 장애로 알린다 — 필터가 fail-open warn 을 간격마다 남기게")
    void swallowedStorageFailureIsSignalled() {
        RedisBucketRateLimiter limiter = limiter();
        answer(new RateLimiter.Response(true, Map.of(RedisRateLimiter.REMAINING_HEADER, "-1")));

        assertThatThrownBy(() -> limiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.member(MEMBER_ID)).block(TIMEOUT))
            .isInstanceOf(RedisBucketRateLimiter.RateLimitStorageUnavailableException.class);
    }

    private RedisBucketRateLimiter limiter() {
        given(redisRateLimiter.getConfig()).willReturn(registered);
        given(redisRateLimiter.getRemainingHeader()).willReturn(RedisRateLimiter.REMAINING_HEADER);
        return new RedisBucketRateLimiter(redisRateLimiter, new GatewayRateLimitProperties(true, null, null, null, null));
    }

    private void answer(RateLimiter.Response response) {
        given(redisRateLimiter.isAllowed("cart-write", "cart-write:member:" + MEMBER_ID)).willReturn(Mono.just(response));
    }
}
