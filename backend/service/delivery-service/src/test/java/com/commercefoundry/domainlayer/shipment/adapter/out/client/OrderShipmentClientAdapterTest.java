package com.commercefoundry.domainlayer.shipment.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * order-service 출고 반영 어댑터를 실제 Feign 파이프라인(서비스 토큰 Capability · Jackson 3 인코딩 · 디코딩 · 기본 ErrorDecoder · 서킷 · yml 타임아웃)으로
 * order-service 흉내 서버(JDK {@link HttpServer})에 붙여 본다. 경로 · 요청 본문 모양(아이디 문자열 · ISO 시각) · 서비스 토큰({@code aud=order-service},
 * {@code iss=delivery-service})과, 4xx 봉투의 상태 · 코드 번역({@code order-flow-guide.md} §4 실패 표)을 함께 본다. 값은 모두 테스트 전용이다.
 *
 * <p>대상 주소만 {@code spring.cloud.openfeign.client.config.orderShipmentClient.url} 로 바꾼다 — 서비스 토큰의 {@code aud} 는 주소가 아니라 Feign 대상 이름이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderShipmentClientAdapterTest {

    private static final long ORDER_ID = 4_611_686_018_427_388_001L;
    private static final long MEMBER_ID = 4_611_686_018_427_387_904L;
    private static final long SHIPMENT_ID = 4_611_686_018_427_389_000L;
    private static final Instant SHIPPED_AT = Instant.parse("2026-11-02T09:30:00.123456Z");
    private static final String PATH = "/internal/v1/orders/" + ORDER_ID + "/shipment";
    private static final String SUCCESS_HEADER = "\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null}";

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>("");
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> REQUEST_BODIES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private OrderShipmentCommandPort orderShipmentCommandPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void orderServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.orderShipmentClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/orders/", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                REQUEST_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length == 0 ? -1 : body.length);
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

    /** 실패 응답이 쌓여 서킷이 열리면 뒤 케이스가 원격을 부르지 않는다 — 케이스마다 닫힌 상태로 시작한다. */
    @BeforeEach
    void reset() {
        REQUESTS.clear();
        REQUEST_BODIES.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("");
        circuitBreakerRegistry.circuitBreaker("order-service").reset();
    }

    private static void respond(int status, String body) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(body);
    }

    private static String failure(String resultCode, String message) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"" + message + "\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private OrderShipmentException rejected() {
        try {
            orderShipmentCommandPort.ship(ORDER_ID, SHIPMENT_ID, SHIPPED_AT);
        } catch (OrderShipmentException exception) {
            return exception;
        }
        throw new AssertionError("OrderShipmentException 이 나야 한다");
    }

    @Test
    @DisplayName("200 — POST 경로 · 본문(deliveryId 문자열 · shippedAt ISO 마이크로초)을 싣고 서비스 토큰 하나를 실어, 회원 · 상태 · 범위로 바꾼다 — 모르는 칸은 무시한다")
    void accepted() {
        respond(200, """
            {%s,"dataBody":{"orderId":"%d","memberId":"%d","status":"SHIPPING","countryCode":"KR","futureField":1}}
            """.formatted(SUCCESS_HEADER, ORDER_ID, MEMBER_ID));

        OrderShipmentConfirmation confirmation = orderShipmentCommandPort.ship(ORDER_ID, SHIPMENT_ID, SHIPPED_AT);

        assertThat(confirmation).isEqualTo(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "SHIPPING", ShippingScope.DOMESTIC));
        assertThat(REQUESTS).containsExactly("POST " + PATH);
        JsonNode body = JSON.readTree(REQUEST_BODIES.getFirst());
        assertThat(body.path("deliveryId").isString()).as("deliveryId 는 문자열이다").isTrue();
        assertThat(body.path("deliveryId").asString()).isEqualTo(String.valueOf(SHIPMENT_ID));
        assertThat(body.path("shippedAt").isString()).as("shippedAt 은 ISO 문자열이다").isTrue();
        assertThat(Instant.parse(body.path("shippedAt").asString())).isEqualTo(SHIPPED_AT);
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).singleElement().asString().startsWith("Bearer ");
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("order-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("order-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("delivery-service");
        });
    }

    @Test
    @DisplayName("409 ORDER_020 본문 → 출고할 수 없는 상태(행 삭제), 메시지의 현재 상태 이름을 꺼낸다")
    void notShippable() {
        respond(409, failure("ORDER_020", "현재 주문 상태(취소 요청)에서는 할 수 없는 요청입니다."));

        OrderShipmentException exception = rejected();

        assertThat(exception.getFailure()).isEqualTo(Failure.NOT_SHIPPABLE);
        assertThat(exception.getOrderStatusName()).isEqualTo("취소 요청");
        assertThat(exception.getFailure().discardsPending()).isTrue();
    }

    @Test
    @DisplayName("409 ORDER_025 → 버전 충돌(행 유지) — 같은 409 라도 코드로 나눈다")
    void conflictRetry() {
        respond(409, failure("ORDER_025", "x"));

        OrderShipmentException exception = rejected();

        assertThat(exception.getFailure()).isEqualTo(Failure.CONFLICT_RETRY);
        assertThat(exception.getFailure().discardsPending()).isFalse();
    }

    @Test
    @DisplayName("404 본문 없음 → 결과 모름(행 유지) — 상태만 보고 지우지 않는다")
    void notFoundWithoutBody() {
        respond(404, "");

        assertThat(rejected().getFailure()).isEqualTo(Failure.UNKNOWN);
    }

    @Test
    @DisplayName("404 ORDER_001 본문 → 없는 주문(행 삭제)")
    void notFound() {
        respond(404, failure("ORDER_001", "x"));

        assertThat(rejected().getFailure()).isEqualTo(Failure.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_159", "ORDER_160"})
    @DisplayName("400 시각 범위 코드 → 시각 거절(행 삭제)")
    void invalidTime(String resultCode) {
        respond(400, failure(resultCode, "x"));

        assertThat(rejected().getFailure()).isEqualTo(Failure.INVALID_TIME);
    }

    @Test
    @DisplayName("5xx 는 결과 모름이다")
    void serverError() {
        respond(503, "");

        assertThatThrownBy(() -> orderShipmentCommandPort.ship(ORDER_ID, SHIPMENT_ID, SHIPPED_AT))
            .isInstanceOfSatisfying(OrderShipmentException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
    }

    // 배송 완료 반영 (#18 PR-B2) — 같은 클라이언트 · 서킷 · 번역이다

    private static final Instant DELIVERED_AT = Instant.parse("2026-11-04T02:10:00.654321Z");
    private static final String COMPLETION_PATH = "/internal/v1/orders/" + ORDER_ID + "/delivery-completion";

    private OrderShipmentException completionRejected() {
        try {
            orderShipmentCommandPort.completeDelivery(ORDER_ID, SHIPMENT_ID, DELIVERED_AT);
        } catch (OrderShipmentException exception) {
            return exception;
        }
        throw new AssertionError("OrderShipmentException 이 나야 한다");
    }

    @Test
    @DisplayName("배송 완료 200 — POST delivery-completion 경로 · 본문(deliveryId 문자열 · deliveredAt ISO 마이크로초) · 서비스 토큰을 싣고 주문 상태를 받는다")
    void completionAccepted() {
        respond(200, """
            {%s,"dataBody":{"orderId":"%d","memberId":"%d","status":"DELIVERED","countryCode":"US"}}
            """.formatted(SUCCESS_HEADER, ORDER_ID, MEMBER_ID));

        OrderShipmentConfirmation confirmation = orderShipmentCommandPort.completeDelivery(ORDER_ID, SHIPMENT_ID, DELIVERED_AT);

        assertThat(confirmation).isEqualTo(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "DELIVERED", ShippingScope.INTERNATIONAL));
        assertThat(REQUESTS).containsExactly("POST " + COMPLETION_PATH);
        JsonNode body = JSON.readTree(REQUEST_BODIES.getFirst());
        assertThat(body.path("deliveryId").isString()).isTrue();
        assertThat(body.path("deliveryId").asString()).isEqualTo(String.valueOf(SHIPMENT_ID));
        assertThat(Instant.parse(body.path("deliveredAt").asString())).isEqualTo(DELIVERED_AT);
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("order-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("order-service");
        });
    }

    @Test
    @DisplayName("배송 완료 409 ORDER_020 → 바꿀 수 없는 상태(되돌림), 상태 이름을 꺼낸다")
    void completionNotDeliverable() {
        respond(409, failure("ORDER_020", "현재 주문 상태(취소 요청)에서는 할 수 없는 요청입니다."));

        OrderShipmentException exception = completionRejected();

        assertThat(exception.getFailure()).isEqualTo(Failure.NOT_SHIPPABLE);
        assertThat(exception.getFailure().definitelyNotApplied()).isTrue();
        assertThat(exception.toCompletionException().getMessage()).isEqualTo("취소 요청 상태라 배송 완료로 바꿀 수 없습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_160", "ORDER_161"})
    @DisplayName("배송 완료 400 시각 범위 · 출고 시각보다 앞섬 → 시각 거절(되돌림)")
    void completionInvalidTime(String resultCode) {
        respond(400, failure(resultCode, "x"));

        assertThat(completionRejected().getFailure()).isEqualTo(Failure.INVALID_TIME);
    }

    @Test
    @DisplayName("배송 완료 409 ORDER_025 → 버전 충돌(반영 대기 유지), 5xx → 결과 모름(반영 대기 유지)")
    void completionKeepsPending() {
        respond(409, failure("ORDER_025", "x"));
        OrderShipmentException conflict = completionRejected();
        respond(503, "");
        OrderShipmentException unknown = completionRejected();

        assertThat(conflict.getFailure()).isEqualTo(Failure.CONFLICT_RETRY);
        assertThat(conflict.getFailure().definitelyNotApplied()).isFalse();
        assertThat(unknown.getFailure()).isEqualTo(Failure.UNKNOWN);
    }
}
