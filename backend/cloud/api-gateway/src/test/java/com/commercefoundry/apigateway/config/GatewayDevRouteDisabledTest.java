package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * dev 라우트 플래그의 기본값(꺼짐). 프로파일이 빠진 배포 환경에서 헬스 · 문서 · Swagger UI 가 열리지 않는지 본다.
 */
@GatewayIntegrationTest
class GatewayDevRouteDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("플래그가 꺼져 있으면 dev 라우트 설정 자체가 없다")
    void devRouteConfigIsAbsent() {
        assertThat(applicationContext.getBeanNamesForType(GatewayDevRouteConfig.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/health/product-service", "/docs/product-service/v3/api-docs/customer",
        "/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html", "/webjars/swagger-ui/index.html"
    })
    @DisplayName("플래그가 꺼져 있으면 헬스 · 문서 · Swagger UI 경로가 모두 404 다")
    void devPathsAreNotFound(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isNotFound();
    }
}
