package com.commercefoundry.domainlayer.payment.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.OrderPaymentResultClient;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException.Failure;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 Feign · 서비스 JWT · 서킷을 통과하는 조회 계약 테스트. SQL · PG · 이벤트 상태 변경 경로는 없다. */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderPaymentResultClientAdapterTest {

    private static final long PAYMENT_ID = 4_611_686_018_427_388_001L;
    private static final long ORDER_ID = PAYMENT_ID + 1;
    private static final long MEMBER_ID = PAYMENT_ID + 2;
    private static final long RESERVATION_ID = PAYMENT_ID + 3;
    private static final Instant APPROVED_AT = Instant.parse("2026-11-01T03:30:00.123456Z");
    private static final String PATH = "/internal/v1/order-payments/" + PAYMENT_ID;
    private static final String CANARY = "private-body-payment-key-canary";
    private static final int READ_TIMEOUT = 300;
    private static final WireMockServer ORDER = startedServer();

    @Autowired
    private OrderPaymentResultQueryPort port;

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.openfeign.client.config.orderPaymentResultClient.url", ORDER::baseUrl);
        properties.add("spring.cloud.openfeign.client.config.orderPaymentResultClient.read-timeout", () -> READ_TIMEOUT);
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:order-payment-result-client;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment");
        properties.add("toss.secret-key", () -> "test_sk_dummyForContextTestsOnly000");
        properties.add("toss.base-url", () -> "http://127.0.0.1:9");
    }

    @AfterAll
    static void stopServer() {
        ORDER.stop();
    }

    @BeforeEach
    void reset() {
        ORDER.resetAll();
        circuit().reset();
    }

    private static WireMockServer startedServer() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    private CircuitBreaker circuit() {
        return registry.circuitBreaker("order-service");
    }

    private static String quoted(Object value) {
        return "\"" + value + "\"";
    }

    private static String body(Result result, String currency, String approvedAt, String reservation) {
        return """
            {"paymentId":"%s","orderId":"%s","memberId":"%s","result":"%s",
             "amount":{"amount":129000,"currency":"%s"},"approvedAt":%s,"stockReservationId":%s,"futureField":true}
            """.formatted(PAYMENT_ID, ORDER_ID, MEMBER_ID, result, currency, approvedAt, reservation);
    }

    private static String validBody() {
        return body(Result.PAID, "KRW", quoted(APPROVED_AT), quoted(RESERVATION_ID));
    }

    private static String success(String body) {
        return "{\"dataHeader\":{\"success\":true},\"dataBody\":" + body + "}";
    }

    private static String failure(String code) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + code
            + "\",\"resultMessage\":\"" + CANARY + "\"},\"dataBody\":null}";
    }

    private static void stub(int status, String response) {
        ORDER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json").withBody(response)));
    }

    private void assertFailure(Failure expected) {
        assertThatThrownBy(() -> port.query(PAYMENT_ID))
            .isInstanceOfSatisfying(OrderPaymentResultQueryException.class, exception -> {
                assertThat(exception.getFailure()).isEqualTo(expected);
                assertThat(exception.getCause()).isNull();
                assertThat(exception.getMessage()).isEqualTo("Order payment result query failed: " + expected);
                assertThat(exception.getSuppressed()).isEmpty();
            });
    }

    private static void assertOneCall() {
        ORDER.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void bindingAndSingleServiceToken() {
        stub(200, success(validBody()));

        assertThat(port.query(PAYMENT_ID)).isEqualTo(new OrderPaymentResultQueryResult(
            PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID, Money.of(129000, CurrencyCode.KRW), APPROVED_AT, RESERVATION_ID));

        assertOneCall();
        assertThat(OrderPaymentResultClient.class.getAnnotation(FeignClient.class).contextId()).isEqualTo("orderPaymentResultClient");
        assertThat(ORDER.findAll(getRequestedFor(urlPathEqualTo(PATH)))).singleElement().satisfies(request -> {
            assertThat(request.getQueryParams()).isEmpty();
            List<String> headers = request.getHeaders().getHeader("Authorization").values();
            assertThat(headers).singleElement().asString().startsWith("Bearer ");
            Jwt jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties("order-service"), Clock.systemUTC())
                .decode(headers.getFirst().substring("Bearer ".length()));
            assertThat(jwt.getClaimAsString("iss")).isEqualTo("payment-service");
            assertThat(jwt.getAudience()).containsExactly("order-service");
            // 서비스 토큰은 사용자 subject · 권한 scopes 를 싣지 않는다.
            assertThat(jwt.getSubject()).isNull();
            assertThat(jwt.getClaims()).doesNotContainKeys("scope", "scopes", "scp");
        });
    }

    @ParameterizedTest
    @EnumSource(Result.class)
    void allResultsPreserveStoredSnapshot(Result result) {
        String reservation = result == Result.PAID || result == Result.REJECTED_ORDER_CANCELED ? quoted(RESERVATION_ID) : "null";
        stub(200, success(body(result, "USD", quoted(APPROVED_AT), reservation)));

        OrderPaymentResultQueryResult response = port.query(PAYMENT_ID);

        assertThat(response.result()).isEqualTo(result);
        assertThat(response.amount()).isEqualTo(Money.of(129000, CurrencyCode.USD));
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(response.stockReservationId()).isEqualTo(reservation.equals("null") ? null : RESERVATION_ID);
        assertOneCall();
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
    void rejectionAllowsNullApprovalAndReservation(Result result) {
        stub(200, success(body(result, "KRW", "null", "null")));

        OrderPaymentResultQueryResult response = port.query(PAYMENT_ID);

        assertThat(response.result()).isEqualTo(result);
        assertThat(response.approvedAt()).isNull();
        assertThat(response.stockReservationId()).isNull();
    }

    static Stream<Arguments> httpFailures() {
        return Stream.of(
            Arguments.of(404, "ORDER_028", Failure.NOT_READY),
            Arguments.of(404, "ORDER_001", Failure.REJECTED),
            Arguments.of(404, "COMMON_001", Failure.REJECTED),
            Arguments.of(400, "ORDER_028", Failure.REJECTED),
            Arguments.of(401, "SECURITY_007", Failure.UNAUTHORIZED),
            Arguments.of(403, "SECURITY_001", Failure.UNAUTHORIZED),
            Arguments.of(409, "ORDER_020", Failure.REJECTED),
            Arguments.of(429, "COMMON_000", Failure.REJECTED),
            Arguments.of(500, "COMMON_000", Failure.UNAVAILABLE),
            Arguments.of(503, "COMMON_000", Failure.UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("httpFailures")
    void httpStatusAndCodeAreClassifiedOnce(int status, String code, Failure expected, CapturedOutput output) {
        stub(status, failure(code));

        assertFailure(expected);

        assertOneCall();
        assertThat(output.getAll()).doesNotContain(CANARY);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(status >= 500 ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void retryAfterDoesNotRetryOrChangeClassification(int status) {
        ORDER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json").withHeader("Retry-After", "1").withBody(failure("ORDER_028"))));

        for (int attempt = 0; attempt < 12; attempt++) {
            assertFailure(status == 404 ? Failure.NOT_READY : Failure.UNAUTHORIZED);
        }

        ORDER.verify(12, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void malformedRejectionAndUnsafeCodeAreRedacted(CapturedOutput output) {
        stub(404, CANARY);
        assertFailure(Failure.REJECTED);
        stub(400, failure(CANARY + "\\r\\nforged"));
        assertFailure(Failure.REJECTED);

        assertThat(output.getAll()).contains("resultCode=null", "resultCode=<unrecognized>").doesNotContain(CANARY, "forged");
    }

    @Test
    void timeoutIsUnavailableWithoutRetry() {
        String timeoutPath = "/internal/v1/order-payments/" + (PAYMENT_ID + 10);
        ORDER.stubFor(get(urlPathEqualTo(timeoutPath)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json").withBody(success(validBody())).withFixedDelay(READ_TIMEOUT * 3)));

        assertThatThrownBy(() -> port.query(PAYMENT_ID + 10)).isInstanceOfSatisfying(OrderPaymentResultQueryException.class,
            exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNAVAILABLE));

        ORDER.verify(1, getRequestedFor(urlPathEqualTo(timeoutPath)));
    }

    @Test
    void connectionResetIsOneUnavailableAdapterCall() {
        ORDER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertFailure(Failure.UNAVAILABLE);

        // JDK HTTP 전송 계층은 안전한 GET 의 연결 reset 을 한 번 재접속할 수 있다. Feign 재시도와는 별개다.
        assertThat(ORDER.findAll(getRequestedFor(urlPathEqualTo(PATH)))).hasSizeBetween(1, 2);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void openCircuitMakesNoCall() {
        circuit().transitionToOpenState();

        assertFailure(Failure.UNAVAILABLE);

        ORDER.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidInputMakesNoCall(long paymentId) {
        assertThatThrownBy(() -> port.query(paymentId)).isInstanceOfSatisfying(OrderPaymentResultQueryException.class,
            exception -> assertThat(exception.getFailure()).isEqualTo(Failure.CONTRACT_VIOLATION));

        assertThat(ORDER.getAllServeEvents()).isEmpty();
    }

    static Stream<String> invalidBodies() {
        String valid = validBody();
        Stream.Builder<String> cases = Stream.builder();
        for (String field : List.of("paymentId", "orderId", "memberId", "stockReservationId")) {
            long value = switch (field) {
                case "paymentId" -> PAYMENT_ID;
                case "orderId" -> ORDER_ID;
                case "memberId" -> MEMBER_ID;
                default -> RESERVATION_ID;
            };
            String keyValue = "\"" + field + "\":\"" + value + "\"";
            for (String invalid : List.of("null", "\"0\"", "\"-1\"", "\"01\"", "\"+1\"", "\" 1\"", "\"1 \"",
                "\"1.0\"", "\"1e3\"", "\"9223372036854775808\"", quoted(CANARY), Long.toString(value))) {
                cases.add(valid.replace(keyValue, "\"" + field + "\":" + invalid));
            }
        }
        cases.add(valid.replace(quoted(PAYMENT_ID), quoted(PAYMENT_ID + 99)));
        for (String invalid : List.of("0", "-1", "null", "1.5", "\"129000\"", "9223372036854775808")) {
            cases.add(valid.replace("\"amount\":129000", "\"amount\":" + invalid));
        }
        cases.add(valid.replace("\"currency\":\"KRW\"", "\"currency\":null"));
        cases.add(valid.replace("\"KRW\"", "\"XYZ\""));
        cases.add(valid.replace("\"amount\":{\"amount\":129000,\"currency\":\"KRW\"}", "\"amount\":null"));
        cases.add(valid.replace("\"PAID\"", quoted(CANARY)));
        cases.add(valid.replace("\"result\":\"PAID\"", "\"result\":null"));
        cases.add(valid.replace(quoted(APPROVED_AT), "null"));
        cases.add(valid.replace(quoted(APPROVED_AT), quoted(CANARY)));
        for (Result result : Result.values()) {
            if (result != Result.PAID && result != Result.REJECTED_ORDER_CANCELED) {
                cases.add(body(result, "KRW", "null", quoted(RESERVATION_ID)));
            }
        }
        return cases.build();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidFieldsNeverEscapeAsRemoteValues(String responseBody, CapturedOutput output) {
        stub(200, success(responseBody));

        assertFailure(Failure.CONTRACT_VIOLATION);

        assertOneCall();
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "null", "{}", "{\"dataBody\":{}}", "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
        "{\"dataHeader\":{\"success\":false},\"dataBody\":null}", "<html>private-body-payment-key-canary</html>"
    })
    void invalidEnvelopeIsContractViolation(String envelope, CapturedOutput output) {
        stub(200, envelope);

        assertFailure(Failure.CONTRACT_VIOLATION);

        assertOneCall();
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    @Test
    void serverBodyIsNotRetained(CapturedOutput output) {
        stub(500, CANARY);

        assertFailure(Failure.UNAVAILABLE);

        assertThat(output.getAll()).doesNotContain(CANARY);
    }
}
