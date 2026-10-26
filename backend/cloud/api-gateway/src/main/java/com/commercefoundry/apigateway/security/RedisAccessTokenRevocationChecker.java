package com.commercefoundry.apigateway.security;

import com.commercefoundry.security.token.revocation.AdminAccessCutoffKeys;
import com.commercefoundry.security.token.revocation.RevokedAccessTokenKeys;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 차단 목록을 Redis 키 존재로 본다 — {@code auth:revoked-access-token:{jti}} ({@link RevokedAccessTokenKeys}).
 * 키와 TTL 은 auth-service 가 로그아웃 때 쓴다. 게이트웨이는 읽기만 한다 ({@code coding-conventions.md} §9-7).
 *
 * <p>템플릿은 Boot 의 {@link ReactiveStringRedisTemplate} 만 주입한다. 명령 타임아웃은 {@code spring.data.redis.timeout} 이다.
 */
@Component
@RequiredArgsConstructor
public class RedisAccessTokenRevocationChecker implements AccessTokenRevocationChecker {

    private final ReactiveStringRedisTemplate redisTemplate;

    /** jti 가 키 규칙에 맞지 않으면 {@link IllegalArgumentException} 을 그 자리에서 던진다 — 저장소 장애와 섞이지 않게. */
    @Override
    public Mono<Boolean> isRevoked(String jti) {
        String key = RevokedAccessTokenKeys.of(jti).value();
        return redisTemplate.hasKey(key);
    }

    /** 값이 없으면 empty. 숫자가 아니면 오류로 올려 경로별 fail-open · fail-closed 가 정하게 한다. */
    @Override
    public Mono<Instant> adminAccessCutoff(long memberId) {
        String key = AdminAccessCutoffKeys.of(memberId).value();
        return redisTemplate.opsForValue().get(key)
            .map(AdminAccessCutoffKeys::parseEpochMilli);
    }
}
