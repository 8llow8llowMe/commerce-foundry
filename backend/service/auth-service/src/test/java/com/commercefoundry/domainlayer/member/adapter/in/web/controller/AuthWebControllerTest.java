package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
import com.commercefoundry.security.issuer.IssuedAccessToken;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 로그인 · 재발급 · 로그아웃 API 의 HTTP 계약 — 봉투 · 상태 코드 · {@code Cache-Control} · 검증 코드 · 인증 주체에서 UseCase 로 가는 값.
 * 슬라이스라 보안 구성을 직접 import 한다.
 */
@WebMvcTest(AuthWebController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AuthWebControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REISSUE = "/api/v1/auth/token/reissue";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String REFRESH_TOKEN = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";
    private static final AuthTokenResponse TOKENS = AuthTokenResponse.builder()
        .accessToken("access.jwt.value").accessTokenExpiresIn(1800).refreshToken(REFRESH_TOKEN).refreshTokenExpiresIn(1209600).build();

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private MemberWebUseCase memberWebUseCase;

    @MockitoBean
    private AuthWebUseCase authWebUseCase;

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class Login {

        @Test
        @DisplayName("익명으로 200 이고 토큰 4칸을 주며 Cache-Control: no-store 다 — 요청은 그대로 커맨드가 된다")
        void ok() {
            given(authWebUseCase.login(any())).willReturn(TOKENS);

            MvcTestResult result = login("{\"email\":\"Customer@Example.com\",\"password\":\"mokoyuri2026!\"}");

            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(result).bodyJson().extractingPath("$.dataBody.accessToken").isEqualTo("access.jwt.value");
            assertThat(result).bodyJson().extractingPath("$.dataBody.accessTokenExpiresIn").asNumber().isEqualTo(1800);
            assertThat(result).bodyJson().extractingPath("$.dataBody.refreshToken").isEqualTo(REFRESH_TOKEN);
            assertThat(result).bodyJson().extractingPath("$.dataBody.refreshTokenExpiresIn").asNumber().isEqualTo(1209600);
            verify(authWebUseCase).login(new LoginCommand("Customer@Example.com", "mokoyuri2026!"));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "이메일 없음   | {\"password\":\"x\"}                              | AUTH_101 | email",
            "비밀번호 없음 | {\"email\":\"a@example.com\"}                     | AUTH_104 | password",
            "비밀번호 공백 | {\"email\":\"a@example.com\",\"password\":\"  \"} | AUTH_104 | password"
        })
        @DisplayName("필드 검증은 400 · 필드별 코드다 — 비밀번호 형식(가입 규칙)은 보지 않는다")
        void invalid(String name, String body, String code, String field) {
            MvcTestResult result = login(body);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(authWebUseCase);
        }

        @Test
        @DisplayName("255자 이메일은 400 · AUTH_102 다")
        void emailTooLong() {
            String email = "a".repeat(243) + "@example.com";

            assertThat(login("{\"email\":\"%s\",\"password\":\"x\"}".formatted(email))).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_102");
        }

        @Test
        @DisplayName("로그인 실패는 400 · AUTH_001 봉투다")
        void failed() {
            given(authWebUseCase.login(any())).willThrow(new MemberException(MemberErrorCode.LOGIN_FAILED));

            MvcTestResult result = login("{\"email\":\"a@example.com\",\"password\":\"wrong-pass1\"}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_001");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        @Test
        @DisplayName("토큰 저장소 장애는 503 · AUTH_013 이다")
        void storeUnavailable() {
            given(authWebUseCase.login(any())).willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE));

            MvcTestResult result = login("{\"email\":\"a@example.com\",\"password\":\"mokoyuri2026!\"}");

            assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_013");
        }

        @ParameterizedTest(name = "남은 {0}ms → Retry-After {1}")
        @CsvSource({"0, 1", "1500, 2", "900000, 900"})
        @DisplayName("시도 초과는 429 · AUTH_040 봉투에 Retry-After(초, 올림, 최소 1)를 붙이고 토큰을 싣지 않는다")
        void attemptLimited(long remainingMillis, String retryAfter) {
            given(authWebUseCase.login(any()))
                .willThrow(new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMillis(remainingMillis)));

            MvcTestResult result = login("{\"email\":\"a@example.com\",\"password\":\"mokoyuri2026!\"}");

            assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_040");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }

        @Test
        @DisplayName("회원가입 시도 초과는 429 · AUTH_041 + Retry-After 다")
        void signUpAttemptLimited() {
            given(memberWebUseCase.signUp(any()))
                .willThrow(new LoginAttemptLimitedException(MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED, Duration.ofSeconds(42)));

            MvcTestResult result = mvc.post().uri("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"a@example.com","password":"mokoyuri2026!","name":"홍길동","phone":null,
                 "agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
                """).exchange();

            assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_041");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("회원가입 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }

        private MvcTestResult login(String body) {
            return mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/token/reissue")
    class Reissue {

        @Test
        @DisplayName("익명으로 200 · Cache-Control: no-store 이고 refresh 토큰이 그대로 UseCase 로 간다")
        void ok() {
            given(authWebUseCase.reissue(REFRESH_TOKEN)).willReturn(TOKENS);

            MvcTestResult result = reissue("{\"refreshToken\":\"" + REFRESH_TOKEN + "\"}");

            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(result).bodyJson().extractingPath("$.dataBody.refreshToken").isEqualTo(REFRESH_TOKEN);
        }

        @Test
        @DisplayName("refresh 토큰이 없어도 필드 검증(400)을 하지 않고 null 로 UseCase 에 넘긴다 — 형식 판정(401 · AUTH_010)은 서버 한 곳에서 한다")
        void missing() {
            given(authWebUseCase.reissue(null)).willThrow(new MemberException(MemberErrorCode.REFRESH_TOKEN_MALFORMED));

            MvcTestResult result = reissue("{}");

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_010");
            verify(authWebUseCase).reissue(null);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"REFRESH_TOKEN_MALFORMED, AUTH_010", "REFRESH_TOKEN_INVALID, AUTH_011", "REFRESH_TOKEN_REUSED, AUTH_012"})
        @DisplayName("refresh 토큰 거절은 401 이다 — BFF 는 4xx 면 쿠키를 지운다")
        void rejected(MemberErrorCode errorCode, String code) {
            given(authWebUseCase.reissue(anyString())).willThrow(new MemberException(errorCode));

            MvcTestResult result = reissue("{\"refreshToken\":\"whatever\"}");

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        }

        private MvcTestResult reissue(String body) {
            return mvc.post().uri(REISSUE).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class Logout {

        @Test
        @DisplayName("access 토큰의 memberId · jti 와 본문의 refresh 토큰이 UseCase 로 가고 200 · 본문 null 이다")
        void ok() {
            IssuedAccessToken access = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER);

            MvcTestResult result = logout(access.tokenValue(), "{\"refreshToken\":\"" + REFRESH_TOKEN + "\"}");

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verify(authWebUseCase).logout(TestTokens.MEMBER_ID, access.tokenId(), REFRESH_TOKEN);
        }

        @Test
        @DisplayName("refresh 토큰은 선택이다 — 없으면 null 로 간다")
        void withoutRefreshToken() {
            IssuedAccessToken access = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER);

            assertThat(logout(access.tokenValue(), "{}")).hasStatusOk();
            verify(authWebUseCase).logout(TestTokens.MEMBER_ID, access.tokenId(), null);
        }

        @Test
        @DisplayName("본문이 없어도 200 이다 — refresh 토큰 없이(null) access 만 막는다")
        void withoutBody() {
            IssuedAccessToken access = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER);

            MvcTestResult result = mvc.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + access.tokenValue()).exchange();

            assertThat(result).hasStatusOk();
            verify(authWebUseCase).logout(TestTokens.MEMBER_ID, access.tokenId(), null);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 UseCase 를 부르지 않는다")
        void anonymous() {
            MvcTestResult result = mvc.post().uri(LOGOUT).contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
            verifyNoInteractions(authWebUseCase);
        }

        @Test
        @DisplayName("토큰 저장소 장애는 503 · AUTH_013 이다")
        void storeUnavailable() {
            willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(authWebUseCase).logout(anyLong(), anyString(), any());
            String access = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

            MvcTestResult result = logout(access, "{}");

            assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_013");
        }

        private MvcTestResult logout(String accessToken, String body) {
            return mvc.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        }
    }
}
