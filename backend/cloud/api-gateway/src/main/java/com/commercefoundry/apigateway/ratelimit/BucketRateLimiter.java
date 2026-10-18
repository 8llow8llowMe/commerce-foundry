package com.commercefoundry.apigateway.ratelimit;

import reactor.core.publisher.Mono;

/**
 * 주체(회원 · 비로그인 클라이언트 IP)의 요청을 정책 버킷에서 하나 꺼낸다 ({@code security-guide.md} §7-3).
 *
 * <p>저장소 장애는 오류 신호로 알린다. 통과시킬지는 호출하는 쪽({@link GatewayRateLimitFilter})이 정한다 — 차단 목록 조회기와 같은 짜임이다.
 */
public interface BucketRateLimiter {

    /** @return 허용이면 {@code true}. 한도를 넘었으면 {@code false}. */
    Mono<Boolean> tryAcquire(RateLimitPolicy policy, RateLimitSubject subject);
}
