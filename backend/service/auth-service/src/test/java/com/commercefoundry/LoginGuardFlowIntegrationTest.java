package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.commercefoundry.support.InMemoryLoginAttemptStore;
import com.commercefoundry.support.InMemoryRefreshTokenStore;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 로그인 · 관리자 로그인 · 회원가입 시도 제한 (#66 완료 기준) — 실제 빈 구성(보안 체인 · Facade · Processor · 예외 처리 · H2)에 계수기만 인메모리 스텁
 * ({@link InMemoryLoginAttemptStore}, Lua 와 같은 고정 창)을 넣고 {@link AdjustableClock} 으로 창을 넘긴다. 정책은 application.yml 기본값(로그인 5회 · 15분,
 * 가입 5회 · 10분)이다. 실제 Lua · 키 · TTL 은 {@code LoginAttemptRedisAdapterTest}(Testcontainers)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
class LoginGuardFlowIntegrationTest {

    private static final String PASSWORD = "mokoyuri2026!";
    private static final String WRONG_PASSWORD = "wrong-password-1";
    private static final String ADMIN_PASSWORD = "admin-password-1";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ADMIN_LOGIN = "/api/v1/auth/admin/login";
    private static final String SIGN_UP = "/api/v1/auth/sign-up";
    private static final String LIMITED_MESSAGE = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private InMemoryLoginAttemptStore attemptStore;

    @Autowired
    private InMemoryRefreshTokenStore tokenStore;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private AdminSeedUseCase adminSeedUseCase;

    @AfterEach
    void restore() {
        attemptStore.unavailable = false;
        tokenStore.unavailable = false;
        attemptStore.clear();
        clock.reset();
    }

    @Test
    @DisplayName("완료 기준 1 — 틀린 비밀번호 5회 → 6회째는 맞는 비밀번호도 429 · AUTH_040 + Retry-After → 창(15분)이 지나면 200")
    void lockedAfterMaxAttemptsThenReleasedAfterWindow() {
        String email = signUpCustomer();
        for (int i = 0; i < 5; i++) {
            assertCode(login(email, WRONG_PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_001");
        }

        MvcTestResult locked = login(email, PASSWORD);
        assertLimited(locked);
        assertThat(retryAfter(locked)).isBetween(1L, 900L);
        assertLimited(login(email, PASSWORD));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(login(email, PASSWORD)).hasStatusOk();
    }

    @Test
    @DisplayName("완료 기준 2 — 없는 이메일도 같은 횟수에서 같은 429 본문 · 같은 Retry-After 모양이다(계정 존재가 드러나지 않는다)")
    void absentEmailGetsSameLimitedResponse() {
        String existing = signUpCustomer();
        String absent = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            MvcTestResult existingFailure = login(existing, WRONG_PASSWORD);
            MvcTestResult absentFailure = login(absent, WRONG_PASSWORD);
            assertThat(body(absentFailure)).isEqualTo(body(existingFailure));
        }

        MvcTestResult existingLocked = login(existing, PASSWORD);
        MvcTestResult absentLocked = login(absent, PASSWORD);

        assertLimited(existingLocked);
        assertLimited(absentLocked);
        assertThat(body(absentLocked)).isEqualTo(body(existingLocked));
        assertThat(retryAfter(absentLocked)).isBetween(retryAfter(existingLocked) - 1, retryAfter(existingLocked) + 1);
        assertThat(absentLocked.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(existingLocked.getResponse().getHeaderNames());
    }

    @Test
    @DisplayName("대소문자 · 앞뒤 공백만 다른 이메일은 한 창에 모인다 — 변형을 바꿔 가며 보내도 6번째에 잠긴다")
    void emailVariantsShareWindow() {
        String email = signUpCustomer();
        String[] variants = {email, email.toUpperCase(), " " + email, email + " ", "  " + email.toUpperCase() + "  "};
        for (String variant : variants) {
            assertCode(login(variant, WRONG_PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_001");
        }

        assertLimited(login(email.substring(0, 1).toUpperCase() + email.substring(1), PASSWORD));
        assertThat(attemptStore.attempts(AttemptKind.LOGIN, email)).isEqualTo(6);
    }

    @Test
    @DisplayName("성공한 로그인은 창을 지운다 — 틀린 4회 → 성공 → 다시 틀린 5회까지 허용된다")
    void successfulLoginResetsWindow() {
        String email = signUpCustomer();
        for (int i = 0; i < 4; i++) {
            login(email, WRONG_PASSWORD);
        }
        assertThat(login(email, PASSWORD)).hasStatusOk();
        assertThat(attemptStore.attempts(AttemptKind.LOGIN, email)).isZero();

        for (int i = 0; i < 5; i++) {
            assertCode(login(email, WRONG_PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_001");
        }
        assertLimited(login(email, PASSWORD));
    }

    @Test
    @DisplayName("고객 · 관리자 로그인은 창이 따로다 — 고객 경로로 관리자 이메일을 잠가도 관리자 로그인은 열려 있고, 관리자 창 초과도 같은 429 · AUTH_040 이다")
    void adminAndCustomerWindowsAreSeparate() {
        String adminEmail = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(adminEmail, ADMIN_PASSWORD, null));
        for (int i = 0; i < 6; i++) {
            login(adminEmail, ADMIN_PASSWORD);
        }
        assertLimited(login(adminEmail, ADMIN_PASSWORD));

        assertThat(adminLogin(adminEmail, ADMIN_PASSWORD)).hasStatusOk();

        // 관리자 성공은 창을 지우지 않으므로(아래 테스트) 성공 1회 + 틀림 4회로 허용 5회를 채운다
        for (int i = 0; i < 4; i++) {
            assertCode(adminLogin(adminEmail, WRONG_PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_030");
        }
        MvcTestResult adminLocked = adminLogin(adminEmail, ADMIN_PASSWORD);
        assertLimited(adminLocked);
        assertThat(attemptStore.attempts(AttemptKind.ADMIN_LOGIN, adminEmail)).isEqualTo(6);
    }

    @Test
    @DisplayName("관리자 로그인은 성공해도 창을 지우지 않는다(사용자 결정 2026-10-22) — 틀린 2회 → 성공 → 남은 2회 성공 뒤 6번째는 맞는 비밀번호도 429, 창이 지나면 200")
    void adminSuccessDoesNotResetWindow() {
        String adminEmail = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(adminEmail, ADMIN_PASSWORD, null));
        for (int i = 0; i < 2; i++) {
            assertCode(adminLogin(adminEmail, WRONG_PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_030");
        }

        assertThat(adminLogin(adminEmail, ADMIN_PASSWORD)).hasStatusOk();
        assertThat(attemptStore.attempts(AttemptKind.ADMIN_LOGIN, adminEmail)).isEqualTo(3);

        for (int i = 0; i < 2; i++) {
            assertThat(adminLogin(adminEmail, ADMIN_PASSWORD)).hasStatusOk();
        }
        assertLimited(adminLogin(adminEmail, ADMIN_PASSWORD));
        assertThat(attemptStore.attempts(AttemptKind.ADMIN_LOGIN, adminEmail)).isEqualTo(6);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(adminLogin(adminEmail, ADMIN_PASSWORD)).hasStatusOk();
    }

    @Test
    @DisplayName("로그인 · 관리자 로그인은 계수기 장애면 fail-closed — 없는 이메일 · 틀린 비밀번호 · 맞는 비밀번호 모두 같은 503 · AUTH_013 이다")
    void loginFailsClosedWhenCounterUnavailable() {
        String email = signUpCustomer();
        attemptStore.unavailable = true;

        MvcTestResult absent = login(uniqueEmail(), PASSWORD);
        MvcTestResult wrong = login(email, WRONG_PASSWORD);
        MvcTestResult right = login(email, PASSWORD);
        MvcTestResult admin = adminLogin(email, PASSWORD);

        for (MvcTestResult result : new MvcTestResult[] {absent, wrong, right, admin}) {
            assertCode(result, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
            assertThat(body(result)).isEqualTo(body(absent));
        }
    }

    @Test
    @DisplayName("가입 — 같은 이메일 5회(201 · 409 ×4) → 6회째 429 · AUTH_041 + Retry-After, 가입된 이메일 · 처음 보는 이메일과 무관하게 같고 창(10분)이 지나면 다시 409 다")
    void signUpLimited() {
        String email = uniqueEmail();
        assertThat(signUp(email)).hasStatus(HttpStatus.CREATED);
        for (int i = 0; i < 4; i++) {
            assertCode(signUp(email.toUpperCase()), HttpStatus.CONFLICT, "AUTH_002");
        }

        MvcTestResult limited = signUp(email);
        assertCode(limited, HttpStatus.TOO_MANY_REQUESTS, "AUTH_041");
        assertThat(retryAfter(limited)).isBetween(1L, 600L);

        String fresh = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            attemptStore.record(AttemptKind.SIGN_UP, fresh, Duration.ofMinutes(10));
        }
        MvcTestResult freshLimited = signUp(fresh);
        assertCode(freshLimited, HttpStatus.TOO_MANY_REQUESTS, "AUTH_041");
        assertThat(body(freshLimited)).isEqualTo(body(limited));

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertCode(signUp(email), HttpStatus.CONFLICT, "AUTH_002");
        assertThat(signUp(fresh)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("가입 · 로그인 창은 따로다 — 가입 시도가 로그인 한도를 깎지 않는다")
    void signUpAndLoginWindowsAreSeparate() {
        String email = signUpCustomer();
        for (int i = 0; i < 5; i++) {
            signUp(email);
        }

        assertThat(login(email, PASSWORD)).hasStatusOk();
    }

    @Test
    @DisplayName("가입은 계수기 장애면 fail-open — 세지 않고 201 이다")
    void signUpFailsOpenWhenCounterUnavailable() {
        attemptStore.unavailable = true;

        assertThat(signUp(uniqueEmail())).hasStatus(HttpStatus.CREATED);
    }

    private String signUpCustomer() {
        String email = uniqueEmail();
        assertThat(signUp(email)).hasStatus(HttpStatus.CREATED);
        return email;
    }

    private MvcTestResult signUp(String email) {
        String body = """
            {"email":"%s","password":"%s","name":"홍길동","phone":null,
             "agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
            """.formatted(email, PASSWORD);
        return mvc.post().uri(SIGN_UP).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private MvcTestResult adminLogin(String email, String password) {
        return mvc.post().uri(ADMIN_LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private static void assertLimited(MvcTestResult result) {
        assertCode(result, HttpStatus.TOO_MANY_REQUESTS, "AUTH_040");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(LIMITED_MESSAGE);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).matches("[1-9][0-9]*");
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }

    private static long retryAfter(MvcTestResult result) {
        return Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueEmail() {
        return "guard-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("동일 IP는 이메일을 바꿔도 가입 31회째 429이고 다른 IP는 허용되며 10분 뒤 다시 가입한다")
    void signUpClientLimitSurvivesEmailRotationAndExpires() {
        String subject = "AbCdEfGhIjKlMnOpQrStUw";
        String other = "abCdEfGhIjKlMnOpQrStUw";
        for (int i = 0; i < 30; i++) {
            attemptStore.record(AttemptKind.SIGN_UP_IP, subject, Duration.ofMinutes(10));
        }
        String email = uniqueEmail();
        MvcTestResult limited = signUpFromClient(email, subject);
        assertCode(limited, HttpStatus.TOO_MANY_REQUESTS, "AUTH_041");
        assertThat(retryAfter(limited)).isBetween(1L, 600L);
        assertThat(attemptStore.attempts(AttemptKind.SIGN_UP, email)).isZero();
        assertCode(signUpFromClient(uniqueEmail(), subject), HttpStatus.TOO_MANY_REQUESTS, "AUTH_041");
        assertThat(signUpFromClient(uniqueEmail(), other)).hasStatus(HttpStatus.CREATED);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(signUpFromClient(email, subject)).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult signUpFromClient(String email, String subject) {
        return mvc.post().uri(SIGN_UP).header("X-Client-Subject", subject).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s","name":"테스트","agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
                """.formatted(email, PASSWORD)).exchange();
    }

}
