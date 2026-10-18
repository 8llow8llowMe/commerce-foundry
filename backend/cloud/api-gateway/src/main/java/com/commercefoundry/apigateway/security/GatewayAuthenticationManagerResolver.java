package com.commercefoundry.apigateway.security;

import com.commercefoundry.apigateway.security.RevocationCheckingAuthenticationManager.FailurePolicy;
import com.commercefoundry.security.principal.MemberJwtAuthenticationConverter;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.reactive.ReactiveAccessTokenDecoderFactory;
import java.time.Clock;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bearer 토큰이 붙은 요청의 인증 매니저를 경로로 고른다 — 어드민 경로({@value GatewaySecurityConfig#ADMIN_PATH_PATTERN})는
 * 차단 목록 장애 시 fail-closed, 그 밖은 fail-open ({@code security-guide.md} §7).
 *
 * <p>두 매니저는 기동 때 한 번 만들고 고르기만 한다. 디코더 · 변환기는 서비스와 같은 security-core 의 것이다 — 같은 토큰이면 같은 판정이다.
 * 경로 판정은 인가 규칙({@code authorizeExchange})과 같은 {@code PathPattern} 매처라 인코딩된 경로({@code /api/v1/%61dmin/x})도 같게 본다.
 *
 * <p>스프링 빈으로 내보낸다. {@link ReactiveAuthenticationManagerResolver} 빈이 있으면 Boot 의
 * {@code ReactiveUserDetailsServiceAutoConfiguration} 이 생성 비밀번호 사용자를 만들지 않는다.
 */
public final class GatewayAuthenticationManagerResolver implements ReactiveAuthenticationManagerResolver<ServerWebExchange> {

    private static final ServerWebExchangeMatcher ADMIN_PATHS = ServerWebExchangeMatchers.pathMatchers(GatewaySecurityConfig.ADMIN_PATH_PATTERN);

    private final RevocationCheckingAuthenticationManager failClosed;
    private final RevocationCheckingAuthenticationManager failOpen;

    public GatewayAuthenticationManagerResolver(JwtVerificationProperties properties, AccessTokenRevocationChecker revocationChecker, Clock clock) {
        JwtReactiveAuthenticationManager tokenAuthenticationManager =
            new JwtReactiveAuthenticationManager(ReactiveAccessTokenDecoderFactory.create(properties, clock));
        tokenAuthenticationManager.setJwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(new MemberJwtAuthenticationConverter()));

        this.failClosed = new RevocationCheckingAuthenticationManager(tokenAuthenticationManager, revocationChecker, FailurePolicy.FAIL_CLOSED, clock);
        this.failOpen = new RevocationCheckingAuthenticationManager(tokenAuthenticationManager, revocationChecker, FailurePolicy.FAIL_OPEN, clock);
    }

    @Override
    public Mono<ReactiveAuthenticationManager> resolve(ServerWebExchange exchange) {
        return ADMIN_PATHS.matches(exchange)
            .map(match -> (match.isMatch() ? failClosed : failOpen).forExchange(exchange));
    }
}
