package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway 의 {@link RedisRateLimiter}(토큰 버킷 Lua 스크립트)로 센다. 키는 프레임워크가 정한다 —
 * {@code request_rate_limiter.{<policy>:member:<memberId>}.tokens} · {@code .timestamp}, TTL 은 스크립트가 버킷이 다시 찰 시간만큼 건다
 * ({@code coding-conventions.md} §9-7 예외 표). 키에는 내부 회원 id 만 들어간다 — 이메일 · IP 같은 개인정보는 넣지 않는다.
 *
 * <p>정책별 한도는 기동 때 {@link RedisRateLimiter#getConfig()} 에 정책 id 로 넣는다. 프레임워크의 라우트 필터 설정과 같은 표를 쓰지만,
 * 이 게이트웨이는 {@code RequestRateLimiter} 라우트 필터를 쓰지 않아 겹치지 않는다.
 *
 * <p>Redis 장애 때 {@link RedisRateLimiter} 는 스스로 오류를 삼키고 허용으로 답하며, 남은 토큰 헤더를 {@code -1} 로 채운다.
 * 그 신호를 {@link RateLimitStorageUnavailableException} 으로 바꿔 장애로 알린다 — 그래야 필터가 fail-open 을 간격마다 한 번 warn 으로 남긴다.
 * 프레임워크 자신의 error 로그(요청마다 스택)는 {@code application.yml} 에서 끈다. 명령이 응답하지 않으면 Lettuce 명령 타임아웃({@code spring.data.redis.timeout}, 1s)이 끊는다.
 */
@Component
public class RedisMemberRateLimiter implements MemberRateLimiter {

    private final RedisRateLimiter redisRateLimiter;

    public RedisMemberRateLimiter(RedisRateLimiter redisRateLimiter, GatewayRateLimitProperties properties) {
        this.redisRateLimiter = redisRateLimiter;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            GatewayRateLimitProperties.Limit limit = policy.limit(properties);
            redisRateLimiter.getConfig().put(policy.getId(), new RedisRateLimiter.Config()
                .setReplenishRate(limit.replenishRate())
                .setBurstCapacity(limit.burstCapacity())
                .setRequestedTokens(limit.requestedTokens()));
        }
    }

    @Override
    public Mono<Boolean> tryAcquire(RateLimitPolicy policy, long memberId) {
        return redisRateLimiter.isAllowed(policy.getId(), bucketId(policy, memberId)).flatMap(response -> storageFailed(response)
            ? Mono.error(new RateLimitStorageUnavailableException())
            : Mono.just(response.isAllowed()));
    }

    /** 프레임워크가 Redis 오류를 삼켰을 때의 응답 — 허용 + 남은 토큰 {@code -1}. 정상 응답의 남은 토큰은 0 이상이다. */
    private boolean storageFailed(RateLimiter.Response response) {
        return STORAGE_FAILURE_REMAINING.equals(response.getHeaders().get(redisRateLimiter.getRemainingHeader()));
    }

    private static final String STORAGE_FAILURE_REMAINING = "-1";

    /** 토큰 버킷 저장소(Redis)에 닿지 못했다. 메시지 · 스택을 싣지 않는다 — 원인은 프레임워크가 삼켰고 로그는 필터가 예외 타입만 남긴다. */
    static final class RateLimitStorageUnavailableException extends RuntimeException {

        RateLimitStorageUnavailableException() {
            super("rate limit storage unavailable", null, false, false);
        }
    }

    /** 정책마다 버킷을 나눈다 — 프레임워크 키에는 라우트 id 가 들어가지 않고 이 값만 들어간다. */
    static String bucketId(RateLimitPolicy policy, long memberId) {
        return policy.getId() + ":member:" + memberId;
    }
}
