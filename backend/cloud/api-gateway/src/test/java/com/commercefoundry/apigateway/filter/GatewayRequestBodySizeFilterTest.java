package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRequestBodyProperties;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
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
    /** 배출 상한 — 상한의 4배. */
    private static final int DISCARD_LIMIT = LIMIT * 4;

    private final GatewayRequestBodySizeFilter filter = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofSeconds(2), Duration.ofSeconds(2));

    @Test
    @DisplayName("요청 가드 바로 뒤, CORS · 보안 체인보다 앞에서 돈다")
    void runsRightAfterGuard() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    @DisplayName("Content-Length 가 상한을 넘고 배출 상한 이하면 본문을 끝까지 읽어 버린 뒤 413 + GATEWAY_007 + Connection: close 이다 — 읽은 버퍼는 모두 해제한다")
    void drainsLargeContentLengthThenRejects() {
        TrackedBuffers buffers = new TrackedBuffers();
        AtomicBoolean drained = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT * 2L)
            .body(Flux.just(buffers.next(LIMIT), buffers.next(LIMIT)).doOnComplete(() -> drained.set(true))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(drained).as("413 을 쓰기 전에 본문을 끝까지 받는다 — 읽지 않은 본문을 남기고 닫으면 클라이언트가 RST 를 본다").isTrue();
        assertThat(buffers.allReleased()).isTrue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("Content-Length 가 배출 상한을 넘으면 본문을 읽지 않고 바로 413 이다 — 큰 본문을 끝까지 받지 않는다")
    void rejectsContentLengthBeyondDiscardLimitWithoutReading() {
        AtomicBoolean subscribed = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(DISCARD_LIMIT + 1L).body(watched(subscribed)));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(subscribed).as("본문을 읽지 않는다").isFalse();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("Expect: 100-continue 면 배출 상한 안이어도 본문을 읽지 않는다 — 구독하면 100 이 나가 클라이언트가 본문을 보내기 시작한다")
    void rejectsExpectContinueWithoutReading() {
        AtomicBoolean subscribed = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT + 1L).header(HttpHeaders.EXPECT, "100-continue").body(watched(subscribed)));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(subscribed).as("본문을 읽지 않는다").isFalse();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("배출이 시간 상한 안에 끝나지 않으면 그때 413 을 쓰고 끊는다 — 408 이 아니다 (천천히 보내며 연결을 잡는 쪽)")
    void stopsDrainingContentLengthAtDiscardTimeout() {
        GatewayRequestBodySizeFilter quick = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofSeconds(5), Duration.ofMillis(200));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT * 2L).body(Flux.concat(Flux.just(buffer(10)), Flux.never())));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        quick.filter(exchange, chain(routed)).block(TIMEOUT);

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
    @DisplayName("chunked 본문이 상한을 넘으면 본문 끝까지 읽어 버린 뒤 413 이고 하류로 넘기지 않는다 — 읽은 버퍼는 모두 해제한다")
    void rejectsLargeChunkedBody() {
        TrackedBuffers buffers = new TrackedBuffers();
        AtomicBoolean drained = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(
            Flux.just(buffers.next(LIMIT / 2), buffers.next(LIMIT / 2), buffers.next(1), buffers.next(LIMIT)).doOnComplete(() -> drained.set(true))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(drained).as("상한을 넘은 뒤의 나머지도 받아 버린다").isTrue();
        assertThat(buffers.allReleased()).isTrue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(filter.reservedBytes()).as("잡아 둔 버퍼 총량을 돌려준다").isZero();
    }

    @Test
    @DisplayName("chunked 본문이 상한을 넘는 순간 버퍼 자리를 돌려준다 — 배출 중인 연결이 동시 수신 총량을 잡지 않는다")
    void releasesReservationAsSoonAsChunkedBodyOverflows() {
        AtomicLong reservedWhileDraining = new AtomicLong(-1);
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.concat(
            Flux.just(buffer(LIMIT), buffer(1)),
            Flux.defer(() -> {
                reservedWhileDraining.set(filter.reservedBytes());
                return Flux.just(buffer(10));
            }))));

        filter.filter(exchange, chain(new AtomicReference<>())).block(TIMEOUT);

        assertThat(reservedWhileDraining).hasValue(0);
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("chunked 본문이 배출 상한을 넘으면 더 읽지 않고 413 으로 끊는다 — 끝없는 본문도 배출 상한에서 멈춘다")
    void stopsDrainingChunkedBodyBeyondDiscardLimit() {
        TrackedBuffers buffers = new TrackedBuffers();
        AtomicLong emitted = new AtomicLong();
        Flux<DataBuffer> endless = Flux.generate(sink -> {
            emitted.incrementAndGet();
            sink.next(buffers.next(LIMIT));
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(endless));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(emitted.get()).as("배출 상한(상한의 4배)을 넘는 다섯 번째 조각에서 멈춘다").isEqualTo(DISCARD_LIMIT / LIMIT + 1);
        assertThat(buffers.allReleased()).isTrue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("chunked 배출이 시간 상한 안에 끝나지 않으면 그때 413 을 쓰고 끊는다 — 수신 시간 상한(408)까지 기다리지 않는다")
    void stopsDrainingChunkedBodyAtDiscardTimeout() {
        GatewayRequestBodySizeFilter quick = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofSeconds(30), Duration.ofMillis(200));
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.concat(Flux.just(buffer(LIMIT + 1)), Flux.never())));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        quick.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(quick.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("상한 이하의 chunked 본문은 모아서 Content-Length 와 함께 넘긴다 — 바이트가 같고 Transfer-Encoding 은 지운다")
    void passesSmallChunkedBodyWithLength() {
        TrackedBuffers buffers = new TrackedBuffers();
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.just(buffers.next(300), buffers.next(300), buffers.next(300))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        ServerHttpRequest forwarded = routed.get();
        assertThat(forwarded.getHeaders().getContentLength()).isEqualTo(900);
        assertThat(forwarded.getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(bodyOf(forwarded)).isEqualTo("a".repeat(900));
        assertThat(bodyOf(forwarded)).as("다시 구독해도 같은 본문이다").isEqualTo("a".repeat(900));
        assertThat(buffers.allReleased()).as("받은 버퍼는 복사한 뒤 해제한다").isTrue();
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("chunked 본문이 수신 시간 상한 안에 다 오지 않으면 408 이고 하류로 넘기지 않는다 (천천히 보내는 연결)")
    void rejectsSlowChunkedBody() {
        GatewayRequestBodySizeFilter quick = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofMillis(200), Duration.ofSeconds(2));
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
        GatewayRequestBodySizeFilter tight = filter(DataSize.ofBytes(LIMIT), Duration.ofSeconds(5), Duration.ofSeconds(2));
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
    @DisplayName("상한 · 총량 · 수신 시간 · 배출 상한 · 배출 시간이 잘못되면 기동하지 않는다")
    void rejectsInvalidProperties() {
        DataSize discard = DataSize.ofMegabytes(4);
        Duration discardTimeout = Duration.ofSeconds(5);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofBytes(0), DataSize.ofMegabytes(32), Duration.ofSeconds(10), discard, discardTimeout))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(101), DataSize.ofMegabytes(200), Duration.ofSeconds(10),
            DataSize.ofMegabytes(200), discardTimeout))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(1), Duration.ofSeconds(10), discard, discardTimeout))
            .as("총량이 상한보다 작으면 chunked 요청을 하나도 받을 수 없다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ZERO, discard, discardTimeout))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ofSeconds(10),
            DataSize.ofMegabytes(1), discardTimeout))
            .as("배출 상한이 상한보다 작으면 상한을 넘은 본문을 하나도 배출할 수 없다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ofSeconds(10), discard, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ofSeconds(10),
            DataSize.ofMegabytes(101), discardTimeout))
            .as("배출 상한은 100MB 를 넘길 수 없다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ofSeconds(10), discard, Duration.ofSeconds(61)))
            .as("배출 시간은 60초를 넘길 수 없다").isInstanceOf(IllegalArgumentException.class);
        assertThat(new GatewayRequestBodyProperties(DataSize.ofMegabytes(2), DataSize.ofMegabytes(32), Duration.ofSeconds(10),
            DataSize.ofMegabytes(100), Duration.ofSeconds(60)))
            .as("위쪽 상한 값 그대로는 받는다").isNotNull();
    }

    @Test
    @DisplayName("1바이트 청크로 상한까지 와도 본문 그대로 넘긴다 — 조각마다 배열을 두지 않고 하나의 배열에 이어 쓴 뒤 버퍼는 모두 해제한다")
    void passesChunkedBodyOfOneByteChunks() {
        TrackedBuffers buffers = new TrackedBuffers();
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.range(0, LIMIT).map(index -> buffers.next(1))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed.get().getHeaders().getContentLength()).isEqualTo(LIMIT);
        assertThat(bodyOf(routed.get())).isEqualTo("a".repeat(LIMIT));
        assertThat(buffers.allReleased()).isTrue();
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("1바이트 청크로 상한을 한 바이트 넘으면 413 이다")
    void rejectsOneByteChunksJustOverLimit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.range(0, LIMIT + 1).map(index -> buffer(1))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("chunked 수집 배열은 청크 크기와 무관하게 상한을 넘지 않고, 상한을 넘으면 바로 놓는다")
    void chunkedBodyCapacityStaysWithinLimit() {
        int limit = 100_000;
        AtomicBoolean overflowed = new AtomicBoolean();
        GatewayRequestBodySizeFilter.ChunkedBody body = new GatewayRequestBodySizeFilter.ChunkedBody(limit, limit * 2L, () -> overflowed.set(true));

        for (int i = 0; i < limit; i++) {
            assertThat(body.add(buffer(1))).isTrue();
            assertThat(body.capacity()).isLessThanOrEqualTo(limit);
        }

        assertThat(body.tooLarge()).as("상한과 같으면 넘지 않았다").isFalse();
        assertThat(body.length()).isEqualTo(limit);
        assertThat(body.capacity()).isEqualTo(limit);
        assertThat(new String(body.array(), 0, body.length(), StandardCharsets.US_ASCII)).isEqualTo("a".repeat(limit));

        assertThat(body.add(buffer(1))).as("배출 상한 안이라 계속 읽는다").isTrue();
        assertThat(body.tooLarge()).isTrue();
        assertThat(overflowed).as("버퍼 자리를 돌려준다").isTrue();
        assertThat(body.capacity()).as("모은 배열을 놓는다").isZero();
    }

    @Test
    @DisplayName("Content-Length 와 chunked 가 함께 오면 길이를 믿지 않고 센다 — 길이가 작다고 적어도 실제 본문이 상한을 넘으면 413 이다")
    void chunkedWinsOverContentLength() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(10).header(HttpHeaders.TRANSFER_ENCODING, "chunked")
            .body(Flux.just(buffer(LIMIT), buffer(1))));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(filter.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("Expect 값이 여럿이어도 그중 100-continue 가 있으면(대소문자 무시) 본문을 읽지 않는다")
    void detectsExpectContinueAmongSeveralValues() {
        AtomicBoolean subscribed = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT + 1L).header(HttpHeaders.EXPECT, "x-other", "100-CONTINUE")
            .body(watched(subscribed)));

        filter.filter(exchange, chain(new AtomicReference<>())).block(TIMEOUT);

        assertThat(subscribed).as("본문을 읽지 않는다").isFalse();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
    }

    @Test
    @DisplayName("배출 중 읽기가 실패하고 413 쓰기도 실패하면(클라이언트가 끊음) 오류를 올리지 않고 조용히 끝낸다")
    void swallowsWriteFailureAfterDiscardReadFailure() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/carts/me/merge")
            .contentType(MediaType.APPLICATION_JSON).contentLength(LIMIT + 1L).body(Flux.error(new IOException("connection reset"))));
        exchange.getResponse().setWriteHandler(body -> Mono.error(new IOException("channel closed")));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        filter.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    @DisplayName("chunked 가 상한을 넘은 뒤 수신 시간 상한이 배출 시간보다 먼저 끝나면 그때 413 이다 — 408 이 아니고 버퍼 자리도 돌려준다")
    void readTimeoutDuringChunkedDrainIsTooLarge() {
        GatewayRequestBodySizeFilter quick = filter(DataSize.ofBytes(LIMIT * 4L), Duration.ofMillis(200), Duration.ofSeconds(2));
        MockServerWebExchange exchange = MockServerWebExchange.from(chunked(Flux.concat(Flux.just(buffer(LIMIT + 1)), Flux.never())));
        AtomicReference<ServerHttpRequest> routed = new AtomicReference<>();

        quick.filter(exchange, chain(routed)).block(TIMEOUT);

        assertThat(routed).hasNullValue();
        assertRejected(exchange, HttpStatus.CONTENT_TOO_LARGE, "GATEWAY_007");
        assertThat(quick.reservedBytes()).isZero();
    }

    private static GatewayRequestBodySizeFilter filter(DataSize budget, Duration readTimeout, Duration discardTimeout) {
        GatewayRequestBodyProperties properties = new GatewayRequestBodyProperties(DataSize.ofBytes(LIMIT), budget, readTimeout, DataSize.ofBytes(DISCARD_LIMIT), discardTimeout);
        return new GatewayRequestBodySizeFilter(properties, new GatewayErrorResponseWriter(JSON));
    }

    private static MockServerHttpRequest chunked(Flux<DataBuffer> body) {
        return MockServerHttpRequest.post("/api/v1/carts/me/merge").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.TRANSFER_ENCODING, "chunked").body(body);
    }

    /** 구독되면 표시만 하고 끝나는 본문 — 필터가 본문을 읽었는지 본다. */
    private static Flux<DataBuffer> watched(AtomicBoolean subscribed) {
        return Flux.defer(() -> {
            subscribed.set(true);
            return Flux.empty();
        });
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

    /** 참조 카운트가 있는 버퍼(Reactor Netty 가 주는 것과 같다) — 필터가 받은 버퍼를 해제하는지 본다. */
    private static final class TrackedBuffers {

        private final NettyDataBufferFactory factory = new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        private final List<NettyDataBuffer> created = new CopyOnWriteArrayList<>();

        DataBuffer next(int size) {
            byte[] bytes = new byte[size];
            Arrays.fill(bytes, (byte) 'a');
            NettyDataBuffer buffer = factory.wrap(Unpooled.wrappedBuffer(bytes));
            created.add(buffer);
            return buffer;
        }

        boolean allReleased() {
            return !created.isEmpty() && created.stream().allMatch(buffer -> buffer.getNativeBuffer().refCnt() == 0);
        }
    }
}
