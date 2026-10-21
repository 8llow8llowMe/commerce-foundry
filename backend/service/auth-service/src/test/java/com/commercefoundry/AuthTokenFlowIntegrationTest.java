package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryRefreshTokenStore;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 로그인 → access 만료 → 재발급 → 로그아웃 → 재사용 거부 흐름을 실제 빈 구성(보안 체인 · Facade · Processor · 발급기 · H2)으로 본다 (#4 완료 기준).
 *
 * <p>토큰 저장소 포트만 인메모리 스텁({@link InMemoryRefreshTokenStore}, {@code @Primary})으로 바꾼다 — 그래서 다른 {@code @SpringBootTest} 와 컨텍스트를 같이 쓰지 않는다.
 * 실제 Lua · 키 · TTL 은 {@code RefreshTokenRedisAdapterTest}(Testcontainers)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthTokenFlowIntegrationTest {

    private static final String PASSWORD = "mokoyuri2026!";
    private static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REISSUE = "/api/v1/auth/token/reissue";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/members/me";

    @TestConfiguration
    static class StoreConfig {

        @Bean
        @Primary
        InMemoryRefreshTokenStore inMemoryRefreshTokenStore() {
            return new InMemoryRefreshTokenStore();
        }

        /** 회전 판정 시계(Processor 의 {@code Clock}). access 토큰 발급 · 검증은 각자 시스템 시계라 앞당겨도 영향이 없다. */
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private InMemoryRefreshTokenStore store;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminSeedUseCase adminSeedUseCase;

    @AfterEach
    void restoreStore() {
        store.unavailable = false;
        clock.reset();
    }

    @Test
    @DisplayName("가입 → 로그인 → 만료된 access 는 401 · SECURITY_002 → 재발급 → 새 access 로 200 → 로그아웃 → 같은 refresh 재발급은 401 · AUTH_011, jti 는 차단 목록에 들어간다")
    void loginReissueLogout() {
        String email = uniqueEmail();
        long memberId = signUp(email);

        MvcTestResult login = login(email.toUpperCase(), PASSWORD);
        assertThat(login).hasStatusOk();
        assertThat(login.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String loginBody = body(login);
        String refreshToken = JsonPath.read(loginBody, "$.dataBody.refreshToken");
        assertThat(refreshToken).hasSize(43);
        assertThat(JsonPath.<Integer>read(loginBody, "$.dataBody.accessTokenExpiresIn")).isBetween(1790, 1800);
        assertThat(JsonPath.<Integer>read(loginBody, "$.dataBody.refreshTokenExpiresIn")).isEqualTo((int) Duration.ofDays(14).toSeconds());
        assertThat(loginBody).doesNotContain(PASSWORD, "$2", "password");
        assertThat(subjectOf(JsonPath.read(loginBody, "$.dataBody.accessToken"))).isEqualTo(String.valueOf(memberId));

        String expired = TestTokens.issuer(Instant.now().minus(Duration.ofHours(2))).issueAccessToken(memberId, MemberRole.CUSTOMER).tokenValue();
        MvcTestResult expiredMe = mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).exchange();
        assertThat(expiredMe).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(expiredMe).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_002");

        MvcTestResult reissue = reissue(refreshToken);
        assertThat(reissue).hasStatusOk();
        assertThat(reissue.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String newAccess = JsonPath.read(body(reissue), "$.dataBody.accessToken");
        String newRefresh = JsonPath.read(body(reissue), "$.dataBody.refreshToken");
        assertThat(newRefresh).hasSize(43).isNotEqualTo(refreshToken);

        MvcTestResult me = mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess).exchange();
        assertThat(me).hasStatusOk();
        assertThat(me).bodyJson().extractingPath("$.dataBody.memberId").asString().isEqualTo(String.valueOf(memberId));

        MvcTestResult logout = logout(newAccess, "{\"refreshToken\":\"" + newRefresh + "\"}");
        assertThat(logout).hasStatusOk();
        assertThat(logout).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(logout).bodyJson().extractingPath("$.dataBody").isNull();
        assertThat(store.accessTokenRevoked(jtiOf(newAccess))).as("TTL 61분은 AccessTokenRevocationRedisAdapter 테스트가 본다").isTrue();

        assertRejected(reissue(newRefresh), HttpStatus.UNAUTHORIZED, "AUTH_011");
        assertRejected(reissue(refreshToken), HttpStatus.UNAUTHORIZED, "AUTH_011");
    }

    /**
     * 관리자 로그아웃 (#6). 로그아웃은 jti 를 차단 목록에 넣을 뿐이고, 차단 목록은 게이트웨이만 본다(security-guide.md §7-1 · #4 결정 3) —
     * 서비스에 직접 오는 같은 토큰은 서명 · 만료만 보므로 200 이 정상이다. 게이트웨이의 거부(401 · SECURITY_004)는 {@code GatewaySecurityTest#revokedTokenIsRejected} 가 본다.
     */
    @Test
    @DisplayName("관리자 access 로 본문 없이 로그아웃하면 jti 가 차단 목록에 들어간다 — 서비스 직접 호출은 차단 목록을 보지 않아 회원 조회가 여전히 200 이다")
    void adminLogoutRevokesJtiOnly() {
        String email = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(email, "admin-password-1", null));
        MvcTestResult login = mvc.post().uri("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"admin-password-1\"}".formatted(email)).exchange();
        assertThat(login).hasStatusOk();
        String access = JsonPath.read(body(login), "$.dataBody.accessToken");

        assertThat(logout(access, "{}")).hasStatusOk();
        assertThat(store.accessTokenRevoked(jtiOf(access))).isTrue();

        MvcTestResult afterLogout = mvc.get().uri("/api/v1/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + access).exchange();
        assertThat(afterLogout).as("서비스는 차단 목록을 보지 않는다 — 막는 곳은 게이트웨이다").hasStatusOk();
    }

    @Test
    @DisplayName("회원 없음 · 비밀번호 틀림 · 관리자 계정은 모두 400 · AUTH_001 같은 문구다")
    void loginFailuresLookTheSame() {
        String customer = uniqueEmail();
        signUp(customer);
        String admin = uniqueEmail();
        memberRepositoryPort.save(Member.restore(System.nanoTime(), admin, passwordEncoder.encode(PASSWORD), "관리자", null, MemberRole.ADMIN, null));

        assertRejected(login(uniqueEmail(), PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_001", LOGIN_FAILED_MESSAGE);
        assertRejected(login(customer, "wrong-password1"), HttpStatus.BAD_REQUEST, "AUTH_001", LOGIN_FAILED_MESSAGE);
        assertRejected(login(admin, PASSWORD), HttpStatus.BAD_REQUEST, "AUTH_001", LOGIN_FAILED_MESSAGE);
    }

    @Test
    @DisplayName("refresh 토큰이 없거나 · 비었거나 · 발급 모양이 아니면 모두 401 · AUTH_010 이다")
    void malformedRefreshToken() {
        assertRejected(reissue("not-a-refresh-token"), HttpStatus.UNAUTHORIZED, "AUTH_010");
        assertRejected(reissue("a".repeat(42) + "="), HttpStatus.UNAUTHORIZED, "AUTH_010");
        assertRejected(reissue(""), HttpStatus.UNAUTHORIZED, "AUTH_010");
        assertRejected(mvc.post().uri(REISSUE).contentType(MediaType.APPLICATION_JSON).content("{}").exchange(), HttpStatus.UNAUTHORIZED, "AUTH_010");
    }

    @Test
    @DisplayName("모양은 맞지만 모르는 refresh 토큰은 401 · AUTH_011 이다")
    void unknownRefreshToken() {
        assertRejected(reissue("A".repeat(43)), HttpStatus.UNAUTHORIZED, "AUTH_011");
    }

    @Test
    @DisplayName("같은 refresh 토큰을 유예 안에 다시 보내면 새 토큰(형제)을 하나 더 준다 — 둘 다 이어서 쓸 수 있다")
    void reuseInGraceIssuesSibling() {
        String email = uniqueEmail();
        signUp(email);
        String refreshToken = JsonPath.read(body(login(email, PASSWORD)), "$.dataBody.refreshToken");

        String first = JsonPath.read(body(reissue(refreshToken)), "$.dataBody.refreshToken");
        MvcTestResult again = reissue(refreshToken);

        assertThat(again).hasStatusOk();
        String sibling = JsonPath.read(body(again), "$.dataBody.refreshToken");
        assertThat(sibling).isNotEqualTo(first);
        assertThat(reissue(first)).hasStatusOk();
        assertThat(reissue(sibling)).hasStatusOk();
    }

    @Test
    @DisplayName("로그아웃은 남의 refresh 토큰 family 를 건드리지 않고, refresh 토큰이 없어도 access 는 차단하고 200 이다")
    void logoutLeavesOthersAlone() {
        String mine = uniqueEmail();
        long myId = signUp(mine);
        String other = uniqueEmail();
        signUp(other);
        String otherRefresh = JsonPath.read(body(login(other, PASSWORD)), "$.dataBody.refreshToken");
        String myAccess = JsonPath.read(body(login(mine, PASSWORD)), "$.dataBody.accessToken");

        assertThat(logout(myAccess, "{\"refreshToken\":\"" + otherRefresh + "\"}")).hasStatusOk();
        assertThat(reissue(otherRefresh)).as("남의 로그인은 그대로다").hasStatusOk();

        String anotherAccess = TestTokens.issuer(Instant.now()).issueAccessToken(myId, MemberRole.CUSTOMER).tokenValue();
        assertThat(logout(anotherAccess, "{}")).hasStatusOk();
        assertThat(store.accessTokenRevoked(jtiOf(anotherAccess))).isTrue();
        assertThat(logout(anotherAccess, "{\"refreshToken\":\"broken\"}")).as("형식이 틀린 refresh 도 200(멱등)").hasStatusOk();

        String thirdAccess = TestTokens.issuer(Instant.now()).issueAccessToken(myId, MemberRole.CUSTOMER).tokenValue();
        MvcTestResult withoutBody = mvc.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdAccess).exchange();
        assertThat(withoutBody).as("본문 없이도 access 를 막고 200").hasStatusOk();
        assertThat(store.accessTokenRevoked(jtiOf(thirdAccess))).isTrue();
    }

    @Test
    @DisplayName("유예 안 형제는 토큰당 3개까지다 — 넷째 재사용은 401 · AUTH_012 이고 그 family 의 토큰이 모두 무효가 된다")
    void siblingCapRevokesFamily() {
        String email = uniqueEmail();
        signUp(email);
        String refreshToken = JsonPath.read(body(login(email, PASSWORD)), "$.dataBody.refreshToken");
        String rotated = JsonPath.read(body(reissue(refreshToken)), "$.dataBody.refreshToken");
        for (int i = 0; i < 3; i++) {
            assertThat(reissue(refreshToken)).hasStatusOk();
        }

        assertRejected(reissue(refreshToken), HttpStatus.UNAUTHORIZED, "AUTH_012");
        assertRejected(reissue(rotated), HttpStatus.UNAUTHORIZED, "AUTH_011");
    }

    @Test
    @DisplayName("로그인 절대 수명 30일 — 13일 · 26일 뒤 재발급은 이어지되 둘째 refresh 수명은 남은 4일로 줄고, 30일이 되면 재발급이 401 · AUTH_011 이다")
    void familyMaxLifetimeEndsLogin() {
        String email = uniqueEmail();
        signUp(email);
        String loginBody = body(login(email, PASSWORD));
        assertThat(JsonPath.<Integer>read(loginBody, "$.dataBody.refreshTokenExpiresIn")).isEqualTo((int) Duration.ofDays(14).toSeconds());
        String first = JsonPath.read(loginBody, "$.dataBody.refreshToken");

        clock.advance(Duration.ofDays(13));
        MvcTestResult beforeCap = reissue(first);
        assertThat(beforeCap).hasStatusOk();
        assertThat(JsonPath.<Integer>read(body(beforeCap), "$.dataBody.refreshTokenExpiresIn")).as("상한까지 17일 남아 TTL 그대로")
            .isEqualTo((int) Duration.ofDays(14).toSeconds());
        String second = JsonPath.read(body(beforeCap), "$.dataBody.refreshToken");

        clock.advance(Duration.ofDays(13));
        MvcTestResult nearCap = reissue(second);
        assertThat(nearCap).hasStatusOk();
        assertThat(JsonPath.<Integer>read(body(nearCap), "$.dataBody.refreshTokenExpiresIn")).as("상한까지 남은 4일로 준다 — BFF 쿠키 만료가 서버 TTL 과 맞는다")
            .isBetween((int) Duration.ofDays(4).minusMinutes(1).toSeconds(), (int) Duration.ofDays(4).toSeconds());
        String third = JsonPath.read(body(nearCap), "$.dataBody.refreshToken");

        clock.advance(Duration.ofDays(4));
        assertRejected(reissue(third), HttpStatus.UNAUTHORIZED, "AUTH_011", "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        assertRejected(reissue(third), HttpStatus.UNAUTHORIZED, "AUTH_011");
    }

    @Test
    @DisplayName("family 의 로그인 시각이 먼 미래로 손상됐으면 상한이 사라지지 않게 재발급이 401 · AUTH_011 이고 그 로그인이 끝난다")
    void corruptedFutureLoginAtEndsLogin() {
        String email = uniqueEmail();
        signUp(email);
        String refreshToken = JsonPath.read(body(login(email, PASSWORD)), "$.dataBody.refreshToken");
        String familyId = store.findSession(new OpaqueTokenGenerator().fingerprint(refreshToken)).orElseThrow().familyId();
        store.corruptFamilyValue(familyId, String.valueOf(clock.instant().plus(Duration.ofDays(365)).toEpochMilli()));

        assertRejected(reissue(refreshToken), HttpStatus.UNAUTHORIZED, "AUTH_011", "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        assertThat(store.familyAlive(familyId)).isFalse();
    }

    @Test
    @DisplayName("토큰 저장소 장애면 로그인 · 재발급 · 로그아웃이 503 · AUTH_013 이다")
    void storeUnavailable() {
        String email = uniqueEmail();
        long memberId = signUp(email);
        String access = TestTokens.issuer(Instant.now()).issueAccessToken(memberId, MemberRole.CUSTOMER).tokenValue();
        store.unavailable = true;

        assertRejected(login(email, PASSWORD), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertRejected(reissue("A".repeat(43)), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertRejected(logout(access, "{}"), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
    }

    private long signUp(String email) {
        String body = """
            {"email":"%s","password":"%s","name":"홍길동","agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
            """.formatted(email, PASSWORD);
        MvcTestResult result = mvc.post().uri("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return Long.parseLong(JsonPath.read(body(result), "$.dataBody.memberId"));
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private MvcTestResult reissue(String refreshToken) {
        return mvc.post().uri(REISSUE).contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)).exchange();
    }

    private MvcTestResult logout(String accessToken, String body) {
        return mvc.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertRejected(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static void assertRejected(MvcTestResult result, HttpStatus status, String code, String message) {
        assertRejected(result, status, code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String jtiOf(String accessToken) {
        return JsonPath.read(payloadOf(accessToken), "$.jti");
    }

    private static String subjectOf(String accessToken) {
        return JsonPath.read(payloadOf(accessToken), "$.sub");
    }

    private static String payloadOf(String accessToken) {
        return new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static String uniqueEmail() {
        return "token-" + UUID.randomUUID() + "@example.com";
    }
}
