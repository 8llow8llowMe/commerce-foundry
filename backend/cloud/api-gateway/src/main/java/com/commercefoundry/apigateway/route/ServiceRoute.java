package com.commercefoundry.apigateway.route;

import java.util.List;
import lombok.Getter;

/**
 * 게이트웨이 라우트의 정본. {@code api-design-guide.md} §1-2 "서비스별 경로 소유" 표를 그대로 옮긴다 (고객 → 어드민 순).
 *
 * <p>새 경로 접두어를 만들면 그 표와 이 enum 을 함께 고친다. yml 에 라우트를 따로 적지 않는다 — 정본이 둘이 된다.
 * {@code /internal/**} 은 어떤 상수에도 넣지 않는다. 서비스 간 API 는 외부에서 닿지 않아야 한다 ({@code architecture-guide.md} §6).
 */
@Getter
public enum ServiceRoute {

    AUTH("auth-service",
        "/api/v1/auth/**", "/api/v1/members/**",
        "/api/v1/admin/members/**"),
    PRODUCT("product-service",
        "/api/v1/categories/**", "/api/v1/products/**",
        "/api/v1/admin/categories/**", "/api/v1/admin/products/**", "/api/v1/admin/stocks/**"),
    ORDER("order-service",
        "/api/v1/carts/**", "/api/v1/orders/**",
        "/api/v1/admin/orders/**", "/api/v1/admin/claims/**"),
    PAYMENT("payment-service",
        "/api/v1/payments/**",
        "/api/v1/admin/payments/**"),
    DELIVERY("delivery-service",
        "/api/v1/deliveries/**", "/api/v1/shipping-fees/**",
        "/api/v1/admin/deliveries/**", "/api/v1/admin/shipping-policies/**");

    /** Eureka 에 등록되는 {@code spring.application.name}. 라우트 id 로도 쓴다. */
    private final String serviceId;

    /** 이 서비스가 소유하는 경로 패턴 (Spring {@code PathPattern} 문법). */
    private final List<String> pathPatterns;

    ServiceRoute(String serviceId, String... pathPatterns) {
        this.serviceId = serviceId;
        this.pathPatterns = List.of(pathPatterns);
    }
}
