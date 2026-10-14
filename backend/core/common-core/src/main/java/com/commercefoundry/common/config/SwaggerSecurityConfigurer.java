package com.commercefoundry.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;

/**
 * Swagger 의 JWT 인증 스킴. 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 ({@code bootstrap-conventions.md}).
 * 고객 / 어드민 그룹 분리는 서비스의 {@code *SwaggerConfig} 가 정한다.
 */
public class SwaggerSecurityConfigurer {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public Components swaggerComponents() {
        return new Components().addSecuritySchemes(BEARER_AUTH,
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"));
    }
}
