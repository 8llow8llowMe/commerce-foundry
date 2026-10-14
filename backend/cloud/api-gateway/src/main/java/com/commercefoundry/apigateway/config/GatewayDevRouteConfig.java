package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.route.ServiceRoute;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * 개발용 라우트 — 서비스 헬스 체크({@code /health/{service-id}})와 Swagger 문서({@code /docs/{service-id}/v3/api-docs/{group}}).
 * {@code gateway.dev-routes.enabled} 가 켜졌을 때만 만든다. 기본은 꺼짐이고 local · dev 만 켠다.
 *
 * <p>운영의 헬스 체크는 게이트웨이를 거치지 않고 내부망에서 서비스에 직접 붙는다. 공개 표면을 늘리지 않으려고 운영에서는 끈다.
 *
 * <p>경로는 서비스마다 정확한 값으로만 연다. {@code /docs/{service-id}/**} 처럼 접두어만 떼는 라우트를 만들면
 * {@code /docs/order-service/internal/v1/...} 로 서비스 간 API 에 닿고, {@code /docs/auth-service/api/v1/admin/...} 로
 * 어드민 경로 판정을 우회한다 ({@code architecture-guide.md} §6). {@link ServiceRoute} 에는 넣지 않는다 — 경로 소유 표와 무관하다.
 */
@Configuration
@ConditionalOnBooleanProperty("gateway.dev-routes.enabled")
public class GatewayDevRouteConfig {

    private static final String LOAD_BALANCED_SCHEME = "lb://";
    private static final String SERVICE_HEALTH_PATH = "/actuator/health";

    /** 서비스 {@code *SwaggerConfig} 의 그룹 이름 ({@code bootstrap-conventions.md}). */
    private static final List<String> SWAGGER_GROUPS = List.of("customer", "admin");

    /** 서비스마다 헬스 라우트({@code <service-id>-health})와 문서 라우트({@code <service-id>-docs})를 하나씩 만든다. GET 만 받는다. */
    @Bean
    public RouteLocator devRouteLocator(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (ServiceRoute route : ServiceRoute.values()) {
            String serviceId = route.getServiceId();
            String uri = LOAD_BALANCED_SCHEME + serviceId;

            routes.route(serviceId + "-health", spec -> spec
                .method(HttpMethod.GET).and().path("/health/" + serviceId)
                .filters(filter -> filter.setPath(SERVICE_HEALTH_PATH))
                .uri(uri));

            String[] docsPaths = SWAGGER_GROUPS.stream()
                .map(group -> "/docs/" + serviceId + "/v3/api-docs/" + group)
                .toArray(String[]::new);
            routes.route(serviceId + "-docs", spec -> spec
                .method(HttpMethod.GET).and().path(docsPaths)
                // 앞의 /docs/{service-id} 두 세그먼트를 떼면 서비스의 /v3/api-docs/{group} 이 된다
                .filters(filter -> filter.stripPrefix(2))
                .uri(uri));
        }
        return routes.build();
    }
}
