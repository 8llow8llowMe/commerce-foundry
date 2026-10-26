package com.commercefoundry.apigateway.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.filter.GatewayRequestGuardFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * 실제 {@link RouteLocator} 의 라우트가 {@code api-design-guide.md} §1-2 경로 소유 표와 맞는지 확인한다.
 *
 * <p>샘플 기대값은 {@link ServiceRoute} 에서 뽑지 않고 표를 손으로 옮긴다 — 검증 대상에서 기대값을 만들면 어긋나도 통과한다.
 * 표 자체와의 대조는 문서를 직접 읽어서 한다.
 */
@GatewayIntegrationTest
class GatewayRouteCoverageTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Gradle 테스트 작업 디렉터리(모듈 디렉터리) 기준. {@code build.gradle} 이 이 파일을 test 태스크 입력으로 선언한다. */
    private static final Path ROUTE_TABLE_DOC = Path.of("../../docs/api-design-guide.md");
    private static final String ROUTE_TABLE_HEADING = "### 1-2. 서비스별 경로 소유";
    private static final Pattern BACKTICK_CODE = Pattern.compile("`([^`]+)`");

    /** 경로 소유 표의 접두어마다 샘플 경로 하나 (고객 → 어드민 순). */
    private static final List<Sample> OWNED_SAMPLES = List.of(
        new Sample("/api/v1/auth/login", "auth-service"),
        new Sample("/api/v1/members/me/shipping-addresses", "auth-service"),
        new Sample("/api/v1/admin/members/1", "auth-service"),
        new Sample("/api/v1/admin/audit-logs", "auth-service"),
        new Sample("/api/v1/categories", "product-service"),
        new Sample("/api/v1/products/1/skus", "product-service"),
        new Sample("/api/v1/displays/main", "product-service"),
        new Sample("/api/v1/admin/categories/1", "product-service"),
        new Sample("/api/v1/admin/products", "product-service"),
        new Sample("/api/v1/admin/stocks/1", "product-service"),
        new Sample("/api/v1/admin/displays/sections/1", "product-service"),
        new Sample("/api/v1/carts/items", "order-service"),
        new Sample("/api/v1/orders/quotes", "order-service"),
        new Sample("/api/v1/admin/orders/1/status", "order-service"),
        new Sample("/api/v1/admin/claims", "order-service"),
        new Sample("/api/v1/payments/webhooks/toss", "payment-service"),
        new Sample("/api/v1/admin/payments/1", "payment-service"),
        new Sample("/api/v1/deliveries/1", "delivery-service"),
        new Sample("/api/v1/shipping-fees", "delivery-service"),
        new Sample("/api/v1/admin/deliveries/1", "delivery-service"),
        new Sample("/api/v1/admin/shipping-policies", "delivery-service"),
        new Sample("/api/v1/admin/jobs/1", "operations-service"),
        new Sample("/api/v1/admin/notifications/1", "operations-service")
    );

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private GatewayRequestGuardFilter requestGuardFilter;

    @Autowired
    private ApplicationContext applicationContext;

    private record Sample(String path, String serviceId) {
    }

    static Stream<Arguments> ownedSamples() {
        return OWNED_SAMPLES.stream().map(sample -> arguments(sample.path(), sample.serviceId()));
    }

    // ── 경로 소유 ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("ownedSamples")
    @DisplayName("경로 소유 표의 접두어마다 소유 서비스의 라우트 하나에만 걸린다")
    void routesOwnedPathToItsService(String path, String serviceId) {
        assertThat(matchingRouteIds(path)).containsExactly(serviceId);
    }

    @Test
    @DisplayName("dev 라우트 플래그가 꺼져 있으면 라우트 id 는 서비스 6종과 정확히 같다 — 헬스 · 문서 라우트가 하나도 없다")
    void routeIdsAreExactlyTheServices() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block(TIMEOUT);

        assertThat(routeIds).containsExactlyInAnyOrder("auth-service", "product-service", "order-service", "payment-service", "delivery-service", "operations-service");
    }

    @Test
    @DisplayName("ServiceRoute 의 경로 패턴은 모두 위 샘플로 검증된다 — 접두어를 추가하면 샘플도 추가해야 한다")
    void everyRoutePatternHasSample() {
        PathPatternParser parser = PathPatternParser.defaultInstance;

        for (ServiceRoute route : ServiceRoute.values()) {
            for (String pattern : route.getPathPatterns()) {
                assertThat(OWNED_SAMPLES)
                    .as("샘플이 없는 경로 패턴: %s", pattern)
                    .anyMatch(sample -> parser.parse(pattern).matches(PathContainer.parsePath(sample.path())));
            }
        }
    }

    @Test
    @DisplayName("ServiceRoute 는 api-design-guide.md §1-2 경로 소유 표와 정확히 같다 — 표만 바뀌어도 잡힌다")
    void serviceRoutesMatchOwnershipTableInDocs() throws IOException {
        assertThat(ROUTE_TABLE_DOC)
            .as("경로 소유 표 문서를 찾지 못했다 (작업 디렉터리: %s)", Path.of("").toAbsolutePath())
            .isRegularFile();

        Map<String, Set<String>> documented = parseOwnershipTable(Files.readAllLines(ROUTE_TABLE_DOC, StandardCharsets.UTF_8));
        Map<String, Set<String>> routed = Arrays.stream(ServiceRoute.values())
            .collect(Collectors.toMap(ServiceRoute::getServiceId, route -> Set.copyOf(route.getPathPatterns())));

        assertThat(documented).isEqualTo(routed);
    }

    // ── 노출되면 안 되는 경로 ─────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/internal/v1/orders/1", "/auth-service/internal/v1/x", "/api/v2/products",
        "/health/product-service", "/docs/product-service/v3/api-docs/customer"
    })
    @DisplayName("서비스 간 API · discovery locator 꼴 경로 · 다른 버전 경로 · 꺼진 dev 라우트 경로는 어떤 라우트에도 걸리지 않는다")
    void unownedPathMatchesNoRoute(String path) {
        assertThat(matchingRouteIds(path)).isEmpty();
    }

    /**
     * 경로 탐색 변형을 라우트 predicate 에 넣으면 어디에 걸리는지 기록한다 ({@code -} 는 어느 라우트에도 안 걸림).
     * predicate 는 정규화하지 않은 원문으로 매칭하므로 {@code ..} 변형이 서비스 라우트에 걸린다 — 라우트만으로는 막히지 않는다.
     * 그래서 라우팅 전에 도는 {@link GatewayRequestGuardFilter} 가 모두 400 으로 끊는지 함께 확인한다.
     */
    @ParameterizedTest(name = "{0} → predicate: {1}")
    @CsvSource(delimiter = '|', textBlock = """
        /api/v1/products/../../../internal/v1/orders/1             | product-service
        /api/v1/products/%2e%2e/%2e%2e/%2e%2e/internal/v1/orders/1 | product-service
        /api/v1/products/..;/..;/..;/internal/v1/orders/1          | product-service
        /api/v1/products;x=1/../../../internal/v1/orders/1         | product-service
        /api/v1/orders/../admin/orders/1                           | order-service
        /api/v1/orders/%2e%2e/admin/orders/1                       | order-service
        /api/v1/payments/webhooks/../../admin/payments/1           | payment-service
        //internal/x                                               | -
        """)
    @DisplayName("경로 탐색 변형은 라우트 predicate 로는 걸러지지 않는다 — 요청 가드가 라우팅 전에 400 으로 끊는다")
    void traversalVariantIsStoppedBeforeRouting(String rawPath, String predicateMatch) {
        List<String> expectedRouteIds = predicateMatch.equals("-") ? List.of() : List.of(predicateMatch);
        assertThat(matchingRouteIds(rawPath)).containsExactlyElementsOf(expectedRouteIds);

        MockServerWebExchange exchange = exchange(rawPath);
        requestGuardFilter.filter(exchange, routed -> Mono.error(new AssertionError("라우팅까지 갔다: " + rawPath))).block(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("discovery locator 는 꺼져 있다 — 켜면 /<service-id>/internal/** 로 서비스 간 API 가 노출된다")
    void discoveryLocatorIsDisabled() {
        // Eureka 를 끈 테스트에선 등록된 서비스가 없어, locator 가 켜져 있어도 위 경로 검사로는 드러나지 않는다. 빈 존재로 확인한다
        assertThat(applicationContext.getBeanNamesForType(DiscoveryClientRouteDefinitionLocator.class)).isEmpty();
    }

    private List<String> matchingRouteIds(String rawPath) {
        return routeLocator.getRoutes()
            .filterWhen(route -> route.getPredicate().apply(exchange(rawPath)))
            .map(Route::getId)
            .collectList()
            .block(TIMEOUT);
    }

    /** {@code URI.create} 로 원문을 그대로 쓴다. {@code MockServerHttpRequest.get(String)} 은 {@code %2e} 를 다시 인코딩한다. */
    private static MockServerWebExchange exchange(String rawPath) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost" + rawPath)));
    }

    /** 표의 첫 칸이 서비스 id, 나머지 칸의 백틱 안이 경로 패턴이다. 머리글·구분선은 첫 칸이 서비스 id 가 아니라 건너뛴다. */
    private static Map<String, Set<String>> parseOwnershipTable(List<String> lines) {
        int heading = lines.indexOf(ROUTE_TABLE_HEADING);
        assertThat(heading).as("'%s' 절을 찾지 못했다", ROUTE_TABLE_HEADING).isNotNegative();

        Map<String, Set<String>> table = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : lines.subList(heading + 1, lines.size())) {
            if (!line.startsWith("|")) {
                if (inTable) {
                    break;
                }
                continue;
            }
            inTable = true;

            String[] cells = line.split("\\|");
            String serviceId = cells[1].strip();
            if (!serviceId.endsWith("-service")) {
                continue;
            }
            Set<String> patterns = new LinkedHashSet<>();
            for (int i = 2; i < cells.length; i++) {
                Matcher matcher = BACKTICK_CODE.matcher(cells[i]);
                while (matcher.find()) {
                    patterns.add(matcher.group(1));
                }
            }
            table.put(serviceId, patterns);
        }
        assertThat(table).as("'%s' 절에서 서비스 행을 하나도 읽지 못했다", ROUTE_TABLE_HEADING).isNotEmpty();
        return table;
    }
}
