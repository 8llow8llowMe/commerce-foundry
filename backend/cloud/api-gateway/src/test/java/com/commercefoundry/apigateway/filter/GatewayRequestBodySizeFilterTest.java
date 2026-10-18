package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRequestBodyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GatewayRequestBodySizeFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int LIMIT = 1024;

    private final GatewayRequestBodySizeFilter filter = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofSeconds(2));

    @Test
    @DisplayName("요청 가드 바로 뒤, CORS · 보안 체인보다 앞에서 돈다")
    void runsRightAfterGuard() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    @DisplayName("Content-Length 가 상한을 넘으면 본문을 읽지 않고 413 + GATEWAY_007 + Connection: close 이다")
    void rejectsLargeContentLength() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT + 1).body(Flux.error(new AssertionError("본문을 읽으면 안 된다"))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("Content-Length 가 상한 이하면 본문을 모으지 않고 그대로 넘긴다")
    void passesSmallContentLengthAsIs() {
        String json = "{\"a\":1}";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/carts/me/items").contentType(MediaType.APPLICATION_JSON)
            .contentLength(json.length()).body(json);
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), chain(routed)).block(TIMEOUT);

        assertThat(routed.get()).isSameAs(request);
    }

    @Test
    @DisplayName("길이 헤더도 chunked 도 없는 요청(본문 없는 GET 등)은 모으지 않고 헤더도 바꾸지 않는다")
    void passesRequestWithoutBodyAsIs() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products").build();
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), chain(routed)).block(TIMEOUT);

        assertThat(routed.get()).isSameAs(request);
        assertThat(routed.get().getHeaders().getContentLength()).isEqualTo(-1);
    }

    @Test
    @DisplayName("chunked 본문이 상한을 넘으면 413 이고 하류로 넘기지 않는다")
    void rejectsLargeChunkedBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.just(buffer(LIMIT / 2), buffer(LIMIT / 2), buffer(1))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(filter.reservedBytes()).as("잡아 둔 버퍼 총량을 돌려준다").isZero();
    }

    @Test
    @DisplayName("상한 이하의 chunked 본문은 모아서 Content-Length 와 함께 넘긴다 — 바이트가 같고 Transfer-Encoding 은 지운다")
    void passesSmallChunkedBodyWithLength() {
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.just(buffer(300), buffer(300), buffer(300))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        ServerHttpRequest forwarded = routed.get();
        assertThat(forwarded.getHeaders().getContentLength()).isEqualTo(900);
        assertThat(forwarded.getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(bodyOf(forwarded)).isEqualTo("a".repeat(900));
        assertThat(bodyOf(forwarded)).as("다시 구독해도 같은 본문이다").isEqualTo("a".repeat(900));
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("chunked 본문이 수신 시간 상한 안에 다 오지 않으면 408 이고 하류로 넘기지 않는다 (천천히 보내는 연결)")
    void rejectsSlowChunkedBody() {
        GatewayRequestBodySizeFilter quick = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofMillis(200));
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.concat(Flux.just(buffer(10)), Flux.never())));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        quick.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.REQUEST_TIMEOUT, "GATEWAY_004");
        assertThat(quick.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("동시에 모으는 총량을 넘는 chunked 요청은 411 로 끊는다 — Content-Length 를 붙여 다시 보내라는 뜻")
    void rejectsWhenBufferingBudgetExhausted() {
        GatewayRequestBodySizeFilter tight = filter(DataSize.ofBytes(LIMIT), Duration.ofSeconds(5));
        // 첫 요청은 본문이 끝나지 않아 버퍼 자리를 잡고 있다
        MockServerWebExchange holding = MockServerWebExchange.from(chunked(Flux.never()));
        tight.filter(holding, chain(new AtomicReference<>())).subscribe();
        MockServerWebExchange second = MockServerWebExchange.from(chunked(Flux.just(buffer(10))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        tight.filter(second, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(second, HttpStatus.LENGTH_REQUIRED, "GATEWAY_004");
    }

    @Test
    @DisplayName("하류에서 난 DataBufferLimitException 은 413 으로 바꾸지 않는다 — 오류 처리는 본문을 모으는 단계에만 건다")
    void downstreamLimitErrorIsNotRemapped() {
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.just(buffer(10))));
        WebFilterChain failingChain = forwarded -> Mono.error(new DataBufferLimitException("codec limit"));

        assertThatThrownBy(() -> filter.filter(exchange, failingChain).block(TIMEOUT)).isInstanceOf(DataBufferLimitException.class);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("Content-Type 을 multipart 로 바꿔도 상한은 같다 — 전역 업로드 특례를 두지 않는다")
    void multipartHasNoSpecialLimit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/products")
            .contentType(MediaType.MULTIPART_FORM_DATA).contentLength(LIMIT + 1).body(Flux.empty()));

        filter.filter(exchange, chain(new AtomicReference<>())).block(TIMEOUT);

        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("상한 · 총량 · 수신 시간이 잘못되면 기동하지 않는다")
    void rejectsInvalidProperties() {
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofBytes(0), DataSize.ofMegabytes(32), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(101), DataSize.ofMegabytes(200), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(1), Duration.ofSeconds(10)))
            .as("총량이 상한보다 작으면 chunked 요청을 하나도 받을 수 없다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static GatewayRequestBodySizeFilter filter(DataSize budget, Duration readTimeout) {
        return new GatewayRequestBodySizeFilter(new GatewayRequestBodyProperties(DataSize.ofBytes(LIMIT), budget, readTimeout), new GatewayErrorResponseWriter(JSON));
    }

    private static MockServerHttpRequest chunked(Flux<DataBuffer> body) {
        return MockServerHttpRequest.post("/api/v1/carts/me/merge").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.TRANSFER_ENCODING, "chunked").body(body);
    }

    private static DataBuffer buffer(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'a');
        return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
    }

    private static String bodyOf(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody()).map(buffer -> buffer.toString(StandardCharsets.UTF_8)).block(TIMEOUT);
    }

    private static WebFilterChain chain(AtomicReference<ServerHttpRequest> routed) {
        return exchange -> {
            routed.set(exchange.getRequest());
            return Mono.empty();
        };
    }

    private static void assertRejected(MockServerWebExchange exchange, HttpStatus status, String resultCode) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONNECTION)).isEqualTo("close");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(exchange.getResponse().getBodyAsString().block(TIMEOUT));
        assertThat(body.path("dataHeader").path("success").asBoolean(true)).isFalse();
        assertThat(body.path("dataHeader").path("resultCode").asString()).isEqualTo(resultCode);
        assertThat(body.path("dataBody").isNull()).isTrue();
    }
}
