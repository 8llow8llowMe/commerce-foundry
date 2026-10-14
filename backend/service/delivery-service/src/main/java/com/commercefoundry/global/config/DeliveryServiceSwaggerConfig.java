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
public class DeliveryServiceSwaggerConfig {

    @Bean
    public OpenAPI deliveryServiceOpenApi(Components components, SwaggerProperties swaggerProperties) {
        return new OpenAPI()
            .components(components)
            .info(new Info()
                .title("배송 서비스 API")
                .description("배송비 정책(국내/해외) · 송장 · 배송 상태 추적")
                .version("v1"))
            .servers(List.of(new Server().url(swaggerProperties.serverUrl())));
    }

    @Bean
    public GroupedOpenApi deliveryCustomerApi() {
        return GroupedOpenApi.builder()
            .group("customer")
            .displayName("배송 — 고객")
            .pathsToMatch("/api/v1/**")
            .pathsToExclude("/api/v1/admin/**")
            .build();
    }

    @Bean
    public GroupedOpenApi deliveryAdminApi() {
        return GroupedOpenApi.builder()
            .group("admin")
            .displayName("배송 — 어드민")
            .pathsToMatch("/api/v1/admin/**")
            .build();
    }
}
