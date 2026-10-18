package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.RemoteAreaItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneRateItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 배송비 정책 조회의 HTTP 계약. 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다
 * ({@code bootstrap-conventions.md} 보안 스모크 테스트). 요청은 모두 토큰 없이(익명) 보낸다 — 공개 API 다.
 */
@WebMvcTest(ShippingFeeWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ShippingFeeWebControllerTest {

    private static final String PATH = "/api/v1/shipping-fees/policy";
    private static final String INTERNATIONAL_PATH = "/api/v1/shipping-fees/international-policy";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShippingFeeWebUseCase shippingFeeWebUseCase;

    @Test
    @DisplayName("익명으로 200 이고 Response 봉투다 — 금액은 {amount, currency}, 우편번호는 문자열")
    void policyEnvelope() {
        given(shippingFeeWebUseCase.getDomesticPolicy()).willReturn(ShippingPolicyResponse.builder()
            .baseFee(MoneyItem.of(3_000, "KRW"))
            .freeShippingThreshold(MoneyItem.of(50_000, "KRW"))
            .remoteAreas(List.of(RemoteAreaItem.builder().name("제주").postalCodeFrom("63000").postalCodeTo("63644").surcharge(MoneyItem.of(3_000, "KRW")).build()))
            .build());

        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.baseFee.amount").asNumber().isEqualTo(3_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.baseFee.currency").isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.freeShippingThreshold.amount").asNumber().isEqualTo(50_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreas[0].name").isEqualTo("제주");
        assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreas[0].postalCodeFrom").asString().isEqualTo("63000");
        assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreas[0].surcharge.amount").asNumber().isEqualTo(3_000);
        assertThat(result).bodyJson().doesNotHavePath("$.dataBody.version").doesNotHavePath("$.dataBody.shippingPolicyId");
    }

    @Test
    @DisplayName("무료배송 기준이 없으면 freeShippingThreshold 가 null 이다")
    void nullThreshold() {
        given(shippingFeeWebUseCase.getDomesticPolicy())
            .willReturn(ShippingPolicyResponse.builder().baseFee(MoneyItem.of(3_000, "KRW")).remoteAreas(List.of()).build());

        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.freeShippingThreshold").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreas").asArray().isEmpty();
    }

    @Test
    @DisplayName("정책이 아직 없으면 200 이고 dataBody 가 null 이다")
    void policyNotConfigured() {
        given(shippingFeeWebUseCase.getDomesticPolicy()).willReturn(null);

        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("해외 정책: 익명으로 200 — 그룹 · 국가 코드 · 무게 구간 · 안내 문구를 내리고 아이디 · 버전은 없다")
    void internationalPolicyEnvelope() {
        given(shippingFeeWebUseCase.getInternationalPolicy()).willReturn(InternationalShippingPolicyResponse.builder()
            .baseFee(MoneyItem.of(500, "USD"))
            .freeShippingThreshold(MoneyItem.of(20_000, "USD"))
            .customsNotice("관세는 받는 분 부담")
            .zones(List.of(ShippingZoneItem.builder()
                .name("아시아")
                .countryCodes(List.of("JP", "SG"))
                .rates(List.of(ShippingZoneRateItem.builder().maxWeightGrams(500).fee(MoneyItem.of(1_000, "USD")).build()))
                .build()))
            .build());

        MvcTestResult result = mvc.get().uri(INTERNATIONAL_PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.baseFee.currency").isEqualTo("USD");
        assertThat(result).bodyJson().extractingPath("$.dataBody.customsNotice").isEqualTo("관세는 받는 분 부담");
        assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].name").isEqualTo("아시아");
        assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].countryCodes[1]").isEqualTo("SG");
        assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].rates[0].maxWeightGrams").asNumber().isEqualTo(500);
        assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].rates[0].fee.amount").asNumber().isEqualTo(1_000);
        assertThat(result).bodyJson().doesNotHavePath("$.dataBody.version").doesNotHavePath("$.dataBody.shippingPolicyId");
    }

    @Test
    @DisplayName("해외 정책: 해외 배송이 꺼졌거나 정책이 없으면 200 이고 dataBody 가 null 이다")
    void internationalPolicyNotOffered() {
        given(shippingFeeWebUseCase.getInternationalPolicy()).willReturn(null);

        MvcTestResult result = mvc.get().uri(INTERNATIONAL_PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
