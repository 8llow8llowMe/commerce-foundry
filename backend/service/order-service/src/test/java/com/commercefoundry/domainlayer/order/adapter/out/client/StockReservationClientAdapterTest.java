package com.commercefoundry.domainlayer.order.adapter.out.client;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Feign 파이프라인(서비스 토큰 · 인코딩 · Jackson 3 · 오류 디코딩)으로 product-service 흉내 서버를 불러 본다 — #10 재고 계약의 요청 모양과
 * 409 {@code resultCode} 분기, 원격 오류 메시지를 싣지 않는지를 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class StockReservationClientAdapterTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private static volatile boolean disconnect;
    private static final long ORDER_ID = 4611686018427387904L;

    @Autowired
    private StockReservationPort stockReservationPort;

    @Autowired
    private StockRestorationPort stockRestorationPort;

    @Autowired
    private OrderStockProcessor processor;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CircuitBreakerRegistry circuits;

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.stockReservationClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.cloud.openfeign.client.config.stockRestorationClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HttpHandler handler = exchange -> {
                PATHS.add(exchange.getRequestURI().getPath());
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                if (disconnect) {
                    exchange.close();
                    return;
                }
                try {
                    Thread.sleep(DELAY_MS.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            };
            server.createContext("/internal/v1/stock-reservations", handler);
            server.createContext("/internal/v1/stock-restorations", handler);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        PATHS.clear();
        BODIES.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set(reservation("RESERVED"));
        DELAY_MS.set(0);
        disconnect = false;
        circuits.circuitBreaker("product-service").reset();
    }

    private static TreeMap<Long, Integer> items() {
        return new TreeMap<>(Map.of(9001L, 2, 9002L, 1));
    }

    private static String reservation(String status) {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"reservationId":"4611686018427388001","orderId":"4611686018427387904","status":"%s","expiresAt":"2099-10-18T09:30:00Z",
               "items":[{"skuId":"9001","quantity":2,"deducted":true},{"skuId":"9002","quantity":1,"deducted":false}],"futureField":1}}
            """.formatted(status);
    }

    private static String failure(String code, String message) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"%s\",\"resultMessage\":\"%s\",\"fieldErrors\":null},\"dataBody\":null}".formatted(code, message);
    }

    @Test
    @DisplayName("선점 — {orderId, items[{skuId, quantity}]} 를 보내고 서비스 토큰(aud=product-service)을 싣고, 응답을 선점 결과로 바꾼다")
    void reserves() {
        RESPONSE_BODY.set(reservation("RESERVED"));

        StockReservationQueryResult reservation = stockReservationPort.reserve(ORDER_ID, items());

        assertThat(reservation).isEqualTo(new StockReservationQueryResult(4611686018427388001L, ORDER_ID, Instant.parse("2099-10-18T09:30:00Z")));
        assertThat(PATHS).containsExactly("/internal/v1/stock-reservations");
        assertThat(BODIES.getFirst()).contains("\"orderId\":4611686018427387904").contains("{\"skuId\":9001,\"quantity\":2}").contains("{\"skuId\":9002,\"quantity\":1}");
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("product-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "409, PRODUCT_021, INSUFFICIENT",
        "409, PRODUCT_022, NOT_PURCHASABLE",
        "409, PRODUCT_023, ITEMS_MISMATCH",
        "409, PRODUCT_026, CONFLICT",
        "400, PRODUCT_122, REJECTED",
        "400, PRODUCT_021, REJECTED",
        "401, PRODUCT_022, REJECTED",
        "404, PRODUCT_003, REJECTED",
        "409, PRODUCT_024, REJECTED",
        "409, PRODUCT_025, REJECTED",
        "401, SECURITY_007, REJECTED",
        "503, X, UNKNOWN"
    })
    @DisplayName("오류는 resultCode 로 사유를 가르고, product-service 의 메시지(SKU 목록)는 예외에 싣지 않는다")
    void failures(int status, String code, Failure expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code, "재고가 부족한 SKU 가 있습니다: [9001]"));

        assertThatThrownBy(() -> stockReservationPort.reserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> {
                assertThat(exception.getFailure()).isEqualTo(expected);
                assertThat(exception.getMessage()).doesNotContain("9001");
            });
    }

    @Test
    @DisplayName("응답의 아이디를 읽을 수 없거나 만료 시각이 없으면 결과를 모르는 실패(UNKNOWN)다 — 주문 아이디 대조는 Processor 가 한다")
    void contractViolation() {
        RESPONSE_BODY.set(reservation("RESERVED").replace("\"reservationId\":\"4611686018427388001\"", "\"reservationId\":\"x\""));
        assertThatThrownBy(() -> stockReservationPort.reserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));

        RESPONSE_BODY.set(reservation("RESERVED").replace("\"expiresAt\":\"2099-10-18T09:30:00Z\",", ""));

        assertThatThrownBy(() -> stockReservationPort.reserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void invalidSuccessIsUnknown(String response) {
        RESPONSE_BODY.set(response);
        assertFailure(Failure.UNKNOWN);
        assertThat(PATHS).hasSize(1);
    }

    static Stream<String> invalidResponses() {
        String valid = reservation("RESERVED");
        return Stream.of("null", "{}", "not-json", "{", valid.replace("true,\"resultCode", "false,\"resultCode"),
            "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
            valid.replace("\"status\":\"RESERVED\"", "\"status\":\"RELEASED\""),
            valid.replace("\"status\":\"RESERVED\"", "\"status\":\"UNKNOWN\""),
            valid.replace("\"items\":", "\"ignoredItems\":"),
            valid.replace("\"items\":[", "\"items\":[],\"ignoredItems\":["),
            valid.replace("\"orderId\":\"4611686018427387904\"", "\"orderId\":\"-1\""),
            valid.replace("\"orderId\":\"4611686018427387904\"", "\"orderId\":\"9223372036854775808\""),
            valid.replace("\"skuId\":\"9001\"", "\"skuId\":\"0\""),
            valid.replace("\"skuId\":\"9001\"", "\"skuId\":\"x\""),
            valid.replace("\"expiresAt\":\"2099-10-18T09:30:00Z\"", "\"expiresAt\":\"invalid\""),
            valid.replace("\"reservationId\":\"4611686018427388001\"", "\"reservationId\":\"0\""),
            valid.replace("\"orderId\":\"4611686018427387904\"", "\"orderId\":\"1\""),
            valid.replace("\"status\":\"RESERVED\"", "\"status\":\"CONFIRMED\""),
            valid.replace("\"items\":[", "\"items\":[null,"),
            valid.replace("\"quantity\":2", "\"quantity\":null"),
            valid.replace("\"quantity\":2", "\"quantity\":0"),
            valid.replace("\"quantity\":2", "\"quantity\":3"),
            valid.replace("\"skuId\":\"9002\"", "\"skuId\":\"9001\""),
            valid.replace("\"skuId\":\"9002\"", "\"skuId\":\"9000\""),
            valid.replace("\"skuId\":\"9002\"", "\"skuId\":\"9003\""),
            valid.replace("\"items\":[{\"skuId\":\"9001\",\"quantity\":2,\"deducted\":true},{\"skuId\":\"9002\",\"quantity\":1,\"deducted\":false}]", "\"items\":null"));
    }

    @Test
    void droppedAcceptedPostIsNotReplayed() {
        disconnect = true;
        assertFailure(Failure.UNKNOWN);
        assertThat(PATHS).hasSize(1);
    }

    @Test
    void timeoutIsNotRetried() {
        DELAY_MS.set(3300);
        assertFailure(Failure.UNKNOWN);
        assertThat(PATHS).hasSize(1);
    }

    @Test
    void circuitOpenSkipsTransport() {
        circuits.circuitBreaker("product-service").transitionToOpenState();
        assertFailure(Failure.UNKNOWN);
        assertThat(PATHS).isEmpty();
    }

    @Test
    void businessRejectionsDoNotCountAsCircuitFailures() {
        RESPONSE_STATUS.set(409);
        RESPONSE_BODY.set(failure("PRODUCT_021", "not public"));
        assertFailure(Failure.INSUFFICIENT);
        assertThat(circuits.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(PATHS).hasSize(1);
    }

    @Test
    void processorRejectsCallerTransactionBeforeHttp() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> processor.reserve(ORDER_ID, domesticQuote(quoteLine(9001, 100, 2)))))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(PATHS).isEmpty();
    }

    @Test
    void expiredResponseIsOrderUnavailable() {
        RESPONSE_BODY.set(reservation("RESERVED").replace("2099-10-18", "2000-10-18"));
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, domesticQuote(quoteLine(9001, 100, 2), quoteLine(9002, 100, 1))))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
        assertThat(PATHS).hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rejectionLogsOnlySanitizedCode(CapturedOutput output) {
        RESPONSE_STATUS.set(409);
        RESPONSE_BODY.set(failure("attacker\\r\\nFORGED_SECRET", "RAW_BODY_SECRET"));
        assertFailure(Failure.REJECTED);
        assertThat(output.getAll()).contains("op=RESERVE").contains("resultCode=<unrecognized>").doesNotContain("FORGED_SECRET", "RAW_BODY_SECRET");
    }

    // --- 확정 · 재선점 (#308) ---

    private static final long RESERVATION_ID = 4611686018427388001L;

    @Test
    @DisplayName("확정 — POST /{reservationId}/confirmation 에 본문 없이 서비스 토큰(aud=product-service)을 싣고, CONFIRMED 응답을 확정 결과로 바꾼다")
    void confirms() {
        RESPONSE_BODY.set(reservation("CONFIRMED"));

        assertThat(stockReservationPort.confirm(RESERVATION_ID)).isEqualTo(new StockConfirmationQueryResult(RESERVATION_ID, ORDER_ID));

        assertThat(PATHS).containsExactly("/internal/v1/stock-reservations/" + RESERVATION_ID + "/confirmation");
        assertThat(BODIES.getFirst()).isEmpty();
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("product-service");
        });
    }

    @ParameterizedTest(name = "확정 {0} {1} → {2}")
    @CsvSource({
        "409, PRODUCT_025, RELEASED",
        "404, PRODUCT_003, NOT_FOUND",
        "409, PRODUCT_026, CONFLICT",
        "409, PRODUCT_024, REJECTED",
        "409, PRODUCT_021, REJECTED",
        "409, PRODUCT_003, REJECTED",
        "404, PRODUCT_025, REJECTED",
        "401, SECURITY_007, REJECTED",
        "500, X, UNKNOWN",
        "503, X, UNKNOWN"
    })
    @DisplayName("확정 오류 — 025 해제 · 003 없음은 재선점 신호, 026 은 일시 충돌, 짝이 맞지 않는 코드는 모르는 거절, 5xx 는 결과를 모르는 실패다")
    void confirmationFailures(int status, String code, Failure expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code, "이미 해제된 재고 선점입니다"));

        assertThatThrownBy(() -> stockReservationPort.confirm(RESERVATION_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(expected));
        assertThat(PATHS).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"RESERVED", "RELEASED"})
    @DisplayName("확정 응답이 CONFIRMED 가 아니거나 다른 선점이면 결과를 모르는 실패(UNKNOWN)다 — 확정은 멱등이라 다시 부르면 된다")
    void confirmationContractViolation(String status) {
        RESPONSE_BODY.set(reservation(status));
        assertThatThrownBy(() -> stockReservationPort.confirm(RESERVATION_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));

        RESPONSE_BODY.set(reservation("CONFIRMED"));
        assertThatThrownBy(() -> stockReservationPort.confirm(RESERVATION_ID + 1))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    @Test
    @DisplayName("확정 타임아웃 · 서킷 오픈은 UNKNOWN 이다 — 서킷이 열려 있으면 부르지 않는다")
    void confirmationTimeoutAndCircuit() {
        DELAY_MS.set(3300);
        assertThatThrownBy(() -> stockReservationPort.confirm(RESERVATION_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
        DELAY_MS.set(0);
        PATHS.clear();

        circuits.circuitBreaker("product-service").transitionToOpenState();
        assertThatThrownBy(() -> stockReservationPort.confirm(RESERVATION_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
        assertThat(PATHS).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"RESERVED", "CONFIRMED"})
    @DisplayName("재선점 — 같은 선점 요청을 보내고, 같은 주문의 이미 확정된 선점(CONFIRMED)도 받는다. 지난 만료 시각도 그대로 받는다")
    void reReserveAcceptsConfirmed(String status) {
        RESPONSE_BODY.set(reservation(status).replace("2099-10-18", "2000-10-18"));

        assertThat(stockReservationPort.reReserve(ORDER_ID, items()).reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(PATHS).containsExactly("/internal/v1/stock-reservations");
        assertThat(BODIES.getFirst()).contains("\"orderId\":4611686018427387904");

        RESPONSE_BODY.set(reservation("RELEASED"));
        assertThatThrownBy(() -> stockReservationPort.reReserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    // --- 해제 프로브 · 결제 후 복원 (#308 PR-C1) ---

    private static final long CLAIM_ID = 4611686018427388201L;

    private static String restoration(long claimId) {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"restorationId":"4611686018427388101","claimId":"%d","reservationId":"4611686018427388001","reason":"CANCELLATION",
               "items":[{"skuId":"9001","quantity":2,"restored":true},{"skuId":"9002","quantity":1,"restored":false}],"futureField":1}}
            """.formatted(claimId);
    }

    @Test
    @DisplayName("해제 — POST /{reservationId}/release 에 본문 없이 서비스 토큰(aud=product-service)을 싣고, RELEASED 응답이면 정상 반환이다")
    void releases() {
        RESPONSE_BODY.set(reservation("RELEASED"));

        stockReservationPort.release(RESERVATION_ID, ORDER_ID);

        assertThat(PATHS).containsExactly("/internal/v1/stock-reservations/" + RESERVATION_ID + "/release");
        assertThat(BODIES.getFirst()).isEmpty();
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("product-service");
        });
    }

    @ParameterizedTest(name = "해제 {0} {1} → {2}")
    @CsvSource({
        "409, PRODUCT_024, CONFIRMED",
        "404, PRODUCT_003, NOT_FOUND",
        "409, PRODUCT_026, CONFLICT",
        "409, PRODUCT_031, CONFLICT",
        "409, PRODUCT_025, REJECTED",
        "404, PRODUCT_024, REJECTED",
        "401, SECURITY_007, REJECTED",
        "503, X, UNKNOWN"
    })
    @DisplayName("해제 오류 — 024 확정은 프로브의 확정 증거, 003 은 후보 아님, 026 · 031 은 일시 충돌, 짝이 맞지 않는 코드는 모르는 거절이다")
    void releaseFailures(int status, String code, Failure expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code, "이미 확정된 재고 선점입니다"));

        assertThatThrownBy(() -> stockReservationPort.release(RESERVATION_ID, ORDER_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(expected));
        assertThat(PATHS).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"RESERVED", "CONFIRMED"})
    @DisplayName("해제 200 이 RELEASED 가 아니거나 다른 선점이면 결과를 모르는 실패(UNKNOWN)다")
    void releaseContractViolation(String status) {
        RESPONSE_BODY.set(reservation(status));
        assertThatThrownBy(() -> stockReservationPort.release(RESERVATION_ID, ORDER_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));

        RESPONSE_BODY.set(reservation("RELEASED"));
        assertThatThrownBy(() -> stockReservationPort.release(RESERVATION_ID + 1, ORDER_ID))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));

        // 200 의 주문 아이디가 다르면 계약 위반이다 — 확정의 주문 대조와 대칭
        assertThatThrownBy(() -> stockReservationPort.release(RESERVATION_ID, ORDER_ID + 1))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    @Test
    @DisplayName("복원 — {claimId, reservationId, reason: CANCELLATION, items} 를 서비스 토큰과 보내고, 같은 클레임 · 선점 · 항목 응답이면 정상 반환이다")
    void restores() {
        RESPONSE_BODY.set(restoration(CLAIM_ID));

        stockRestorationPort.restoreCancellation(CLAIM_ID, RESERVATION_ID, items());

        assertThat(PATHS).containsExactly("/internal/v1/stock-restorations");
        assertThat(BODIES.getFirst()).contains("\"claimId\":" + CLAIM_ID).contains("\"reservationId\":" + RESERVATION_ID)
            .contains("\"reason\":\"CANCELLATION\"").contains("{\"skuId\":9001,\"quantity\":2}").contains("{\"skuId\":9002,\"quantity\":1}");
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("product-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @ParameterizedTest(name = "복원 {0} {1} → {2}")
    @CsvSource({
        "409, PRODUCT_027, NOT_CONFIRMED",
        "409, PRODUCT_028, QUANTITY_EXCEEDED",
        "409, PRODUCT_029, RESTORATION_MISMATCH",
        "404, PRODUCT_003, NOT_FOUND",
        "409, PRODUCT_026, CONFLICT",
        "409, PRODUCT_030, CONFLICT",
        "409, PRODUCT_031, CONFLICT",
        "409, PRODUCT_024, REJECTED",
        "400, PRODUCT_101, REJECTED",
        "500, X, UNKNOWN",
        "503, X, UNKNOWN"
    })
    @DisplayName("복원 오류 — 027 · 029 종결, 028 이미 복원, 003 선점 없음, 026 · 030 · 031 일시 충돌, 짝이 맞지 않는 코드는 모르는 거절, 5xx 는 결과를 모르는 실패다")
    void restorationFailures(int status, String code, Failure expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code, "복원 가능 수량을 넘은 SKU 가 있습니다: [9001]"));

        assertThatThrownBy(() -> stockRestorationPort.restoreCancellation(CLAIM_ID, RESERVATION_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> {
                assertThat(exception.getFailure()).isEqualTo(expected);
                assertThat(exception.getMessage()).doesNotContain("9001");
            });
        assertThat(PATHS).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("invalidRestorations")
    @DisplayName("복원 200 이 다른 클레임 · 선점 · 사유 · 항목이거나 읽을 수 없으면 결과를 모르는 실패(UNKNOWN)다 — 같은 claimId 로 다시 부르면 된다")
    void restorationContractViolation(String response) {
        RESPONSE_BODY.set(response);
        assertThatThrownBy(() -> stockRestorationPort.restoreCancellation(CLAIM_ID, RESERVATION_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
        assertThat(PATHS).hasSize(1);
    }

    static Stream<String> invalidRestorations() {
        String valid = restoration(CLAIM_ID);
        return Stream.of("{}", "not-json", restoration(CLAIM_ID + 1),
            valid.replace("\"reservationId\":\"4611686018427388001\"", "\"reservationId\":\"4611686018427388002\""),
            valid.replace("\"reason\":\"CANCELLATION\"", "\"reason\":\"RETURN\""),
            valid.replace("\"quantity\":2", "\"quantity\":1"),
            valid.replace("\"skuId\":\"9002\"", "\"skuId\":\"9001\""),
            valid.replace("\"restorationId\":\"4611686018427388101\"", "\"restorationId\":\"0\""));
    }

    @Test
    @DisplayName("복원 타임아웃은 다시 보내지 않고 UNKNOWN 이다 — 재시도는 이벤트 재전달이 같은 claimId 로 한다")
    void restorationTimeoutIsNotRetried() {
        DELAY_MS.set(3300);
        assertThatThrownBy(() -> stockRestorationPort.restoreCancellation(CLAIM_ID, RESERVATION_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
        assertThat(PATHS).hasSize(1);
    }

    private void assertFailure(Failure expected) {
        assertThatThrownBy(() -> stockReservationPort.reserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(expected));
    }
}
