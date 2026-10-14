package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.properties.GatewayCorsProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS 는 게이트웨이 한 곳에서만 처리한다. 서비스가 CORS 헤더를 또 붙이면 응답 헤더가 중복돼 브라우저가 거부한다.
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
    public CorsWebFilter corsWebFilter(GatewayCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // 빈 목록이면 어떤 origin 도 통과하지 못한다 — 설정이 빠졌을 때 열리지 않고 닫힌다
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        // Authorization 헤더는 allowedHeaders 로 충분하고, 지금은 쿠키를 쓰는 곳이 없다.
        // #4 에서 refresh 토큰을 쿠키로 두면 그때 true 로 바꾸고 SameSite · CSRF 대응을 함께 정한다
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
