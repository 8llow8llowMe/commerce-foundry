package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.AdminLoginRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Duration;
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
 * 관리자 로그인 API 의 HTTP 계약 (#6) — 익명 공개, access 만 있는 응답 · {@code Cache-Control: no-store}, 검증 코드(130~), 실패 코드(030).
 */
@WebMvcTest(AdminAuthWebController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AdminAuthWebControllerTest {

    private static final String LOGIN = "/api/v1/auth/admin/login";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminAuthWebUseCase adminAuthWebUseCase;

    @Test
    @DisplayName("익명으로 200 이고 access 두 칸만 주며(refresh 없음) Cache-Control: no-store 다 — 요청은 그대로 커맨드가 된다")
    void ok() {
        given(adminAuthWebUseCase.login(any())).willReturn(AdminLoginResponse.builder().accessToken("access.jwt.value").accessTokenExpiresIn(1800).build());

        MvcTestResult result = login("{\"email\":\"Admin@Example.com\",\"password\":\"admin-password-1\"}");

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.accessToken").isEqualTo("access.jwt.value");
        assertThat(result).bodyJson().extractingPath("$.dataBody.accessTokenExpiresIn").asNumber().isEqualTo(1800);
        assertThat(result).bodyJson().doesNotHavePath("$.dataBody.refreshToken");
        assertThat(result).bodyJson().doesNotHavePath("$.dataBody.refreshTokenExpiresIn");
        verify(adminAuthWebUseCase).login(new AdminLoginCommand("Admin@Example.com", "admin-password-1"));
    }

    @Test
    @DisplayName("고객 토큰을 붙여 와도 막지 않는다(permitAll) — 판정은 이메일 · 비밀번호다")
    void customerTokenIsNotRejected() {
        given(adminAuthWebUseCase.login(any())).willReturn(AdminLoginResponse.builder().accessToken("access.jwt.value").accessTokenExpiresIn(1800).build());
        String customer = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        MvcTestResult result = mvc.post().uri(LOGIN).header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@example.com\",\"password\":\"admin-password-1\"}").exchange();

        assertThat(result).hasStatusOk();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "이메일 없음   | {\"password\":\"x\"}                                  | AUTH_130 | email",
        "이메일 공백   | {\"email\":\"  \",\"password\":\"x\"}                 | AUTH_130 | email",
        "비밀번호 없음 | {\"email\":\"admin@example.com\"}                     | AUTH_132 | password",
        "비밀번호 공백 | {\"email\":\"admin@example.com\",\"password\":\"  \"} | AUTH_132 | password"
    })
    @DisplayName("필드 검증은 400 · 관리자 대역 코드다 — 비밀번호 형식은 보지 않는다")
    void invalid(String name, String body, String code, String field) {
        MvcTestResult result = login(body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(adminAuthWebUseCase);
    }

    @Test
    @DisplayName("255자 이메일은 400 · AUTH_131 이다")
    void emailTooLong() {
        String email = "a".repeat(243) + "@example.com";

        assertThat(login("{\"email\":\"%s\",\"password\":\"x\"}".formatted(email))).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_131");
        verifyNoInteractions(adminAuthWebUseCase);
    }

    @Test
    @DisplayName("로그인 실패는 400 · AUTH_030 이고 문구는 고객 로그인 실패와 같다")
    void failed() {
        given(adminAuthWebUseCase.login(any())).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        MvcTestResult result = login("{\"email\":\"admin@example.com\",\"password\":\"wrong-pass1\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_030");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(MemberErrorCode.LOGIN_FAILED.getMessage());
    }

    @Test
    @DisplayName("시도 초과는 고객 로그인과 같은 429 · AUTH_040 봉투 + Retry-After 다")
    void attemptLimited() {
        given(adminAuthWebUseCase.login(any())).willThrow(new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofSeconds(899).plusMillis(1)));

        MvcTestResult result = login("{\"email\":\"admin@example.com\",\"password\":\"admin-password-1\"}");

        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_040");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(result).bodyJson().doesNotHavePath("$.dataBody.accessToken");
    }

    @Test
    @DisplayName("요청 · 응답 DTO 의 toString 에 이메일 · 비밀번호 · 토큰 원문이 없다")
    void toStringHidesSecrets() {
        assertThat(new AdminLoginRequest("admin@example.com", "admin-password-1").toString()).doesNotContain("admin@example.com", "admin-password-1");
        assertThat(AdminLoginResponse.builder().accessToken("access.jwt.value").accessTokenExpiresIn(1800).build().toString()).doesNotContain("access.jwt.value");
    }

    private MvcTestResult login(String body) {
        return mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
