package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderInternalUseCase;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 결제용 주문 요약(#14)의 HTTP 계약 — 허용 호출자, 경로 · 쿼리 파라미터 검증 코드, 응답 모양, 404.
 *
 * <p>{@code /internal/**} 은 internal 체인({@link InternalResourceServerConfigurer})이 받는다 — 사용자 체인과 함께 올린다. order-service 는
 * spring-security-test 를 두지 않아 {@code @WithMockUser} 대신 실제 서비스 토큰({@link TestServiceTokens}, {@code aud = order-service})을 싣는다 —
 * 비밀은 security-core 테스트 픽스처가 컨텍스트에 넣은 것과 같다.
 */
@WebMvcTest(OrderPaymentSummaryInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class OrderPaymentSummaryInternalControllerTest {

    /** 이 서비스의 아이디 — 받는 서비스 토큰의 {@code aud}. */
    private static final String SELF = "order-service";
    private static final String PAYMENT_SERVICE = "payment-service";
    private static final long ORDER_ID = 4611686018427387904L;
    private static final long MEMBER_ID = 4611686018427387905L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderInternalUseCase orderInternalUseCase;

    private MvcTestResult get(String orderId, String query) {
        return getAs(TestServiceTokens.bearer(PAYMENT_SERVICE, SELF), orderId, query);
    }

    private MvcTestResult getAs(String authorization, String orderId, String query) {
        var request = mvc.get().uri("/internal/v1/orders/" + orderId + "/payment-summary" + query);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private MvcTestResult getValid() {
        return get(String.valueOf(ORDER_ID), "?memberId=" + MEMBER_ID);
    }

    private MvcTestResult getValidAs(String authorization) {
        return getAs(authorization, String.valueOf(ORDER_ID), "?memberId=" + MEMBER_ID);
    }

    private static OrderPaymentSummaryInternalResponse summary(String status) {
        return OrderPaymentSummaryInternalResponse.builder()
            .orderId(String.valueOf(ORDER_ID))
            .memberId(String.valueOf(MEMBER_ID))
            .status(status)
            .totalAmount(MoneyItem.of(129_000, "KRW"))
            .orderName("생활한복 저고리 외 2건")
            .paymentDueAt(Instant.parse("2026-10-18T09:30:00Z"))
            .build();
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("payment-service 서비스 토큰이면 200 이고 아이디는 문자열, 금액은 {amount, currency}, 결제 기한은 UTC ISO 다")
        void paymentServiceIsAllowed() {
            given(orderInternalUseCase.getPaymentSummary(ORDER_ID, MEMBER_ID)).willReturn(summary("PENDING_PAYMENT"));

            MvcTestResult result = getValid();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("PENDING_PAYMENT");
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalAmount.amount").asNumber().isEqualTo(129_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalAmount.currency").isEqualTo("KRW");
            assertThat(result).bodyJson().extractingPath("$.dataBody.orderName").isEqualTo("생활한복 저고리 외 2건");
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentDueAt").isEqualTo("2026-10-18T09:30:00Z");
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody.stockReservationId");
        }

        @Test
        @DisplayName("서비스 토큰이 없으면 401 · SECURITY_007 이고 유스케이스를 부르지 않는다")
        void withoutServiceTokenIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(null).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(orderInternalUseCase);
        }

        @Test
        @DisplayName("허용 목록 밖 서비스(product-service) 토큰이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(TestServiceTokens.bearer("product-service", SELF)).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(orderInternalUseCase);
        }

        @Test
        @DisplayName("사용자 access 토큰(ADMIN)은 401 · SECURITY_007 이다 — internal 은 서비스 토큰만 받는다")
        void userTokenIsUnauthorized() {
            String admin = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();

            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(admin).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(orderInternalUseCase);
        }

        @Test
        @DisplayName("aud 가 다른 서비스인 payment-service 토큰은 401 · SECURITY_007 이다")
        void otherAudienceIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(getValidAs(TestServiceTokens.bearer(PAYMENT_SERVICE, "product-service")).getResponse(),
                SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(orderInternalUseCase);
        }
    }

    @Nested
    @DisplayName("상태 · 도메인 오류")
    class Domain {

        @Test
        @DisplayName("결제 대기가 아닌 주문(EXPIRED)도 200 이고 상태를 그대로 낸다 — 판정은 payment-service 몫이다")
        void expiredOrderIsStillOk() {
            given(orderInternalUseCase.getPaymentSummary(ORDER_ID, MEMBER_ID)).willReturn(summary("EXPIRED"));

            MvcTestResult result = getValid();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("없거나 다른 회원의 주문이면 404 · ORDER_001 이다")
        void notFound() {
            given(orderInternalUseCase.getPaymentSummary(ORDER_ID, MEMBER_ID)).willThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));

            MvcTestResult result = getValid();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_001");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }
    }

    @Nested
    @DisplayName("파라미터 검증")
    class ParameterValidation {

        @ParameterizedTest(name = "orderId={0}, query={1} → {2}")
        @CsvSource(delimiter = '|', value = {
            "4611686018427387904 | ?memberId=0   | ORDER_142 | memberId",
            "4611686018427387904 | ?memberId=-1  | ORDER_142 | memberId",
            "4611686018427387904 | ?memberId=abc | ORDER_198 | memberId",
            "4611686018427387904 | ''            | ORDER_199 | memberId",
            "0                   | ?memberId=1   | ORDER_141 | orderId",
            "-1                  | ?memberId=1   | ORDER_141 | orderId",
            "abc                 | ?memberId=1   | ORDER_198 | orderId"
        })
        @DisplayName("양수가 아니면 141 · 142, 숫자가 아니면 198, memberId 가 없으면 199 로 400 이고 유스케이스를 부르지 않는다")
        void rejects(String orderId, String query, String code, String field) {
            MvcTestResult result = get(orderId, query);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(orderInternalUseCase);
        }
    }
}
