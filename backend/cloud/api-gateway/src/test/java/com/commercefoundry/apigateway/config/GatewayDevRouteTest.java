package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.route.ServiceRoute;
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
import java.util.Arrays;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * dev 라우트(헬스 · 문서)와 Swagger UI 를 켠 게이트웨이 (local · dev 프로파일과 같은 스위치 셋).
 *
 * <p>서비스 대신 JDK {@link HttpServer} 하나를 띄워 서비스 5종의 인스턴스로 등록한다 (Eureka 대신 simple discovery).
 * 게이트웨이가 하류에 넘긴 경로를 기록해 rewrite 결과를 직접 확인한다.
 *
 * <p>하류 서버는 이 클래스의 수명과 같다. 클래스가 끝나면 서버를 닫고 컨텍스트도 캐시에서 내린다 — 닫힌 서버를 가리키는
 * 컨텍스트가 재사용되지 않게. 테스트는 순차 실행을 가정한다 (기록된 하류 경로를 테스트마다 비운다).
 *
 * <p>라우트 · 하류 경로 기대값은 {@link GatewayDevRouteConfig} 에서 뽑지 않고 손으로 적는다 — 검증 대상에서 기대값을 만들면 어긋나도 통과한다.
 * Swagger UI 목록(yml)만은 다른 출처인 {@link ServiceRoute} 와 대조한다 — 서비스를 더하고 yml 을 빠뜨리면 잡히게.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = {"gateway.dev-routes.enabled=true", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayDevRouteTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");

    /** 서비스 {@code *SwaggerConfig} 의 그룹 이름 ({@code bootstrap-conventions.md}). */
    private static final List<String> SWAGGER_GROUPS = List.of("customer", "admin");

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

    @ParameterizedTest(name = "{0} → {1} → 하류 {2}")
    @CsvSource(delimiter = '|', textBlock = """
        /health/auth-service                           | auth-service-health     | /actuator/health
        /health/product-service                        | product-service-health  | /actuator/health
        /health/order-service                          | order-service-health    | /actuator/health
        /health/payment-service                        | payment-service-health  | /actuator/health
        /health/delivery-service                       | delivery-service-health | /actuator/health
        /docs/auth-service/v3/api-docs/customer        | auth-service-docs       | /v3/api-docs/customer
        /docs/auth-service/v3/api-docs/admin           | auth-service-docs       | /v3/api-docs/admin
        /docs/product-service/v3/api-docs/customer     | product-service-docs    | /v3/api-docs/customer
        /docs/product-service/v3/api-docs/admin        | product-service-docs    | /v3/api-docs/admin
        /docs/order-service/v3/api-docs/customer       | order-service-docs      | /v3/api-docs/customer
        /docs/order-service/v3/api-docs/admin          | order-service-docs      | /v3/api-docs/admin
        /docs/payment-service/v3/api-docs/customer     | payment-service-docs    | /v3/api-docs/customer
        /docs/payment-service/v3/api-docs/admin        | payment-service-docs    | /v3/api-docs/admin
        /docs/delivery-service/v3/api-docs/customer    | delivery-service-docs   | /v3/api-docs/customer
        /docs/delivery-service/v3/api-docs/admin       | delivery-service-docs   | /v3/api-docs/admin
        """)
    @DisplayName("헬스 · 문서 경로는 정해진 라우트 하나에만 걸리고, 하류에는 고정된 경로로만 간다")
    void devPathGoesToFixedDownstreamPath(String path, String routeId, String downstreamPath) {
        assertThat(matchingRouteIds(HttpMethod.GET, path)).containsExactly(routeId);

        webTestClient.get().uri(path).exchange().expectStatus().isOk();
        assertThat(UPSTREAM_PATHS).containsExactly(downstreamPath);
    }

    @Test
    @DisplayName("dev 라우트를 켜도 서비스 라우트는 그대로다")
    void serviceRoutesAreUnchanged() {
        assertThat(matchingRouteIds(HttpMethod.GET, "/api/v1/products")).containsExactly("product-service");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/health", "/health/unknown-service", "/health/api-gateway", "/health/product-service/db", "/health/product-service/liveness",
        "/health/product-service/", "/HEALTH/product-service", "/health/PRODUCT-SERVICE",
        "/docs/product-service/v3/api-docs", "/docs/product-service/v3/api-docs/swagger-config", "/docs/product-service/v3/api-docs/customer/extra",
        "/docs/product-service/v3/api-docs/customer/", "/docs/product-service/v3/api-docs/CUSTOMER",
        "/docs/order-service/internal/v1/orders/1", "/docs/auth-service/api/v1/admin/members/1", "/docs/unknown-service/v3/api-docs/customer"
    })
    @DisplayName("정해진 헬스 · 문서 경로 밖(하위 경로 · 끝 슬래시 · 대소문자 변형 포함)은 어떤 라우트에도 걸리지 않는다")
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

    // ── Swagger UI ────────────────────────────────────────────────

    @Test
    @DisplayName("Swagger UI 의 문서 목록은 ServiceRoute 의 서비스 × 고객 / 어드민 그룹과 같고, 각 문서는 그 서비스의 문서 라우트에 걸린다")
    void swaggerUiListsEveryServiceGroupDocs() {
        byte[] body = webTestClient.get().uri("/v3/api-docs/swagger-config").exchange()
            .expectStatus().isOk()
            .expectBody().returnResult().getResponseBody();
        List<String> urls = JsonPath.read(new String(body, StandardCharsets.UTF_8), "$.urls[*].url");

        List<String> expected = Arrays.stream(ServiceRoute.values())
            .flatMap(route -> SWAGGER_GROUPS.stream().map(group -> "/docs/" + route.getServiceId() + "/v3/api-docs/" + group))
            .toList();
        assertThat(urls).containsExactlyInAnyOrderElementsOf(expected);

        for (ServiceRoute route : ServiceRoute.values()) {
            for (String group : SWAGGER_GROUPS) {
                assertThat(matchingRouteIds(HttpMethod.GET, "/docs/" + route.getServiceId() + "/v3/api-docs/" + group))
                    .containsExactly(route.getServiceId() + "-docs");
            }
        }
    }

    @Test
    @DisplayName("Swagger UI 가 열리고, 게이트웨이 자신의 기본 문서와 /webjars 경로는 열리지 않는다")
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
