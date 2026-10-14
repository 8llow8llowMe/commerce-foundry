package com.commercefoundry.apigateway.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예외 → 상태 코드 · 게이트웨이 오류 코드 매핑. 실제 서버로는 재현하기 어려운 연결 실패 · 연결 끊김 · 타임아웃 · 그 밖의 4xx 를 여기서 본다.
 */
class GatewayErrorWebExceptionHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(new GatewayErrorResponseWriter(JSON));

    static Stream<Arguments> mappings() {
        return Stream.of(
            arguments("라우트 없음", new ResponseStatusException(HttpStatus.NOT_FOUND), 404, "GATEWAY_001"),
            arguments("인스턴스 없음", NotFoundException.create(false, "Unable to find instance for product-service"), 503, "GATEWAY_002"),
            arguments("인스턴스 없음 (use404 를 켜도)", NotFoundException.create(true, "Unable to find instance for product-service"), 503, "GATEWAY_002"),
            arguments("연결 거부", new ConnectException("Connection refused"), 503, "GATEWAY_002"),
            arguments("감싼 연결 거부", new IllegalStateException(new ConnectException("Connection refused")), 503, "GATEWAY_002"),
            arguments("호스트로 가는 경로 없음", new NoRouteToHostException("No route to host"), 503, "GATEWAY_002"),
            arguments("그 밖의 소켓 오류", new SocketException("Network is unreachable"), 503, "GATEWAY_002"),
            arguments("호스트 이름 해석 실패", new UnknownHostException("product-service"), 503, "GATEWAY_002"),
            arguments("503 상태 예외", new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), 503, "GATEWAY_002"),
            arguments("응답 전 연결 끊김", PrematureCloseException.TEST_EXCEPTION, 502, "GATEWAY_005"),
            arguments("감싼 응답 전 연결 끊김", new IllegalStateException(PrematureCloseException.TEST_EXCEPTION), 502, "GATEWAY_005"),
            arguments("502 상태 예외", new ResponseStatusException(HttpStatus.BAD_GATEWAY), 502, "GATEWAY_005"),
            arguments("응답 타임아웃 (게이트웨이가 던지는 모양)",
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "timeout", new TimeoutException("Response took longer than timeout")), 504, "GATEWAY_003"),
            arguments("감싸지 않은 게이트웨이 타임아웃", new TimeoutException("Response took longer than timeout"), 504, "GATEWAY_003"),
            arguments("잘못된 입력", new ServerWebInputException("bad request"), 400, "GATEWAY_004"),
            arguments("그 밖의 4xx 는 상태 유지", new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED), 405, "GATEWAY_004"),
            arguments("그 밖의 5xx 는 500", new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED), 500, "GATEWAY_000"),
            arguments("예상하지 못한 예외", new IllegalStateException("unexpected"), 500, "GATEWAY_000")
        );
    }

    @ParameterizedTest(name = "{0} → {2} {3}")
    @MethodSource("mappings")
    @DisplayName("예외를 상태 코드와 게이트웨이 오류 코드로 바꿔 Response 봉투로 쓴다")
    void mapsExceptionToEnvelope(String description, Throwable ex, int expectedStatus, String expectedCode) {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, ex).block(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        JsonNode body = JSON.readTree(exchange.getResponse().getBodyAsString().block(TIMEOUT));
        assertThat(body.path("dataHeader").path("success").asBoolean()).isFalse();
        assertThat(body.path("dataHeader").path("resultCode").asString()).isEqualTo(expectedCode);
        assertThat(body.path("dataBody").isNull()).isTrue();
    }

    @Test
    @DisplayName("예외가 실어 둔 응답 헤더(405 의 Allow 등)를 지킨다")
    void preservesErrorResponseHeaders() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new MethodNotAllowedException(HttpMethod.TRACE, Set.of(HttpMethod.GET, HttpMethod.POST))).block(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.getResponse().getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("예외 메시지·타입은 응답에 실리지 않는다")
    void doesNotLeakExceptionDetails() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new IllegalStateException("upstream host=10.0.0.12 token=abc")).block(TIMEOUT);

        String body = exchange.getResponse().getBodyAsString().block(TIMEOUT);
        assertThat(body).doesNotContain("10.0.0.12", "token", "IllegalStateException");
    }

    @Test
    @DisplayName("이미 커밋된 응답이면 덮어쓰지 않고 예외를 그대로 흘려보낸다")
    void propagatesErrorWhenResponseAlreadyCommitted() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block(TIMEOUT);
        IllegalStateException ex = new IllegalStateException("after commit");

        assertThatThrownBy(() -> handler.handle(exchange, ex).block(TIMEOUT)).isSameAs(ex);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products/1"));
    }
}
