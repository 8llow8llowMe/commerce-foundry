package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException;
import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
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

/**
 * 실제 Feign 파이프라인(서비스 토큰 · Jackson 3 디코딩 · 기본 ErrorDecoder · 타임아웃 설정)으로 payment-service 흉내 서버를 불러 본다 (#308 PR-B1).
 * 요청 경로 · 서비스 토큰({@code aud=payment-service}, {@code iss=order-service}), PR-A2 응답 계약(모르는 칸 무시), 404 = 없는 결제, 5xx · 타임아웃 · 서킷 = 일시 오류,
 * 모르는 4xx = 거절, 계약 위반을 구분하는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentClientTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final long PAYMENT_ID = 4611686018427387906L;

    @Autowired
    private PaymentQueryPort paymentQueryPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.paymentClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/payments", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                try {
                    Thread.sleep(DELAY_MS.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length == 0 ? -1 : body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
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
        REQUESTS.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set(payment("DONE"));
        DELAY_MS.set(0);
        circuitBreakerRegistry.circuitBreaker("payment-service").reset();
    }

    private static String payment(String status) {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"paymentId":"4611686018427387906","orderId":"4611686018427387905","memberId":"4611686018427387904",
               "status":{"code":"%s","name":"결제 완료","description":"결제가 승인되었습니다."},
               "amount":{"amount":129000,"currency":"KRW"},
               "method":{"code":"EASY_PAY","name":"간편결제","description":"간편결제"},
               "approvedAt":"2026-10-21T01:00:03Z","futureField":1}}
            """.formatted(status);
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"RAW_REMOTE_MESSAGE\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private void assertFailure(Failure expected) {
        assertThatThrownBy(() -> paymentQueryPort.findPayment(PAYMENT_ID))
            .isInstanceOfSatisfying(PaymentQueryException.class, exception -> {
                assertThat(exception.getFailure()).isEqualTo(expected);
                assertThat(exception.isRetryable()).isEqualTo(expected == Failure.UNAVAILABLE || expected == Failure.UNAUTHORIZED);
                assertThat(exception.getMessage()).doesNotContain("RAW_REMOTE_MESSAGE");
            });
    }

    @Test
    @DisplayName("200 — GET /internal/v1/payments/{paymentId} 에 서비스 토큰 하나(aud=payment-service, iss=order-service)를 싣고, 응답을 재조회 결과로 바꾼다")
    void roundTrip() {
        PaymentQueryResult result = paymentQueryPort.findPayment(PAYMENT_ID).orElseThrow();

        assertThat(result).isEqualTo(new PaymentQueryResult(PAYMENT_ID, 4611686018427387905L, 4611686018427387904L, "DONE",
            Money.of(129_000, CurrencyCode.KRW), Instant.parse("2026-10-21T01:00:03Z")));
        assertThat(result.isDone()).isTrue();
        assertThat(REQUESTS).containsExactly("GET /internal/v1/payments/" + PAYMENT_ID);
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).hasSize(1);
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("payment-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("payment-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @Test
    @DisplayName("DONE 이 아닌 결제(이미 취소 등)는 승인 시각이 없어도 결과다 — 반영 여부는 부른 쪽이 정한다")
    void notDoneIsResult() {
        RESPONSE_BODY.set(payment("CANCELED").replace("\"approvedAt\":\"2026-10-21T01:00:03Z\"", "\"approvedAt\":null"));

        PaymentQueryResult result = paymentQueryPort.findPayment(PAYMENT_ID).orElseThrow();

        assertThat(result.isDone()).isFalse();
        assertThat(result.statusCode()).isEqualTo("CANCELED");
        assertThat(result.approvedAt()).isNull();
    }

    @Test
    @DisplayName("404 PAYMENT_001 — 없는 결제는 빈 결과다(예외 아님), 서킷 실패로 세지 않는다")
    void notFoundIsEmpty() {
        RESPONSE_STATUS.set(404);
        RESPONSE_BODY.set(failure("PAYMENT_001"));

        assertThat(paymentQueryPort.findPayment(PAYMENT_ID)).isEmpty();
        assertThat(circuitBreakerRegistry.circuitBreaker("payment-service").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "500, X, UNAVAILABLE",
        "503, X, UNAVAILABLE",
        "404, X, REJECTED",
        "404, PAYMENT_002, REJECTED",
        "400, PAYMENT_115, REJECTED",
        "401, SECURITY_007, UNAUTHORIZED",
        "403, SECURITY_006, UNAUTHORIZED",
        "403, X, UNAUTHORIZED"
    })
    @DisplayName("5xx 는 일시 오류, 401 · 403 은 인증/인가(둘 다 재시도), 그 밖의 4xx 는 거절(비재시도)이다 — 원격 메시지는 예외에 싣지 않는다")
    void failures(int status, String code, Failure expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code));

        assertFailure(expected);
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    @DisplayName("타임아웃(read 3초)은 일시 오류다 — 다시 보내지 않는다")
    void timeoutIsUnavailable() {
        DELAY_MS.set(3300);

        assertFailure(Failure.UNAVAILABLE);
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    @DisplayName("서킷이 열려 있으면 부르지 않고 일시 오류다")
    void circuitOpenSkipsTransport() {
        circuitBreakerRegistry.circuitBreaker("payment-service").transitionToOpenState();

        assertFailure(Failure.UNAVAILABLE);
        assertThat(REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("contractViolations")
    @DisplayName("성공 응답이 계약과 다르면(다른 결제 · 아이디 형식 · 금액 · 통화 · 상태 · DONE 의 승인 시각 · 봉투) 계약 위반이다 — 다시 불러도 같다")
    void contractViolation(String body) {
        RESPONSE_BODY.set(body);

        assertFailure(Failure.CONTRACT_VIOLATION);
    }

    static Stream<String> contractViolations() {
        String valid = payment("DONE");
        return Stream.of(
            valid.replace("\"paymentId\":\"4611686018427387906\"", "\"paymentId\":\"4611686018427387907\""),
            valid.replace("\"orderId\":\"4611686018427387905\"", "\"orderId\":\"x\""),
            valid.replace("\"memberId\":\"4611686018427387904\"", "\"memberId\":\"0\""),
            valid.replace("\"amount\":129000", "\"amount\":-1"),
            valid.replace("\"amount\":129000", "\"amount\":0"),
            valid.replace("\"currency\":\"KRW\"", "\"currency\":\"JPY\""),
            valid.replace("\"code\":\"DONE\"", "\"code\":\"\""),
            valid.replace("\"status\":{", "\"ignoredStatus\":{"),
            valid.replace("\"approvedAt\":\"2026-10-21T01:00:03Z\"", "\"approvedAt\":null"),
            valid.replace("\"success\":true", "\"success\":false"),
            "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
            "null");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("거절 로그에는 코드 모양의 resultCode 만 남고 원격 메시지 · 위조 줄바꿈은 남지 않는다")
    void rejectionLogsOnlySanitizedCode(CapturedOutput output) {
        RESPONSE_STATUS.set(403);
        RESPONSE_BODY.set(failure("attacker\\r\\nFORGED_LINE"));

        assertFailure(Failure.UNAUTHORIZED);
        assertThat(output.getAll()).contains("resultCode=<unrecognized>").doesNotContain("FORGED_LINE", "RAW_REMOTE_MESSAGE");
    }
}
