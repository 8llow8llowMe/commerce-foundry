package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 서비스 간 출고 · 배송 완료 반영(#18)의 HTTP 계약 — 허용 호출자(delivery-service 만), 본문 · 경로 검증 코드, 응답 모양, 오류 봉투.
 *
 * <p>{@code OrderPaymentSummaryInternalControllerTest} 와 같이 internal 체인과 사용자 체인을 함께 올리고 실제 서비스 토큰({@code aud = order-service})을 싣는다.
 */
@WebMvcTest(OrderFulfillmentInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class OrderFulfillmentInternalControllerTest {

    private static final String SELF = "order-service";
    private static final String DELIVERY_SERVICE = "delivery-service";
    private static final long ORDER_ID = 4611686018427387904L;
    private static final long DELIVERY_ID = 4611686018427387906L;
    private static final String SHIPMENT = "/internal/v1/orders/" + ORDER_ID + "/shipment";
    private static final String DELIVERY_COMPLETION = "/internal/v1/orders/" + ORDER_ID + "/delivery-completion";
    private static final String SHIPMENT_BODY = "{\"deliveryId\":\"" + DELIVERY_ID + "\",\"shippedAt\":\"2026-11-02T09:30:00Z\"}";
    private static final String DELIVERY_BODY = "{\"deliveryId\":\"" + DELIVERY_ID + "\",\"deliveredAt\":\"2026-11-04T05:00:00Z\"}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderInternalUseCase orderInternalUseCase;

    private MvcTestResult post(String uri, String body) {
        return postAs(TestServiceTokens.bearer(DELIVERY_SERVICE, SELF), uri, body);
    }

    private MvcTestResult postAs(String authorization, String uri, String body) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private static OrderFulfillmentInternalResponse result(String status) {
        return OrderFulfillmentInternalResponse.builder()
            .orderId(String.valueOf(ORDER_ID))
            .memberId("4611686018427387905")
            .status(status)
            .countryCode("KR")
            .build();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Nested
    @DisplayName("응답 · 권한")
    class Authorization {

        @Test
        @DisplayName("delivery-service 토큰으로 출고를 반영하면 200 이고 {orderId, memberId, status, countryCode} 다 — 아이디는 문자열이고 송장 아이디 · 시각을 그대로 넘긴다")
        void shipmentIsOk() {
            given(orderInternalUseCase.ship(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-02T09:30:00Z"))).willReturn(result("SHIPPING"));

            MvcTestResult result = post(SHIPMENT, SHIPMENT_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("SHIPPING");
            assertThat(result).bodyJson().extractingPath("$.dataBody.countryCode").isEqualTo("KR");
        }

        @Test
        @DisplayName("배송 완료 반영도 200 이고 같은 모양이다")
        void deliveryCompletionIsOk() {
            given(orderInternalUseCase.completeDelivery(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-04T05:00:00Z"))).willReturn(result("DELIVERED"));

            MvcTestResult result = post(DELIVERY_COMPLETION, DELIVERY_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("DELIVERED");
            verify(orderInternalUseCase).completeDelivery(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-04T05:00:00Z"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"payment-service", "product-service"})
        @DisplayName("허용 목록 밖 서비스 토큰이면 두 경로 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden(String caller) {
            String token = TestServiceTokens.bearer(caller, SELF);

            SecurityEnvelopeAssertions.assertEnvelope(postAs(token, SHIPMENT, SHIPMENT_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(postAs(token, DELIVERY_COMPLETION, DELIVERY_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(orderInternalUseCase);
        }

        @Test
        @DisplayName("서비스 토큰이 없거나 사용자 access 토큰(ADMIN · CUSTOMER)이면 401 · SECURITY_007 이다 — internal 은 서비스 토큰만 받는다")
        void withoutServiceTokenIsUnauthorized() {
            String admin = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
            String customer = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

            SecurityEnvelopeAssertions.assertEnvelope(postAs(null, SHIPMENT, SHIPMENT_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(postAs(admin, SHIPMENT, SHIPMENT_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(postAs(customer, DELIVERY_COMPLETION, DELIVERY_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(orderInternalUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류")
    class Domain {

        @Test
        @DisplayName("없는 주문은 404 · ORDER_001")
        void notFound() {
            given(orderInternalUseCase.ship(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-02T09:30:00Z"))).willThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));

            assertFailure(post(SHIPMENT, SHIPMENT_BODY), HttpStatus.NOT_FOUND, "ORDER_001");
        }

        @Test
        @DisplayName("반영할 수 없는 상태는 409 · ORDER_020 이고 메시지에 현재 상태가 있다")
        void transitionInvalid() {
            given(orderInternalUseCase.ship(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-02T09:30:00Z")))
                .willThrow(new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, "취소 요청"));

            MvcTestResult result = post(SHIPMENT, SHIPMENT_BODY);

            assertFailure(result, HttpStatus.CONFLICT, "ORDER_020");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").asString().contains("취소 요청");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "ORDER_159 | shippedAt",
            "ORDER_160 | deliveredAt",
            "ORDER_161 | deliveredAt"
        })
        @DisplayName("시각 범위 · 순서 위반은 400 이고 대표 코드와 fieldErrors 에 그 칸이 있다")
        void timeOutOfRange(String code, String field) {
            given(orderInternalUseCase.ship(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-02T09:30:00Z")))
                .willThrow(new OrderFieldValidationException(List.of(new OrderFieldValidationException.Violation(code, field, "시각이 올바르지 않습니다."))));

            MvcTestResult result = post(SHIPMENT, SHIPMENT_BODY);

            assertFailure(result, HttpStatus.BAD_REQUEST, code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        }

        @Test
        @DisplayName("버전 충돌은 409 · ORDER_025")
        void concurrentlyChanged() {
            given(orderInternalUseCase.completeDelivery(ORDER_ID, DELIVERY_ID, Instant.parse("2026-11-04T05:00:00Z")))
                .willThrow(new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));

            assertFailure(post(DELIVERY_COMPLETION, DELIVERY_BODY), HttpStatus.CONFLICT, "ORDER_025");
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "송장 아이디 없음    | shipment            | {\"shippedAt\":\"2026-11-02T09:30:00Z\"}                                       | ORDER_155",
            "송장 아이디 형식    | shipment            | {\"deliveryId\":\"abc\",\"shippedAt\":\"2026-11-02T09:30:00Z\"}                | ORDER_156",
            "송장 아이디 0       | shipment            | {\"deliveryId\":\"0\",\"shippedAt\":\"2026-11-02T09:30:00Z\"}                  | ORDER_156",
            "송장 아이디 범위    | shipment            | {\"deliveryId\":\"9999999999999999999\",\"shippedAt\":\"2026-11-02T09:30:00Z\"} | ORDER_156",
            "출고 시각 없음      | shipment            | {\"deliveryId\":\"1\"}                                                          | ORDER_157",
            "출고 시각 형식      | shipment            | {\"deliveryId\":\"1\",\"shippedAt\":\"어제\"}                                   | ORDER_100",
            "배송 송장 없음      | delivery-completion | {\"deliveredAt\":\"2026-11-04T05:00:00Z\"}                                     | ORDER_155",
            "배송 완료 시각 없음 | delivery-completion | {\"deliveryId\":\"1\"}                                                          | ORDER_158",
            "깨진 JSON           | delivery-completion | {                                                                               | ORDER_100"
        })
        @DisplayName("본문 검증은 155~158 · 형식 오류 100 으로 400 이고 유스케이스를 부르지 않는다")
        void rejectsBody(String caseName, String action, String body, String code) {
            assertFailure(post("/internal/v1/orders/" + ORDER_ID + "/" + action.strip(), body.strip()), HttpStatus.BAD_REQUEST, code.strip());
            verifyNoInteractions(orderInternalUseCase);
        }

        @ParameterizedTest(name = "orderId={0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "0   | ORDER_141",
            "-1  | ORDER_141",
            "abc | ORDER_198"
        })
        @DisplayName("주문 아이디가 양수가 아니면 141, 숫자가 아니면 198 로 400 이다")
        void rejectsOrderId(String orderId, String code) {
            assertFailure(post("/internal/v1/orders/" + orderId.strip() + "/shipment", SHIPMENT_BODY), HttpStatus.BAD_REQUEST, code.strip());
            verifyNoInteractions(orderInternalUseCase);
        }
    }
}
