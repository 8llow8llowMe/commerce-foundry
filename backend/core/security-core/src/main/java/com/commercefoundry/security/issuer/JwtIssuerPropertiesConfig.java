package com.commercefoundry.security.issuer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * auth-service 의 {@code *PropertiesConfig} 가 {@code JwtVerificationPropertiesConfig} 와 함께 {@code @Import} 한다.
 * 발급기도 {@code iss} · {@code aud} · 공개키(쌍 검사)를 검증 설정에서 읽는다.
 */
@Configuration
@EnableConfigurationProperties(JwtIssuanceProperties.class)
public class JwtIssuerPropertiesConfig {

}
