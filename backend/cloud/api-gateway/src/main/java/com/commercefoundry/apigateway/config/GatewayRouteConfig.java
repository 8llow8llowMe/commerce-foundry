package com.commercefoundry.apigateway.config;

import com.commercefoundry.apigateway.route.ServiceRoute;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfig {

    private static final String LOAD_BALANCED_SCHEME = "lb://";

    /** {@link ServiceRoute} 마다 라우트 하나를 만든다. 라우트 id 는 서비스 id, 대상은 Eureka 에서 찾은 인스턴스다. */
    @Bean
    public RouteLocator serviceRouteLocator(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (ServiceRoute route : ServiceRoute.values()) {
            routes.route(route.getServiceId(), spec -> spec
                .path(route.getPathPatterns().toArray(String[]::new))
                .uri(LOAD_BALANCED_SCHEME + route.getServiceId()));
        }
        return routes.build();
    }
}
