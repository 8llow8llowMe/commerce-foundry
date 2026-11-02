package com.commercefoundry.domainlayer.payment.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.adapter.in.web.presenter.PaymentWebPresenter;
import com.commercefoundry.domainlayer.payment.application.service.PaymentWebFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderSummaryProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.properties.PaymentGateProperties;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 실제 Controller → Facade → Presenter를 거쳐 과거 저장된 PG 원문도 HTTP 응답에서 제거되는지 확인한다. */
@WebMvcTest(PaymentWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class, PaymentWebFacade.class,
    PaymentWebPresenter.class, PaymentFailureWebTest.Config.class})
class PaymentFailureWebTest {

    @Autowired private MockMvcTester mvc;
    @MockitoBean private OrderSummaryProcessor orders;
    @MockitoBean private PaymentProcessor payments;
    @MockitoBean private PaymentGatewayProcessor gateway;

    @Test
    void failedReplayAndStatusReadNeverExposeStoredProviderTextOrResendApproval() {
        Payment failed = PaymentFixtures.inProgress().reject("MERCHANT_CONFIGURATION_ERROR", "private merchant diagnostic <script>");
        given(payments.getOwnedByPgOrderId(anyLong(), anyString())).willReturn(failed);
        given(payments.findByApprovalKey(anyLong(), anyString())).willReturn(Optional.of(failed));
        given(payments.getOwned(anyLong(), anyLong())).willReturn(failed);
        String bearer = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        String body = "{\"paymentKey\":\"%s\",\"pgOrderId\":\"%s\",\"amount\":{\"amount\":129000,\"currency\":\"KRW\"}}"
            .formatted(failed.paymentKey(), failed.pgOrderId());

        var confirmed = mvc.post().uri("/api/v1/payments/confirm").header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", PaymentFixtures.APPROVAL_KEY).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        var queried = mvc.get().uri("/api/v1/payments/" + failed.id()).header(HttpHeaders.AUTHORIZATION, bearer).exchange();

        for (var response : java.util.List.of(confirmed, queried)) {
            assertThat(response).hasStatusOk();
            assertThat(response).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("FAILED");
            assertThat(response).bodyJson().extractingPath("$.dataBody.failure.code").isEqualTo("MERCHANT_CONFIGURATION_ERROR");
            assertThat(response).bodyJson().extractingPath("$.dataBody.failure.message")
                .isEqualTo("결제를 완료하지 못했습니다. 다른 결제 수단으로 다시 시도해 주세요.");
        }
        assertThat(failed.failureMessage()).isEqualTo("private merchant diagnostic <script>");
        verifyNoInteractions(orders, gateway);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean PaymentGateProperties paymentGateProperties() { return new PaymentGateProperties(true); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
