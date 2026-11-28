package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.PaymentRestorationObservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationObservationInternalUseCase;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 결제 반영 재고 복원 작업 관측(#808)의 HTTP 계약 — operations-service 만 허용, 응답 모양 · no-store, 관측 불가 503. */
@WebMvcTest(PaymentRestorationObservationInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class PaymentRestorationObservationInternalControllerTest {

    private static final String SELF = "order-service";
    private static final String PATH = "/internal/v1/payment-restorations/observation";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PaymentRestorationObservationInternalUseCase useCase;

    private MvcTestResult getAs(String authorization) {
        var request = mvc.get().uri(PATH);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private MvcTestResult getAsOperations() {
        return getAs(TestServiceTokens.bearer("operations-service", SELF));
    }

    @Test
    @DisplayName("operations-service 토큰이면 200 · no-store 이고 건수와 UTC 시각만 낸다")
    void operationsServiceIsAllowed() {
        given(useCase.observe()).willReturn(new PaymentRestorationObservationInternalResponse(Instant.parse("2026-11-29T01:00:00.123456Z"), 3,
            Instant.parse("2026-11-29T00:20:00Z"), 1, Instant.parse("2026-11-29T00:10:00Z")));

        MvcTestResult result = getAsOperations();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.observedAt").isEqualTo("2026-11-29T01:00:00.123456Z");
        assertThat(result).bodyJson().extractingPath("$.dataBody.pendingCount").asNumber().isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.dataBody.oldestPendingCreatedAt").isEqualTo("2026-11-29T00:20:00Z");
        assertThat(result).bodyJson().extractingPath("$.dataBody.heldCount").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.dataBody.oldestHeldCreatedAt").isEqualTo("2026-11-29T00:10:00Z");
    }

    @Test
    @DisplayName("작업이 없으면 0 과 null 이다")
    void emptyIsZeroAndNull() {
        given(useCase.observe()).willReturn(new PaymentRestorationObservationInternalResponse(Instant.parse("2026-11-29T01:00:00Z"), 0, null, 0, null));

        MvcTestResult result = getAsOperations();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.pendingCount").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.dataBody.oldestPendingCreatedAt").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.oldestHeldCreatedAt").isNull();
    }

    @Test
    @DisplayName("관측 불가는 503 · ORDER_047 이고 0 건으로 보이지 않는다")
    void unavailableIs503() {
        given(useCase.observe()).willThrow(new OrderException(OrderErrorCode.PAYMENT_RESTORATION_OBSERVATION_UNAVAILABLE));

        MvcTestResult result = getAsOperations();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_047");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "delivery-service", "product-service"})
    @DisplayName("허용 목록 밖 서비스 토큰이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void otherServiceIsForbidden(String caller) {
        SecurityEnvelopeAssertions.assertEnvelope(getAs(TestServiceTokens.bearer(caller, SELF)).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("서비스 토큰이 없거나 사용자 토큰(ADMIN)이면 401 · SECURITY_007 이다")
    void withoutServiceTokenIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(getAs(null).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        String admin = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
        SecurityEnvelopeAssertions.assertEnvelope(getAs(admin).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }
}
