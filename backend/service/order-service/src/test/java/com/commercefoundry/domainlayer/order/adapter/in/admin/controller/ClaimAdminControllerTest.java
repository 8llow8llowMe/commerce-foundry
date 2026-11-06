package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 클레임 조회 API 의 인가 계약 (#587) — 클레임 조회는 주문 업무라 {@code AdminAuthority.ORDER_READ} 다. 테스트 값은 모두 가짜다.
 * 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(ClaimAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ClaimAdminControllerTest {

    private static final String PATH = "/api/v1/admin/claims";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ClaimAdminUseCase claimAdminUseCase;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult getClaims(MemberRole role) {
        return mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("클레임 목록은 ORDER_READ 라 ADMIN · OWNER · MANAGER · STAFF 모두 200 이고 관리자 아이디는 토큰에서 온다 (#587)")
    void orderReadersMayListClaims(MemberRole role) {
        given(claimAdminUseCase.getClaims(any(), anyLong())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        assertThat(getClaims(role)).hasStatusOk();

        verify(claimAdminUseCase).getClaims(any(ClaimAdminFilter.class), eq(TestTokens.MEMBER_ID));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("클레임 상세도 ORDER_READ 라 ADMIN · OWNER · MANAGER · STAFF 모두 유스케이스에 닿고 관리자 아이디는 토큰에서 온다 (#587)")
    void orderReadersMayReadClaim(MemberRole role) {
        assertThat(mvc.get().uri(PATH + "/1").header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();

        verify(claimAdminUseCase).getClaim(1L, TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("고객 토큰은 목록 · 상세 모두 403 · SECURITY_005, 토큰 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void customerAndAnonymousMayNotListClaims() {
        for (String uri : List.of(PATH, PATH + "/1")) {
            SecurityEnvelopeAssertions.assertEnvelope(
                mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(uri).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        }
        verifyNoInteractions(claimAdminUseCase);
    }
}
