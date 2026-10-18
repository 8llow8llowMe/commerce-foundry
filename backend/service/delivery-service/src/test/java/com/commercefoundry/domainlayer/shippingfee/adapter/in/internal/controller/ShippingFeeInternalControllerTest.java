package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeInternalUseCase;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 배송비 견적의 HTTP 계약 — 허용 호출자와 본문 검증 코드, 도메인 오류 봉투.
 *
 * <p>{@code /internal/**} 은 internal 체인({@link InternalResourceServerConfigurer})이 받는다 — 사용자 체인은 그 경로를 막으므로 두 Configurer 를
 * 함께 올린다. 서비스 토큰을 만들지 않고 인증 주체를 {@code @WithMockUser} 로 넣어, 체인이 아니라 핸들러의 허용 호출자({@code @PreAuthorize})를 본다.
 * 실제 서비스 토큰으로 부르는 경로는 {@code DeliveryServiceSecuritySmokeTest} 가 본다.
 */
@WebMvcTest(ShippingFeeInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class ShippingFeeInternalControllerTest {

    private static final String PATH = "/internal/v1/shipping-fees/quotes";
    private static final String ORDER_SERVICE = "SERVICE_order-service";
    private static final String VALID_BODY = """
        {"orderAmount": {"amount": 99000, "currency": "KRW"}, "countryCode": "KR", "postalCode": "63100"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShippingFeeInternalUseCase shippingFeeInternalUseCase;

    private MvcTestResult post(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("order-service 권한이면 200 이고 본문을 command 로 넘긴다")
        void orderServiceIsAllowed() {
            given(shippingFeeInternalUseCase.quote(any())).willReturn(ShippingFeeQuoteInternalResponse.builder()
                .shippingFee(MoneyItem.of(6_000, "KRW"))
                .baseFee(MoneyItem.of(3_000, "KRW"))
                .freeShippingApplied(false)
                .remoteAreaSurcharge(MoneyItem.of(3_000, "KRW"))
                .remoteAreaName("제주")
                .freeShippingThreshold(MoneyItem.of(100_000, "KRW"))
                .shippingPolicyVersion(3L)
                .build());

            MvcTestResult result = post(VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.shippingFee.amount").asNumber().isEqualTo(6_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.freeShippingApplied").asBoolean().isFalse();
            assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreaName").isEqualTo("제주");
            assertThat(result).bodyJson().extractingPath("$.dataBody.freeShippingThreshold.amount").asNumber().isEqualTo(100_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.shippingPolicyVersion").asNumber().isEqualTo(3);
            verify(shippingFeeInternalUseCase).quote(new ShippingFeeQuoteCommand(Money.of(99_000, CurrencyCode.KRW), "KR", new PostalCode("63100")));
        }

        @Test
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("다른 서비스 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shippingFeeInternalUseCase);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("회원 권한(ROLE_ADMIN)이어도 403 · SECURITY_005 다")
        void memberIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shippingFeeInternalUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류")
    @WithMockUser(authorities = ORDER_SERVICE)
    class DomainErrors {

        @Test
        @DisplayName("정책 없음은 409 · DELIVERY_001")
        void policyNotConfigured() {
            given(shippingFeeInternalUseCase.quote(any())).willThrow(new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED));

            assertError(post(VALID_BODY), HttpStatus.CONFLICT, "DELIVERY_001");
        }

        @Test
        @DisplayName("배송할 수 없는 국가는 400 · DELIVERY_002")
        void destinationNotSupported() {
            given(shippingFeeInternalUseCase.quote(any())).willThrow(new DeliveryException(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED));

            assertError(post(VALID_BODY.replace("\"KR\"", "\"US\"")), HttpStatus.BAD_REQUEST, "DELIVERY_002");
        }

        @Test
        @DisplayName("통화 불일치는 400 · DELIVERY_003")
        void currencyMismatch() {
            given(shippingFeeInternalUseCase.quote(any())).willThrow(new DeliveryException(DeliveryErrorCode.CURRENCY_MISMATCH));

            assertError(post(VALID_BODY.replace("KRW", "USD")), HttpStatus.BAD_REQUEST, "DELIVERY_003");
        }

        private void assertError(MvcTestResult result, HttpStatus status, String code) {
            assertThat(result).hasStatus(status);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(authorities = ORDER_SERVICE)
    class BodyValidation {

        @ParameterizedTest(name = "{0} → {1} ({2})")
        @CsvSource(delimiter = '|', value = {
            "{\"countryCode\": \"KR\", \"postalCode\": \"63100\"}                                                     | DELIVERY_101 | orderAmount",
            "{\"orderAmount\": {\"currency\": \"KRW\"}, \"countryCode\": \"KR\", \"postalCode\": \"63100\"}           | DELIVERY_102 | orderAmount.amount",
            "{\"orderAmount\": {\"amount\": -1, \"currency\": \"KRW\"}, \"countryCode\": \"KR\", \"postalCode\": \"63100\"} | DELIVERY_103 | orderAmount.amount",
            "{\"orderAmount\": {\"amount\": 1000}, \"countryCode\": \"KR\", \"postalCode\": \"63100\"}               | DELIVERY_104 | orderAmount.currency",
            "{\"orderAmount\": {\"amount\": 1000, \"currency\": \"KRW\"}, \"postalCode\": \"63100\"}                 | DELIVERY_105 | countryCode",
            "{\"orderAmount\": {\"amount\": 1000, \"currency\": \"KRW\"}, \"countryCode\": \"kr\", \"postalCode\": \"63100\"} | DELIVERY_106 | countryCode",
            "{\"orderAmount\": {\"amount\": 1000, \"currency\": \"KRW\"}, \"countryCode\": \"KR\"}                    | DELIVERY_107 | postalCode",
            "{\"orderAmount\": {\"amount\": 1000, \"currency\": \"KRW\"}, \"countryCode\": \"KR\", \"postalCode\": \"6310\"} | DELIVERY_108 | postalCode",
            "{\"orderAmount\": {\"amount\": 1000, \"currency\": \"JPY\"}, \"countryCode\": \"KR\", \"postalCode\": \"63100\"} | DELIVERY_100 | orderAmount.currency",
            "{\"orderAmount\":                                                                                       | DELIVERY_100 | request"
        })
        @DisplayName("필드별 검증 코드로 400 이고 유스케이스를 부르지 않는다")
        void rejects(String body, String code, String field) {
            MvcTestResult result = post(body);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(shippingFeeInternalUseCase);
        }

        /** 인증을 통과한 호출자 기준으로 본문 검증이 {@code @PreAuthorize} 보다 먼저 돈다 — 컨트롤러 주석이 이 순서를 적는다. */
        @Test
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("현재 동작 기록 — 허용되지 않은 호출자도 본문이 틀리면 403 이 아니라 400 이다")
        void validationRunsBeforeAuthorization() {
            assertThat(post("{}")).hasStatus(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(shippingFeeInternalUseCase);
        }
    }
}
