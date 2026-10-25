package com.commercefoundry.apigateway.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.common.exception.RedactedThrowable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Hooks;

/**
 * 하류가 응답 본문을 보내는 도중 연결을 닫을 때 게이트웨이 프로세스 로그를 본다 (#291).
 *
 * <p>실측: {@code Operators.onErrorDropped} 기본 로그는 나지 않는다. reactor-netty {@code HttpServerOperations} 는
 * {@code Error finishing response} 를 ERROR 로 남기고, 그때 흘린 예외의 체크포인트에 원문 경로 · 쿼리가 있었다.
 * 커밋 뒤 오류는 {@link RedactedThrowable} 만 다시 던지므로 그 줄에 경로 · 쿼리 · 원래 메시지가 없다.
 * {@code Hooks.onErrorDropped} 는 이 로거를 바꾸지 못한다.
 */
@GatewayIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayStreamingAbortLogTest {

    private static final String SECRET_SEGMENT = "ORDER-SECRET-MARKER";
    private static final String SECRET_QUERY = "MARKER-QUERY";
    private static final AtomicInteger UPSTREAM_HITS = new AtomicInteger();
    private static final ServerSocket UPSTREAM = startUpstream();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void registerUpstream(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.cloud.discovery.client.simple.instances.product-service[0].uri",
            () -> "http://127.0.0.1:" + UPSTREAM.getLocalPort()
        );
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        UPSTREAM.close();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("본문 도중 하류가 끊으면 프레임워크 ERROR 에 원문 경로 · 쿼리 · onErrorDropped 기본 로그가 없다")
    void frameworkLogsDoNotKeepRawPathWhenUpstreamAborts(CapturedOutput output) {
        Hooks.resetOnErrorDropped();
        try {
            webTestClient.get()
                .uri("/api/v1/products/" + SECRET_SEGMENT + "?paymentKey=" + SECRET_QUERY)
                .exchange()
                .expectBody(String.class)
                .returnResult();
        } catch (RuntimeException exception) {
            assertThat(exception.toString()).doesNotContain(SECRET_SEGMENT, SECRET_QUERY);
        }

        assertThat(UPSTREAM_HITS).hasPositiveValue();
        assertThat(output.getAll())
            .contains("gateway response failed after commit", "path=/api/v1/products/…", "Error finishing response. Closing connection")
            .contains(RedactedThrowable.class.getName() + ": reactor.netty.http.client.PrematureCloseException")
            .doesNotContain(SECRET_SEGMENT, SECRET_QUERY, "Operator called default onErrorDropped");
    }

    private static ServerSocket startUpstream() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread thread = new Thread(() -> accept(server), "gateway-abort-upstream");
            thread.setDaemon(true);
            thread.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 헤더와 청크 하나를 보낸 뒤 마지막 청크 없이 닫는다. 게이트웨이 클라이언트가 응답 도중 끊김으로 본다. */
    private static void accept(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                UPSTREAM_HITS.incrementAndGet();
                readHeaders(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                out.write("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain\r
                    Transfer-Encoding: chunked\r
                    \r
                    5\r
                    hello\r
                    """.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException exception) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void readHeaders(InputStream input) throws IOException {
        byte[] tail = new byte[4];
        int filled = 0;
        while (filled < 8192) {
            int next = input.read();
            if (next < 0) {
                return;
            }
            tail[filled % 4] = (byte) next;
            filled++;
            if (filled >= 4
                && tail[(filled - 4) % 4] == '\r'
                && tail[(filled - 3) % 4] == '\n'
                && tail[(filled - 2) % 4] == '\r'
                && tail[(filled - 1) % 4] == '\n') {
                return;
            }
        }
    }
}
