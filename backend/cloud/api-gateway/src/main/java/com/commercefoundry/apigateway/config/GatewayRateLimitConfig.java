package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 고객 쓰기 API 회원 단위 요청 제한의 한도 ({@code security-guide.md} §7-3). 필터 · 제한기는 {@code ratelimit} 패키지의 컴포넌트다. */
@Configuration
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
public class GatewayRateLimitConfig {
}
