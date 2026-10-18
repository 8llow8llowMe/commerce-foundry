package com.commercefoundry.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

class RedisAccessTokenRevocationCheckerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final RedisAccessTokenRevocationChecker checker = new RedisAccessTokenRevocationChecker(redisTemplate);

    @Test
    @DisplayName("auth:revoked-access-token:{jti} 키가 있으면 차단이다 — auth 가 쓰는 키와 같은 형식")
    void revokedWhenKeyExists() {
        String jti = UUID.randomUUID().toString();
        given(redisTemplate.hasKey("auth:revoked-access-token:" + jti)).willReturn(Mono.just(true));

        assertThat(checker.isRevoked(jti).block(TIMEOUT)).isTrue();
    }

    @Test
    @DisplayName("키가 없으면 차단이 아니다")
    void notRevokedWhenKeyIsAbsent() {
        String jti = UUID.randomUUID().toString();
        given(redisTemplate.hasKey("auth:revoked-access-token:" + jti)).willReturn(Mono.just(false));

        assertThat(checker.isRevoked(jti).block(TIMEOUT)).isFalse();
    }

    @Test
    @DisplayName("키 규칙에 맞지 않는 jti 는 Redis 를 부르지 않고 그 자리에서 IllegalArgumentException 이다 — 저장소 장애와 섞이지 않게")
    void malformedJtiFailsBeforeRedis() {
        assertThatThrownBy(() -> checker.isRevoked("a:b")).isInstanceOf(IllegalArgumentException.class);
        verify(redisTemplate, never()).hasKey(anyString());
    }
}
