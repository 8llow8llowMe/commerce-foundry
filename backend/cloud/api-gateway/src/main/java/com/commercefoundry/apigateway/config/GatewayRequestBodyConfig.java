package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.properties.GatewayRequestBodyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 요청 본문 크기 상한 ({@code security-guide.md} §7-4). 필터는 {@code GatewayRequestBodySizeFilter} 컴포넌트다. */
@Configuration
@EnableConfigurationProperties(GatewayRequestBodyProperties.class)
public class GatewayRequestBodyConfig {
}
