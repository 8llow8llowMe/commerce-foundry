package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MemberDisplayNameInternalItem;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MemberDisplayNameInternalUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 회원 표시 이름 조회의 HTTP 계약 — 허용 호출자와 {@code ids} 검증 코드.
 * 서비스 토큰 검증 · 익명 401 은 인가 스모크가 본다.
 */
@WebMvcTest(MemberDisplayNameInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class MemberDisplayNameInternalControllerTest {

    private static final String PATH = "/internal/v1/member-display-names";
    private static final String DELIVERY_SERVICE = "SERVICE_delivery-service";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private MemberDisplayNameInternalUseCase memberDisplayNameInternalUseCase;

    @Test
    @WithMockUser(authorities = DELIVERY_SERVICE)
    @DisplayName("delivery-service 면 200 이고 마스킹된 이름만 나간다 — 이름이 없으면 키를 빼지 않고 null")
    void deliveryServiceIsAllowed() {
        given(memberDisplayNameInternalUseCase.getDisplayNames(List.of(2L, 1L))).willReturn(MemberDisplayNameInternalResponse.builder()
            .members(List.of(
                MemberDisplayNameInternalItem.builder().memberId("2").displayName("홍*동").build(),
                MemberDisplayNameInternalItem.builder().memberId("1").displayName(null).build()
            )).build());

        MvcTestResult result = mvc.get().uri(PATH).param("ids", "2,1").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[0].memberId").isEqualTo("2");
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[0].displayName").isEqualTo("홍*동");
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[1].displayName").isNull();
        verify(memberDisplayNameInternalUseCase).getDisplayNames(List.of(2L, 1L));
    }

    @Test
    @WithMockUser(authorities = "SERVICE_order-service")
    @DisplayName("다른 서비스면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void otherServiceIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).param("ids", "1").exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(memberDisplayNameInternalUseCase);
    }

    @Nested
    @DisplayName("ids 검증")
    @WithMockUser(authorities = DELIVERY_SERVICE)
    class IdsValidation {

        @Test
        @DisplayName("파라미터가 없으면 400 · AUTH_199")
        void missing() {
            assertBadRequest(mvc.get().uri(PATH).exchange(), "AUTH_199");
        }

        @Test
        @DisplayName("빈 목록이면 400 · AUTH_137")
        void empty() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "").exchange(), "AUTH_137");
        }

        @Test
        @DisplayName("101개면 400 · AUTH_138")
        void tooMany() {
            String ids = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(",")) + ",1";
            assertBadRequest(mvc.get().uri(PATH).param("ids", ids).exchange(), "AUTH_138");
        }

        @Test
        @DisplayName("0 · 음수 · 빈 원소는 400 · AUTH_139, 숫자가 아니면 400 · AUTH_198")
        void rejectedElements() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "0").exchange(), "AUTH_139");
            assertBadRequest(mvc.get().uri(PATH).param("ids", "1,-3").exchange(), "AUTH_139");
            assertBadRequest(mvc.get().uri(PATH).param("ids", "1,,2").exchange(), "AUTH_139");
            assertBadRequest(mvc.get().uri(PATH).param("ids", "a").exchange(), "AUTH_198");
        }

        private void assertBadRequest(MvcTestResult result, String code) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(memberDisplayNameInternalUseCase);
        }
    }
}
