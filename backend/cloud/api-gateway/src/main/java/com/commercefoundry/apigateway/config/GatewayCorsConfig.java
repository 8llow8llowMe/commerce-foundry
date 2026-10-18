package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.properties.GatewayCorsProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS 는 게이트웨이 한 곳에서만 처리한다. 서비스가 CORS 헤더를 또 붙이면 응답 헤더가 중복돼 브라우저가 거부한다.
 *
 * <p>필터가 아니라 설정 원천만 빈으로 둔다. 보안 체인({@code GatewaySecurityConfig})이 이 원천으로 CORS 필터를 인증보다 앞에 둔다 —
 * 따로 둔 {@code CorsWebFilter} 빈은 보안 체인보다 뒤에 돌아 토큰 없는 어드민 preflight 가 401 이 된다.
 */
@Configuration
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewayCorsConfig {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final List<String> ALLOWED_METHODS = List.of(
        HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
        HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name());

    private static final List<String> ALLOWED_HEADERS = List.of(
        HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, IDEMPOTENCY_KEY, HttpHeaders.ACCEPT_LANGUAGE);

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // 빈 목록이면 어떤 origin 도 통과하지 못한다 — 설정이 빠졌을 때 열리지 않고 닫힌다
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        // 토큰은 Authorization 헤더 · 응답 본문으로만 오가고 쿠키를 쓰지 않는다 (#4 결정 1 — refresh 는 응답 본문, BFF 가 보관).
        // 쿠키를 쓰게 되면 그때 true 로 바꾸고 SameSite · CSRF 대응을 함께 정한다
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
