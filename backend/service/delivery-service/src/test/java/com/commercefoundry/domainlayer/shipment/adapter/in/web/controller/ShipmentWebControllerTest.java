package com.commercefoundry.domainlayer.shipment.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentWebUseCase;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 배송 조회 API (#18 PR-B2) — CUSTOMER 전용 · 회원은 토큰 주체 · 없으면 200 + null · 파라미터 검증 코드. 실제 테스트 키로 서명한 access 토큰을 보낸다.
 */
@WebMvcTest(ShipmentWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ShipmentWebControllerTest {

    private static final String PATH = "/api/v1/deliveries";
    private static final long ORDER_ID = 4611686018427387904L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShipmentWebUseCase shipmentWebUseCase;

    private MvcTestResult get(String query, MemberRole role) {
        return mvc.get().uri(PATH + query).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    @Test
    @DisplayName("토큰의 memberId 로 주문의 배송 정보를 읽고, 아이디는 문자열 · 코드 값은 code · name · description 이다")
    void returnsShipment() {
        given(shipmentWebUseCase.getOrderShipment(ORDER_ID, TestTokens.MEMBER_ID)).willReturn(ShipmentResponse.builder()
            .deliveryId("4611686018427387906").orderId(String.valueOf(ORDER_ID)).carrier(CarrierCode.LOTTE.toMetadata()).trackingNumber("L-1")
            .status(ShipmentStatus.IN_TRANSIT.toMetadata()).shippedAt(Instant.parse("2026-11-02T09:30:00Z")).build());

        MvcTestResult result = get("?orderId=" + ORDER_ID, MemberRole.CUSTOMER);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.deliveryId").asString().isEqualTo("4611686018427387906");
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").asString().isEqualTo("IN_TRANSIT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.carrier.name").asString().isEqualTo("롯데택배");
        assertThat(result).bodyJson().extractingPath("$.dataBody.deliveredAt").isNull();
    }

    @Test
    @DisplayName("송장이 없거나 남의 주문 · 출고 반영 전이면 200 + dataBody null 이다(404 를 내지 않는다)")
    void absentIsNull() {
        MvcTestResult result = get("?orderId=" + ORDER_ID, MemberRole.CUSTOMER);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("ADMIN 토큰은 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void adminIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(get("?orderId=" + ORDER_ID, MemberRole.ADMIN).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(shipmentWebUseCase);
    }

    @Test
    @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH + "?orderId=1").exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
        "orderId 없음 | '' | DELIVERY_199",
        "orderId 숫자 아님 | ?orderId=abc | DELIVERY_198",
        "orderId 0 | ?orderId=0 | DELIVERY_142"
    }, delimiter = '|')
    @DisplayName("파라미터 오류는 400 과 코드이고 유스케이스를 부르지 않는다")
    void rejects(String name, String query, String code) {
        MvcTestResult result = get(query, MemberRole.CUSTOMER);

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").asString().isEqualTo(code);
        verifyNoInteractions(shipmentWebUseCase);
    }
}
