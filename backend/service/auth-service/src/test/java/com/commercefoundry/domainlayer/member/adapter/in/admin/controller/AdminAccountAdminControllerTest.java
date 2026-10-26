package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 가게 운영자 계정의 HTTP 계약 (#193). 상품을 파는 사업자의 직원 계정이다. 개발자 콘솔이 아니다.
 */
@WebMvcTest(AdminAccountAdminController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AdminAccountAdminControllerTest {

    private static final String CREATE = "/api/v1/admin/admins";
    private static final String PASSWORD = "/api/v1/admin/me/password";
    private static final String CREATE_BODY = """
        {"email":"staff@example.com","password":"shop-staff-2026","name":"김운영"}
        """;
    private static final String PASSWORD_BODY = """
        {"currentPassword":"current-password1","newPassword":"next-password-2026"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminAccountUseCase adminAccountUseCase;

    @Test
    @DisplayName("ADMIN 이 운영자를 추가하면 201 이고 memberId 는 문자열이다")
    void adminCreatesAccount() {
        given(adminAccountUseCase.createAdmin(TestTokens.MEMBER_ID, "staff@example.com", "shop-staff-2026", "김운영"))
            .willReturn(AdminAccountCreatedResponse.builder().memberId(String.valueOf(TestTokens.MEMBER_ID)).build());

        MvcTestResult result = post(CREATE, CREATE_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo(String.valueOf(TestTokens.MEMBER_ID));
    }

    @Test
    @DisplayName("ADMIN 이 다른 운영자를 끄면 200 이고 dataBody 는 없다")
    void adminDeactivates() {
        MvcTestResult result = patch("/api/v1/admin/admins/" + TestTokens.MEMBER_ID, null, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verify(adminAccountUseCase).deactivateAdmin(eq(TestTokens.MEMBER_ID), eq(TestTokens.MEMBER_ID), any());
    }

    @Test
    @DisplayName("ADMIN 이 본인 비밀번호를 바꾸면 200 이다. 행위자는 토큰의 memberId 다")
    void adminChangesOwnPassword() {
        MvcTestResult result = patch(PASSWORD, PASSWORD_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        verify(adminAccountUseCase).changePassword(eq(TestTokens.MEMBER_ID), any(), eq("current-password1"), eq("next-password-2026"));
    }

    @Test
    @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void customerIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(post(CREATE, CREATE_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(adminAccountUseCase);
    }

    @Test
    @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(CREATE).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY).exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(adminAccountUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "이메일 형식 | {\"email\":\"not-an-email\",\"password\":\"shop-staff-2026\",\"name\":\"김운영\"} | AUTH_140 | email",
        "비밀번호 짧음 | {\"email\":\"staff@example.com\",\"password\":\"short1\",\"name\":\"김운영\"} | AUTH_141 | password",
        "이름 없음 | {\"email\":\"staff@example.com\",\"password\":\"shop-staff-2026\",\"name\":\"  \"} | AUTH_143 | name"
    })
    @DisplayName("추가 요청이 틀리면 400 · 필드별 코드이고 유스케이스를 부르지 않는다")
    void invalidCreate(String caseName, String body, String code, String field) {
        MvcTestResult result = post(CREATE, body, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(adminAccountUseCase);
    }

    @Test
    @DisplayName("운영자 아이디가 0 이면 400 · AUTH_146 이다")
    void nonPositiveAdminId() {
        MvcTestResult result = patch("/api/v1/admin/admins/0", null, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_146");
        verifyNoInteractions(adminAccountUseCase);
    }

    private MvcTestResult post(String uri, String json, MemberRole role) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult patch(String uri, String json, MemberRole role) {
        var request = mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String bearer(MemberRole role) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        return "Bearer " + token;
    }
}
