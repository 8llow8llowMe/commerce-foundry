package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminOtpLoginCommand;
import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Totp;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAdminOtpChallengeStore;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.commercefoundry.support.InMemoryLoginAttemptStore;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 관리자 로그인 2단계 (#238 PR-B) — OTP 를 등록한 관리자는 비밀번호만으로 토큰을 받지 못하고 올바른 TOTP 또는 복구 코드로만 로그인된다는 것을
 * 실제 빈 구성(보안 체인 · Facade · Processor · 영속성 어댑터 · 암호화 · H2)으로 본다. Redis 포트(시도 계수 · 챌린지)는 {@link InMemoryAuthStoresConfig} 스텁이다 —
 * 실제 키 · TTL · GETDEL 은 {@code AdminOtpChallengeRedisAdapterTest}(Testcontainers)가 본다.
 *
 * <p>OTP 등록은 등록 API 대신 {@link AdminOtpProcessor#confirm} 으로 바로 넣는다(같은 저장 경로). 확인 step 을 지금보다 2 이전으로 둬 지금 코드를 받게 한다.
 * H2 는 컨텍스트 동안 살아 있으므로 관리자는 테스트마다 새로 만든다. 값은 전부 테스트 전용이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminOtpLoginFlowIntegrationTest {

    private static final String ADMIN_LOGIN = "/api/v1/auth/admin/login";
    private static final String ADMIN_LOGIN_OTP = "/api/v1/auth/admin/login/otp";
    private static final String PASSWORD = "admin-password-1";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AdminSeedUseCase adminSeedUseCase;

    @Autowired
    private AdminAuthWebUseCase adminAuthWebUseCase;

    @Autowired
    private AdminAccountUseCase adminAccountUseCase;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private AdminOtpProcessor adminOtpProcessor;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private InMemoryLoginAttemptStore loginAttemptStore;

    @Autowired
    private InMemoryAdminOtpChallengeStore challengeStore;

    @Autowired
    private InMemoryAdminAccessCutoffStore cutoffStore;

    @AfterEach
    void resetStubs() {
        clock.reset();
        loginAttemptStore.clear();
        challengeStore.clear();
        cutoffStore.clear();
    }

    /** OTP 를 등록한 관리자 — 비밀 · 복구 코드 원문을 테스트가 든다. */
    private record EnrolledAdmin(long id, String email, byte[] secret, List<String> recoveryCodes) {

        String currentCode(AdjustableClock clock) {
            return Totp.code(secret, Totp.timeStep(clock.instant()));
        }
    }

    @Test
    @DisplayName("OTP 등록 관리자는 비밀번호만으로 토큰을 받지 못한다 — 1단계는 챌린지만(no-store), 2단계 TOTP 로 role=ADMIN access 를 받고 성공 감사는 2단계에서 한 번 남는다")
    void passwordAloneIsNotEnough(CapturedOutput output) throws Exception {
        EnrolledAdmin admin = enrolledAdmin();
        Instant loginStartedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        MvcTestResult first = login(admin.email(), PASSWORD);

        assertThat(first).hasStatusOk();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(first).bodyJson().extractingPath("$.dataBody.otpRequired").isEqualTo(true);
        assertThat(first).bodyJson().extractingPath("$.dataBody.accessToken").isNull();
        assertThat(first).bodyJson().extractingPath("$.dataBody.accessTokenExpiresIn").isNull();
        assertThat(first).bodyJson().extractingPath("$.dataBody.challengeExpiresIn").asNumber().isEqualTo(300);
        String challenge = challengeOf(first);
        assertThat(challenge).matches("[A-Za-z0-9_-]{43}");
        // 저장소에는 원문이 아니라 해시만 있다
        assertThat(challengeStore.hashes()).isNotEmpty().doesNotContain(challenge);
        assertThat(output.getOut()).contains("action=ADMIN_LOGIN_PASSWORD_VERIFIED actorMemberId=- target=member:" + admin.id()
            + " result=SUCCESS");

        clock.advance(Duration.ofSeconds(2));
        MvcTestResult second = otp(challenge, admin.currentCode(clock));

        assertThat(second).hasStatusOk();
        assertThat(second.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(second).bodyJson().extractingPath("$.dataBody.otpRequired").isEqualTo(false);
        assertThat(second).bodyJson().extractingPath("$.dataBody.challengeToken").isNull();
        SignedJWT token = SignedJWT.parse(JsonPath.read(content(second), "$.dataBody.accessToken"));
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(String.valueOf(admin.id()));
        assertThat(token.getJWTClaimsSet().getStringClaim(MemberRole.CLAIM_NAME)).isEqualTo("ADMIN");
        assertThat(token.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(loginStartedAt);

        String successLine = "action=ADMIN_LOGIN actorMemberId=" + admin.id() + " target=member:" + admin.id() + " result=SUCCESS";
        assertThat(output.getOut().split(successLine, -1)).hasSize(2);
        assertThat(output.getOut()).doesNotContain(challenge);

        // 챌린지는 1회용이다 — 성공한 챌린지를 다시 쓰면 AUTH_034
        assertThat(code(otp(challenge, admin.currentCode(clock)))).isEqualTo("AUTH_034");
    }

    @Test
    @DisplayName("OTP 미등록 관리자의 1단계는 지금과 같이 토큰이고 otpRequired=false 다(하위 호환)")
    void notEnrolledAdminGetsTokenAsBefore() {
        String email = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null));

        MvcTestResult result = login(email, PASSWORD);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.otpRequired").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataBody.challengeToken").isNull();
        assertThat(JsonPath.<String>read(content(result), "$.dataBody.accessToken")).isNotBlank();
    }

    @Test
    @DisplayName("같은 TOTP(같은 30초 step)는 다음 로그인에서 다시 쓸 수 없다 — 400 AUTH_035 이고 대상 관리자로 실패 감사한다")
    void sameTotpCannotBeReused(CapturedOutput output) {
        EnrolledAdmin admin = enrolledAdmin();
        String code = admin.currentCode(clock);
        assertThat(otp(challengeOf(login(admin.email(), PASSWORD)), code)).hasStatusOk();

        MvcTestResult reused = otp(challengeOf(login(admin.email(), PASSWORD)), code);

        assertThat(reused).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(code(reused)).isEqualTo("AUTH_035");
        assertThat(output.getOut()).contains("action=ADMIN_LOGIN actorMemberId=- target=member:" + admin.id() + " result=FAILURE");
    }

    @Test
    @DisplayName("복구 코드로도 로그인된다(소문자 · 하이픈 생략 허용) — 쓴 복구 코드는 다시 쓸 수 없고(AUTH_035) 남은 코드가 하나 줄어든다")
    void recoveryCodeIsSingleUse() {
        EnrolledAdmin admin = enrolledAdmin();
        String recovery = admin.recoveryCodes().get(0);

        MvcTestResult first = otp(challengeOf(login(admin.email(), PASSWORD)), " " + recovery.replace("-", "").toLowerCase() + " ");

        assertThat(first).hasStatusOk();
        assertThat(JsonPath.<String>read(content(first), "$.dataBody.accessToken")).isNotBlank();
        assertThat(adminOtpProcessor.status(admin.id()).remainingRecoveryCodes()).isEqualTo(7);

        MvcTestResult reused = otp(challengeOf(login(admin.email(), PASSWORD)), recovery);
        assertThat(code(reused)).isEqualTo("AUTH_035");
    }

    @Test
    @DisplayName("다른 관리자의 챌린지에 내 코드를 붙여도 통과하지 못한다 — 챌린지가 가리키는 관리자의 비밀로 확인한다(AUTH_035)")
    void anotherAdminsChallenge() {
        EnrolledAdmin owner = enrolledAdmin();
        EnrolledAdmin other = enrolledAdmin();
        String ownersChallenge = challengeOf(login(owner.email(), PASSWORD));

        assertThat(code(otp(ownersChallenge, other.currentCode(clock)))).isEqualTo("AUTH_035");
        assertThat(code(otp(ownersChallenge, other.recoveryCodes().get(0)))).isEqualTo("AUTH_035");
        // 틀린 시도 뒤에도 챌린지는 TTL 안에서 다시 쓸 수 있다 — 주인의 코드로는 통과한다
        assertThat(otp(ownersChallenge, owner.currentCode(clock))).hasStatusOk();
    }

    @Test
    @DisplayName("만료된 챌린지 · 모양이 다른 챌린지는 400 AUTH_034 다 — 1단계부터 다시 한다")
    void expiredOrMalformedChallenge() {
        EnrolledAdmin admin = enrolledAdmin();
        String challenge = challengeOf(login(admin.email(), PASSWORD));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(code(otp(challenge, admin.currentCode(clock)))).isEqualTo("AUTH_034");
        assertThat(code(otp("not-a-challenge", admin.currentCode(clock)))).isEqualTo("AUTH_034");
    }

    @Test
    @DisplayName("2단계 시도는 재인증과 다른 창(ADMIN_OTP_LOGIN)으로 5회까지 — 6번째는 코드가 맞아도 429 AUTH_043 + Retry-After, 성공해도 창을 지우지 않는다")
    void secondStepAttemptLimit() {
        EnrolledAdmin admin = enrolledAdmin();
        String challenge = challengeOf(login(admin.email(), PASSWORD));

        assertThat(otp(challenge, admin.currentCode(clock))).hasStatusOk();
        assertThat(loginAttemptStore.attempts(AttemptKind.ADMIN_OTP_LOGIN, String.valueOf(admin.id()))).isEqualTo(1);

        String retry = challengeOf(login(admin.email(), PASSWORD));
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(code(otp(retry, "000000".equals(admin.currentCode(clock)) ? "111111" : "000000"))).isEqualTo("AUTH_035");
        }
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS * 2));
        MvcTestResult limited = otp(retry, admin.currentCode(clock));

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(code(limited)).isEqualTo("AUTH_043");
        assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(loginAttemptStore.attempts(AttemptKind.ADMIN_OTP, String.valueOf(admin.id()))).isZero();
    }

    @Test
    @DisplayName("같은 챌린지의 동시 2단계(TOTP 한 쪽 · 복구 코드 한 쪽)는 한쪽만 토큰을 받는다 — 진 쪽은 400 AUTH_034 · AUTH_035 다")
    void concurrentSecondSteps() throws Exception {
        EnrolledAdmin admin = enrolledAdmin();
        String challenge = challengeOf(login(admin.email(), PASSWORD));
        List<String> codes = List.of(admin.currentCode(clock), admin.recoveryCodes().get(1));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(codes.size());
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String code : codes) {
                Callable<String> attempt = () -> {
                    start.await();
                    try {
                        adminAuthWebUseCase.loginWithOtp(new AdminOtpLoginCommand(challenge, code));
                        return "TOKEN";
                    } catch (MemberException failure) {
                        return failure.getErrorCode().getCode();
                    }
                };
                results.add(executor.submit(attempt));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).containsOnlyOnce("TOKEN");
            assertThat(outcomes).filteredOn(outcome -> !outcome.equals("TOKEN"))
                .singleElement().isIn(MemberErrorCode.OTP_CHALLENGE_INVALID.getCode(), MemberErrorCode.OTP_CODE_INVALID.getCode(), MemberErrorCode.OTP_BUSY.getCode());
            // TOTP 가 이겼으면 복구 코드는 남아야 한다. 복구 코드가 이긴 경우에는 정확히 한 개만 소비된다.
            assertThat(adminOtpProcessor.status(admin.id()).remainingRecoveryCodes())
                .isEqualTo(outcomes.get(1).equals("TOKEN") ? 7L : 8L);
            if (outcomes.getFirst().equals("TOKEN")) {
                assertThat(otp(challengeOf(login(admin.email(), PASSWORD)), admin.recoveryCodes().get(1))).hasStatusOk();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("1단계 뒤 실제 비밀번호 변경은 옛 챌린지를 무효화하고, 새 비밀번호로 받은 챌린지는 통과한다")
    void passwordChangeInvalidatesOldChallenge() {
        EnrolledAdmin admin = enrolledAdmin();
        String oldChallenge = challengeOf(login(admin.email(), PASSWORD));
        String newPassword = "new-admin-password-2";
        adminAccountUseCase.changePassword(admin.id(), UUID.randomUUID().toString(), PASSWORD, newPassword);

        assertThat(code(otp(oldChallenge, admin.currentCode(clock)))).isEqualTo("AUTH_034");
        clock.advance(Duration.ofSeconds(2));
        assertThat(code(login(admin.email(), PASSWORD))).isEqualTo("AUTH_030");
        String newChallenge = challengeOf(login(admin.email(), newPassword));
        assertThat(otp(newChallenge, admin.currentCode(clock))).hasStatusOk();
    }

    private EnrolledAdmin enrolledAdmin() {
        String email = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null));
        long adminId = memberRepositoryPort.findByEmail(email).orElseThrow().id();
        byte[] secret = new byte[Totp.SECRET_BYTES];
        RANDOM.nextBytes(secret);
        IssuedRecoveryCodes issued = adminOtpProcessor.issueRecoveryCodes();
        adminOtpProcessor.confirm(adminId, secret, Totp.timeStep(clock.instant()) - 2, clock.instant(), issued.hashes());
        return new EnrolledAdmin(adminId, email, secret, issued.toInfo().recoveryCodes());
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri(ADMIN_LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private MvcTestResult otp(String challengeToken, String code) {
        return mvc.post().uri(ADMIN_LOGIN_OTP).contentType(MediaType.APPLICATION_JSON)
            .content("{\"challengeToken\":\"%s\",\"code\":\"%s\"}".formatted(challengeToken, code)).exchange();
    }

    private static String challengeOf(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return JsonPath.read(content(result), "$.dataBody.challengeToken");
    }

    private static String code(MvcTestResult result) {
        return JsonPath.read(content(result), "$.dataHeader.resultCode");
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueEmail() {
        return "admin-otp-" + UUID.randomUUID() + "@example.com";
    }
}
