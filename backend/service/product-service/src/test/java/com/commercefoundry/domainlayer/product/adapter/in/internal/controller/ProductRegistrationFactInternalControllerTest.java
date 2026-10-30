package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.ProductRegistrationFactInternalResponse;
import com.commercefoundry.domainlayer.product.application.port.in.ProductRegistrationFactInternalUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 상품 등록 사실 internal API 의 허용 호출자, 응답 봉투, memberId 검증 계약. */
@WebMvcTest(ProductRegistrationFactInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class ProductRegistrationFactInternalControllerTest {

    private static final String PATH = "/internal/v1/onboarding-facts/product-registration";
    private static final long MEMBER_ID = 4611686018427387904L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductRegistrationFactInternalUseCase useCase;

    private MvcTestResult get(String query) {
        return mvc.get().uri(PATH + query).exchange();
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("operations-service 만 상품 사실을 읽고 nullable 최초 등록 시각을 받는다")
    void operationsServiceCanRead() {
        given(useCase.getFact(MEMBER_ID))
            .willReturn(new ProductRegistrationFactInternalResponse(true, Instant.parse("2026-10-01T00:00:00Z")));

        MvcTestResult result = get("?memberId=" + MEMBER_ID);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.storeReady").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.dataBody.memberFirstRegisteredAt").isEqualTo("2026-10-01T00:00:00Z");
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("등록 기록이 없으면 시각 키를 null 로 유지한다")
    void noRegistrationHasNullTimestamp() {
        given(useCase.getFact(MEMBER_ID)).willReturn(new ProductRegistrationFactInternalResponse(false, null));

        MvcTestResult result = get("?memberId=" + MEMBER_ID);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().hasPathSatisfying("$.dataBody.memberFirstRegisteredAt", value -> assertThat(value).isNull());
    }

    @Test
    @DisplayName("서비스 토큰이 없으면 401 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(get("?memberId=" + MEMBER_ID).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }

    @Test
    @WithMockUser(authorities = "SERVICE_order-service")
    @DisplayName("다른 서비스는 403 이다")
    void otherServiceIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(get("?memberId=" + MEMBER_ID).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "?memberId=0   | PRODUCT_140",
        "?memberId=-1  | PRODUCT_140",
        "?memberId=abc | PRODUCT_198",
        "''            | PRODUCT_199"
    })
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("memberId 가 양수가 아니거나 형식이 틀리거나 없으면 400 이다")
    void invalidMemberId(String query, String code) {
        MvcTestResult result = get(query);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }
}
