package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청은 {@code URI.create} 로 만든다. {@code MockServerHttpRequest.get(String)} 은 URI 템플릿이라
 * {@code %2e} 를 {@code %252e} 로 다시 인코딩해 버려, 공격자가 보내는 원문을 재현하지 못한다.
 * 원문 역슬래시({@code \})는 {@link URI} 가 받지 않아(서버도 라우팅 전에 400 으로 끊는다) {@code %5c} 변형으로 본다.
 */
class GatewayRequestGuardFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final GatewayRequestGuardFilter filter = new GatewayRequestGuardFilter(new GatewayErrorResponseWriter(JSON));

    @Test
    @DisplayName("다른 모든 WebFilter(CORS 포함)보다 먼저 돈다")
    void runsFirst() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/products/../../../internal/v1/orders/1",
        "/api/v1/products/%2e%2e/%2e%2e/%2e%2e/internal/v1/orders/1",
        "/api/v1/products/%2E%2E/%2E%2E/%2E%2E/internal/v1/orders/1",
        "/api/v1/products/..;/..;/..;/internal/v1/orders/1",
        "/api/v1/products;x=1/../../../internal/v1/orders/1",
        "/api/v1/orders/../admin/orders/1",
        "/api/v1/orders/%2e%2e/admin/orders/1",
        "/api/v1/payments/webhooks/../../admin/payments/1",
        "/api/v1/products/..%2f..%2f..%2finternal/v1/orders/1",
        "/api/v1/products/..%5c..%5cinternal/v1/orders/1",
        "//internal/x",
        "/api/v1/products/..",
        "/api/v1/products/.",
        "/api/v1/products/./1",
        "/api/v1/products//1",
        "/api/v1/products;jsessionid=abc",
        "/api/v1/products/%252e%252e/internal/v1/orders/1",
        "/api/v1/products/%00",
        "/api/v1/products/%1F"
    })
    @DisplayName("정규화되지 않은 경로는 라우팅 전에 400 + GATEWAY_004 로 거절한다")
    void rejectsUnnormalizedPath(String rawPath) {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, rawPath);
        AtomicBoolean routed = new AtomicBoolean();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        // 테스트가 원문을 그대로 보냈는지부터 확인한다 — 중간에 인코딩·정규화되면 이 테스트는 아무것도 증명하지 못한다
        assertThat(exchange.getRequest().getURI().getRawPath()).isEqualTo(rawPath);
        assertThat(routed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertGatewayBadRequestEnvelope(exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/",
        "/api/v1/products/1",
        "/api/v1/products/",
        "/api/v1/products?keyword=..&next=../../internal/v1/orders/1",
        "/api/v1/products/file.name.jpg",
        "/api/v1/products/%EC%83%9D%ED%99%9C%ED%95%9C%EB%B3%B5"
    })
    @DisplayName("정상 경로(끝 슬래시, 쿼리 문자열의 .., 세그먼트 안의 점, 인코딩된 한글)는 통과한다")
    void passesNormalizedPath(String rawPathAndQuery) {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, rawPathAndQuery);
        AtomicBoolean routed = new AtomicBoolean();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    @DisplayName("허용 메서드는 통과한다")
    void passesAllowedMethod(String method) {
        MockServerWebExchange exchange = exchange(HttpMethod.valueOf(method), "/api/v1/products");
        AtomicBoolean routed = new AtomicBoolean();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "CONNECT", "PROPFIND"})
    @DisplayName("허용하지 않는 메서드는 405 + GATEWAY_004 + Allow 헤더로 거절한다")
    void rejectsDisallowedMethod(String method) {
        MockServerWebExchange exchange = exchange(HttpMethod.valueOf(method), "/api/v1/products");
        AtomicBoolean routed = new AtomicBoolean();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.getResponse().getHeaders().getAllow()).containsExactlyInAnyOrder(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);
        assertGatewayBadRequestEnvelope(exchange);
    }

    private static MockServerWebExchange exchange(HttpMethod method, String rawPathAndQuery) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create("http://localhost" + rawPathAndQuery)));
    }

    private static WebFilterChain chain(AtomicBoolean routed) {
        return exchange -> {
            routed.set(true);
            return Mono.empty();
        };
    }

    private static void assertGatewayBadRequestEnvelope(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(exchange.getResponse().getBodyAsString().block(TIMEOUT));
        assertThat(body.path("dataHeader").path("success").asBoolean()).isFalse();
        assertThat(body.path("dataHeader").path("resultCode").asString()).isEqualTo("GATEWAY_004");
        assertThat(body.path("dataBody").isNull()).isTrue();
    }
}
