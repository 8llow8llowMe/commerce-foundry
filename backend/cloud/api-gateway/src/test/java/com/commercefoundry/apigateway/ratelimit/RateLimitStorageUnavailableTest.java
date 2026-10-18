package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Redis 에 닿지 못할 때의 동작을 <b>실제 프레임워크 제한기</b>로 본다. 스텁으로는 {@code RedisRateLimiter} 가 오류를 삼키고 남은 토큰 {@code -1} 로 답하는 동작이
 * 검증되지 않는다. Docker 없이 돈다.
 *
 * <p>Redis 포트를 <b>열리지 않는 포트(1)</b>로 바꾼다 — 기본 통합 테스트 값(127.0.0.1:6379)에 기대면, 같은 PC 에서 다른 작업이 로컬 Redis 를 띄웠을 때
 * 명령이 성공해 테스트가 흔들린다.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = "spring.data.redis.port=1")
class RateLimitStorageUnavailableTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private RedisBucketRateLimiter rateLimiter;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("프레임워크가 삼킨 Redis 오류를 저장소 장애로 알린다 — 허용으로 조용히 넘어가지 않고 필터의 fail-open 경로를 탄다")
    void swallowedRedisFailureIsSignalled() {
        assertThatThrownBy(() -> rateLimiter.tryAcquire(RateLimitPolicy.CART_WRITE, RateLimitSubject.member(TestTokens.MEMBER_ID)).block(TIMEOUT))
            .isInstanceOf(RedisBucketRateLimiter.RateLimitStorageUnavailableException.class);
    }

    @Test
    @DisplayName("Redis 장애 중 장바구니 쓰기는 429 가 아니라 통과한다 (fail-open) — 하류 인스턴스가 없어 503 GATEWAY_002 로 끝난다")
    void cartWritePassesDuringStorageFailure() {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        webTestClient.post().uri("/api/v1/carts/me/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_002");
    }
}
