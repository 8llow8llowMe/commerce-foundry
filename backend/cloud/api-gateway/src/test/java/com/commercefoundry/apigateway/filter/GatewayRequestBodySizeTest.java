package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

/**
 * 본문 크기 상한을 실제 서버(Reactor Netty)로 본다 — {@code Content-Length} 요청과 chunked 요청 둘 다 하류에 닿지 않고 413 이어야 한다 ({@code security-guide.md} §7-4).
 * 하류는 받은 경로 · 본문 길이를 기록하는 가짜 서비스다. 공개 경로(상품)로 보내 인증과 무관하게 본다.
 *
 * <p>배출 상한 안의 거절은 반복해서 본다(#145) — 본문을 비우지 않고 닫으면 클라이언트가 413 대신 연결 리셋을 보는 일이 타이밍에 따라 갈려
 * 한 번 통과로는 증거가 되지 않는다. 같은 컨텍스트에서 20번 보내면 고치기 전에는 로컬(Windows)에서도 실패했다.
 */
@GatewayIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayRequestBodySizeTest {

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");
    private static final String PATH = "/api/v1/products";
    /** 기본 상한 2MB 보다 크고 기본 배출 상한 4MB 이하다 — 게이트웨이가 본문을 비운 뒤 413 을 쓰는 쪽. */
    private static final int TOO_LARGE = 2 * 1024 * 1024 + 1;
    /** 기본 배출 상한 4MB 보다 크다 — 본문을 비우지 않고 끊는 쪽. */
    private static final int BEYOND_DISCARD = 8 * 1024 * 1024;
    private static final Queue<Integer> UPSTREAM_BODY_LENGTHS = new ConcurrentLinkedQueue<>();
    /** 하류가 받은 {@code Content-Length} · {@code Transfer-Encoding} 헤더 (없으면 none). */
    private static final Queue<String> UPSTREAM_FRAMING = new ConcurrentLinkedQueue<>();
    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

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

    @RepeatedTest(20)
    @DisplayName("Content-Length 가 상한을 넘는 요청은 413 + GATEWAY_007 이고 하류에 닿지 않는다")
    void largeContentLengthIsRejected() {
        webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(new byte[TOO_LARGE])
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_007");

        assertThat(UPSTREAM_BODY_LENGTHS).as("하류에 닿지 않는다").isEmpty();
    }

    @RepeatedTest(20)
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
    @DisplayName("본문을 끝까지 쓰고 나서 응답을 읽는 클라이언트도 413 을 읽는다 — 게이트웨이가 본문을 비운 뒤 정상 종료(FIN)로 닫는다")
    void blockingClientReadsRejectionAfterWritingWholeBody() throws IOException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(requestHead(TOO_LARGE, false));
            byte[] chunk = new byte[64 * 1024];
            for (int written = 0; written < TOO_LARGE; written += chunk.length) {
                out.write(chunk, 0, Math.min(chunk.length, TOO_LARGE - written));
            }
            out.flush();

            String response = readUntilClose(socket.getInputStream());

            assertThat(response).startsWith("HTTP/1.1 413").contains("GATEWAY_007");
        }
        assertThat(UPSTREAM_BODY_LENGTHS).as("하류에 닿지 않는다").isEmpty();
    }

    @Test
    @DisplayName("Expect: 100-continue 로 상한을 넘으면 100 을 보내지 않고 본문이 오기 전에 413 으로 끊는다")
    void expectContinueIsRejectedBeforeBody() throws IOException {
        try (Socket socket = connect()) {
            socket.getOutputStream().write(requestHead(TOO_LARGE, true));
            socket.getOutputStream().flush();

            String response = readUntilClose(socket.getInputStream());

            assertThat(response).startsWith("HTTP/1.1 413").doesNotContain("100 Continue").contains("GATEWAY_007");
        }
        assertThat(UPSTREAM_BODY_LENGTHS).as("하류에 닿지 않는다").isEmpty();
    }

    /**
     * 배출 상한을 넘는 본문은 비우지 않고 바로 끊는다 — 남용 쪽이라 클라이언트가 413 을 읽을지 연결 종료를 볼지는 타이밍에 달렸다({@code security-guide.md} §7-4 의 계약).
     * 그래서 이 테스트만 두 결과를 다 받는다. 지키는 것은 "하류에 닿지 않는다" 다.
     */
    @Test
    @DisplayName("배출 상한을 넘는 Content-Length 요청은 본문을 비우지 않고 끊는다 — 413 이거나 연결 종료이고 하류에 닿지 않는다")
    void contentLengthBeyondDiscardLimitIsCut() {
        try {
            webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(new byte[BEYOND_DISCARD])
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.dataHeader.resultCode").isEqualTo("GATEWAY_007");
        } catch (WebClientRequestException connectionClosed) {
            assertThat(connectionClosed).as("응답 대신 연결 종료").hasMessageContaining("Connection");
        }

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

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
        socket.setSoTimeout(30_000);
        return socket;
    }

    private static byte[] requestHead(int contentLength, boolean expectContinue) {
        String head = "POST " + PATH + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + contentLength + "\r\n"
            + (expectContinue ? "Expect: 100-continue\r\n" : "") + "\r\n";
        return head.getBytes(StandardCharsets.US_ASCII);
    }

    /** 서버가 닫을 때까지 읽는다 — 정상 종료(FIN)가 아니라 리셋이면 여기서 예외가 난다. */
    private static String readUntilClose(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        in.transferTo(received);
        return received.toString(StandardCharsets.UTF_8);
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
