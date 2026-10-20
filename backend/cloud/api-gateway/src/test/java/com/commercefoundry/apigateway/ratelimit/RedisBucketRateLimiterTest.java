package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 회원 · 클라이언트 IP 단위 제한을 실제 Redis 에서 Spring Cloud Gateway 의 토큰 버킷 Lua 스크립트로 돌려 본다 — 스텁으로는 키 형식 · 한도 변환 · TTL 이 검증되지 않는다.
 * 게이트웨이 컨텍스트를 그대로 띄우고 Redis 접속만 컨테이너로 바꾼다. Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 * 비로그인 버킷 키를 만들려면 IP 를 해시해야 하므로 비로그인 IP 계수(기본 끔)를 켠다.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = {GatewayIntegrationTest.CLIENT_IP_ENABLED, GatewayIntegrationTest.CLIENT_IP_HASH_KEY})
@Testcontainers
@EnabledWithDockerOrOnCi
class RedisBucketRateLimiterTest {

    private static final String PASSWORD = "gateway-test-only";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withCommand("redis-server", "--requirepass", PASSWORD)
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> PASSWORD);
    }

    @Autowired
    private RedisBucketRateLimiter rateLimiter;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    private ClientIpHasher clientIpHasher;

    @Test
    @DisplayName("병합은 순간 3회까지 허용하고 4번째를 거절한다 — 분당 6회 · 순간 3회 기본 한도")
    void cartMergeBurstIsEnforced() {
        long memberId = randomMemberId();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(rateLimiter.tryAcquire(RateLimitPolicy.CART_MERGE, RateLimitSubject.member(memberId)).block(TIMEOUT)).as("%d 번째", attempt).isTrue();
        }
        assertThat(rateLimiter.tryAcquire(RateLimitPolicy.CART_MERGE, RateLimitSubject.member(memberId)).block(TIMEOUT)).as("4 번째").isFalse();
    }

    @Test
    @DisplayName("정책 · 회원마다 버킷이 따로다 — 병합 한도를 다 써도 일반 장바구니 쓰기와 다른 회원은 허용된다")
    void bucketsAreSeparatedByPolicyAndMember() {
        long memberId = randomMemberId();
        for (int attempt = 0; attempt < 4; attempt++) {
            rateLimiter.tryAcquire(RateLimitPolicy.CART_MERGE, RateLimitSubject.member(memberId)).block(TIMEOUT);
        }

        assertThat(rateLimiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.member(memberId)).block(TIMEOUT)).isTrue();
        assertThat(rateLimiter.tryAcquire(RateLimitPolicy.CART_MERGE, RateLimitSubject.member(randomMemberId())).block(TIMEOUT)).isTrue();
    }

    @Test
    @DisplayName("키는 request_rate_limiter.{<policy>.<policy>:member:<memberId>}.tokens 이고 TTL 이 걸린다 — 개인정보 원문이 없다")
    void keyFormatAndTtl() {
        long memberId = randomMemberId();

        rateLimiter.tryAcquire(RateLimitPolicy.ORDER_WRITE, RateLimitSubject.member(memberId)).block(TIMEOUT);

        // 프레임워크 키는 {<routeId>.<id>} 다 — routeId 자리에 정책 id 가 한 번 더 들어간다
        String key = "request_rate_limiter.{order-write.order-write:member:" + memberId + "}.tokens";
        assertThat(redisTemplate.hasKey(key).block(TIMEOUT)).isTrue();
        assertThat(redisTemplate.getExpire(key).block(TIMEOUT)).isPositive();
    }

    @Test
    @DisplayName("비로그인 버킷 키는 request_rate_limiter.{<policy>.<policy>:ip:<HMAC>}.tokens 다 — Redis 어디에도 IP 원문이 없다")
    void clientKeyHasNoRawIp() {
        String ip = "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);

        rateLimiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.client(clientIpHasher.hash(ip))).block(TIMEOUT);

        assertThat(redisTemplate.hasKey("request_rate_limiter.{cart-write.cart-write:ip:" + clientIpHasher.hash(ip) + "}.tokens").block(TIMEOUT)).isTrue();
        assertThat(redisTemplate.keys("*" + ip + "*").collectList().block(TIMEOUT)).as("IP 원문이 든 키").isEmpty();
    }

    private static long randomMemberId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
