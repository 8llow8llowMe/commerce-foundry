package com.commercefoundry.apigateway.security;

import reactor.core.publisher.Mono;

/**
 * 로그아웃한 access 토큰(차단 목록)인지 확인한다. 차단 목록은 auth-service 가 로그아웃 때 쓰고 게이트웨이만 읽는다
 * ({@code security-guide.md} §7). 서비스 5종은 차단 목록을 보지 않는다.
 *
 * <p>저장소 장애는 오류 신호로 알린다. 통과시킬지(fail-open) 503 으로 끊을지(fail-closed)는 호출하는 쪽
 * ({@link RevocationCheckingAuthenticationManager})이 경로로 정한다.
 */
public interface AccessTokenRevocationChecker {

    /** @param jti access 토큰의 {@code jti}. 차단돼 있으면 {@code true}. */
    Mono<Boolean> isRevoked(String jti);
}
