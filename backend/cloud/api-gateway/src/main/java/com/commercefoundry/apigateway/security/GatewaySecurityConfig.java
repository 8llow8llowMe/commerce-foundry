package com.commercefoundry.apigateway.security;

import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.token.JwtVerificationProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.FormLoginSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.HeaderSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.HttpBasicSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.LogoutSpec;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 보안 체인 — 모든 요청에 걸리는 전역 WebFilter 다. 라우트 필터로 두지 않는다: 한 라우트({@code ServiceRoute})가 고객 · 어드민 패턴을
 * 섞어 갖고, dev 라우트({@code /health/**} · {@code /docs/**})도 덮어야 한다 ({@code security-guide.md} §7).
 *
 * <ul>
 *   <li>{@value #ADMIN_PATH_PATTERN} 은 ADMIN 만 통과한다. 토큰 없으면 401(001), CUSTOMER 면 403(005).</li>
 *   <li>그 밖은 열어 둔다 — 토큰 없는 요청은 그대로 하류로 가고 서비스가 판정한다 ({@code architecture-guide.md} §6).
 *       다만 토큰을 보냈으면 공개 경로여도 검증한다. 틀리거나 만료되거나 로그아웃된 토큰은 여기서 401 이다.</li>
 *   <li>{@code Authorization} 헤더는 그대로 하류로 넘긴다. 서비스가 JWT 를 직접 검증한다. 검증 결과를 {@code X-Member-Id} 같은 헤더로 만들지 않는다.</li>
 * </ul>
 *
 * <p>필터 순서 — {@code GatewayCorrelationIdFilter}(가장 앞, 상관 id) → {@code GatewayRequestGuardFilter}(비정규 경로 거절) → 이 체인({@code WebFilterChainProxy}, -100: CORS → 인증 → 인가)
 * → 라우팅. 정규화되지 않은 경로로 인가 판정을 하지 않는다.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /** 어드민 경로. 서비스 체인(security-core {@code ResourceServerSecurityConfigurer})의 어드민 패턴과 같은 값이어야 한다. */
    public static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";

    private static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public GatewaySecurityErrorResponder gatewaySecurityErrorResponder(JsonMapper jsonMapper) {
        return new GatewaySecurityErrorResponder(new SecurityErrorEnvelope(jsonMapper));
    }

    /** 빈으로 내보내 Boot 가 생성 비밀번호 사용자({@code ReactiveUserDetailsServiceAutoConfiguration})를 만들지 않게 한다. */
    @Bean
    public GatewayAuthenticationManagerResolver gatewayAuthenticationManagerResolver(
        JwtVerificationProperties jwtVerificationProperties, AccessTokenRevocationChecker accessTokenRevocationChecker
    ) {
        return new GatewayAuthenticationManagerResolver(jwtVerificationProperties, accessTokenRevocationChecker, Clock.systemUTC());
    }

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(
        ServerHttpSecurity http,
        GatewayAuthenticationManagerResolver authenticationManagerResolver,
        GatewaySecurityErrorResponder errorResponder,
        CorsConfigurationSource corsConfigurationSource
    ) {
        // 기본(true)이면 차단 목록 장애 등 AuthenticationServiceException 이 진입점을 지나지 않고 봉투 없는 500 이 된다 (security-guide.md §4-1)
        ServerAuthenticationEntryPointFailureHandler failureHandler = new ServerAuthenticationEntryPointFailureHandler(errorResponder);
        failureHandler.setRethrowAuthenticationServiceException(false);

        return http
            // 인증보다 앞(SecurityWebFiltersOrder.CORS)에 둔다 — 토큰 없는 preflight(OPTIONS /api/v1/admin/**)가 401 이 되지 않게.
            // source 는 직접 넘긴다. withDefaults() 는 CorsConfigurationSource 빈이 하나일 때만 찾고, 둘 이상이면 조용히 CORS 를 빼고 간다
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(CsrfSpec::disable)
            .httpBasic(HttpBasicSpec::disable)
            .formLogin(FormLoginSpec::disable)
            .logout(LogoutSpec::disable)
            .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
            // 세션을 만들지 않는다 (STATELESS). SESSION 쿠키가 나가지 않는다
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            // 보안 헤더를 붙이지 않는다. 프록시한 서비스 응답에는 서비스 보안 체인의 헤더가 이미 있고, 게이트웨이가 HSTS(includeSubDomains) ·
            // Cache-Control 을 덧붙이면 서비스가 정한 값(no-store 등)과 겹치거나 바뀐다
            .headers(HeaderSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers(ADMIN_PATH_PATTERN).hasRole(ADMIN_ROLE)
                .anyExchange().permitAll())
            // 토큰 없는 어드민 요청(001)과 역할 부족(005). 아래 oauth2ResourceServer 의 진입점은 Bearer 토큰이 붙은 요청에만 걸린다
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(errorResponder)
                .accessDeniedHandler(errorResponder))
            // jwt() DSL 은 resolver 와 같이 쓸 수 없다 (Spring Security 7 validateConfiguration). 디코더 · 변환기는 resolver 안에서 조합한다
            .oauth2ResourceServer(resourceServer -> resourceServer
                .authenticationManagerResolver(authenticationManagerResolver)
                .authenticationEntryPoint(errorResponder)
                .accessDeniedHandler(errorResponder)
                .authenticationFailureHandler(failureHandler))
            .build();
    }
}
