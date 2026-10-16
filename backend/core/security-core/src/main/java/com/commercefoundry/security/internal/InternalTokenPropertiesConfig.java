package com.commercefoundry.security.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스의 {@code *PropertiesConfig} 가 {@code JwtVerificationPropertiesConfig} 와 함께 {@code @Import} 한다 (서비스 5종).
 * 비밀이 비었거나 형식이 틀리면 {@link InternalTokenProperties} 가 기동을 멈춘다.
 */
@Configuration
@EnableConfigurationProperties(InternalTokenProperties.class)
public class InternalTokenPropertiesConfig {

}
