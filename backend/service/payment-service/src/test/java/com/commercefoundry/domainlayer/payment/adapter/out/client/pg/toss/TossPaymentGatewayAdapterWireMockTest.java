package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult.NotSentReason;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentInquiry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 토스 어댑터를 실제 Feign 클라이언트 · 서킷 · yml 설정으로 가짜 토스 서버(WireMock)에 붙여 결과 분류 표를 재현한다 ({@code payment-guide.md} §11).
 * 키는 형식만 흉내 낸 더미다 — 실키가 아니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class TossPaymentGatewayAdapterWireMockTest {

    /** 형식만 맞춘 더미 키. OS 환경변수 TOSS_SECRET_KEY 가 있어도 이 값으로 덮는다. */
    private static final String SECRET_KEY = "test_sk_dummyWireMockOnly0001";
    private static final String EXPECTED_AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final String PG_ORDER_ID = Long.toString(PaymentFixtures.PAYMENT_ID);
    private static final String INQUIRY_PATH = "/v1/payments/orders/" + PG_ORDER_ID;
    private static final String PAYMENT_KEY_INQUIRY_PATH = "/v1/payments/" + PAYMENT_KEY;
    /**
     * 응답 타임아웃을 줄여 타임아웃을 빨리 재현한다. 너무 짧으면 느린 CI 에서 정상 응답도 타임아웃으로 떨어지므로 2초로 둔다.
     * 지연 스텁은 이 두 배({@link #TIMEOUT_DELAY_MILLIS})다.
     */
    private static final int READ_TIMEOUT_MILLIS = 2_000;
    private static final int TIMEOUT_DELAY_MILLIS = READ_TIMEOUT_MILLIS * 2;
    private static final String REJECTION_MESSAGE = "카드사에서 거절했습니다. 고객 확인이 필요합니다.";

    /**
     * 클래스 전체에서 한 번만 띄운다. JUnit 확장({@code WireMockExtension})은 {@code @Nested} 클래스마다 멈췄다 새 포트로 다시 띄워,
     * 처음 만든 Spring 컨텍스트(캐시)가 가리키는 주소가 닫힌다.
     */
    static final WireMockServer TOSS = startedServer();

    @AfterAll
    static void stopServer() {
        TOSS.stop();
    }

    @DynamicPropertySource
    static void tossProperties(DynamicPropertyRegistry registry) {
        registry.add("toss.base-url", TOSS::baseUrl);
        registry.add("toss.secret-key", () -> SECRET_KEY);
        registry.add("spring.cloud.openfeign.client.config.toss-payments.read-timeout", () -> READ_TIMEOUT_MILLIS);
    }

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        // 테스트끼리 스텁 · 받은 요청 · 서킷 실패 횟수를 나눠 갖지 않게 한다
        TOSS.resetAll();
        circuitBreaker().reset();
    }

    private static WireMockServer startedServer() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        @Test
        @DisplayName("200 DONE — Approved. Basic 인증(base64(키:)) · 멱등 키(결제 시도 아이디) · 본문(결제키 · 주문번호 · 금액)을 싣고, 도메인이 DONE 으로 기록한다")
        void approved() {
            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "간편결제", "토스페이"));
            Payment inProgress = PaymentFixtures.inProgress();

            PgApprovalQueryResult result = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));

            assertThat(result).isInstanceOf(PgApprovalQueryResult.Approved.class);
            PaymentApproval approval = ((PgApprovalQueryResult.Approved) result).approval();
            assertThat(approval.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(approval.method()).isEqualTo(PaymentMethod.EASY_PAY);
            assertThat(approval.easyPayProvider()).isEqualTo("토스페이");
            assertThat(approval.approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(approval.pgStatus()).isEqualTo("DONE");
            assertThat(approval.amount()).isEqualTo(krw(129_000));
            assertThat(inProgress.approve(approval).status()).isEqualTo(PaymentStatus.DONE);

            TOSS.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH))
                .withHeader("Authorization", equalTo(EXPECTED_AUTHORIZATION))
                .withHeader("Idempotency-Key", equalTo(PG_ORDER_ID))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":129000}".formatted(PAYMENT_KEY, PG_ORDER_ID))));
        }

        @ParameterizedTest
        @CsvSource({
            "403, REJECT_CARD_PAYMENT", "403, REJECT_CARD_COMPANY", "403, FDS_ERROR", "403, FORBIDDEN_REQUEST", "403, EXCEED_MAX_AUTH_COUNT",
            "400, EXCEED_MAX_AMOUNT", "400, INVALID_REQUEST", "400, INVALID_CARD_NUMBER", "400, EXCEED_MAX_CARD_INSTALLMENT_PLAN", "400, UNAPPROVED_ORDER_ID",
            "404, NOT_FOUND_PAYMENT_SESSION", "404, NOT_FOUND_PAYMENT"
        })
        @DisplayName("토스 승인 오류표의 처리 전 거절 코드 — Rejected(code, message). toString 에 메시지가 없다")
        void rejected(int status, String code) {
            stubConfirm(status, errorJson(code, REJECTION_MESSAGE));

            PgApprovalQueryResult result = approve();

            assertThat(result).isEqualTo(new PgApprovalQueryResult.Rejected(code, REJECTION_MESSAGE));
            assertThat(result.toString()).doesNotContain(REJECTION_MESSAGE);
            assertThat(PaymentFixtures.inProgress().reject(code, REJECTION_MESSAGE).status()).isEqualTo(PaymentStatus.FAILED);
        }

        @ParameterizedTest
        @CsvSource({"400, ALREADY_PROCESSED_PAYMENT", "400, ALREADY_PROCESSING_REQUEST", "400, DUPLICATED_ORDER_ID", "409, IDEMPOTENT_REQUEST_PROCESSING"})
        @DisplayName("이미 처리됨 · 처리 중 · 중복 주문번호 — 앞선 요청이 승인됐을 수 있어 Unknown(ALREADY_PROCESSED)")
        void alreadyProcessed(int status, String code) {
            stubConfirm(status, errorJson(code, "이미 처리된 결제 입니다."));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED));
        }

        @ParameterizedTest
        @CsvSource({"401, UNAUTHORIZED_KEY", "401, ANY_OTHER_CODE", "400, INVALID_API_KEY", "403, INCORRECT_BASIC_AUTH_FORMAT"})
        @DisplayName("키 · 인증 설정 오류(401 · INVALID_API_KEY · INCORRECT_BASIC_AUTH_FORMAT) — 승인이 일어나지 않은 것이 확실해 NotSent(CONFIG_ERROR)")
        void configError(int status, String code) {
            stubConfirm(status, errorJson(code, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONFIG_ERROR));
        }

        @ParameterizedTest
        @CsvSource({"400, SOMETHING_NEW", "403, NOT_IN_CONFIRM_TABLE", "404, NOT_FOUND", "405, METHOD_NOT_ALLOWED"})
        @DisplayName("승인 오류표에 없는 4xx 코드 — 뜻을 모르니 거절로 확정하지 않는다. Unknown(CONTRACT)")
        void unrecognizedClientError(int status, String code) {
            stubConfirm(status, errorJson(code, "알 수 없는 오류"));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("4xx 인데 본문을 읽지 못함 — Unknown(CONTRACT)")
        void unreadableClientError() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(403).withHeader("Content-Type", "text/html").withBody("<html>WAF</html>")));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("read timeout — 보냈는데 응답을 못 받았다. Unknown(TIMEOUT), 자동 재시도하지 않는다")
        void readTimeout() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))
                .withFixedDelay(TIMEOUT_DELAY_MILLIS)));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
            TOSS.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        }

        @ParameterizedTest
        @CsvSource({
            "500, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "500, UNKNOWN_PAYMENT_ERROR", "502, COMMON_ERROR", "429, TOO_MANY_REQUESTS",
            "400, PROVIDER_ERROR", "400, CARD_PROCESSING_ERROR"
        })
        @DisplayName("5xx · 429 · 결제기관 처리 오류(PROVIDER_ERROR · CARD_PROCESSING_ERROR) — PG 에서 처리됐는지 모른다. Unknown(SERVER_ERROR), 자동 재시도하지 않는다")
        void serverError(int status, String code) {
            stubConfirm(status, errorJson(code, "일시적인 오류"));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));
            TOSS.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        }

        @Test
        @DisplayName("응답을 받는 중 연결이 끊김 — 보냈을 수 있어 Unknown(TIMEOUT), 자동 재시도하지 않는다")
        void connectionResetAfterSend() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
            TOSS.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        }

        @Test
        @DisplayName("200 인데 JSON 이 아니다 — Unknown(CONTRACT)")
        void nonJsonBody() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>ok</html>")));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @ParameterizedTest
        @CsvSource({"IN_PROGRESS", "WAITING_FOR_DEPOSIT", "CANCELED", "ABORTED"})
        @DisplayName("200 인데 status 가 DONE 이 아니다 — Unknown(CONTRACT)")
        void nonDoneStatus(String status) {
            stubConfirm(200, paymentJson(status, PG_ORDER_ID, "129000", "카드", null));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("200 DONE 인데 필수 칸(approvedAt)이 없다 — Unknown(CONTRACT)")
        void missingRequiredField() {
            stubConfirm(200, """
                {"paymentKey":"%s","orderId":"%s","status":"DONE","method":"카드","totalAmount":129000,"currency":"KRW"}""".formatted(PAYMENT_KEY, PG_ORDER_ID));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @ParameterizedTest
        @CsvSource({"2026/10/21 10:00:03", "2026-10-21T10:00:03", "not-a-date"})
        @DisplayName("200 DONE 인데 approvedAt 형식이 틀렸다 — 예외를 내지 않고 Unknown(CONTRACT)")
        void malformedApprovedAt(String approvedAt) {
            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null).replace("2026-10-21T10:00:03+09:00", approvedAt));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("200 DONE 인데 다른 주문번호 · 모르는 통화 · KRW 소수 금액 — Unknown(CONTRACT)")
        void contractViolations() {
            stubConfirm(200, paymentJson("DONE", "9000000002", "129000", "카드", null));
            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null).replace("\"KRW\"", "\"JPY\""));
            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000.5", "카드", null));
            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("서킷이 열려 있으면 요청을 보내지 않는다 — NotSent(CIRCUIT_OPEN)")
        void circuitOpen() {
            circuitBreaker().transitionToForcedOpenState();

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CIRCUIT_OPEN));
            TOSS.verify(0, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        }

        @Test
        @DisplayName("4xx(카드 거절)는 서킷 실패로 세지 않고, 5xx 는 센다 — 카드 거절이 몰려도 서킷이 열리지 않는다")
        void circuitCountsServerErrorsOnly() {
            stubConfirm(403, errorJson("REJECT_CARD_PAYMENT", REJECTION_MESSAGE));
            for (int call = 0; call < 20; call++) {
                approve();
            }
            assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            stubConfirm(500, errorJson("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 오류"));
            for (int call = 0; call < 20; call++) {
                approve();
            }
            assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("429 는 Retry-After 가 없어도 서킷 실패로 센다 — 4xx 무시 규칙에서 429 만 뺐다")
        void circuitCountsTooManyRequests() {
            stubConfirm(429, errorJson("TOO_MANY_REQUESTS", "요청이 많습니다"));
            for (int call = 0; call < 20; call++) {
                approve();
            }
            assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("Retry-After 가 붙은 429 도 같이 센다")
        void circuitCountsTooManyRequestsWithRetryAfter() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(json(429, errorJson("TOO_MANY_REQUESTS", "요청이 많습니다")).withHeader("Retry-After", "1")));
            for (int call = 0; call < 20; call++) {
                assertThat(approve()).isNotInstanceOf(PgApprovalQueryResult.Rejected.class);
            }
            assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("길이가 넘는 오류 코드 · 간편결제 제공자는 저장 칸 길이로 잘라 도메인 기록이 실패하지 않게 한다")
        void normalizesLongValues() {
            // 승인 오류는 오류표의 코드만 Rejected 라 긴 코드가 오지 않는다 — 긴 실패 코드는 조회의 failure.code 로 본다
            String longCode = "REJECT_" + "X".repeat(150);
            stubInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null, longCode, REJECTION_MESSAGE.repeat(30)));

            PgInquiryQueryResult failed = paymentGatewayPort.inquire(PG_ORDER_ID);

            assertThat(failed).isInstanceOf(PgInquiryQueryResult.Failed.class);
            PgInquiryQueryResult.Failed failure = (PgInquiryQueryResult.Failed) failed;
            assertThat(failure.failureCode()).hasSize(Payment.FAILURE_CODE_MAX_LENGTH);
            assertThat(PaymentFixtures.unknown().resolve(failure.toInquiry()).status()).isEqualTo(PaymentStatus.FAILED);

            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "간편결제", "제공자".repeat(30)));

            PgApprovalQueryResult approved = approve();

            assertThat(approved).isInstanceOf(PgApprovalQueryResult.Approved.class);
            PaymentApproval approval = ((PgApprovalQueryResult.Approved) approved).approval();
            assertThat(approval.easyPayProvider()).hasSize(Payment.EASY_PAY_PROVIDER_MAX_LENGTH);
            assertThat(PaymentFixtures.inProgress().approve(approval).status()).isEqualTo(PaymentStatus.DONE);
        }

        @Test
        @DisplayName("중복 승인 — 같은 멱등 키의 두 번째 요청에 토스는 첫 응답(200 DONE)을 그대로 돌려준다. 둘 다 같은 결제키의 Approved 이고, DONE 은 다시 기록되지 않는다")
        void duplicateApproval() {
            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null));
            Payment inProgress = PaymentFixtures.inProgress();

            PgApprovalQueryResult first = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));
            PgApprovalQueryResult second = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));

            assertThat(first).isInstanceOf(PgApprovalQueryResult.Approved.class);
            assertThat(second).isEqualTo(first);
            assertThat(((PgApprovalQueryResult.Approved) second).approval().paymentKey()).isEqualTo(PAYMENT_KEY);
            TOSS.verify(2, postRequestedFor(urlEqualTo(CONFIRM_PATH)).withHeader("Idempotency-Key", equalTo(PG_ORDER_ID)));
            // 첫 결과로 DONE 이 된 결제 시도에 두 번째 승인 결과를 다시 적용할 수 없다 — 이중 기록이 없다
            Payment done = inProgress.approve(((PgApprovalQueryResult.Approved) first).approval());
            assertThatThrownBy(() -> done.approve(((PgApprovalQueryResult.Approved) second).approval())).isInstanceOf(PaymentTransitionException.class);
        }

        @Test
        @DisplayName("중복 승인 중 앞선 요청이 아직 처리 중(409 IDEMPOTENT_REQUEST_PROCESSING) — Unknown(ALREADY_PROCESSED), DONE 을 UNKNOWN 으로 덮지 않는다")
        void duplicateApprovalWhileProcessing() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("duplicate").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))).willSetStateTo("processing"));
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("duplicate").whenScenarioStateIs("processing")
                .willReturn(json(409, errorJson("IDEMPOTENT_REQUEST_PROCESSING", "이전 요청이 처리 중입니다."))));
            Payment inProgress = PaymentFixtures.inProgress();

            PgApprovalQueryResult first = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));
            PgApprovalQueryResult second = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));

            assertThat(second).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED));
            Payment done = inProgress.approve(((PgApprovalQueryResult.Approved) first).approval());
            assertThatThrownBy(() -> done.markUnknown(PaymentUnknownReason.ALREADY_PROCESSED)).isInstanceOf(PaymentTransitionException.class);
        }

        @Test
        @DisplayName("금액 불일치 — 어댑터는 PG 응답을 그대로 Approved 로 넘기고, 도메인이 승인으로 기록하지 않는다(AMOUNT)")
        void amountMismatch() {
            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "1000", "카드", null));

            PgApprovalQueryResult result = approve();

            assertThat(result).isInstanceOf(PgApprovalQueryResult.Approved.class);
            PaymentApproval approval = ((PgApprovalQueryResult.Approved) result).approval();
            assertThat(approval.amount()).isEqualTo(krw(1_000));
            assertThatThrownBy(() -> PaymentFixtures.inProgress().approve(approval))
                .isInstanceOfSatisfying(PaymentApprovalMismatchException.class,
                    exception -> assertThat(exception.getField()).isEqualTo(PaymentApprovalMismatchException.Field.AMOUNT));
        }

        @Test
        @DisplayName("로그에 시크릿 키 · Authorization · 결제키 · 거절 메시지가 남지 않는다")
        void doesNotLogSecrets(CapturedOutput output) {
            stubConfirm(403, errorJson("REJECT_CARD_PAYMENT", REJECTION_MESSAGE));
            approve();
            stubConfirm(401, errorJson("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키"));
            approve();
            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null));
            approve();
            stubConfirm(200, "not-json " + PAYMENT_KEY);
            approve();

            assertThat(output.getAll())
                .contains("toss confirm rejected")
                .doesNotContain(SECRET_KEY)
                .doesNotContain(EXPECTED_AUTHORIZATION)
                .doesNotContain(PAYMENT_KEY)
                .doesNotContain(REJECTION_MESSAGE);
        }
    }

    @Nested
    @DisplayName("조회")
    class Inquire {

        @Test
        @DisplayName("DONE — Approved. 주문번호로 묻고 Basic 인증을 싣는다")
        void approved() {
            stubInquiry(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null));

            PgInquiryQueryResult result = paymentGatewayPort.inquire(PG_ORDER_ID);

            assertThat(result).isInstanceOf(PgInquiryQueryResult.Approved.class);
            PaymentApproval approval = ((PgInquiryQueryResult.Approved) result).approval();
            assertThat(approval.method()).isEqualTo(PaymentMethod.CARD);
            assertThat(PaymentFixtures.unknown().resolve(((PgInquiryQueryResult.Approved) result).toInquiry()).status()).isEqualTo(PaymentStatus.DONE);
            TOSS.verify(1, getRequestedFor(urlEqualTo(INQUIRY_PATH)).withHeader("Authorization", equalTo(EXPECTED_AUTHORIZATION)));
        }

        @Test
        @DisplayName("ABORTED — Failed(failure.code). 도메인이 FAILED 로 확정한다")
        void aborted() {
            stubInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null, "REJECT_CARD_COMPANY", REJECTION_MESSAGE));

            PgInquiryQueryResult result = paymentGatewayPort.inquire(PG_ORDER_ID);

            assertThat(result).isEqualTo(new PgInquiryQueryResult.Failed(PAYMENT_KEY, "REJECT_CARD_COMPANY", REJECTION_MESSAGE));
            assertThat(PaymentFixtures.unknown().resolve(((PgInquiryQueryResult.Failed) result).toInquiry()).status()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("EXPIRED 이고 failure 가 없다 — Failed(EXPIRED)")
        void expiredWithoutFailure() {
            stubInquiry(200, paymentJson("EXPIRED", PG_ORDER_ID, "129000", null, null));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Failed(PAYMENT_KEY, "EXPIRED", null));
        }

        @ParameterizedTest
        @CsvSource({"READY", "IN_PROGRESS"})
        @DisplayName("READY · IN_PROGRESS — 아직 처리 중이라 실패로 확정하지 않는다(Pending)")
        void pending(String status) {
            stubInquiry(200, paymentJson(status, PG_ORDER_ID, "129000", null, null));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Pending(PAYMENT_KEY));
        }

        @Test
        @DisplayName("404 NOT_FOUND_PAYMENT — NotFound. 실패로 뭉개지 않는다")
        void notFound() {
            stubInquiry(404, errorJson("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.NotFound());
        }

        @ParameterizedTest
        @CsvSource({"CANCELED", "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT"})
        @DisplayName("승인 뒤 취소됨 · 입금 대기 — 이 흐름으로 확정하지 않고 사람이 확인한다(Unknown(MANUAL_REVIEW))")
        void manualReview(String status) {
            stubInquiry(200, paymentJson(status, PG_ORDER_ID, "129000", "카드", null));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.MANUAL_REVIEW));
        }

        @Test
        @DisplayName("5xx · 429 · 연속 요청 제한(403 FORBIDDEN_CONSECUTIVE_REQUEST) · 타임아웃 — 조회 실패, Unknown")
        void failures() {
            stubInquiry(500, errorJson("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 오류"));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));

            stubInquiry(429, errorJson("TOO_MANY_REQUESTS", "요청이 많습니다"));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));

            stubInquiry(403, errorJson("FORBIDDEN_CONSECUTIVE_REQUEST", "반복적인 요청은 허용되지 않습니다."));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));

            TOSS.stubFor(get(urlEqualTo(INQUIRY_PATH)).willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))
                .withFixedDelay(TIMEOUT_DELAY_MILLIS)));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
        }

        @Test
        @DisplayName("DONE 인데 approvedAt 형식이 틀렸다 — 예외를 내지 않고 Unknown(CONTRACT)")
        void malformedApprovedAt() {
            stubInquiry(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null).replace("2026-10-21T10:00:03+09:00", "2026/10/21 10:00:03"));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("다른 주문번호 · 모르는 상태 · 키 설정 오류 · 결제키 없음 — Unknown(CONTRACT)")
        void contractViolations() {
            stubInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null).replace("\"paymentKey\":\"" + PAYMENT_KEY + "\",", ""));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubInquiry(200, paymentJson("DONE", "9000000002", "129000", "카드", null));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubInquiry(200, paymentJson("SOMETHING_NEW", PG_ORDER_ID, "129000", "카드", null));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubInquiry(401, errorJson("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키"));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }
    }

    @Nested
    @DisplayName("결제키 조회 — 이 결제키가 승인되지 않았는지 확인한다")
    class InquireByPaymentKey {

        @Test
        @DisplayName("ABORTED — 요청한 결제키의 Failed. 되돌린 READY 를 그 결과로 버릴 수 있다")
        void failedProvesThisKeyNotApproved() {
            stubPaymentKeyInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null, "NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다."));

            PgInquiryQueryResult result = paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID);

            assertThat(result).isEqualTo(new PgInquiryQueryResult.Failed(PAYMENT_KEY, "NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다."));
            Payment aborted = PaymentFixtures.reverted().abortAfterInquiry(((PgInquiryQueryResult.Failed) result).toInquiry(), PaymentAbortReason.REPREPARED);
            assertThat(aborted.status()).isEqualTo(PaymentStatus.ABORTED);
            TOSS.verify(1, getRequestedFor(urlEqualTo(PAYMENT_KEY_INQUIRY_PATH)).withHeader("Authorization", equalTo(EXPECTED_AUTHORIZATION)));
        }

        @Test
        @DisplayName("DONE — Approved(같은 결제키)")
        void approved() {
            stubPaymentKeyInquiry(200, paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null));

            PgInquiryQueryResult result = paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID);

            assertThat(result).isInstanceOf(PgInquiryQueryResult.Approved.class);
            assertThat(((PgInquiryQueryResult.Approved) result).paymentKey()).isEqualTo(PAYMENT_KEY);
        }

        @Test
        @DisplayName("IN_PROGRESS — Pending(같은 결제키), 404 NOT_FOUND_PAYMENT — NotFound, CANCELED — Unknown(MANUAL_REVIEW)")
        void otherOutcomes() {
            stubPaymentKeyInquiry(200, paymentJson("IN_PROGRESS", PG_ORDER_ID, "129000", null, null));
            assertThat(paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Pending(PAYMENT_KEY));

            stubPaymentKeyInquiry(404, errorJson("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));
            assertThat(paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.NotFound());

            stubPaymentKeyInquiry(200, paymentJson("CANCELED", PG_ORDER_ID, "129000", "카드", null));
            assertThat(paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID))
                .isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.MANUAL_REVIEW));
        }

        @Test
        @DisplayName("응답의 결제키 · 주문번호가 요청과 다르다 — 다른 결제의 답이라 Unknown(CONTRACT)")
        void otherPaymentOrOrder() {
            stubPaymentKeyInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null).replace(PAYMENT_KEY, PAYMENT_KEY + "X"));
            assertThat(paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubPaymentKeyInquiry(200, paymentJson("ABORTED", "9000000002", "129000", null, null));
            assertThat(paymentGatewayPort.inquireByPaymentKey(PAYMENT_KEY, PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }

        @Test
        @DisplayName("주문번호 조회의 Failed 가 다른 결제키면 도메인이 되돌린 READY 를 버리지 않는다")
        void orderInquiryWithOtherKeyDoesNotAbort() {
            stubInquiry(200, paymentJson("ABORTED", PG_ORDER_ID, "129000", null, null).replace(PAYMENT_KEY, PAYMENT_KEY + "X"));

            PgInquiryQueryResult result = paymentGatewayPort.inquire(PG_ORDER_ID);

            assertThat(result).isInstanceOf(PgInquiryQueryResult.Failed.class);
            PaymentInquiry.Failed otherKey = ((PgInquiryQueryResult.Failed) result).toInquiry();
            assertThatThrownBy(() -> PaymentFixtures.reverted().abortAfterInquiry(otherKey, PaymentAbortReason.REPREPARED))
                .isInstanceOf(PaymentTransitionException.class);
        }
    }

    @Nested
    @DisplayName("연결 실패")
    class ConnectFailure {

        @DynamicPropertySource
        static void closedBaseUrl(DynamicPropertyRegistry registry) {
            // 1번 포트(tcpmux)는 보통 아무도 듣지 않아 연결이 거부된다 — 열었다 닫은 임의 포트는 그 사이 다른 프로세스가 가져갈 수 있다
            registry.add("toss.base-url", () -> "http://127.0.0.1:1");
        }

        @Autowired
        private PaymentGatewayPort closedPortGateway;

        @Test
        @DisplayName("연결 거부 — 요청이 나가기 전이라 NotSent(CONNECT_FAILED)")
        void connectionRefused() {
            assertThat(closedPortGateway.approve(PgApprovalRequest.of(PaymentFixtures.inProgress())))
                .isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED));
        }
    }

    private PgApprovalQueryResult approve() {
        return paymentGatewayPort.approve(PgApprovalRequest.of(PaymentFixtures.inProgress()));
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(TossPaymentsResponseSupport.CIRCUIT_BREAKER);
    }

    private static void stubConfirm(int status, String body) {
        TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(json(status, body)));
    }

    private static void stubInquiry(int status, String body) {
        TOSS.stubFor(get(urlEqualTo(INQUIRY_PATH)).willReturn(json(status, body)));
    }

    private static void stubPaymentKeyInquiry(int status, String body) {
        TOSS.stubFor(get(urlEqualTo(PAYMENT_KEY_INQUIRY_PATH)).willReturn(json(status, body)));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static ResponseDefinitionBuilder okJson(String body) {
        return json(200, body);
    }

    private static String errorJson(String code, String message) {
        return "{\"code\":\"%s\",\"message\":\"%s\"}".formatted(code, message);
    }

    private static String paymentJson(String status, String orderId, String totalAmount, String method, String easyPayProvider) {
        return paymentJson(status, orderId, totalAmount, method, easyPayProvider, null, null);
    }

    /** 토스 Payment 객체를 흉내 낸 응답. 읽지 않는 칸(orderName · card 등)도 섞어 모르는 칸을 무시하는지 같이 본다. */
    private static String paymentJson(String status, String orderId, String totalAmount, String method, String easyPayProvider, String failureCode,
        String failureMessage) {
        boolean approved = "DONE".equals(status) || "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status);
        return """
            {"mId":"tosspayments","version":"2022-11-16","paymentKey":"%s","orderId":"%s","orderName":"생활한복 저고리 외 2건","currency":"KRW",
             "method":%s,"totalAmount":%s,"balanceAmount":%s,"status":"%s","requestedAt":"2026-10-21T10:00:00+09:00","approvedAt":%s,
             "easyPay":%s,"failure":%s,"card":null}""".formatted(
            PAYMENT_KEY, orderId, quoted(method), totalAmount, totalAmount, status, approved ? "\"2026-10-21T10:00:03+09:00\"" : "null",
            easyPayProvider == null ? "null" : "{\"provider\":%s,\"amount\":%s,\"discountAmount\":0}".formatted(quoted(easyPayProvider), totalAmount),
            failureCode == null ? "null" : "{\"code\":%s,\"message\":%s}".formatted(quoted(failureCode), quoted(failureMessage)));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
