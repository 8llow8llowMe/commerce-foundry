package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;
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
 * 회원가입 · 내 정보 API 의 HTTP 계약 — 봉투 모양 · 상태 코드 · 필드별 검증 코드. 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다
 * ({@code bootstrap-conventions.md} 보안 스모크 테스트).
 *
 * <p>인증이 필요한 요청은 픽스처 발급기로 만든 실제 access 토큰을 보낸다 — {@code @AuthenticationPrincipal MemberPrincipal} 이 토큰에서 풀려
 * 그 memberId 가 UseCase 로 가는지까지 본다.
 */
@WebMvcTest({AuthWebController.class, MemberWebController.class})
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class MemberWebControllerTest {

    private static final String SIGN_UP = "/api/v1/auth/sign-up";
    private static final String ME = "/api/v1/members/me";
    private static final long MEMBER_ID = 4611686018427387904L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private MemberWebUseCase memberWebUseCase;

    /** {@link AuthWebController} 의 로그인 · 토큰 의존. 이 테스트는 부르지 않는다 — 계약은 {@code AuthWebControllerTest}. */
    @MockitoBean
    private AuthWebUseCase authWebUseCase;

    @Nested
    @DisplayName("POST /api/v1/auth/sign-up")
    class SignUp {

        @Test
        @DisplayName("익명으로 201 이고 memberId 는 문자열이다 — 요청은 그대로 커맨드가 된다")
        void created() {
            given(memberWebUseCase.signUp(any())).willReturn(SignUpResponse.builder().memberId("4611686018427387904").build());

            MvcTestResult result = signUp(body("customer@example.com", "mokoyuri2026!", "홍길동", "\"010-1234-5678\"", "true", "true", "false"));

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").asString().isEqualTo("4611686018427387904");
            verify(memberWebUseCase).signUp(new SignUpCommand("customer@example.com", "mokoyuri2026!", "홍길동", "010-1234-5678", new SignUpAgreements(true, true, false)));
        }

        @Test
        @DisplayName("전화번호는 선택이다 — 없어도 201")
        void phoneOptional() {
            given(memberWebUseCase.signUp(any())).willReturn(SignUpResponse.builder().memberId("1").build());

            assertThat(signUp(body("customer@example.com", "mokoyuri2026!", "홍길동", "null", "true", "true", "true"))).hasStatus(HttpStatus.CREATED);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "이메일 없음            | ''                     | mokoyuri2026! | 홍길동 | AUTH_101 | email",
            "이메일 형식            | not-an-email           | mokoyuri2026! | 홍길동 | AUTH_103 | email",
            "이메일 점 없는 도메인  | a@localhost            | mokoyuri2026! | 홍길동 | AUTH_103 | email",
            "비밀번호 없음          | customer@example.com   | ''            | 홍길동 | AUTH_104 | password",
            "비밀번호 7자           | customer@example.com   | abcd123       | 홍길동 | AUTH_105 | password",
            "비밀번호 숫자 없음     | customer@example.com   | abcdefghij    | 홍길동 | AUTH_106 | password",
            "비밀번호 영문 없음     | customer@example.com   | 1234567890    | 홍길동 | AUTH_106 | password",
            "비밀번호 공백 포함     | customer@example.com   | abcd 12345    | 홍길동 | AUTH_106 | password",
            "비밀번호 한글 포함     | customer@example.com   | abcd1234가    | 홍길동 | AUTH_106 | password",
            "이름 공백              | customer@example.com   | mokoyuri2026! | '   '  | AUTH_107 | name",
            "이름 빈 문자열         | customer@example.com   | mokoyuri2026! | ''     | AUTH_107 | name",
            // #155 회귀 — @NotBlank(Hibernate Validator 9)는 도메인 strip 과 같이 Character.isWhitespace 로 본다. 전각 공백만이면 400 이다
            "이름 전각 공백만       | customer@example.com   | mokoyuri2026! | '　'      | AUTH_107 | name",
            "이름 전각 · 탭 섞인 공백 | customer@example.com | mokoyuri2026! | '　\\t　' | AUTH_107 | name",
            "이메일 전각 공백만     | '　'               | mokoyuri2026! | 홍길동 | AUTH_101 | email"
        })
        @DisplayName("필드별 검증 코드로 400 이고 UseCase 를 부르지 않는다")
        void fieldErrors(String caseName, String email, String password, String name, String code, String field) {
            MvcTestResult result = signUp(body(email, password, name, "null", "true", "true", "false"));

            assertValidationError(result, code, field);
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("이메일 254자 초과는 AUTH_102, 이름 50자 초과는 AUTH_108, 비밀번호 65자는 AUTH_105 다")
        void lengthErrors() {
            assertValidationError(signUp(body("a".repeat(243) + "@example.com", "mokoyuri2026!", "홍길동", "null", "true", "true", "false")), "AUTH_102", "email");
            assertValidationError(signUp(body("a@example.com", "mokoyuri2026!", "가".repeat(51), "null", "true", "true", "false")), "AUTH_108", "name");
            assertValidationError(signUp(body("a@example.com", "a1" + "b".repeat(63), "홍길동", "null", "true", "true", "false")), "AUTH_105", "password");
        }

        /*
         * #233 — İ(U+0130)는 소문자로 바꾸면 i̇(2글자)다. 원문 204자 · 정규화 268자가 @Size · @Email 을 지나 도메인 normalizeEmail 에서 500 이 났다.
         * İ 는 JSON 유니코드 이스케이프로 보낸다. 로컬 파트 64자 · 도메인 라벨 63자 이하라 @Email(Hibernate) 은 통과한다.
         */
        @Test
        @DisplayName("소문자화로 254자를 넘는 이메일은 AUTH_102 · email 로 400 이고 UseCase 를 부르지 않는다")
        void lowercasedEmailTooLong() {
            String email = "\\u0130".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + ".example.com";

            MvcTestResult result = signUp(body(email, "mokoyuri2026!", "홍길동", "null", "true", "true", "false"));

            assertValidationError(result, "AUTH_102", "email");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").asArray().hasSize(1);
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("원문이 254자를 넘으면 AUTH_102 는 한 건뿐이다 — 정규화 길이 검증은 @Size 몫을 겹쳐 내지 않는다")
        void rawEmailTooLongReportsOnce() {
            // 원문 255자(로컬 64 + @ + 도메인 190) — 형식은 맞아 @Size 만 걸린다
            String email = "\\u0130".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(58) + ".com";

            MvcTestResult result = signUp(body(email, "mokoyuri2026!", "홍길동", "null", "true", "true", "false"));

            assertValidationError(result, "AUTH_102", "email");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").asArray().hasSize(1);
            verifyNoInteractions(memberWebUseCase);
        }

        /*
         * #261 — 형식도 틀리고 소문자화 뒤 길이도 넘으면 커스텀 길이 제약(@ValidNormalizedEmailLength)이 형식(@Email)보다 먼저다.
         * 원문 254자 초과로 @Size 가 걸릴 때와 같은 대표 코드여야 한다. 원문 142자(@ 없음) · 정규화 272자.
         */
        @Test
        @DisplayName("형식 오류와 소문자화 길이 초과가 겹치면 대표 코드는 길이(AUTH_102)이고 형식(AUTH_103)이 뒤에 온다")
        void lowercasedLengthComesBeforeFormat() {
            String email = "\\u0130".repeat(130) + ".example.com";

            MvcTestResult result = signUp(body(email, "mokoyuri2026!", "홍길동", "null", "true", "true", "false"));

            assertValidationError(result, "AUTH_102", "email");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray().containsExactly("AUTH_102", "AUTH_103");
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("국내 휴대폰 형식이 아니거나 빈 문자열이면 AUTH_109 다 — 가입에서 빈 문자열은 '없음' 이 아니다")
        void phoneFormat() {
            assertValidationError(signUp(body("a@example.com", "mokoyuri2026!", "홍길동", "\"02-123-4567\"", "true", "true", "false")), "AUTH_109", "phone");
            assertValidationError(signUp(body("a@example.com", "mokoyuri2026!", "홍길동", "\"\"", "true", "true", "false")), "AUTH_109", "phone");
            verifyNoInteractions(memberWebUseCase);
        }

        @ParameterizedTest(name = "이용약관={0}, 개인정보={1}, 마케팅={2} → {3}")
        @CsvSource({
            "false, true, false, AUTH_111, agreements.termsOfService",
            "null, true, false, AUTH_111, agreements.termsOfService",
            "true, false, false, AUTH_112, agreements.privacyPolicy",
            "true, true, null, AUTH_113, agreements.marketing"
        })
        @DisplayName("필수 동의가 true 가 아니거나 마케팅 여부가 없으면 400 이다")
        void agreementErrors(String termsOfService, String privacyPolicy, String marketing, String code, String field) {
            assertValidationError(signUp(body("a@example.com", "mokoyuri2026!", "홍길동", "null", termsOfService, privacyPolicy, marketing)), code, field);
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("agreements 가 없으면 AUTH_110 이다")
        void agreementsMissing() {
            MvcTestResult result = signUp("{\"email\":\"a@example.com\",\"password\":\"mokoyuri2026!\",\"name\":\"홍길동\"}");

            assertValidationError(result, "AUTH_110", "agreements");
        }

        @Test
        @DisplayName("본문이 없거나 깨진 JSON 이면 봉투 있는 400 · AUTH_100 이다")
        void unreadableBody() {
            assertThat(mvc.post().uri(SIGN_UP).contentType(MediaType.APPLICATION_JSON).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_100");
            assertThat(signUp("{\"email\":"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_100");
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 409 · AUTH_002 다")
        void duplicate() {
            given(memberWebUseCase.signUp(any())).willThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));

            MvcTestResult result = signUp(body("customer@example.com", "mokoyuri2026!", "홍길동", "null", "true", "true", "false"));

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_002");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("이미 가입된 이메일입니다.");
        }

        @Test
        @DisplayName("가입 시도 초과는 429 · AUTH_041 + Retry-After 다 (#66)")
        void attemptLimited() {
            given(memberWebUseCase.signUp(any()))
                .willThrow(new LoginAttemptLimitedException(MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED, Duration.ofSeconds(42)));

            MvcTestResult result = signUp(body("customer@example.com", "mokoyuri2026!", "홍길동", "null", "true", "true", "false"));

            assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_041");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("회원가입 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }

        private MvcTestResult signUp(String json) {
            return mvc.post().uri(SIGN_UP).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
        }

        private static String body(String email, String password, String name, String phoneJson, String termsOfService, String privacyPolicy, String marketing) {
            return """
                {"email":"%s","password":"%s","name":"%s","phone":%s,
                 "agreements":{"termsOfService":%s,"privacyPolicy":%s,"marketing":%s}}
                """.formatted(email, password, name, phoneJson, termsOfService, privacyPolicy, marketing);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/members/me")
    class GetMe {

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 UseCase 를 부르지 않는다")
        void anonymous() {
            MvcTestResult result = mvc.get().uri(ME).exchange();

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("토큰의 memberId 로 조회하고 200 · 아이디 문자열 · phone null 을 그대로 낸다")
        void ok() {
            given(memberWebUseCase.getMyProfile(MEMBER_ID)).willReturn(MemberProfileResponse.builder()
                .memberId("4611686018427387904").email("customer@example.com").name("홍길동").phone(null).marketingAgreed(true).build());

            MvcTestResult result = mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").asString().isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.email").isEqualTo("customer@example.com");
            assertThat(result).bodyJson().extractingPath("$.dataBody.phone").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.marketingAgreed").asBoolean().isTrue();
        }

        @Test
        @DisplayName("회원이 없으면 404 · AUTH_003 이다")
        void notFound() {
            given(memberWebUseCase.getMyProfile(MEMBER_ID)).willThrow(new MemberException(MemberErrorCode.NOT_FOUND_MEMBER));

            MvcTestResult result = mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_003");
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/members/me")
    class PatchMe {

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymous() {
            MvcTestResult result = patch("{\"name\":\"김철수\"}", null);

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("보낸 칸만 커맨드에 싣고 토큰의 memberId 로 수정한다")
        void ok() {
            given(memberWebUseCase.updateMyProfile(anyLong(), any())).willReturn(MemberProfileResponse.builder()
                .memberId("4611686018427387904").email("customer@example.com").name("김철수").marketingAgreed(false).build());

            MvcTestResult result = patch("{\"name\":\"김철수\",\"phone\":\"\"}", bearer());

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.name").isEqualTo("김철수");
            verify(memberWebUseCase).updateMyProfile(MEMBER_ID, new MemberProfileUpdateCommand("김철수", "", null));
        }

        @Test
        @DisplayName("칸이 모두 없거나 null 이면 400 · AUTH_115 다 — phone: null 은 '지우기' 가 아니라 '그대로' 다")
        void empty() {
            assertValidationError(patch("{}", bearer()), "AUTH_115", "anyFieldPresent");
            assertValidationError(patch("{\"phone\":null}", bearer()), "AUTH_115", "anyFieldPresent");
            verifyNoInteractions(memberWebUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "이름 빈 문자열   | {\"name\":\"\"}             | AUTH_108 | name",
            "이름 공백만      | {\"name\":\"   \"}          | AUTH_114 | name",
            // #155 — 전각 공백만인 이름이 \S 정규식을 지나 도메인 strip 에서 500 이 났다
            "이름 전각 공백만 | {\"name\":\"\\u3000\"}       | AUTH_114 | name",
            "이름 전각 · 일반 공백 | {\"name\":\"\\u3000 \\u3000\"} | AUTH_114 | name",
            "전화 형식        | {\"phone\":\"010-12\"}      | AUTH_109 | phone",
            "전화 전각 공백만 | {\"phone\":\"\\u3000\"}      | AUTH_109 | phone"
        })
        @DisplayName("필드별 검증 코드로 400 이다")
        void fieldErrors(String caseName, String json, String code, String field) {
            assertValidationError(patch(json, bearer()), code, field);
            verifyNoInteractions(memberWebUseCase);
        }

        @Test
        @DisplayName("마케팅 동의 자리에 문자열이 오면 AUTH_100 이고 필드를 밝힌다")
        void typeMismatch() {
            MvcTestResult result = patch("{\"marketingAgreed\":\"yes\"}", bearer());

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_100");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("marketingAgreed");
        }

        @Test
        @DisplayName("다른 요청이 먼저 고쳤으면 409 · AUTH_004 다")
        void conflict() {
            given(memberWebUseCase.updateMyProfile(anyLong(), any())).willThrow(new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT));

            MvcTestResult result = patch("{\"name\":\"김철수\"}", bearer());

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_004");
        }

        private MvcTestResult patch(String json, String authorization) {
            var request = mvc.patch().uri(ME).contentType(MediaType.APPLICATION_JSON).content(json);
            return authorization == null ? request.exchange() : request.header(HttpHeaders.AUTHORIZATION, authorization).exchange();
        }
    }

    private static void assertValidationError(MvcTestResult result, String code, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }
}
