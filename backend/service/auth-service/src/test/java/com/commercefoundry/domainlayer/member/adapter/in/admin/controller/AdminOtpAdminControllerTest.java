package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpEnrollmentResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpStatusResponse;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/** 관리자 본인 OTP 의 HTTP 계약 (#238 PR-A2) — 권한 · 응답 형식 · no-store · 요청 검증 · 오류 봉투. */
@WebMvcTest(AdminOtpAdminController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AdminOtpAdminControllerTest {

    private static final String BASE = "/api/v1/admin/me/otp";
    private static final String CONFIRM_BODY = """
        {"currentPassword":"current-password1","code":"123456"}
        """;
    private static final String REAUTH_BODY = """
        {"currentPassword":"current-password1","code":"abcde-fgh23"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminOtpUseCase adminOtpUseCase;

    @Test
    @DisplayName("상태 — 200 {enrolled, confirmedAt, remainingRecoveryCodes}")
    void status() {
        given(adminOtpUseCase.getStatus(TestTokens.MEMBER_ID)).willReturn(AdminOtpStatusResponse.builder()
            .enrolled(true).confirmedAt(Instant.parse("2026-10-29T03:00:00Z")).remainingRecoveryCodes(7).build());

        MvcTestResult result = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.enrolled").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.confirmedAt").isEqualTo("2026-10-29T03:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.dataBody.remainingRecoveryCodes").isEqualTo(7);
    }

    @Test
    @DisplayName("등록 시작 — 200 {secret, otpauthUri, expiresAt} · Cache-Control: no-store")
    void startEnrollment() {
        given(adminOtpUseCase.startEnrollment(TestTokens.MEMBER_ID)).willReturn(AdminOtpEnrollmentResponse.builder()
            .secret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").otpauthUri("otpauth://totp/Shop:a%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            .expiresAt(Instant.parse("2026-10-29T03:15:00Z")).build());

        MvcTestResult result = post(BASE + "/enrollment", null, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.secret").isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(result).bodyJson().extractingPath("$.dataBody.expiresAt").isEqualTo("2026-10-29T03:15:00Z");
    }

    @Test
    @DisplayName("등록 확인 — 201 {recoveryCodes} · no-store. 행위자는 토큰의 memberId 다")
    void confirm() {
        given(adminOtpUseCase.confirmEnrollment(TestTokens.MEMBER_ID, "current-password1", "123456"))
            .willReturn(AdminOtpRecoveryCodesResponse.builder().recoveryCodes(List.of("ABCDE-FGH23", "JKLMN-PQR45")).build());

        MvcTestResult result = post(BASE, CONFIRM_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.recoveryCodes[1]").isEqualTo("JKLMN-PQR45");
    }

    @Test
    @DisplayName("해제 — POST /deactivation 200 · dataBody null")
    void disable() {
        MvcTestResult result = post(BASE + "/deactivation", REAUTH_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verify(adminOtpUseCase).disable(TestTokens.MEMBER_ID, "current-password1", "abcde-fgh23");
    }

    @Test
    @DisplayName("복구 코드 재발급 — 200 {recoveryCodes} · no-store")
    void regenerate() {
        given(adminOtpUseCase.regenerateRecoveryCodes(TestTokens.MEMBER_ID, "current-password1", "abcde-fgh23"))
            .willReturn(AdminOtpRecoveryCodesResponse.builder().recoveryCodes(List.of("ABCDE-FGH23")).build());

        MvcTestResult result = post(BASE + "/recovery-codes", REAUTH_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.recoveryCodes[0]").isEqualTo("ABCDE-FGH23");
    }

    @Test
    @DisplayName("CUSTOMER 면 403 · SECURITY_005, 토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void forbiddenAndUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(post(BASE, CONFIRM_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(BASE + "/deactivation").contentType(MediaType.APPLICATION_JSON).content(REAUTH_BODY).exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(adminOtpUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "확인 비밀번호 없음 | /api/v1/admin/me/otp | {\"currentPassword\":\" \",\"code\":\"123456\"} | AUTH_145 | currentPassword",
        "확인 코드 없음 | /api/v1/admin/me/otp | {\"currentPassword\":\"current-password1\"} | AUTH_147 | code",
        "확인은 복구 코드를 받지 않음 | /api/v1/admin/me/otp | {\"currentPassword\":\"current-password1\",\"code\":\"ABCDE-FGH23\"} | AUTH_148 | code",
        "확인 5자리 | /api/v1/admin/me/otp | {\"currentPassword\":\"current-password1\",\"code\":\"12345\"} | AUTH_148 | code",
        "해제 형식 틀림 | /api/v1/admin/me/otp/deactivation | {\"currentPassword\":\"current-password1\",\"code\":\"ABCDE-FGH2O\"} | AUTH_149 | code",
        "재발급 너무 김 | /api/v1/admin/me/otp/recovery-codes | {\"currentPassword\":\"current-password1\",\"code\":\"123456123456123456123456123456123\"} | AUTH_149 | code"
    })
    @DisplayName("요청이 틀리면 400 · 필드별 코드이고 유스케이스를 부르지 않는다")
    void invalidRequest(String caseName, String uri, String body, String code, String field) {
        MvcTestResult result = post(uri, body, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(adminOtpUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "앞뒤 공백 6자리 | '  123456 '",
        "복구 코드 소문자 · 하이픈 없음 | abcdefgh23",
        "복구 코드 표시 형식 | ABCDE-FGH23"
    })
    @DisplayName("해제 코드는 공백을 둘러싼 6자리 · 소문자 · 하이픈 없는 복구 코드를 받는다(서버가 정규화한다)")
    void acceptedCodeShapes(String caseName, String code) {
        MvcTestResult result = post(BASE + "/deactivation", "{\"currentPassword\":\"current-password1\",\"code\":\"" + code + "\"}", MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        verify(adminOtpUseCase).disable(TestTokens.MEMBER_ID, "current-password1", code);
    }

    @Test
    @DisplayName("시도 초과는 429 · AUTH_043 · Retry-After, 잠금 대기 초과는 409 · AUTH_044, 코드 틀림은 400 · AUTH_035")
    void errorEnvelopes() {
        willThrow(new LoginAttemptLimitedException(MemberErrorCode.OTP_ATTEMPT_LIMITED, Duration.ofMillis(90_001)))
            .given(adminOtpUseCase).disable(TestTokens.MEMBER_ID, "current-password1", "abcde-fgh23");
        MvcTestResult limited = post(BASE + "/deactivation", REAUTH_BODY, MemberRole.ADMIN);
        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited).headers().hasValue(HttpHeaders.RETRY_AFTER, "91");
        assertThat(limited).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_043");

        given(adminOtpUseCase.regenerateRecoveryCodes(TestTokens.MEMBER_ID, "current-password1", "abcde-fgh23"))
            .willThrow(new MemberException(MemberErrorCode.OTP_BUSY));
        MvcTestResult busy = post(BASE + "/recovery-codes", REAUTH_BODY, MemberRole.ADMIN);
        assertThat(busy).hasStatus(HttpStatus.CONFLICT);
        assertThat(busy).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_044");

        given(adminOtpUseCase.confirmEnrollment(TestTokens.MEMBER_ID, "current-password1", "123456"))
            .willThrow(new MemberException(MemberErrorCode.OTP_CODE_INVALID));
        MvcTestResult invalid = post(BASE, CONFIRM_BODY, MemberRole.ADMIN);
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalid).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_035");
    }

    private MvcTestResult post(String uri, String json, MemberRole role) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role));
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
