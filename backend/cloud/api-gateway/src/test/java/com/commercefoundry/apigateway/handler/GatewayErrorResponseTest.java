package com.commercefoundry.apigateway.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이가 끊은 응답이 실제 서버에서 서비스와 같은 {@code Response} 봉투로 나가는지 확인한다 ({@code api-design-guide.md} §2-1).
 */
@GatewayIntegrationTest
class GatewayErrorResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private HttpClientProperties httpClientProperties;

    @LocalServerPort
    private int port;

    // ── 라우팅 결과 ───────────────────────────────────────────────

    @Test
    @DisplayName("어느 라우트에도 없는 경로는 404 + GATEWAY_001 봉투로 답한다")
    void unknownPathReturnsRouteNotFound() {
        JsonNode body = getExpectingError("/api/v1/unknown-resources", HttpStatus.NOT_FOUND);

        assertFailEnvelope(body, "GATEWAY_001", GatewayErrorCode.ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("라우트는 있지만 살아 있는 인스턴스가 없으면 503 + GATEWAY_002 로 답한다")
    void routedPathWithoutInstanceReturnsServiceUnavailable() {
        JsonNode body = getExpectingError("/api/v1/products", HttpStatus.SERVICE_UNAVAILABLE);

        assertFailEnvelope(body, "GATEWAY_002", GatewayErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("서비스 간 API(/internal/**)는 라우트가 없어 404 + GATEWAY_001 로 막힌다")
    void internalPathIsNotRouted() {
        JsonNode body = getExpectingError("/internal/v1/orders/1", HttpStatus.NOT_FOUND);

        assertFailEnvelope(body, "GATEWAY_001", GatewayErrorCode.ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("응답 · 연결 타임아웃이 yml 대로 게이트웨이 HTTP 클라이언트에 걸린다 (키 오타 회귀 방지)")
    void httpClientTimeoutsAreApplied() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(3000);
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    // ── 요청 가드 (원문 경로 · 메서드) ──────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/products/../../../internal/v1/orders/1",
        "/api/v1/orders/%2e%2e/admin/orders/1",
        "/api/v1/products/..;/..;/..;/internal/v1/orders/1"
    })
    @DisplayName("경로 탐색 변형은 라우팅 전에 400 + GATEWAY_004 로 끊긴다 — 503 이 아니므로 서비스 라우트까지 가지 않았다")
    void traversalIsRejectedBeforeRouting(String rawPath) throws IOException {
        // WebTestClient 는 URI 를 인코딩·정리할 수 있어, 공격자가 보내는 원문을 그대로 소켓에 쓴다
        RawResponse response = sendRaw("GET", rawPath);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertFailEnvelope(response.jsonBody(), "GATEWAY_004", GatewayErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("요청 가드는 CORS 보다 먼저 돈다 — 허용 안 된 origin 의 경로 탐색도 403 이 아니라 400 으로 끊긴다")
    void requestGuardRunsBeforeCors() throws IOException {
        RawResponse response = sendRaw("GET", "/api/v1/orders/../admin/orders/1", HttpHeaders.ORIGIN + ": https://evil.example");

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertFailEnvelope(response.jsonBody(), "GATEWAY_004", GatewayErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("TRACE 같은 허용하지 않는 메서드는 405 + GATEWAY_004 + Allow 헤더로 답한다")
    void disallowedMethodReturnsMethodNotAllowed() {
        byte[] body = webTestClient.method(HttpMethod.TRACE).uri("/api/v1/products")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(Arrays.stream(allow.split(",")).map(String::strip))
                .containsExactlyInAnyOrder("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            .expectBody().returnResult().getResponseBody();

        assertFailEnvelope(JSON.readTree(body), "GATEWAY_004", GatewayErrorCode.BAD_REQUEST);
    }

    private JsonNode getExpectingError(String path, HttpStatus expectedStatus) {
        byte[] body = webTestClient.get().uri(path)
            .exchange()
            .expectStatus().isEqualTo(expectedStatus)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody().returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private static void assertFailEnvelope(JsonNode body, String expectedCode, GatewayErrorCode errorCode) {
        JsonNode header = body.path("dataHeader");

        assertThat(header.path("success").isBoolean()).isTrue();
        assertThat(header.path("success").asBoolean()).isFalse();
        assertThat(header.path("resultCode").asString()).isEqualTo(expectedCode);
        // 예외 메시지가 아니라 고정된 사용자 문구만 나간다
        assertThat(header.path("resultMessage").asString()).isEqualTo(errorCode.getMessage());
        assertThat(header.path("fieldErrors").isNull()).isTrue();
        assertThat(body.has("dataBody")).isTrue();
        assertThat(body.path("dataBody").isNull()).isTrue();
    }

    // ── 원문 HTTP 요청 ────────────────────────────────────────────

    private RawResponse sendRaw(String method, String rawTarget, String... extraHeaders) throws IOException {
        StringBuilder request = new StringBuilder()
            .append(method).append(' ').append(rawTarget).append(" HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Connection: close\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return RawResponse.parse(socket.getInputStream().readAllBytes());
        }
    }

    private record RawResponse(int status, Map<String, String> headers, byte[] body) {

        static RawResponse parse(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int headerEnd = text.indexOf("\r\n\r\n");
            assertThat(headerEnd).as("HTTP 응답 머리를 찾지 못했다").isPositive();

            String[] headLines = text.substring(0, headerEnd).split("\r\n");
            int status = Integer.parseInt(headLines[0].split(" ")[1]);
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < headLines.length; i++) {
                int colon = headLines[i].indexOf(':');
                headers.put(headLines[i].substring(0, colon).strip(), headLines[i].substring(colon + 1).strip());
            }
            // 오류 봉투는 버퍼 하나로 쓰므로 Content-Length 로 나간다. chunked 로 바뀌면 이 헬퍼를 고친다
            assertThat(headers).as("Content-Length 가 없는 응답").containsKey(HttpHeaders.CONTENT_LENGTH);
            int length = Integer.parseInt(headers.get(HttpHeaders.CONTENT_LENGTH));
            byte[] body = Arrays.copyOfRange(bytes, headerEnd + 4, headerEnd + 4 + length);
            return new RawResponse(status, headers, body);
        }

        JsonNode jsonBody() {
            return JSON.readTree(body);
        }
    }
}
