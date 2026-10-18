package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayCorsProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

@GatewayIntegrationTest
class ApiGatewayCorsTest {

    private static final String ALLOWED_ORIGIN = GatewayIntegrationTest.ALLOWED_ORIGIN;
    private static final String DENIED_ORIGIN = "https://evil.example";
    private static final String PREFLIGHT_PATH = "/api/v1/orders";

    @Autowired
    private WebTestClient webTestClient;

    // ── 실제 서버 ─────────────────────────────────────────────────

    @Test
    @DisplayName("허용 origin 의 preflight 는 허용 메서드·헤더를 돌려주고, 자격 증명은 허용하지 않는다")
    void preflightFromAllowedOriginIsAccepted() {
        webTestClient.options().uri(PREFLIGHT_PATH)
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, Idempotency-Key, Accept-Language")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, methods -> assertThat(split(methods))
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, headers -> assertThat(split(headers))
                .containsExactlyInAnyOrder("Authorization", "Content-Type", "Idempotency-Key", "Accept-Language"));
    }

    @Test
    @DisplayName("허용 목록에 없는 origin 의 preflight 는 403 으로 거부된다")
    void preflightFromUnknownOriginIsRejected() {
        webTestClient.options().uri(PREFLIGHT_PATH)
            .header(HttpHeaders.ORIGIN, DENIED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("허용 목록에 없는 origin 은 preflight 가 아닌 실제 요청도 라우팅 전에 403 으로 거부된다")
    void actualRequestFromUnknownOriginIsRejected() {
        webTestClient.get().uri("/api/v1/products")
            .header(HttpHeaders.ORIGIN, DENIED_ORIGIN)
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("허용 origin 이어도 허용 목록에 없는 요청 헤더를 쓰면 preflight 가 거부된다")
    void preflightWithUnknownHeaderIsRejected() {
        webTestClient.options().uri(PREFLIGHT_PATH)
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Debug-Mode")
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("토큰 없는 어드민 경로 preflight 도 보안 체인의 인증 앞에서 CORS 가 답한다 — 401 이 아니고, 세션 쿠키 · 보안 헤더를 붙이지 않는다")
    void adminPreflightWithoutTokenIsAnsweredByCors() {
        webTestClient.options().uri("/api/v1/admin/orders/1")
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.PATCH.name())
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
            .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
            .expectHeader().doesNotExist("Strict-Transport-Security")
            .expectHeader().doesNotExist("X-Frame-Options")
            .expectHeader().doesNotExist("X-Content-Type-Options")
            .expectHeader().doesNotExist(HttpHeaders.CACHE_CONTROL);
    }

    // ── 설정 값 ──────────────────────────────────────────────────

    @Test
    @DisplayName("허용 origin 이 비어 있으면 열리지 않고 닫힌다 — 어떤 origin 의 preflight 도 통과하지 못한다")
    void emptyAllowedOriginsRejectsEveryOrigin() {
        // 보안 체인이 이 원천으로 만드는 것과 같은 필터다 (GatewaySecurityConfig 의 cors)
        CorsWebFilter filter = new CorsWebFilter(new GatewayCorsConfig().corsConfigurationSource(new GatewayCorsProperties(null)));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.options(PREFLIGHT_PATH)
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()));

        filter.filter(exchange, ignored -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("콤마 목록의 공백·빈 칸은 정리되고, 와일드카드(*)는 기동 단계에서 거부된다")
    void allowedOriginsAreNormalizedAndWildcardIsRejected() {
        assertThat(new GatewayCorsProperties(List.of(" http://localhost:3000 ", "")).allowedOrigins()).containsExactly(ALLOWED_ORIGIN);
        assertThatThrownBy(() -> new GatewayCorsProperties(List.of("*")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> split(String headerValue) {
        return Arrays.stream(headerValue.split(",")).map(String::strip).toList();
    }
}
