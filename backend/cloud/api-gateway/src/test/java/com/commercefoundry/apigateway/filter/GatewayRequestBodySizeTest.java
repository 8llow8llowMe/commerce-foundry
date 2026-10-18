package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

/**
 * 본문 크기 상한을 실제 서버(Reactor Netty)로 본다 — {@code Content-Length} 요청과 chunked 요청 둘 다 하류에 닿지 않고 413 이어야 한다 ({@code security-guide.md} §7-4).
 * 하류는 받은 경로 · 본문 길이를 기록하는 가짜 서비스다. 공개 경로(상품)로 보내 인증과 무관하게 본다.
 */
@GatewayIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayRequestBodySizeTest {

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");
    private static final String PATH = "/api/v1/products";
    /** 기본 상한 2MB 보다 크다. */
    private static final int TOO_LARGE = 2 * 1024 * 1024 + 1;
    private static final Queue<Integer> UPSTREAM_BODY_LENGTHS = new ConcurrentLinkedQueue<>();
    /** 하류가 받은 {@code Content-Length} · {@code Transfer-Encoding} 헤더 (없으면 none). */
    private static final Queue<String> UPSTREAM_FRAMING = new ConcurrentLinkedQueue<>();
    private static final HttpServer UPSTREAM = startUpstream();

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
    void reset() {
        UPSTREAM_BODY_LENGTHS.clear();
        UPSTREAM_FRAMING.clear();
    }

    @Test
    @DisplayName("Content-Length 가 상한을 넘는 요청은 413 + GATEWAY_007 이고 하류에 닿지 않는다")
    void largeContentLengthIsRejected() {
        webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(new byte[TOO_LARGE])
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_007");

        assertThat(UPSTREAM_BODY_LENGTHS).as("하류에 닿지 않는다").isEmpty();
    }

    @Test
    @DisplayName("chunked 로 상한을 넘는 요청도 413 + GATEWAY_007 이고 하류에 한 바이트도 닿지 않는다")
    void largeChunkedBodyIsRejected() {
        webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromDataBuffers(chunks(64 * 1024, TOO_LARGE)))
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_007");

        assertThat(UPSTREAM_BODY_LENGTHS).as("하류에 닿지 않는다").isEmpty();
    }

    @Test
    @DisplayName("상한 이하의 chunked 요청은 본문이 그대로, Content-Length 로 바뀌어 하류에 간다")
    void smallChunkedBodyIsForwardedIntact() {
        int size = 1024 * 1024;

        webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromDataBuffers(chunks(64 * 1024, size)))
            .exchange()
            .expectStatus().isOk();

        assertThat(UPSTREAM_BODY_LENGTHS).containsExactly(size);
        assertThat(UPSTREAM_FRAMING).containsExactly("content-length=" + size + ", transfer-encoding=none");
    }

    @Test
    @DisplayName("상한 이하의 Content-Length 요청은 그대로 하류에 간다")
    void smallContentLengthIsForwarded() {
        webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"윤금 드레스\"}")
            .exchange()
            .expectStatus().isOk();

        assertThat(UPSTREAM_BODY_LENGTHS).containsExactly("{\"name\":\"윤금 드레스\"}".getBytes(StandardCharsets.UTF_8).length);
    }

    /** 길이를 알리지 않는 본문 — 클라이언트가 chunked 로 보낸다. */
    private static Flux<DataBuffer> chunks(int chunkSize, int total) {
        return Flux.range(0, (total + chunkSize - 1) / chunkSize)
            .map(index -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[Math.min(chunkSize, total - index * chunkSize)]));
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_BODY_LENGTHS.add(exchange.getRequestBody().readAllBytes().length);
                String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
                String transferEncoding = exchange.getRequestHeaders().getFirst("Transfer-Encoding");
                UPSTREAM_FRAMING.add("content-length=" + (contentLength == null ? "none" : contentLength)
                    + ", transfer-encoding=" + (transferEncoding == null ? "none" : transferEncoding));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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
