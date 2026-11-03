package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentResultInternalPresenter;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentResultInternalFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentResultProcessor;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 실제 서비스 JWT → Controller → Facade → Processor → 저장소 포트의 응답 · 인가 계약. */
@WebMvcTest(OrderPaymentResultInternalController.class)
@Import({
    InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class, OrderPaymentResultInternalFacade.class, OrderPaymentResultProcessor.class,
    OrderPaymentResultInternalPresenter.class
})
class OrderPaymentResultInternalControllerTest {

    private static final long PAYMENT_ID = 4611686018427387904L;
    private static final long ORDER_ID = PAYMENT_ID + 1;
    private static final long MEMBER_ID = PAYMENT_ID + 2;
    private static final long RESERVATION_ID = PAYMENT_ID + 3;
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T00:00:01.123456Z");

    @Autowired private MockMvcTester mvc;
    @MockitoBean private OrderPaymentRepositoryPort orderPayments;
    @MockitoBean private OrderRepositoryPort orders;

    @Test
    void paidHasExactlySevenFieldsAndPreservesLargeIdentifiersMoneyAndApproval() {
        given(orderPayments.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(record(Result.PAID, APPROVED_AT, RESERVATION_ID)));

        MvcTestResult response = get(String.valueOf(PAYMENT_ID));

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(response).bodyJson().extractingPath("$.dataBody").asMap().containsOnly(
            Map.entry("paymentId", String.valueOf(PAYMENT_ID)), Map.entry("orderId", String.valueOf(ORDER_ID)),
            Map.entry("memberId", String.valueOf(MEMBER_ID)), Map.entry("result", "PAID"),
            Map.entry("amount", Map.of("amount", 129000, "currency", "KRW")),
            Map.entry("approvedAt", APPROVED_AT.toString()), Map.entry("stockReservationId", String.valueOf(RESERVATION_ID)));
        verify(orderPayments).findByPaymentId(PAYMENT_ID);
        verifyNoMoreInteractions(orderPayments);
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
    void rejectionIsOriginalControlCodeWithNullApprovalAndReservation(Result result) {
        given(orderPayments.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(record(result, null, null)));

        MvcTestResult response = get(String.valueOf(PAYMENT_ID));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.dataBody").asMap().hasSize(7);
        assertThat(response).bodyJson().extractingPath("$.dataBody.result").isEqualTo(result.name());
        assertThat(response).bodyJson().extractingPath("$.dataBody.approvedAt").isNull();
        assertThat(response).bodyJson().extractingPath("$.dataBody.stockReservationId").isNull();
        verifyNoInteractions(orders);
    }

    @Test
    void usdMinorUnitsAboveIntegerRangeRemainLongAndAreNotConverted() {
        OrderPayment source = new OrderPayment(1L, ORDER_ID, PAYMENT_ID, MEMBER_ID, Money.of(4_294_967_295L, CurrencyCode.USD), APPROVED_AT,
            Result.PAID, RESERVATION_ID, UUID.randomUUID(), null, null, Instant.parse("2026-11-02T01:00:00Z"));
        given(orderPayments.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(source));

        MvcTestResult response = get(String.valueOf(PAYMENT_ID));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.dataBody").asMap().hasSize(7);
        assertThat(response).bodyJson().extractingPath("$.dataBody.amount.amount").asNumber().isEqualTo(4_294_967_295L);
        assertThat(response).bodyJson().extractingPath("$.dataBody.amount.currency").isEqualTo("USD");
        assertThat(response).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo(String.valueOf(PAYMENT_ID));
        assertThat(response).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo(String.valueOf(ORDER_ID));
        assertThat(response).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo(String.valueOf(MEMBER_ID));
        assertThat(response).bodyJson().extractingPath("$.dataBody.stockReservationId").isEqualTo(String.valueOf(RESERVATION_ID));
    }

    @Test
    void canceledAfterConfirmationPreservesOriginalReservationAndApproval() {
        given(orderPayments.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(record(Result.REJECTED_ORDER_CANCELED, APPROVED_AT, RESERVATION_ID)));

        MvcTestResult response = get(String.valueOf(PAYMENT_ID));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.dataBody.result").isEqualTo("REJECTED_ORDER_CANCELED");
        assertThat(response).bodyJson().extractingPath("$.dataBody.approvedAt").isEqualTo(APPROVED_AT.toString());
        assertThat(response).bodyJson().extractingPath("$.dataBody.stockReservationId").isEqualTo(String.valueOf(RESERVATION_ID));
    }

    @Test
    void absentRecordHasDedicatedNotFoundCode() {
        given(orderPayments.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

        MvcTestResult response = get(String.valueOf(PAYMENT_ID));

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_028");
        assertThat(response).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource({"0,ORDER_162", "-1,ORDER_162", "abc,ORDER_198", "9223372036854775808,ORDER_198"})
    void invalidPaymentIdDoesNotReachPersistence(String id, String code) {
        MvcTestResult response = get(id);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(response).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("paymentId");
        assertThat(response).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(orderPayments, orders);
    }

    @Test
    void missingServiceTokenIsUnauthorized() {
        assertDenied(null, SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    void userTokenIsUnauthorized() {
        assertDenied("Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue(),
            SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    void wrongAudienceIsUnauthorized() {
        assertDenied(TestServiceTokens.bearer("payment-service", "product-service"), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @ParameterizedTest
    @CsvSource({"product-service", "delivery-service", "operations-service"})
    void otherValidServiceIsForbidden(String caller) {
        assertDenied(TestServiceTokens.bearer(caller, "order-service"), SecurityErrorCode.FORBIDDEN);
    }

    private void assertDenied(String token, SecurityErrorCode code) {
        SecurityEnvelopeAssertions.assertEnvelope(getAs(String.valueOf(PAYMENT_ID), token).getResponse(), code);
        verifyNoInteractions(orderPayments, orders);
    }

    private MvcTestResult get(String id) {
        return getAs(id, TestServiceTokens.bearer("payment-service", "order-service"));
    }

    private MvcTestResult getAs(String id, String token) {
        var request = mvc.get().uri("/internal/v1/order-payments/" + id);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        return request.exchange();
    }

    private static OrderPayment record(Result result, Instant approvedAt, Long reservationId) {
        return new OrderPayment(1L, ORDER_ID, PAYMENT_ID, MEMBER_ID, Money.of(129000, CurrencyCode.KRW), approvedAt, result,
            reservationId, UUID.randomUUID(), null, null, Instant.parse("2026-11-02T01:00:00Z"));
    }
}
