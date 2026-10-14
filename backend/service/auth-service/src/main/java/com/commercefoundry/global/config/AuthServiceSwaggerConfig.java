package com.commercefoundry.global.config;

import com.commercefoundry.common.properties.SwaggerProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 고객 / 어드민 문서를 그룹으로 나눈다 ({@code architecture-guide.md} §2). {@code /internal/**} 은 어느 그룹에도 넣지 않는다.
 *
 * <p>문서 노출 여부는 {@code springdoc.*.enabled} 가 정한다 — 기본은 닫혀 있고 local · dev 만 연다.
 */
@Configuration
public class AuthServiceSwaggerConfig {

    @Bean
    public OpenAPI authServiceOpenApi(Components components, SwaggerProperties swaggerProperties) {
        return new OpenAPI()
            .components(components)
            .info(new Info()
                .title("인증 서비스 API")
                .description("회원가입·로그인 · JWT 발급·재발급·로그아웃 · 카카오·네이버 소셜 · 관리자 계정 · 배송지 주소록")
                .version("v1"))
            .servers(List.of(new Server().url(swaggerProperties.serverUrl())));
    }

    @Bean
    public GroupedOpenApi authCustomerApi() {
        return GroupedOpenApi.builder()
            .group("customer")
            .displayName("인증 — 고객")
            .pathsToMatch("/api/v1/**")
            .pathsToExclude("/api/v1/admin/**")
            .build();
    }

    @Bean
    public GroupedOpenApi authAdminApi() {
        return GroupedOpenApi.builder()
            .group("admin")
            .displayName("인증 — 어드민")
            .pathsToMatch("/api/v1/admin/**")
            .build();
    }
}
