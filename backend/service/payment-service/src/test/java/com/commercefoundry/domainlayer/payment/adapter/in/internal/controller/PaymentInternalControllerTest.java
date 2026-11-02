package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
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
