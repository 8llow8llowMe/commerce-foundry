package com.commercefoundry.security.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스·게이트웨이의 {@code *PropertiesConfig} 가 {@code @Import} 한다. 서블릿 의존이 없다.
 */
@Configuration
@EnableConfigurationProperties(JwtVerificationProperties.class)
public class JwtVerificationPropertiesConfig {

}
