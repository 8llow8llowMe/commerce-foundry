package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpPeerRecoveryUseCase;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 동료 OTP 복구 API의 권한·요청·비밀 응답 계약. */
@WebMvcTest(AdminOtpPeerRecoveryAdminController.class)
@Import({JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class AdminOtpPeerRecoveryAdminControllerTest {

    private static final String PATH = "/api/v1/admin/admins/9/otp/recovery";
    private static final String BODY = """
        {"currentPassword":"current-password1","code":"ABCDE-FGH23"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminOtpPeerRecoveryUseCase useCase;

    @Test
    @DisplayName("ADMIN은 JWT 행위자 ID와 경로 대상 ID를 넘기고 새 코드만 no-store 응답으로 받는다")
    void success() {
        given(useCase.recover(TestTokens.MEMBER_ID, 9L, "current-password1", "ABCDE-FGH23"))
            .willReturn(AdminOtpRecoveryCodesResponse.builder().recoveryCodes(List.of("JKLMP-QRST2")).build());

        MvcTestResult result = post(PATH, BODY, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.recoveryCodes[0]").isEqualTo("JKLMP-QRST2");
        verify(useCase).recover(TestTokens.MEMBER_ID, 9L, "current-password1", "ABCDE-FGH23");
    }

    @Test
    @DisplayName("CUSTOMER와 비로그인은 403/401이며 유스케이스를 호출하지 않는다")
    void requiresAdmin() {
        SecurityEnvelopeAssertions.assertEnvelope(post(PATH, BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("대상 ID와 현재 비밀번호·코드 검증 오류는 400 필드 코드이고 유스케이스를 호출하지 않는다")
    void invalidRequest() {
        MvcTestResult target = post("/api/v1/admin/admins/0/otp/recovery", BODY, MemberRole.ADMIN);
        MvcTestResult password = post(PATH, "{\"currentPassword\":\" \",\"code\":\"123456\"}", MemberRole.ADMIN);
        MvcTestResult code = post(PATH, "{\"currentPassword\":\"current-password1\",\"code\":\"ABCDE-FGH2O\"}", MemberRole.ADMIN);

        assertThat(target).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(target).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_146");
        assertThat(password).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_145");
        assertThat(code).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_149");
        verifyNoInteractions(useCase);
    }

    private MvcTestResult post(String path, String body, MemberRole role) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
