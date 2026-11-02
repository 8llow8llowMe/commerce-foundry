package com.commercefoundry.domainlayer.payment.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentSummaryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * order-service 주문 요약 어댑터를 실제 Feign 클라이언트 · 서비스 토큰 Capability · 서킷 · yml 설정으로 가짜 order-service(WireMock)에 붙여 본다.
 * 경로 · 쿼리 바인딩, 서비스 토큰 한 개, 404 {@code ORDER_001} → 없음, 그 밖의 실패 → {@code PAYMENT_011}(503) 을 재현한다. 값은 모두 테스트 전용이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OrderPaymentSummaryClientAdapterTest {

    private static final long ORDER_ID = 4_611_686_018_427_388_001L;
    private static final long MEMBER_ID = 4_611_686_018_427_387_904L;
    private static final String PATH = "/internal/v1/orders/" + ORDER_ID + "/payment-summary";
    private static final String ORDER_NAME = "테스트 저고리 외 1건";
    private static final Instant DUE_AT = Instant.parse("2026-11-01T03:30:00Z");
    private static final int READ_TIMEOUT_MILLIS = 1_000;
    private static final String SUCCESS_HEADER = "\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null}";

    /** 클래스 전체에서 한 번만 띄운다 — Spring 컨텍스트(캐시)가 처음 포트를 가리킨다. */
    static final WireMockServer ORDER = startedServer();

    @AfterAll
    static void stopServer() {
        ORDER.stop();
    }

    @DynamicPropertySource
    static void orderServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.orderPaymentSummaryClient.url", ORDER::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.orderPaymentSummaryClient.read-timeout", () -> READ_TIMEOUT_MILLIS);
        // OS 환경변수 TOSS_SECRET_KEY 가 있어도 형식만 맞춘 더미로 덮는다 — 이 테스트는 토스를 부르지 않는다
        registry.add("toss.secret-key", () -> "test_sk_dummyForContextTestsOnly000");
        registry.add("toss.base-url", () -> "http://127.0.0.1:9");
    }

    @Autowired
    private OrderPaymentSummaryPort orderPaymentSummaryPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        ORDER.resetAll();
        circuitBreaker().reset();
    }

    private static WireMockServer startedServer() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker("order-service");
    }

    private static void stub(int status, String body) {
        ORDER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String success(String body) {
        return "{" + SUCCESS_HEADER + ",\"dataBody\":" + body + "}";
    }

    private static String summary(String orderId, String memberId, String amount, String currency, String orderName, String dueAt) {
        return """
            {"orderId":%s,"memberId":%s,"status":"PENDING_PAYMENT","totalAmount":{"amount":%s,"currency":%s},"orderName":%s,"paymentDueAt":%s,"futureField":1}
            """.formatted(orderId, memberId, amount, currency, orderName, dueAt);
    }

    private static String validSummary() {
        return summary("\"" + ORDER_ID + "\"", "\"" + MEMBER_ID + "\"", "129000", "\"KRW\"", "\"" + ORDER_NAME + "\"", "\"" + DUE_AT + "\"");
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> orderPaymentSummaryPort.find(ORDER_ID, MEMBER_ID))
            .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE));
    }

    @Nested
    @DisplayName("Feign 바인딩 · 서비스 토큰")
    class Binding {

        @Test
        @DisplayName("200 — GET 경로에 주문 아이디, 쿼리에 memberId 를 싣고 봉투를 조회 결과로 바꾼다. 모르는 칸은 무시한다")
        void roundTrip() {
            stub(200, success(validSummary()));

            OrderPaymentSummaryQueryResult result = orderPaymentSummaryPort.find(ORDER_ID, MEMBER_ID).orElseThrow();

            assertThat(result).isEqualTo(new OrderPaymentSummaryQueryResult(
                ORDER_ID, MEMBER_ID, "PENDING_PAYMENT", Money.of(129_000, CurrencyCode.KRW), ORDER_NAME, DUE_AT));
            ORDER.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("memberId", equalTo(Long.toString(MEMBER_ID))));
        }

        @Test
        @DisplayName("Authorization 은 서비스 토큰 하나다 — aud order-service · iss payment-service")
        void carriesSingleServiceToken() {
            stub(200, success(validSummary()));

            orderPaymentSummaryPort.find(ORDER_ID, MEMBER_ID);

            List<LoggedRequest> requests = ORDER.findAll(getRequestedFor(urlPathEqualTo(PATH)));
            assertThat(requests).singleElement().satisfies(request -> {
                List<String> authorizations = request.getHeaders().getHeader("Authorization").values();
                assertThat(authorizations).singleElement().asString().startsWith("Bearer ");
                Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("order-service"), Clock.systemUTC())
                    .decode(authorizations.getFirst().substring("Bearer ".length()));
                assertThat(token.getAudience()).containsExactly("order-service");
                assertThat(token.getClaimAsString("iss")).isEqualTo("payment-service");
            });
        }
    }

    @Nested
    @DisplayName("거절 · 장애")
    class Failures {

        @Test
        @DisplayName("404 ORDER_001 — 주문 없음(소유 아님 포함)이라 비어 있다. 서킷 실패로 세지 않는다")
        void orderNotFoundIsEmpty() {
            stub(404, failure("ORDER_001"));

            assertThat(orderPaymentSummaryPort.find(ORDER_ID, MEMBER_ID)).isEmpty();
            assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("다른 404(매핑 없는 경로 COMMON_001) — 배포 불일치라 PAYMENT_011 이고 error 로그에 status · resultCode 만 남는다")
        void otherNotFoundIsUnavailable(CapturedOutput output) {
            stub(404, failure("COMMON_001"));

            assertUnavailable();
            assertThat(output.getAll()).contains("order-service rejected internal request. status=404, resultCode=COMMON_001");
        }

        @Test
        @DisplayName("401 SECURITY_007(서비스 인증 실패) — 재시도로 풀리지 않는 설정 문제라 PAYMENT_011 이고 한 번만 부른다")
        void serviceAuthenticationFailure(CapturedOutput output) {
            stub(401, failure("SECURITY_007"));

            assertUnavailable();
            ORDER.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
            assertThat(output.getAll()).contains("status=401, resultCode=SECURITY_007");
            assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("본문을 읽지 못한 4xx · 코드 모양이 아닌 resultCode — PAYMENT_011, 원문 대신 null · <unrecognized> 로 남긴다")
        void unreadableRejection(CapturedOutput output) {
            stub(400, "not json");
            assertUnavailable();
            stub(400, failure("bad\\r\\ncode"));
            assertUnavailable();

            assertThat(output.getAll()).contains("resultCode=null").contains("resultCode=<unrecognized>").doesNotContain("not json");
        }

        @Test
        @DisplayName("5xx — PAYMENT_011 이고 서킷 실패로 센다")
        void serverError() {
            stub(500, failure("COMMON_000"));

            assertUnavailable();
            assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답 타임아웃 — PAYMENT_011")
        void timeout() {
            // 클라이언트 타임아웃 뒤 완료되는 서버 요청 기록이 다음 테스트의 PATH 횟수에 섞이지 않게 분리한다.
            long timeoutOrderId = ORDER_ID + 1;
            String timeoutPath = "/internal/v1/orders/" + timeoutOrderId + "/payment-summary";
            ORDER.stubFor(get(urlPathEqualTo(timeoutPath)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(success(validSummary().replace(Long.toString(ORDER_ID), Long.toString(timeoutOrderId))))
                .withFixedDelay(READ_TIMEOUT_MILLIS * 2)));

            assertThatThrownBy(() -> orderPaymentSummaryPort.find(timeoutOrderId, MEMBER_ID))
                .isInstanceOfSatisfying(PaymentException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("서킷이 열려 있으면 부르지 않고 PAYMENT_011 이다")
        void circuitOpen() {
            stub(200, success(validSummary()));
            circuitBreaker().transitionToOpenState();

            assertUnavailable();
            ORDER.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("응답 계약 위반 — PAYMENT_011")
    class ContractViolations {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "otherOrder", "otherMember", "zeroAmount", "negativeAmount", "missingAmount", "unknownCurrency", "missingCurrency",
            "blankOrderName", "longOrderName", "missingDueAt", "blankStatus", "nonNumericId"
        })
        @DisplayName("본문 칸이 계약과 다르면 추측하지 않고 503 이다. 로그에 응답 값(주문명)을 남기지 않는다")
        void bodyViolations(String violation, CapturedOutput output) {
            String id = "\"" + ORDER_ID + "\"";
            String member = "\"" + MEMBER_ID + "\"";
            String name = "\"" + ORDER_NAME + "\"";
            String due = "\"" + DUE_AT + "\"";
            String body = switch (violation) {
                case "otherOrder" -> summary("\"" + (ORDER_ID + 1) + "\"", member, "129000", "\"KRW\"", name, due);
                case "otherMember" -> summary(id, "\"" + (MEMBER_ID + 1) + "\"", "129000", "\"KRW\"", name, due);
                case "zeroAmount" -> summary(id, member, "0", "\"KRW\"", name, due);
                case "negativeAmount" -> summary(id, member, "-1", "\"KRW\"", name, due);
                case "missingAmount" -> summary(id, member, "null", "\"KRW\"", name, due);
                case "unknownCurrency" -> summary(id, member, "129000", "\"XYZ\"", name, due);
                case "missingCurrency" -> summary(id, member, "129000", "null", name, due);
                case "blankOrderName" -> summary(id, member, "129000", "\"KRW\"", "\" \"", due);
                case "longOrderName" -> summary(id, member, "129000", "\"KRW\"", "\"" + "가".repeat(101) + "\"", due);
                case "missingDueAt" -> summary(id, member, "129000", "\"KRW\"", name, "null");
                case "blankStatus" -> summary(id, member, "129000", "\"KRW\"", name, due).replace("\"PENDING_PAYMENT\"", "\"\"");
                case "nonNumericId" -> summary("\"order-1\"", member, "129000", "\"KRW\"", name, due);
                default -> throw new IllegalArgumentException(violation);
            };
            stub(200, success(body));

            assertUnavailable();
            assertThat(output.getAll()).contains("order-service payment summary violates contract.").doesNotContain(ORDER_NAME);
        }

        @Test
        @DisplayName("주문명 100자는 받는다(경계)")
        void orderNameAtLimit() {
            String name = "가".repeat(100);
            stub(200, success(summary("\"" + ORDER_ID + "\"", "\"" + MEMBER_ID + "\"", "129000", "\"KRW\"", "\"" + name + "\"", "\"" + DUE_AT + "\"")));

            assertThat(orderPaymentSummaryPort.find(ORDER_ID, MEMBER_ID)).get().extracting(OrderPaymentSummaryQueryResult::orderName).isEqualTo(name);
        }

        @Test
        @DisplayName("실패 봉투 · 본문 없음 · JSON 아님 · 빈 본문(200)도 503 이다 — 빈 본문을 주문 없음으로 읽지 않는다")
        void envelopeViolations() {
            stub(200, failure("ORDER_001"));
            assertUnavailable();
            stub(200, "{" + SUCCESS_HEADER + ",\"dataBody\":null}");
            assertUnavailable();
            stub(200, "<html>not json</html>");
            assertUnavailable();
            ORDER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
            assertUnavailable();
        }
    }
}
