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
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
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
import java.io.IOException;
import java.net.ServerSocket;
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
    /** 응답 타임아웃을 짧게 줄여 타임아웃을 빨리 재현한다. 지연은 이보다 길게 준다. */
    private static final int READ_TIMEOUT_MILLIS = 500;
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
            "403, REJECT_CARD_PAYMENT", "403, REJECT_CARD_COMPANY", "403, FDS_ERROR", "400, EXCEED_MAX_AMOUNT", "400, INVALID_REQUEST",
            "404, NOT_FOUND_PAYMENT_SESSION", "404, NOT_FOUND_PAYMENT"
        })
        @DisplayName("명확한 거절(400 · 403 · 404 + 코드) — Rejected(code, message). toString 에 메시지가 없다")
        void rejected(int status, String code) {
            stubConfirm(status, errorJson(code, REJECTION_MESSAGE));

            PgApprovalQueryResult result = approve();

            assertThat(result).isEqualTo(new PgApprovalQueryResult.Rejected(code, REJECTION_MESSAGE));
            assertThat(result.toString()).doesNotContain(REJECTION_MESSAGE);
            assertThat(PaymentFixtures.inProgress().reject(code, REJECTION_MESSAGE).status()).isEqualTo(PaymentStatus.FAILED);
        }

        @ParameterizedTest
        @CsvSource({"400, ALREADY_PROCESSED_PAYMENT", "400, ALREADY_PROCESSING_REQUEST", "409, IDEMPOTENT_REQUEST_PROCESSING"})
        @DisplayName("이미 처리됨 · 처리 중 — 앞선 요청이 승인됐을 수 있어 Unknown(ALREADY_PROCESSED)")
        void alreadyProcessed(int status, String code) {
            stubConfirm(status, errorJson(code, "이미 처리된 결제 입니다."));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED));
        }

        @ParameterizedTest
        @CsvSource({"401, UNAUTHORIZED_KEY", "400, INVALID_API_KEY", "403, INCORRECT_BASIC_AUTH_FORMAT", "403, FORBIDDEN_REQUEST"})
        @DisplayName("키 · 인증 설정 오류 — 승인이 일어나지 않은 것이 확실해 NotSent(CONFIG_ERROR)")
        void configError(int status, String code) {
            stubConfirm(status, errorJson(code, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONFIG_ERROR));
        }

        @Test
        @DisplayName("read timeout — 보냈는데 응답을 못 받았다. Unknown(TIMEOUT), 자동 재시도하지 않는다")
        void readTimeout() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))
                .withFixedDelay(READ_TIMEOUT_MILLIS * 3)));

            assertThat(approve()).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
            TOSS.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        }

        @ParameterizedTest
        @CsvSource({"500, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "500, UNKNOWN_PAYMENT_ERROR", "502, COMMON_ERROR", "429, TOO_MANY_REQUESTS"})
        @DisplayName("5xx · 429 — PG 에서 처리됐는지 모른다. Unknown(SERVER_ERROR), 자동 재시도하지 않는다")
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
        @DisplayName("길이가 넘는 오류 코드 · 간편결제 제공자는 저장 칸 길이로 잘라 도메인 기록이 실패하지 않게 한다")
        void normalizesLongValues() {
            String longCode = "REJECT_" + "X".repeat(150);
            stubConfirm(403, errorJson(longCode, REJECTION_MESSAGE));

            PgApprovalQueryResult rejected = approve();

            assertThat(rejected).isInstanceOf(PgApprovalQueryResult.Rejected.class);
            String failureCode = ((PgApprovalQueryResult.Rejected) rejected).failureCode();
            assertThat(failureCode).hasSize(Payment.FAILURE_CODE_MAX_LENGTH);
            assertThat(PaymentFixtures.inProgress().reject(failureCode, REJECTION_MESSAGE.repeat(30)).status()).isEqualTo(PaymentStatus.FAILED);

            stubConfirm(200, paymentJson("DONE", PG_ORDER_ID, "129000", "간편결제", "제공자".repeat(30)));

            PgApprovalQueryResult approved = approve();

            assertThat(approved).isInstanceOf(PgApprovalQueryResult.Approved.class);
            PaymentApproval approval = ((PgApprovalQueryResult.Approved) approved).approval();
            assertThat(approval.easyPayProvider()).hasSize(Payment.EASY_PAY_PROVIDER_MAX_LENGTH);
            assertThat(PaymentFixtures.inProgress().approve(approval).status()).isEqualTo(PaymentStatus.DONE);
        }

        @Test
        @DisplayName("중복 승인 — 같은 결제 시도의 두 번째 요청도 같은 멱등 키를 싣고, PG 의 '이미 처리됨' 은 Unknown 이라 DONE 기록을 다시 건드리지 않는다")
        void duplicateApproval() {
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("duplicate").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))).willSetStateTo("approved"));
            TOSS.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("duplicate").whenScenarioStateIs("approved")
                .willReturn(json(400, errorJson("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."))));
            Payment inProgress = PaymentFixtures.inProgress();

            PgApprovalQueryResult first = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));
            PgApprovalQueryResult second = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));

            assertThat(first).isInstanceOf(PgApprovalQueryResult.Approved.class);
            assertThat(second).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED));
            TOSS.verify(2, postRequestedFor(urlEqualTo(CONFIRM_PATH)).withHeader("Idempotency-Key", equalTo(PG_ORDER_ID)));
            // 첫 결과로 DONE 이 된 결제 시도는 두 번째 결과로 UNKNOWN 이 되지 않는다 — 이중 기록이 없다
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

            assertThat(result).isEqualTo(new PgInquiryQueryResult.Failed("REJECT_CARD_COMPANY", REJECTION_MESSAGE));
            assertThat(PaymentFixtures.unknown().resolve(((PgInquiryQueryResult.Failed) result).toInquiry()).status()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("EXPIRED 이고 failure 가 없다 — Failed(EXPIRED)")
        void expiredWithoutFailure() {
            stubInquiry(200, paymentJson("EXPIRED", PG_ORDER_ID, "129000", null, null));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Failed("EXPIRED", null));
        }

        @ParameterizedTest
        @CsvSource({"READY", "IN_PROGRESS"})
        @DisplayName("READY · IN_PROGRESS — 아직 처리 중이라 실패로 확정하지 않는다(Pending)")
        void pending(String status) {
            stubInquiry(200, paymentJson(status, PG_ORDER_ID, "129000", null, null));

            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Pending());
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
        @DisplayName("5xx · 타임아웃 — 조회 실패, Unknown")
        void failures() {
            stubInquiry(500, errorJson("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 오류"));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));

            TOSS.stubFor(get(urlEqualTo(INQUIRY_PATH)).willReturn(okJson(paymentJson("DONE", PG_ORDER_ID, "129000", "카드", null))
                .withFixedDelay(READ_TIMEOUT_MILLIS * 3)));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
        }

        @Test
        @DisplayName("다른 주문번호 · 모르는 상태 · 키 설정 오류 — Unknown(CONTRACT)")
        void contractViolations() {
            stubInquiry(200, paymentJson("DONE", "9000000002", "129000", "카드", null));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubInquiry(200, paymentJson("SOMETHING_NEW", PG_ORDER_ID, "129000", "카드", null));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));

            stubInquiry(401, errorJson("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키"));
            assertThat(paymentGatewayPort.inquire(PG_ORDER_ID)).isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
        }
    }

    @Nested
    @DisplayName("연결 실패")
    class ConnectFailure {

        /** 열었다 닫은 포트 — 연결이 거부된다. */
        private static final int CLOSED_PORT = closedPort();

        @DynamicPropertySource
        static void closedBaseUrl(DynamicPropertyRegistry registry) {
            registry.add("toss.base-url", () -> "http://127.0.0.1:" + CLOSED_PORT);
        }

        @Autowired
        private PaymentGatewayPort closedPortGateway;

        @Test
        @DisplayName("연결 거부 — 요청이 나가기 전이라 NotSent(CONNECT_FAILED)")
        void connectionRefused() {
            assertThat(closedPortGateway.approve(PgApprovalRequest.of(PaymentFixtures.inProgress())))
                .isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED));
        }

        private static int closedPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
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
