package com.commercefoundry.domainlayer.order.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
    private static final long ORDER_ID = 4611686018427387904L;

    @Autowired
    private StockReservationPort stockReservationPort;

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.stockReservationClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/stock-reservations", exchange -> {
                PATHS.add(exchange.getRequestURI().getPath());
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
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
    }

    private static TreeMap<Long, Integer> items() {
        return new TreeMap<>(Map.of(9001L, 2, 9002L, 1));
    }

    private static String reservation(String status) {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"reservationId":"4611686018427388001","orderId":"4611686018427387904","status":"%s","expiresAt":"2026-10-18T09:30:00Z",
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

        assertThat(reservation).isEqualTo(new StockReservationQueryResult(4611686018427388001L, ORDER_ID, Instant.parse("2026-10-18T09:30:00Z")));
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

        RESPONSE_BODY.set(reservation("RESERVED").replace("\"expiresAt\":\"2026-10-18T09:30:00Z\",", ""));

        assertThatThrownBy(() -> stockReservationPort.reserve(ORDER_ID, items()))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    @Test
    @DisplayName("해제 — 선점 아이디 경로로 보낸다. 이미 확정된 선점(024)은 ALREADY_CONFIRMED")
    void releases() {
        RESPONSE_BODY.set(reservation("RELEASED"));
        assertThatCode(() -> stockReservationPort.release(4611686018427388001L)).doesNotThrowAnyException();
        assertThat(PATHS).containsExactly("/internal/v1/stock-reservations/4611686018427388001/release");

        RESPONSE_STATUS.set(409);
        RESPONSE_BODY.set(failure("PRODUCT_024", "이미 확정된 재고 선점입니다."));
        assertThatThrownBy(() -> stockReservationPort.release(4611686018427388001L))
            .isInstanceOfSatisfying(StockReservationException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.ALREADY_CONFIRMED));
    }
}
