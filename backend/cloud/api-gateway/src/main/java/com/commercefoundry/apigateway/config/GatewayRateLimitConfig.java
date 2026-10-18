package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 고객 쓰기 API 요청 제한의 한도와 클라이언트 IP 규칙(신뢰 프록시 · HMAC 키) ({@code security-guide.md} §7-3).
 * 필터 · 제한기 · IP 해석기는 {@code ratelimit} 패키지의 컴포넌트다.
 */
@Configuration
@EnableConfigurationProperties({GatewayClientIpProperties.class, GatewayRateLimitProperties.class})
public class GatewayRateLimitConfig {
}
