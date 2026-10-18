package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

/** 프레임워크 응답 해석과 정책 등록을 본다. 실제 토큰 버킷은 {@link RedisMemberRateLimiterTest} 가 실제 Redis 로 본다. */
class RedisMemberRateLimiterUnitTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long MEMBER_ID = 42L;

    private final RedisRateLimiter redisRateLimiter = mock(RedisRateLimiter.class);
    private final Map<String, RedisRateLimiter.Config> registered = new HashMap<>();

    @Test
    @DisplayName("정책마다 토큰 버킷 설정을 정책 id 로 등록한다 — 분당 횟수 · 순간 허용을 바꾼 값")
    void registersPolicyConfigs() {
        limiter();

        assertThat(registered).containsOnlyKeys("cart-merge", "cart-write", "order-write");
        RedisRateLimiter.Config merge = registered.get("cart-merge");
        assertThat(merge.getReplenishRate()).isEqualTo(1);
        assertThat(merge.getRequestedTokens()).isEqualTo(10);
        assertThat(merge.getBurstCapacity()).isEqualTo(30);
    }

    @Test
    @DisplayName("버킷 id 는 정책 · 회원 id 로 만든다 — 프레임워크 키에는 이 값만 들어간다")
    void bucketIdSeparatesPolicies() {
        assertThat(RedisMemberRateLimiter.bucketId(RateLimitPolicy.CART_WRITE, MEMBER_ID)).isEqualTo("cart-write:member:42");
        assertThat(RedisMemberRateLimiter.bucketId(RateLimitPolicy.ORDER_WRITE, MEMBER_ID)).isEqualTo("order-write:member:42");
    }

    @Test
    @DisplayName("허용 · 거절은 프레임워크 응답을 그대로 따른다")
    void followsFrameworkDecision() {
        RedisMemberRateLimiter limiter = limiter();
        answer(new RateLimiter.Response(false, Map.of(RedisRateLimiter.REMAINING_HEADER, "0")));

        assertThat(limiter.tryAcquire(RateLimitPolicy.CART_WRITE, MEMBER_ID).block(TIMEOUT)).isFalse();
    }

    @Test
    @DisplayName("프레임워크가 Redis 오류를 삼킨 응답(남은 토큰 -1)은 저장소 장애로 알린다 — 필터가 fail-open warn 을 간격마다 남기게")
    void swallowedStorageFailureIsSignalled() {
        RedisMemberRateLimiter limiter = limiter();
        answer(new RateLimiter.Response(true, Map.of(RedisRateLimiter.REMAINING_HEADER, "-1")));

        assertThatThrownBy(() -> limiter.tryAcquire(RateLimitPolicy.CART_WRITE, MEMBER_ID).block(TIMEOUT))
            .isInstanceOf(RedisMemberRateLimiter.RateLimitStorageUnavailableException.class);
    }

    private RedisMemberRateLimiter limiter() {
        given(redisRateLimiter.getConfig()).willReturn(registered);
        given(redisRateLimiter.getRemainingHeader()).willReturn(RedisRateLimiter.REMAINING_HEADER);
        return new RedisMemberRateLimiter(redisRateLimiter, new GatewayRateLimitProperties(true, null, null, null));
    }

    private void answer(RateLimiter.Response response) {
        given(redisRateLimiter.isAllowed("cart-write", "cart-write:member:" + MEMBER_ID)).willReturn(Mono.just(response));
    }
}
