package com.commercefoundry.domainlayer.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockObservationPort;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Feign 파이프라인(서비스 토큰 · Jackson 디코딩 · 기본 ErrorDecoder · 서킷)으로 product 품절 임박 커서 조회 흉내 서버를 부른다(#229).
 * 요청 모양 · 토큰 · 필요한 칸만 읽기 · 엄격한 계약 검증 · 실패를 {@code NOTIFICATION_053} 으로 바꾸기 · 계약 위반이 공유 서킷 실패로 세지지 않음을 본다.
 */
@SpringBootTest(properties = {"operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:low-stock-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LowStockObservationClientTest {
    private static final String PATH = "/internal/v1/stocks/low";
    private static final String SENTINEL = "SENTINEL_PRIVATE_PRODUCT_NAME_BODY";
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = start();

    @Autowired LowStockObservationPort source;
    @Autowired CircuitBreakerRegistry circuits;

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext(PATH, exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(STATUS.get(), bytes.length == 0 ? -1 : bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // 클라이언트가 먼저 끊었다.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void url(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.lowStockObservationClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void close() {
        SERVER.stop(0);
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        AUTHORIZATIONS.clear();
        STATUS.set(200);
        BODY.set(page("[" + item("\"11\"") + "," + item("\"15\"") + "]", "true", "\"15\""));
        circuit().reset();
    }

    private CircuitBreaker circuit() {
        return circuits.circuitBreaker("product-service");
    }

    static String item(String skuId) {
        return "{\"skuId\":" + skuId + ",\"productId\":\"9\",\"productName\":\"" + SENTINEL + "\",\"optionValueKey\":\"\",\"availableQuantity\":1,"
            + "\"thresholdQuantity\":3,\"productVisible\":true,\"productSaleStatus\":{\"code\":\"ON_SALE\"},\"skuSaleStatus\":{\"code\":\"ON_SALE\"},"
            + "\"policyUpdatedAt\":\"2026-11-01T00:00:00Z\"}";
    }

    static String page(String contents, String hasNext, String next) {
        return "{\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null},\"dataBody\":{"
            + "\"observedAt\":\"2026-11-28T01:00:00.123456789Z\",\"contents\":" + contents + ",\"hasNext\":" + hasNext
            + (next == null ? "" : ",\"nextAfterSkuId\":" + next) + "}}";
    }

    @Test
    void sendsCursorAndScopedServiceTokenAndReadsOnlySkuIdsAndCursor() {
        var page = source.observe(10, 2);
        assertThat(page.skuIds()).containsExactly(11L, 15L);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextAfterSkuId()).isEqualTo(15L);
        assertThat(page.afterSkuId()).isEqualTo(10L);
        // 나노초는 거부하지 않는다. 마이크로초 내림은 판정 쪽(Scan)이 한다.
        assertThat(page.observedAt()).isEqualTo(Instant.parse("2026-11-28T01:00:00.123456789Z"));
        assertThat(REQUESTS).containsExactly("GET " + PATH + "?afterSkuId=10&size=2");
        var jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
            .decode(AUTHORIZATIONS.getFirst().substring(7));
        assertThat(jwt.getAudience()).containsExactly("product-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("operations-service");
        assertThat(circuit().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void lastPageAcceptsNullOrMissingCursorAndEmptyContents() {
        BODY.set(page("[" + item("\"12\"") + "]", "false", "null"));
        assertThat(source.observe(0, 100).skuIds()).containsExactly(12L);
        BODY.set(page("[]", "false", null));
        var empty = source.observe(0, 100);
        assertThat(empty.skuIds()).isEmpty();
        assertThat(empty.hasNext()).isFalse();
        assertThat(empty.nextAfterSkuId()).isNull();
    }

    static Stream<Arguments> invalidBodies() {
        String two = "[" + item("\"11\"") + "," + item("\"15\"") + "]";
        return Stream.of(
            Arguments.of("success false", page(two, "true", "\"15\"").replace("\"success\":true", "\"success\":false")),
            Arguments.of("success string", page(two, "true", "\"15\"").replace("\"success\":true", "\"success\":\"true\"")),
            Arguments.of("no header", "{\"dataBody\":{}}"),
            Arguments.of("body null", "{\"dataHeader\":{\"success\":true},\"dataBody\":null}"),
            Arguments.of("root array", "[]"),
            Arguments.of("observedAt missing", page(two, "true", "\"15\"").replace("\"observedAt\":\"2026-11-28T01:00:00.123456789Z\",", "")),
            Arguments.of("observedAt bad", page(two, "true", "\"15\"").replace("2026-11-28T01:00:00.123456789Z", "yesterday")),
            Arguments.of("observedAt number", page(two, "true", "\"15\"").replace("\"2026-11-28T01:00:00.123456789Z\"", "1")),
            Arguments.of("contents object", page("{}", "false", "null")),
            Arguments.of("contents over size", page("[" + item("\"11\"") + "," + item("\"15\"") + "," + item("\"16\"") + "]", "false", "null")),
            Arguments.of("skuId number", page("[" + item("11") + "]", "false", "null")),
            Arguments.of("skuId zero", page("[" + item("\"0\"") + "]", "false", "null")),
            Arguments.of("skuId negative", page("[" + item("\"-1\"") + "]", "false", "null")),
            Arguments.of("skuId leading zero", page("[" + item("\"011\"") + "]", "false", "null")),
            Arguments.of("skuId overflow", page("[" + item("\"9223372036854775808\"") + "]", "false", "null")),
            Arguments.of("skuId not after cursor", page("[" + item("\"10\"") + "]", "false", "null")),
            Arguments.of("skuId descending", page("[" + item("\"15\"") + "," + item("\"11\"") + "]", "false", "null")),
            Arguments.of("skuId duplicate", page("[" + item("\"11\"") + "," + item("\"11\"") + "]", "false", "null")),
            Arguments.of("item not object", page("[\"11\"]", "false", "null")),
            Arguments.of("hasNext string", page(two, "\"true\"", "\"15\"")),
            Arguments.of("hasNext missing cursor", page(two, "true", null)),
            Arguments.of("hasNext wrong cursor", page(two, "true", "\"11\"")),
            Arguments.of("hasNext numeric cursor", page(two, "true", "15")),
            Arguments.of("hasNext empty", page("[]", "true", "\"15\"")),
            Arguments.of("end with cursor", page(two, "false", "\"15\"")),
            Arguments.of("sentinel body", "\"" + SENTINEL + "\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void contractViolationIsUnavailableOutsideTheSharedCircuit(String description, String body, CapturedOutput output) {
        BODY.set(body);
        unavailable(10, 2);
        assertThat(REQUESTS).hasSize(1);
        // 계약 위반은 우리 쪽 해석 문제다. 공유 서킷 product-service 의 실패로 세지 않는다.
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void serverErrorsCountAsCircuitFailuresAndClientErrorsDoNotAndNeitherRetries(CapturedOutput output) {
        BODY.set("{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + SENTINEL + "\"},\"dataBody\":null}");
        for (int status : List.of(500, 503)) {
            STATUS.set(status);
            unavailable(0, 100);
        }
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        for (int status : List.of(400, 401, 403)) {
            STATUS.set(status);
            unavailable(0, 100);
        }
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(REQUESTS).hasSize(5);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void openCircuitDoesNotCallProduct() {
        circuit().transitionToOpenState();
        unavailable(0, 100);
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void invalidRequestFailsBeforeRemoteCall() {
        for (var request : List.of(new long[] {-1, 100}, new long[] {0, 0}, new long[] {0, 101})) {
            assertThatThrownBy(() -> source.observe(request[0], (int) request[1])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(REQUESTS).isEmpty();
    }

    private void unavailable(long afterSkuId, int size) {
        assertThatThrownBy(() -> source.observe(afterSkuId, size)).isInstanceOfSatisfying(NotificationException.class, exception -> {
            assertThat(exception.getErrorCode().getCode()).isEqualTo("NOTIFICATION_053");
            assertThat(exception.getCause()).isNull();
        });
    }
}
