package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderHistoryWebUseCase;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 고객 주문 내역 · 상세 · 결제 전 취소 API 의 HTTP 계약 (#16) — 고객만 · 회원은 토큰에서 · 파라미터 · 본문 검증. 테스트 값은 모두 가짜다. */
@WebMvcTest(OrderHistoryWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OrderHistoryWebControllerTest {

    private static final String CANCEL_BODY = "{\"reason\":\"CHANGE_OF_MIND\"}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderHistoryWebUseCase orderHistoryWebUseCase;

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult request(String method, String uri, String body, String authorization) {
        var builder = "GET".equals(method)
            ? mvc.get().uri(uri)
            : mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return builder.exchange();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "GET  | /api/v1/orders",
        "GET  | /api/v1/orders/1",
        "POST | /api/v1/orders/1/cancellation"
    })
    @DisplayName("세 경로 모두 관리자 토큰은 403 SECURITY_005, 토큰 없으면 401 SECURITY_001 — 유스케이스에 닿지 않는다")
    void customerOnly(String method, String uri) {
        assertFailure(request(method.strip(), uri.strip(), CANCEL_BODY, bearer(MemberRole.ADMIN)), HttpStatus.FORBIDDEN, "SECURITY_005");
        assertFailure(request(method.strip(), uri.strip(), CANCEL_BODY, null), HttpStatus.UNAUTHORIZED, "SECURITY_001");
        verifyNoInteractions(orderHistoryWebUseCase);
    }

    @Test
    @DisplayName("목록 — 토큰의 회원 · 페이지 · 크기를 넘기고 SliceResponse 를 그대로 낸다")
    void listUsesTokenMember() {
        given(orderHistoryWebUseCase.getOrders(anyLong(), anyInt(), anyInt())).willReturn(new SliceResponse<>(List.of(), true));

        MvcTestResult result = request("GET", "/api/v1/orders?page=2&size=10", null, bearer(MemberRole.CUSTOMER));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.dataBody.hasNext").isEqualTo(true);
        verify(orderHistoryWebUseCase).getOrders(TestTokens.MEMBER_ID, 2, 10);
    }

    @Test
    @DisplayName("상세 · 취소 — 토큰의 회원과 경로의 주문 아이디, 본문의 사유를 넘긴다")
    void detailAndCancelUseTokenMember() {
        OrderDetailResponse detail = OrderDetailResponse.builder().orderId("9").build();
        given(orderHistoryWebUseCase.getOrder(TestTokens.MEMBER_ID, 9L)).willReturn(detail);
        given(orderHistoryWebUseCase.cancel(TestTokens.MEMBER_ID, 9L, OrderCancelReason.CHANGE_OF_MIND)).willReturn(detail);

        assertThat(request("GET", "/api/v1/orders/9", null, bearer(MemberRole.CUSTOMER))).hasStatus(HttpStatus.OK);
        MvcTestResult canceled = request("POST", "/api/v1/orders/9/cancellation", CANCEL_BODY, bearer(MemberRole.CUSTOMER));

        assertThat(canceled).hasStatus(HttpStatus.OK);
        assertThat(canceled).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("9");
        verify(orderHistoryWebUseCase).cancel(eq(TestTokens.MEMBER_ID), eq(9L), eq(OrderCancelReason.CHANGE_OF_MIND));
    }

    @Test
    @DisplayName("결제 뒤 · 만료 주문의 취소 거절은 409 ORDER_020, 남의 주문은 404 ORDER_001 이다")
    void cancelFailures() {
        given(orderHistoryWebUseCase.cancel(TestTokens.MEMBER_ID, 9L, OrderCancelReason.CHANGE_OF_MIND))
            .willThrow(new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, "결제 완료"));
        given(orderHistoryWebUseCase.cancel(TestTokens.MEMBER_ID, 10L, OrderCancelReason.CHANGE_OF_MIND))
            .willThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));

        assertFailure(request("POST", "/api/v1/orders/9/cancellation", CANCEL_BODY, bearer(MemberRole.CUSTOMER)), HttpStatus.CONFLICT, "ORDER_020");
        assertFailure(request("POST", "/api/v1/orders/10/cancellation", CANCEL_BODY, bearer(MemberRole.CUSTOMER)), HttpStatus.NOT_FOUND, "ORDER_001");
    }

    @Test
    void purchaseConfirmationUsesAuthenticatedMemberAndHidesOtherOrders() {
        OrderDetailResponse detail = OrderDetailResponse.builder().orderId("9").build();
        given(orderHistoryWebUseCase.confirmPurchase(TestTokens.MEMBER_ID, 9L)).willReturn(detail);
        var customer = mvc.patch().uri("/api/v1/orders/9/purchase-confirmation")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange();
        assertThat(customer).hasStatus(HttpStatus.OK);
        assertThat(customer).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("9");
        verify(orderHistoryWebUseCase).confirmPurchase(TestTokens.MEMBER_ID, 9L);

        var admin = mvc.patch().uri("/api/v1/orders/9/purchase-confirmation")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertFailure(admin, HttpStatus.FORBIDDEN, "SECURITY_005");

        given(orderHistoryWebUseCase.confirmPurchase(TestTokens.MEMBER_ID, 10L))
            .willThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        var other = mvc.patch().uri("/api/v1/orders/10/purchase-confirmation")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange();
        assertFailure(other, HttpStatus.NOT_FOUND, "ORDER_001");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "페이지 음수   | GET  | /api/v1/orders?page=-1             | -                                | ORDER_144",
        "크기 101      | GET  | /api/v1/orders?size=101            | -                                | ORDER_145",
        "크기 0        | GET  | /api/v1/orders?size=0              | -                                | ORDER_145",
        "주문 아이디 0 | GET  | /api/v1/orders/0                   | -                                | ORDER_141",
        "사유 없음     | POST | /api/v1/orders/9/cancellation      | {}                               | ORDER_154",
        "모르는 사유   | POST | /api/v1/orders/9/cancellation      | {\"reason\":\"WHATEVER\"}       | ORDER_100"
    })
    void validation(String caseName, String method, String uri, String body, String code) {
        assertFailure(request(method.strip(), uri.strip(), body.strip(), bearer(MemberRole.CUSTOMER)), HttpStatus.BAD_REQUEST, code.strip());
        verifyNoInteractions(orderHistoryWebUseCase);
    }
}
