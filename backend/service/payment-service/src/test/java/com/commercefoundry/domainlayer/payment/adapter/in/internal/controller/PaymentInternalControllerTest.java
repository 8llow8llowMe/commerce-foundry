package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationExternalRefundUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationVoidUseCase;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentInternalUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 결제 조회(#308)의 HTTP 계약 — 허용 호출자(order-service), 응답 모양 · 내리지 않는 칸, 404, 경로 변수 검증 코드.
 *
 * <p>{@code /internal/**} 은 internal 체인({@link InternalResourceServerConfigurer})이 받는다 — 사용자 체인과 함께 올린다. payment-service 는
 * spring-security-test 를 두지 않아 실제 서비스 토큰({@link TestServiceTokens}, {@code aud = payment-service})을 싣는다.
 */
@WebMvcTest(PaymentInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class PaymentInternalControllerTest {

    /** 이 서비스의 아이디 — 받는 서비스 토큰의 {@code aud}. */
    private static final String SELF = "payment-service";
    private static final String ORDER_SERVICE = "order-service";
    private static final long PAYMENT_ID = 4611686018427387906L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PaymentInternalUseCase paymentInternalUseCase;

    @MockitoBean
    private PaymentCancellationUseCase paymentCancellationUseCase;

    @MockitoBean
    private PaymentCancellationVoidUseCase paymentCancellationVoidUseCase;

    @MockitoBean
    private PaymentCancellationExternalRefundUseCase paymentCancellationExternalRefundUseCase;

    private static final long ORDER_ID = 4611686018427387905L;
    private static final String VOID_BODY = "{\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\"}";

    private MvcTestResult voidAs(String authorization, String claimId, String body) {
        return voidAs(authorization, String.valueOf(PAYMENT_ID), claimId, body);
    }

    private MvcTestResult voidAs(String authorization, String paymentId, String claimId, String body) {
        var request = mvc.post().uri("/internal/v1/payments/" + paymentId + "/cancellations/" + claimId + "/void")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    @Nested
    @DisplayName("무환불 판정 (#691)")
    class VoidCancellation {

        @Test
        @DisplayName("order-service 면 200 — 판정은 데이터다. 식별자는 문자열, 상태는 metadata, 잔액은 {amount, currency}")
        void orderServiceGetsDecision() {
            given(paymentCancellationVoidUseCase.voidCancellation(PAYMENT_ID, ORDER_ID, 42, 10_000, CurrencyCode.KRW))
                .willReturn(PaymentCancelVoidResponse.builder().paymentId(String.valueOf(PAYMENT_ID)).claimId("42")
                    .outcome("NO_REFUND_CONFIRMED").reason("CLAIM_NEVER_REQUESTED").paymentStatus(PaymentStatus.DONE.toMetadata())
                    .retainedAmount(MoneyItem.of(129_000, "KRW")).sealed(true).build());

            MvcTestResult result = voidAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", VOID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo("4611686018427387906");
            assertThat(result).bodyJson().extractingPath("$.dataBody.claimId").isEqualTo("42");
            assertThat(result).bodyJson().extractingPath("$.dataBody.outcome").isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.reason").isEqualTo("CLAIM_NEVER_REQUESTED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentStatus.code").isEqualTo("DONE");
            assertThat(result).bodyJson().extractingPath("$.dataBody.retainedAmount.amount").asNumber().isEqualTo(129_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.retainedAmount.currency").isEqualTo("KRW");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sealed").isEqualTo(true);
        }

        @Test
        @DisplayName("서비스 토큰이 없으면 401 · SECURITY_007, 다른 서비스면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void onlyOrderService() {
            SecurityEnvelopeAssertions.assertEnvelope(voidAs(null, "42", VOID_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(voidAs(TestServiceTokens.bearer("delivery-service", SELF), "42", VOID_BODY).getResponse(),
                SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(paymentCancellationVoidUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MemberRole.class)
        @DisplayName("회원 access 토큰은 역할과 상관없이 401 · SECURITY_007 이다 — internal 체인은 서비스 토큰만 받는다")
        void memberTokenIsRejected(MemberRole role) {
            String member = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();

            SecurityEnvelopeAssertions.assertEnvelope(voidAs(member, "42", VOID_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(paymentCancellationVoidUseCase);
        }

        @ParameterizedTest(name = "claimId={0} body={1} → {2}")
        @CsvSource(delimiter = '|', value = {
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":0,\"currency\":\"KRW\"}  | PAYMENT_110",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":-1,\"currency\":\"KRW\"} | PAYMENT_110",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000}                    | PAYMENT_111",
            "42 | {\"amount\":10000,\"currency\":\"KRW\"}                                    | PAYMENT_101",
            "42 | {\"orderId\":0,\"amount\":10000,\"currency\":\"KRW\"}                      | PAYMENT_102",
            "0  | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\"} | PAYMENT_123"
        })
        @DisplayName("금액 양수 · 통화 필수 · orderId 필수 · 양수 · claimId 양수가 아니면 400 이고 유스케이스를 부르지 않는다")
        void validation(String claimId, String body, String code) {
            MvcTestResult result = voidAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), claimId, body);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(paymentCancellationVoidUseCase);
        }

        @ParameterizedTest(name = "paymentId={0} → {1}")
        @CsvSource(delimiter = '|', value = {"0 | PAYMENT_115", "abc | PAYMENT_198"})
        @DisplayName("경로 paymentId 가 양수가 아니면 115, 숫자가 아니면 198 로 400 이고 유스케이스를 부르지 않는다")
        void paymentIdValidation(String paymentId, String code) {
            MvcTestResult result = voidAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), paymentId, "42", VOID_BODY);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("paymentId");
            verifyNoInteractions(paymentCancellationVoidUseCase);
        }

        @Test
        @DisplayName("결제가 없으면 404 PAYMENT_001, 다른 주문의 결제 · claimId 재사용이면 409 PAYMENT_019 다")
        void domainErrors() {
            given(paymentCancellationVoidUseCase.voidCancellation(PAYMENT_ID, ORDER_ID, 42, 10_000, CurrencyCode.KRW))
                .willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT))
                .willThrow(new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED));

            MvcTestResult missing = voidAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", VOID_BODY);
            assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(missing).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_001");

            MvcTestResult reused = voidAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", VOID_BODY);
            assertThat(reused).hasStatus(HttpStatus.CONFLICT);
            assertThat(reused).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_019");
        }
    }

    private static final String EXTERNAL_BODY = "{\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"PG\"}";

    private MvcTestResult externalAs(String authorization, String paymentId, String claimId, String body) {
        var request = mvc.post().uri("/internal/v1/payments/" + paymentId + "/cancellations/" + claimId + "/external-refund")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private MvcTestResult externalAs(String authorization, String claimId, String body) {
        return externalAs(authorization, String.valueOf(PAYMENT_ID), claimId, body);
    }

    @Nested
    @DisplayName("외부 확인 환불 기록 (#693)")
    class ExternalRefund {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("order-service 면 200 — 판정은 데이터다. 식별자는 문자열, 채널은 제어 코드, 상태는 metadata, 금액은 {amount, currency}")
        void orderServiceGetsDecision(Channel channel) {
            given(paymentCancellationExternalRefundUseCase.recordExternalRefund(PAYMENT_ID, ORDER_ID, 42, 10_000, CurrencyCode.KRW, channel))
                .willReturn(PaymentCancelExternalRefundResponse.builder().paymentId(String.valueOf(PAYMENT_ID)).claimId("42")
                    .outcome("RECORDED").reason("PG_VERIFIED").channel(channel.name()).paymentStatus(PaymentStatus.PARTIAL_CANCELED.toMetadata())
                    .refundedAmount(MoneyItem.of(10_000, "KRW")).retainedAmount(MoneyItem.of(119_000, "KRW")).build());
            String body = "{\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"" + channel.name() + "\"}";

            MvcTestResult result = externalAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", body);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo("4611686018427387906");
            assertThat(result).bodyJson().extractingPath("$.dataBody.claimId").isEqualTo("42");
            assertThat(result).bodyJson().extractingPath("$.dataBody.outcome").isEqualTo("RECORDED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.reason").isEqualTo("PG_VERIFIED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.channel").isEqualTo(channel.name());
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentStatus.code").isEqualTo("PARTIAL_CANCELED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.refundedAmount.amount").asNumber().isEqualTo(10_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.retainedAmount.amount").asNumber().isEqualTo(119_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.retainedAmount.currency").isEqualTo("KRW");
        }

        @Test
        @DisplayName("미확정 판정의 channel 은 null 로 나간다")
        void unconfirmedHasNullChannel() {
            given(paymentCancellationExternalRefundUseCase.recordExternalRefund(PAYMENT_ID, ORDER_ID, 42, 10_000, CurrencyCode.KRW, Channel.PG))
                .willReturn(PaymentCancelExternalRefundResponse.builder().paymentId(String.valueOf(PAYMENT_ID)).claimId("42")
                    .outcome("UNCONFIRMED").reason("PG_LEDGER_MISMATCH").paymentStatus(PaymentStatus.DONE.toMetadata())
                    .refundedAmount(MoneyItem.of(0, "KRW")).retainedAmount(MoneyItem.of(129_000, "KRW")).build());

            MvcTestResult result = externalAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", EXTERNAL_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.outcome").isEqualTo("UNCONFIRMED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.channel").isNull();
        }

        @Test
        @DisplayName("서비스 토큰이 없으면 401 · SECURITY_007, order 외 서비스면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void onlyOrderService() {
            SecurityEnvelopeAssertions.assertEnvelope(externalAs(null, "42", EXTERNAL_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            for (String other : new String[] {"delivery-service", "operations-service", "product-service", "auth-service"}) {
                SecurityEnvelopeAssertions.assertEnvelope(externalAs(TestServiceTokens.bearer(other, SELF), "42", EXTERNAL_BODY).getResponse(),
                    SecurityErrorCode.FORBIDDEN);
            }
            verifyNoInteractions(paymentCancellationExternalRefundUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MemberRole.class)
        @DisplayName("회원 access 토큰은 역할과 상관없이 401 · SECURITY_007 이다 — internal 체인은 서비스 토큰만 받는다")
        void memberTokenIsRejected(MemberRole role) {
            String member = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();

            SecurityEnvelopeAssertions.assertEnvelope(externalAs(member, "42", EXTERNAL_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(paymentCancellationExternalRefundUseCase);
        }

        @ParameterizedTest(name = "claimId={0} body={1} → {2}")
        @CsvSource(delimiter = '|', value = {
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\"}                           | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":null}           | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"\"}           | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"pg\"}         | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"CARD\"}       | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"SUCCEEDED\"}  | PAYMENT_124",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":0,\"currency\":\"KRW\",\"channel\":\"PG\"}             | PAYMENT_110",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":-1,\"currency\":\"KRW\",\"channel\":\"PG\"}            | PAYMENT_110",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":1e20,\"currency\":\"KRW\",\"channel\":\"PG\"}          | PAYMENT_100",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"XYZ\",\"channel\":\"PG\"}         | PAYMENT_100",
            "42 | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"channel\":\"PG\"}                             | PAYMENT_111",
            "42 | {\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"PG\"}                                             | PAYMENT_101",
            "42 | {\"orderId\":0,\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"OFF_PG\"}                       | PAYMENT_102",
            "0  | {\"orderId\":\"4611686018427387905\",\"amount\":10000,\"currency\":\"KRW\",\"channel\":\"PG\"}         | PAYMENT_123"
        })
        @DisplayName("채널 누락 · 모르는 값은 124, 금액 · 통화 · orderId · claimId 는 무환불 판정과 같은 코드로 400 이고 유스케이스를 부르지 않는다")
        void validation(String claimId, String body, String code) {
            MvcTestResult result = externalAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), claimId, body);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(paymentCancellationExternalRefundUseCase);
        }

        @ParameterizedTest(name = "paymentId={0} → {1}")
        @CsvSource(delimiter = '|', value = {"0 | PAYMENT_115", "abc | PAYMENT_198"})
        @DisplayName("경로 paymentId 가 양수가 아니면 115, 숫자가 아니면 198 로 400 이다")
        void paymentIdValidation(String paymentId, String code) {
            MvcTestResult result = externalAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), paymentId, "42", EXTERNAL_BODY);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(paymentCancellationExternalRefundUseCase);
        }

        @Test
        @DisplayName("도메인 오류는 404 PAYMENT_001 · 409 PAYMENT_019 · 409 PAYMENT_017 · 409 PAYMENT_012 로 나간다")
        void domainErrors() {
            given(paymentCancellationExternalRefundUseCase.recordExternalRefund(PAYMENT_ID, ORDER_ID, 42, 10_000, CurrencyCode.KRW, Channel.PG))
                .willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT))
                .willThrow(new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED))
                .willThrow(new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID))
                .willThrow(new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT));

            String[][] expected = {{"404", "PAYMENT_001"}, {"409", "PAYMENT_019"}, {"409", "PAYMENT_017"}, {"409", "PAYMENT_012"}};
            for (String[] row : expected) {
                MvcTestResult result = externalAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), "42", EXTERNAL_BODY);
                assertThat(result).hasStatus(Integer.parseInt(row[0]));
                assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(row[1]);
            }
        }
    }

    private MvcTestResult getAs(String authorization, String paymentId) {
        var request = mvc.get().uri("/internal/v1/payments/" + paymentId);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private MvcTestResult get(String paymentId) {
        return getAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF), paymentId);
    }

    private MvcTestResult getValidAs(String authorization) {
        return getAs(authorization, String.valueOf(PAYMENT_ID));
    }

    private static PaymentInternalResponse done() {
        return PaymentInternalResponse.builder()
            .paymentId(String.valueOf(PAYMENT_ID))
            .orderId("4611686018427387905")
            .memberId("4611686018427387904")
            .status(PaymentStatus.DONE.toMetadata())
            .amount(MoneyItem.of(129_000, "KRW"))
            .method(PaymentMethod.EASY_PAY.toMetadata())
            .approvedAt(Instant.parse("2026-10-21T01:00:03Z"))
            .build();
    }

    private MvcTestResult cancelAs(String authorization) {
        var request = mvc.post().uri("/internal/v1/payments/" + PAYMENT_ID + "/cancellations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimId\":42,\"amount\":10000,\"currency\":\"KRW\"}")
            .header("Idempotency-Key", "42");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    @Test
    void orderServiceCanCancelAndOtherServicesCannot() {
        given(paymentCancellationUseCase.cancel(PAYMENT_ID, 42, 10_000, com.commercefoundry.shared.commerce.CurrencyCode.KRW, "42"))
            .willReturn(new PaymentCancelResponse(Long.toString(PAYMENT_ID), "42", "PARTIAL_CANCELED", 10_000));
        assertThat(cancelAs(TestServiceTokens.bearer(ORDER_SERVICE, SELF))).hasStatusOk();
        SecurityEnvelopeAssertions.assertEnvelope(cancelAs(TestServiceTokens.bearer("product-service", SELF)).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(cancelAs(null).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    void cancelRequiresIdempotencyHeader() {
        MvcTestResult result = mvc.post().uri("/internal/v1/payments/" + PAYMENT_ID + "/cancellations")
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(ORDER_SERVICE, SELF))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimId\":42,\"amount\":10000,\"currency\":\"KRW\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_113");
        verifyNoInteractions(paymentCancellationUseCase);
    }

    @Nested
    @DisplayName("권한 · 응답")
    class Authorization {

        @Test
        @DisplayName("order-service 서비스 토큰이면 200 — 아이디는 문자열, 상태 · 수단은 metadata, 금액은 {amount, currency}, 승인 시각은 UTC ISO")
        void orderServiceIsAllowed() {
            given(paymentInternalUseCase.getPayment(PAYMENT_ID)).willReturn(done());

            MvcTestResult result = get(String.valueOf(PAYMENT_ID));

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo("4611686018427387906");
            assertThat(result).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("DONE");
            assertThat(result).bodyJson().extractingPath("$.dataBody.amount.amount").asNumber().isEqualTo(129_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.amount.currency").isEqualTo("KRW");
            assertThat(result).bodyJson().extractingPath("$.dataBody.method.code").isEqualTo("EASY_PAY");
            assertThat(result).bodyJson().extractingPath("$.dataBody.approvedAt").isEqualTo("2026-10-21T01:00:03Z");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "paymentKey", "approvalIdempotencyKey", "idempotencyKey", "orderName", "pgStatus", "easyPayProvider", "failure", "pgOrderId",
            "unknownReason", "orderSynced", "orderSyncFailureCode"
        })
        @DisplayName("결제키 · 멱등 키 · 주문명 · PG 원문 · 간편결제 제공자 · 실패 사유 · PG 주문번호 · 내부 처리 기록은 응답에 없다")
        void doesNotExposeSensitiveFields(String field) {
            given(paymentInternalUseCase.getPayment(PAYMENT_ID)).willReturn(done());

            MvcTestResult result = get(String.valueOf(PAYMENT_ID));

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody." + field);
        }

        @Test
        @DisplayName("승인 전 결제는 method · approvedAt 키를 빼지 않고 null 로 낸다")
        void notApprovedHasNullApprovalFields() {
            given(paymentInternalUseCase.getPayment(PAYMENT_ID)).willReturn(PaymentInternalResponse.builder()
                .paymentId(String.valueOf(PAYMENT_ID)).orderId("4611686018427387905").memberId("4611686018427387904")
                .status(PaymentStatus.READY.toMetadata()).amount(MoneyItem.of(129_000, "KRW")).build());

            MvcTestResult result = get(String.valueOf(PAYMENT_ID));

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("READY");
            assertThat(result).bodyJson().hasPath("$.dataBody.method");
            assertThat(result).bodyJson().extractingPath("$.dataBody.method").isNull();
            assertThat(result).bodyJson().hasPath("$.dataBody.approvedAt");
            assertThat(result).bodyJson().extractingPath("$.dataBody.approvedAt").isNull();
        }

        @Test
        @DisplayName("서비스 토큰이 없으면 401 · SECURITY_007 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(null).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(paymentInternalUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"product-service", "delivery-service", "auth-service"})
        @DisplayName("허용 목록 밖 서비스 토큰이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden(String caller) {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(TestServiceTokens.bearer(caller, SELF)).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(paymentInternalUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MemberRole.class)
        @DisplayName("회원 access 토큰은 역할과 상관없이 401 · SECURITY_007 이다 — internal 체인은 서비스 토큰만 받는다")
        void memberTokenIsRejected(MemberRole role) {
            String member = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();

            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(member).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(paymentInternalUseCase);
        }

        @Test
        @DisplayName("aud 가 다른 서비스인 order-service 토큰은 401 · SECURITY_007 이다")
        void otherAudienceIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(TestServiceTokens.bearer(ORDER_SERVICE, "product-service")).getResponse(),
                SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(paymentInternalUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류")
    class Domain {

        @Test
        @DisplayName("없는 결제면 404 · PAYMENT_001 이다")
        void notFound() {
            given(paymentInternalUseCase.getPayment(PAYMENT_ID)).willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));

            MvcTestResult result = get(String.valueOf(PAYMENT_ID));

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_001");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }
    }

    @Nested
    @DisplayName("경로 변수 검증")
    class PathValidation {

        @ParameterizedTest(name = "paymentId={0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "0                    | PAYMENT_115",
            "-1                   | PAYMENT_115",
            "abc                  | PAYMENT_198",
            "99999999999999999999 | PAYMENT_198"
        })
        @DisplayName("양수가 아니면 115, 숫자 · long 범위가 아니면 198 로 400 이고 유스케이스를 부르지 않는다")
        void rejects(String paymentId, String code) {
            MvcTestResult result = get(paymentId);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("paymentId");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(paymentInternalUseCase);
        }

        @Test
        @DisplayName("허용되지 않은 호출자도 형식이 틀리면 400 이다 — 메서드 검증이 @PreAuthorize 보다 먼저 돈다")
        void validationBeforeAuthorization() {
            MvcTestResult result = getAs(TestServiceTokens.bearer("product-service", SELF), "0");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_115");
            verifyNoInteractions(paymentInternalUseCase);
        }
    }
}
