package com.commercefoundry.apigateway.config;

import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 공통 프로퍼티 {@code @Import} ({@code bootstrap-conventions.md}). 게이트웨이는 검증만 한다 — 발급({@code JwtIssuerPropertiesConfig})과
 * 서비스 간 인증({@code InternalTokenPropertiesConfig})은 가져오지 않는다.
 */
@Configuration
@Import({
    JwtVerificationPropertiesConfig.class
})
public class GatewayPropertiesConfig {

}
