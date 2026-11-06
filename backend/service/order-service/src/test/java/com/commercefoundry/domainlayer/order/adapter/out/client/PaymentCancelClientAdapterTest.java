package com.commercefoundry.domainlayer.order.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult.Outcome;
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
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Feign 파이프라인(서비스 토큰 · Jackson 3 · 기본 ErrorDecoder · 클라이언트별 타임아웃)으로 payment-service 흉내 서버의 결제 취소를 불러 본다 (#16 PR-2).
 * 요청 경로 · {@code Idempotency-Key = claimId} · 본문 계약, 200 = 성공, 016/017/019/001 = 종결 거절, 018 · 5xx · 401/403 · 타임아웃 · 서킷 · 계약 위반 = 미확정을 본다.
 * 타임아웃 시험이 길어지지 않게 read timeout 을 이 시험에서만 짧게 둔다(운영 값은 {@code application.yml} 10초).
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentCancelClientAdapterTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> KEYS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final long PAYMENT_ID = 4611686018427387906L;
    private static final long CLAIM_ID = 4611686018427387999L;
    private static final Money AMOUNT = Money.of(28_000, CurrencyCode.KRW);
    private static final String CIRCUIT = "payment-cancellation";

    @Autowired
    private PaymentCancelPort paymentCancelPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.paymentCancelClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.cloud.openfeign.client.config.paymentCancelClient.read-timeout", () -> "800");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/payments", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                KEYS.add(exchange.getRequestHeaders().getOrDefault("Idempotency-Key", List.of()));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        BODIES.clear();
        KEYS.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set(success());
        DELAY_MS.set(0);
        circuitBreakerRegistry.circuitBreaker(CIRCUIT).reset();
        circuitBreakerRegistry.circuitBreaker("payment-service").reset();
    }

    private static String success() {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"paymentId":"4611686018427387906","claimId":"4611686018427387999","status":"CANCELED","canceledAmount":28000,"futureField":1}}
            """;
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"RAW_REMOTE_MESSAGE\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private PaymentCancelQueryResult cancel() {
        return paymentCancelPort.cancel(PAYMENT_ID, CLAIM_ID, AMOUNT);
    }

    @Test
    @DisplayName("200 — POST /internal/v1/payments/{paymentId}/cancellations 에 서비스 토큰 · Idempotency-Key=claimId · {claimId, amount, currency} 를 싣고 성공이다")
    void succeeded() {
        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.succeeded());

        assertThat(REQUESTS).containsExactly("POST /internal/v1/payments/" + PAYMENT_ID + "/cancellations");
        assertThat(KEYS).containsExactly(List.of(Long.toString(CLAIM_ID)));
        JsonNode body = JsonMapper.builder().build().readTree(BODIES.getFirst());
        assertThat(body.path("claimId").asLong()).isEqualTo(CLAIM_ID);
        assertThat(body.path("amount").asLong()).isEqualTo(28_000);
        assertThat(body.path("currency").asString()).isEqualTo("KRW");
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).hasSize(1);
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("payment-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("payment-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "409, PAYMENT_016, REJECTED",
        "409, PAYMENT_017, REJECTED",
        "409, PAYMENT_019, REJECTED",
        "404, PAYMENT_001, REJECTED",
        "503, PAYMENT_018, UNKNOWN",
        "500, X, UNKNOWN",
        "502, X, UNKNOWN",
        "401, SECURITY_007, UNKNOWN",
        "403, SECURITY_006, UNKNOWN"
    })
    @DisplayName("016 · 017 · 019 · 001 은 종결 거절(코드 그대로), 018 · 5xx · 401 · 403 은 미확정이다 — 한 번만 보낸다")
    void classifiesFailures(int status, String code, Outcome expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(code));

        PaymentCancelQueryResult result = cancel();

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.failureCode()).isEqualTo(expected == Outcome.REJECTED ? code : null);
        assertThat(REQUESTS).hasSize(1);
    }

    @ParameterizedTest(name = "400 {0} → REJECTED")
    @CsvSource({"PAYMENT_100", "PAYMENT_115", "PAYMENT_199"})
    @DisplayName("400 의 payment 검증 코드(PAYMENT_1xx)는 종결 거절이다 — 요청을 바꾸지 않으면 같다")
    void validationErrorsAreRejected(String code) {
        RESPONSE_STATUS.set(400);
        RESPONSE_BODY.set(failure(code));

        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.rejected(code));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> unclassifiedClientErrors() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(409, failure("PAYMENT_012")),
            org.junit.jupiter.params.provider.Arguments.of(409, ""),
            org.junit.jupiter.params.provider.Arguments.of(409, failure("attacker\\r\\nFORGED")),
            org.junit.jupiter.params.provider.Arguments.of(408, failure("X")),
            org.junit.jupiter.params.provider.Arguments.of(429, ""),
            org.junit.jupiter.params.provider.Arguments.of(404, ""),
            org.junit.jupiter.params.provider.Arguments.of(404, failure("GATEWAY_001")),
            org.junit.jupiter.params.provider.Arguments.of(400, ""),
            org.junit.jupiter.params.provider.Arguments.of(400, failure("PAYMENT_008")),
            org.junit.jupiter.params.provider.Arguments.of(422, failure("PAYMENT_115")));
    }

    @ParameterizedTest(name = "{0} {1} → UNKNOWN")
    @MethodSource("unclassifiedClientErrors")
    @DisplayName("허용 목록 밖의 4xx(409 PAYMENT_012 · 본문 없는 409 · 408 · 429 · 봉투 없는 404 · 그 밖)는 미확정이다 — 실패 코드를 남기지 않는다")
    void unclassifiedClientErrorsAreUnknown(int status, String body) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(body);

        PaymentCancelQueryResult result = cancel();

        assertThat(result).isEqualTo(PaymentCancelQueryResult.unknown());
        assertThat(result.failureCode()).isNull();
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    @DisplayName("운영 설정의 결제 취소 read timeout 은 10초다(기본 3초보다 길다)")
    void productionReadTimeoutIsLonger() throws Exception {
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
            .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).getFirst();

        assertThat(yaml.getProperty("spring.cloud.openfeign.client.config.paymentCancelClient.read-timeout")).hasToString("10000");
        assertThat(yaml.getProperty("spring.cloud.openfeign.client.config.default.read-timeout")).hasToString("3000");
    }

    @Test
    @DisplayName("read timeout 은 미확정이다 — 같은 클레임으로 나중에 다시 보낸다. 운영 read timeout 은 기본 3초보다 길다")
    void timeoutIsUnknown() {
        DELAY_MS.set(1_200);

        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.unknown());
        assertThat(REQUESTS).hasSize(1);
        assertThat(circuitBreakerRegistry.circuitBreaker(CIRCUIT).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuitBreakerRegistry.circuitBreaker("payment-service").getMetrics().getNumberOfFailedCalls()).as("조회 서킷과 나뉜다").isZero();
        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.default.read-timeout", Integer.class)).isEqualTo(3000);
    }

    @Test
    @DisplayName("서킷이 열려 있으면 부르지 않고 미확정이다")
    void circuitOpenSkipsTransport() {
        circuitBreakerRegistry.circuitBreaker(CIRCUIT).transitionToOpenState();

        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.unknown());
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("4xx 는 서킷 실패로 세지 않는다")
    void clientErrorsDoNotCountAsCircuitFailures() {
        RESPONSE_STATUS.set(409);
        RESPONSE_BODY.set(failure("PAYMENT_016"));

        cancel();

        assertThat(circuitBreakerRegistry.circuitBreaker(CIRCUIT).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest
    @MethodSource("contractViolations")
    @DisplayName("200 인데 계약과 다르면(다른 결제 · 다른 클레임 · 다른 금액 · 금액 없음 · 실패 봉투 · 본문 없음) 미확정이다 — 같은 키 재요청으로 다시 확인한다")
    void contractViolationIsUnknown(String body) {
        RESPONSE_BODY.set(body);

        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.unknown());
    }

    static Stream<String> contractViolations() {
        String valid = success();
        return Stream.of(
            valid.replace("\"paymentId\":\"4611686018427387906\"", "\"paymentId\":\"4611686018427387907\""),
            valid.replace("\"claimId\":\"4611686018427387999\"", "\"claimId\":\"1\""),
            valid.replace("\"canceledAmount\":28000", "\"canceledAmount\":27000"),
            valid.replace("\"canceledAmount\":28000,", ""),
            valid.replace("\"success\":true", "\"success\":false"),
            "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
            "null");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("로그에는 코드 모양의 resultCode 만 남고 원격 메시지 · 위조 줄바꿈은 남지 않는다")
    void logsOnlySanitizedCode(CapturedOutput output) {
        RESPONSE_STATUS.set(403);
        RESPONSE_BODY.set(failure("attacker\\r\\nFORGED_LINE"));

        assertThat(cancel()).isEqualTo(PaymentCancelQueryResult.unknown());
        assertThat(output.getAll()).contains("resultCode=<unrecognized>").doesNotContain("FORGED_LINE", "RAW_REMOTE_MESSAGE");
    }

    @Test
    @DisplayName("양수가 아닌 아이디 · 0원은 부르지 않는다")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> paymentCancelPort.cancel(0, CLAIM_ID, AMOUNT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentCancelPort.cancel(PAYMENT_ID, 0, AMOUNT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentCancelPort.cancel(PAYMENT_ID, CLAIM_ID, Money.zero(CurrencyCode.KRW))).isInstanceOf(IllegalArgumentException.class);
        assertThat(REQUESTS).isEmpty();
    }
}
