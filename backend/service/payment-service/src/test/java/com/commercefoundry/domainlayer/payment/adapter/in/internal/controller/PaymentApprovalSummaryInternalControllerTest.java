package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentApprovalSummaryInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationExternalRefundUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationVoidUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentInternalUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentApprovalSummaryQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentApprovalSummaryQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.PaymentApprovalSummaryInternalFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentApprovalSummaryProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
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
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest({PaymentApprovalSummaryInternalController.class, PaymentInternalController.class})
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class, PaymentApprovalSummaryInternalFacade.class, PaymentApprovalSummaryProcessor.class,
    PaymentApprovalSummaryInternalPresenter.class})
class PaymentApprovalSummaryInternalControllerTest {
    private static final Instant FROM = Instant.parse("2024-12-15T00:00:00Z");
    private static final Instant TO = Instant.parse("2025-01-15T00:00:00Z");
    private static final String SELF = "payment-service";
    @Autowired MockMvcTester mvc;
    @MockitoBean PaymentApprovalSummaryQueryPort queryPort;
    @MockitoBean PaymentInternalUseCase paymentInternalUseCase;
    @MockitoBean PaymentCancellationUseCase paymentCancellationUseCase;
    @MockitoBean PaymentCancellationVoidUseCase paymentCancellationVoidUseCase;
    @MockitoBean PaymentCancellationExternalRefundUseCase paymentCancellationExternalRefundUseCase;

    @Test
    void operationsGetsOnlyAnonymousAggregateFieldsWithMoneyMetadataAndUtcEchoAt31Days() {
        given(queryPort.summarize(FROM, TO)).willReturn(List.of(
            new PaymentApprovalSummaryQueryResult(PaymentMethod.CARD, 2, Money.of(12345, CurrencyCode.USD))));
        var result = get(service("operations-service"), FROM.toString(), TO.toString());
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody").asMap().containsOnlyKeys("from", "to", "groups");
        assertThat(result).bodyJson().extractingPath("$.dataBody.from").isEqualTo(FROM.toString());
        assertThat(result).bodyJson().extractingPath("$.dataBody.to").isEqualTo(TO.toString());
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0]").asMap()
            .containsOnlyKeys("method", "approvalCount", "grossApprovedAmount");
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0].method.code").isEqualTo("CARD");
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0].approvalCount").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0].grossApprovedAmount").asMap().containsOnlyKeys("amount", "currency");
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0].grossApprovedAmount.amount").asNumber().isEqualTo(12345);
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups[0].grossApprovedAmount.currency").isEqualTo("USD");
        verify(queryPort).summarize(FROM, TO);
        // The literal mapping wins over the existing /{paymentId} route.
        verifyNoInteractions(paymentInternalUseCase, paymentCancellationUseCase);
    }

    @Test
    void emptyResultIsAnArray() {
        given(queryPort.summarize(FROM, TO)).willReturn(List.of());
        var result = get(service("operations-service"), FROM.toString(), TO.toString());
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.groups").asArray().isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-service", "auth-service", "product-service", "delivery-service"})
    void everyOtherServiceIsForbidden(String caller) {
        SecurityEnvelopeAssertions.assertEnvelope(get(service(caller), FROM.toString(), TO.toString()).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(queryPort);
    }

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    void userTokensCannotReadInternalAggregate(MemberRole role) {
        String token = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        SecurityEnvelopeAssertions.assertEnvelope(get(token, FROM.toString(), TO.toString()).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(queryPort);
    }

    @Test
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(get(null, FROM.toString(), TO.toString()).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(queryPort);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "NULL|2025-01-15T00:00:00Z|PAYMENT_118|from",
        "broken|2025-01-15T00:00:00Z|PAYMENT_119|from",
        "2024-12-15T00:00:00Z|NULL|PAYMENT_120|to",
        "2024-12-15T00:00:00Z|broken|PAYMENT_121|to"
    }, delimiter = '|', nullValues = "NULL")
    void missingAndMalformedParametersHaveFieldCodes(String from, String to, String code, String field) {
        var result = get(service("operations-service"), from, to);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(queryPort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-12-15T00:00:00Z", "2024-12-14T23:59:59Z", "2025-01-15T00:00:00.000000001Z"})
    void equalReversedAndOver31DayPeriodsFailBeforeQuery(String to) {
        var result = get(service("operations-service"), FROM.toString(), to);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_122");
        verifyNoInteractions(queryPort);
    }

    @Test
    void overflowIsOneFailureEnvelopeWithNoPartialGroups() {
        given(queryPort.summarize(FROM, TO)).willThrow(new PaymentException(PaymentErrorCode.APPROVAL_SUMMARY_OVERFLOW));
        var result = get(service("operations-service"), FROM.toString(), TO.toString());
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_020");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private MvcTestResult get(String token, String from, String to) {
        var request = mvc.get().uri("/internal/v1/payments/approval-summary");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        return request.exchange();
    }

    private static String service(String caller) {
        return TestServiceTokens.bearer(caller, SELF);
    }
}
