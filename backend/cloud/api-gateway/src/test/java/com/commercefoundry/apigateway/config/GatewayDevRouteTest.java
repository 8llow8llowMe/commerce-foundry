package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * dev 라우트(헬스 · 문서)와 Swagger UI 를 켠 게이트웨이 ({@code gateway.dev-routes.enabled=true}, local · dev 와 같은 설정).
 *
 * <p>서비스 대신 JDK {@link HttpServer} 하나를 띄워 서비스 5종의 인스턴스로 등록한다 (Eureka 대신 simple discovery).
 * 게이트웨이가 하류에 넘긴 경로를 기록해 rewrite 결과를 직접 확인한다.
 *
 * <p>기대값은 {@link GatewayDevRouteConfig} 에서 뽑지 않고 손으로 적는다 — 검증 대상에서 기대값을 만들면 어긋나도 통과한다.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = "gateway.dev-routes.enabled=true")
class GatewayDevRouteTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");

    /** 하류(가짜 서비스)가 받은 원문 경로. */
    private static final Queue<String> UPSTREAM_PATHS = new ConcurrentLinkedQueue<>();

    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void registerServiceInstances(DynamicPropertyRegistry registry) {
        String upstreamUri = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        for (String serviceId : SERVICE_IDS) {
            registry.add("spring.cloud.discovery.client.simple.instances." + serviceId + "[0].uri", () -> upstreamUri);
        }
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @BeforeEach
    void clearUpstreamPaths() {
        UPSTREAM_PATHS.clear();
    }

    // ── 라우트 ────────────────────────────────────────────────────

    @Test
    @DisplayName("플래그를 켜면 라우트는 서비스 5종 + 서비스마다 헬스 · 문서 라우트 하나씩이다")
    void routeIdsAreServicesPlusDevRoutes() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block(TIMEOUT);

        assertThat(routeIds).containsExactlyInAnyOrder(
            "auth-service", "product-service", "order-service", "payment-service", "delivery-service",
            "auth-service-health", "product-service-health", "order-service-health", "payment-service-health", "delivery-service-health",
            "auth-service-docs", "product-service-docs", "order-service-docs", "payment-service-docs", "delivery-service-docs");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', textBlock = """
        /health/auth-service                           | auth-service-health
        /health/product-service                        | product-service-health
        /health/order-service                          | order-service-health
        /health/payment-service                        | payment-service-health
        /health/delivery-service                       | delivery-service-health
        /docs/auth-service/v3/api-docs/customer        | auth-service-docs
        /docs/auth-service/v3/api-docs/admin           | auth-service-docs
        /docs/product-service/v3/api-docs/customer     | product-service-docs
        /docs/product-service/v3/api-docs/admin        | product-service-docs
        /docs/order-service/v3/api-docs/customer       | order-service-docs
        /docs/order-service/v3/api-docs/admin          | order-service-docs
        /docs/payment-service/v3/api-docs/customer     | payment-service-docs
        /docs/payment-service/v3/api-docs/admin        | payment-service-docs
        /docs/delivery-service/v3/api-docs/customer    | delivery-service-docs
        /docs/delivery-service/v3/api-docs/admin       | delivery-service-docs
        /api/v1/products                               | product-service
        """)
    @DisplayName("헬스 · 문서 경로는 서비스마다 정해진 라우트 하나에만 걸리고, 서비스 라우트는 그대로다")
    void routesDevPathToItsRoute(String path, String routeId) {
        assertThat(matchingRouteIds(HttpMethod.GET, path)).containsExactly(routeId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/health", "/health/unknown-service", "/health/api-gateway", "/health/product-service/db", "/health/product-service/liveness",
        "/docs/product-service/v3/api-docs", "/docs/product-service/v3/api-docs/swagger-config", "/docs/product-service/v3/api-docs/customer/extra",
        "/docs/order-service/internal/v1/orders/1", "/docs/auth-service/api/v1/admin/members/1", "/docs/unknown-service/v3/api-docs/customer"
    })
    @DisplayName("정해진 헬스 · 문서 경로 밖은 어떤 라우트에도 걸리지 않는다 — 서비스 간 API · 어드민 경로 · 다른 actuator 로 새지 않는다")
    void otherDevPathMatchesNoRoute(String path) {
        assertThat(matchingRouteIds(HttpMethod.GET, path)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("헬스 · 문서 라우트는 GET 만 받는다")
    void devRoutesAcceptGetOnly(String method) {
        assertThat(matchingRouteIds(HttpMethod.valueOf(method), "/health/product-service")).isEmpty();
        assertThat(matchingRouteIds(HttpMethod.valueOf(method), "/docs/product-service/v3/api-docs/customer")).isEmpty();
    }

    // ── 하류로 넘기는 경로 ─────────────────────────────────────────

    @Test
    @DisplayName("게이트웨이 경유 헬스 체크는 서비스의 /actuator/health 로만 간다")
    void healthRouteGoesToActuatorHealth() {
        webTestClient.get().uri("/health/product-service").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");

        assertThat(UPSTREAM_PATHS).containsExactly("/actuator/health");
    }

    @Test
    @DisplayName("게이트웨이 경유 문서는 앞의 /docs/{service-id} 를 떼고 서비스의 /v3/api-docs/{group} 으로 간다")
    void docsRouteGoesToServiceGroupDocs() {
        webTestClient.get().uri("/docs/order-service/v3/api-docs/admin").exchange()
            .expectStatus().isOk();

        assertThat(UPSTREAM_PATHS).containsExactly("/v3/api-docs/admin");
    }

    // ── Swagger UI ────────────────────────────────────────────────

    @Test
    @DisplayName("Swagger UI 의 문서 목록은 서비스 5종 × 고객 / 어드민 그룹이고 모두 /docs/** 를 가리킨다")
    void swaggerUiListsServiceGroupDocs() {
        byte[] body = webTestClient.get().uri("/v3/api-docs/swagger-config").exchange()
            .expectStatus().isOk()
            .expectBody().returnResult().getResponseBody();

        List<String> urls = JsonPath.read(new String(body, StandardCharsets.UTF_8), "$.urls[*].url");
        assertThat(urls).containsExactlyInAnyOrder(
            "/docs/auth-service/v3/api-docs/customer", "/docs/auth-service/v3/api-docs/admin",
            "/docs/product-service/v3/api-docs/customer", "/docs/product-service/v3/api-docs/admin",
            "/docs/order-service/v3/api-docs/customer", "/docs/order-service/v3/api-docs/admin",
            "/docs/payment-service/v3/api-docs/customer", "/docs/payment-service/v3/api-docs/admin",
            "/docs/delivery-service/v3/api-docs/customer", "/docs/delivery-service/v3/api-docs/admin");
    }

    @Test
    @DisplayName("Swagger UI 가 열리고, 게이트웨이 자신의 기본 문서는 내지 않는다")
    void servesSwaggerUiWithoutGatewayDocs() {
        URI uiLocation = webTestClient.get().uri("/swagger-ui.html").exchange()
            .expectStatus().is3xxRedirection()
            .returnResult(Void.class).getResponseHeaders().getLocation();

        assertThat(uiLocation).hasPath("/swagger-ui/index.html");
        webTestClient.get().uri(uiLocation.getPath()).exchange().expectStatus().isOk();
        webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isNotFound();
        // 정적 리소스 매핑을 껐으므로 Boot 기본 /webjars/** 로는 열리지 않는다 (UI 는 springdoc 경로로만)
        webTestClient.get().uri("/webjars/swagger-ui/index.html").exchange().expectStatus().isNotFound();
    }

    private List<String> matchingRouteIds(HttpMethod method, String rawPath) {
        return routeLocator.getRoutes()
            .filterWhen(route -> route.getPredicate().apply(exchange(method, rawPath)))
            .map(Route::getId)
            .collectList()
            .block(TIMEOUT);
    }

    private static MockServerWebExchange exchange(HttpMethod method, String rawPath) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create("http://localhost" + rawPath)));
    }

    /** 어떤 경로든 받은 원문 경로를 기록하고 {@code {"status":"UP"}} 로 답한다. */
    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_PATHS.add(exchange.getRequestURI().getRawPath());
                byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
