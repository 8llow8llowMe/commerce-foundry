package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingPolicyFactInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.OnboardingFactInternalUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 운영자 온보딩 사실(#357)의 HTTP 계약 — 허용 호출자, {@code memberId} 검증 코드, 응답 모양.
 *
 * <p>{@link ShippingFeeInternalControllerTest} 와 같이 internal · 사용자 Configurer 를 함께 올리고 인증 주체를 {@code @WithMockUser} 로 넣는다.
 * 실제 서비스 토큰으로 부르는 경로는 {@code DeliveryServiceSecuritySmokeTest} 가 본다.
 */
@WebMvcTest(OnboardingFactInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class OnboardingFactInternalControllerTest {

    private static final String PATH = "/internal/v1/onboarding-facts/shipping-policy";
    private static final String OPERATIONS_SERVICE = "SERVICE_operations-service";
    private static final long MEMBER_ID = 4611686018427387904L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OnboardingFactInternalUseCase onboardingFactInternalUseCase;

    private MvcTestResult get(String query) {
        return mvc.get().uri(PATH + query).exchange();
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @WithMockUser(authorities = OPERATIONS_SERVICE)
        @DisplayName("operations-service 권한이면 200 이고 storeReady · memberFirstSavedAt(UTC ISO)을 낸다")
        void operationsServiceIsAllowed() {
            given(onboardingFactInternalUseCase.getShippingPolicyFact(MEMBER_ID))
                .willReturn(new ShippingPolicyFactInternalResponse(true, Instant.parse("2026-10-18T03:00:00Z")));

            MvcTestResult result = get("?memberId=" + MEMBER_ID);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.storeReady").asBoolean().isTrue();
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberFirstSavedAt").isEqualTo("2026-10-18T03:00:00Z");
        }

        @Test
        @WithMockUser(authorities = OPERATIONS_SERVICE)
        @DisplayName("저장한 적이 없으면 memberFirstSavedAt 키를 빼지 않고 null 로 낸다")
        void neverSavedIsNull() {
            given(onboardingFactInternalUseCase.getShippingPolicyFact(MEMBER_ID)).willReturn(new ShippingPolicyFactInternalResponse(false, null));

            MvcTestResult result = get("?memberId=" + MEMBER_ID);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.storeReady").asBoolean().isFalse();
            assertThat(result).bodyJson().hasPathSatisfying("$.dataBody.memberFirstSavedAt", value -> assertThat(value).isNull());
        }

        @Test
        @DisplayName("인증 주체(서비스 토큰)가 없으면 401 · SECURITY_007 이고 유스케이스를 부르지 않는다")
        void withoutServiceTokenIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(get("?memberId=" + MEMBER_ID).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(onboardingFactInternalUseCase);
        }

        @Test
        @WithMockUser(authorities = "SERVICE_order-service")
        @DisplayName("다른 서비스 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get("?memberId=" + MEMBER_ID).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(onboardingFactInternalUseCase);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("회원 권한(ROLE_ADMIN)이어도 403 · SECURITY_005 다")
        void memberIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get("?memberId=" + MEMBER_ID).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(onboardingFactInternalUseCase);
        }
    }

    @Nested
    @DisplayName("memberId 검증")
    @WithMockUser(authorities = OPERATIONS_SERVICE)
    class MemberIdValidation {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "?memberId=0   | DELIVERY_139",
            "?memberId=-1  | DELIVERY_139",
            "?memberId=abc | DELIVERY_198",
            "''            | DELIVERY_199"
        })
        @DisplayName("양수가 아니면 139, 숫자가 아니면 198, 없으면 199 로 400 이고 유스케이스를 부르지 않는다")
        void rejects(String query, String code) {
            MvcTestResult result = get(query);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("memberId");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(onboardingFactInternalUseCase);
        }
    }
}
