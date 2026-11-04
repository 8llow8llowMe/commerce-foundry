package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.commercefoundry.support.InMemoryRefreshTokenStore;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 실제 서명 JWT · 보안 체인 · Facade · Processor · H2 커밋. Redis만 인메모리 장애 주입이며 네트워크 장애 시험은 아니다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminAccountAuditFlowIntegrationTest {

    private static final String PASSWORD = "audit-password-2026";
    private static final String NEXT_PASSWORD = "next-password-2026";
    private static final String NAME = "감사전용이름";

    @Autowired
    private MockMvcTester mvc;
    @Autowired
    private AdminSeedUseCase seeds;
    @Autowired
    private MemberRepositoryPort members;
    @Autowired
    private AdminAuditLogRepositoryPort audits;
    @Autowired
    private InMemoryAdminAccessCutoffStore cutoffs;
    @Autowired
    private InMemoryRefreshTokenStore tokens;
    @Autowired
    private AdjustableClock clock;
    @Autowired
    private PasswordEncoder encoder;

    @AfterEach
    void restoreStores() {
        cutoffs.clear();
        tokens.unavailable = false;
        clock.reset();
    }

    @Test
    void successfulAccountActionsRecordJwtActorAndPersistedTargets(CapturedOutput output) throws Exception {
        Member actor = seed();
        String jwt = jwt(actor.id(), MemberRole.ADMIN);
        String email = uniqueEmail();
        MvcTestResult create = create(jwt, email);
        assertThat(create).hasStatus(HttpStatus.CREATED);
        long createdId = Long.parseLong(JsonPath.read(body(create), "$.dataBody.memberId"));
        assertThat(members.findById(createdId)).hasValueSatisfying(member -> {
            assertThat(member.email()).isEqualTo(email);
            assertThat(encoder.matches(PASSWORD, member.passwordHash())).isTrue();
        });
        assertAudit("ADMIN_CREATE", actor.id(), "member:" + createdId, "SUCCESS");

        assertThat(deactivate(jwt, createdId)).hasStatusOk();
        assertThat(members.findById(createdId).orElseThrow().deactivatedAt()).isNotNull();
        assertThat(cutoffs.findCutoff(createdId)).isPresent();
        assertAudit("ADMIN_DEACTIVATE", actor.id(), "member:" + createdId, "SUCCESS");

        assertThat(password(jwt, PASSWORD)).hasStatusOk();
        assertThat(encoder.matches(NEXT_PASSWORD, members.findById(actor.id()).orElseThrow().passwordHash())).isTrue();
        assertThat(tokens.accessTokenRevoked(SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID())).isTrue();
        assertAudit("ADMIN_PASSWORD_CHANGE", actor.id(), "member:" + actor.id(), "SUCCESS");
        assertSingleLog(output, "ADMIN_CREATE", actor.id(), "SUCCESS");
        assertSingleLog(output, "ADMIN_DEACTIVATE", actor.id(), "SUCCESS");
        assertSingleLog(output, "ADMIN_PASSWORD_CHANGE", actor.id(), "SUCCESS");
        assertThat(output.getAll()).contains("action=ADMIN_CREATE actorMemberId=" + actor.id(), "result=SUCCESS")
            .doesNotContain(email, NAME, PASSWORD, NEXT_PASSWORD, jwt, SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deactivationCommitSurvivesCutoffOrJtiFailure(boolean failRevocation, CapturedOutput output) throws Exception {
        Member actor = seed();
        seed(); // 본인 비활성화의 마지막 대표 관리자 가드를 통과한다.
        String jwt = jwt(actor.id(), MemberRole.ADMIN);
        failInvalidation(failRevocation);

        assertUnavailable(deactivate(jwt, actor.id()));

        assertThat(members.findById(actor.id()).orElseThrow().deactivatedAt()).isNotNull();
        assertThat(cutoffs.findCutoff(actor.id()).isPresent()).isEqualTo(failRevocation);
        assertAudit("ADMIN_DEACTIVATE", actor.id(), "member:" + actor.id(), "FAILURE");
        assertSingleLog(output, "ADMIN_DEACTIVATE", actor.id(), "FAILURE");
        assertNoSecrets(output, jwt, actor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordCommitSurvivesCutoffOrJtiFailure(boolean failRevocation, CapturedOutput output) throws Exception {
        Member actor = seed();
        String jwt = jwt(actor.id(), MemberRole.ADMIN);
        failInvalidation(failRevocation);

        assertUnavailable(password(jwt, PASSWORD));

        assertThat(encoder.matches(NEXT_PASSWORD, members.findById(actor.id()).orElseThrow().passwordHash())).isTrue();
        assertThat(cutoffs.findCutoff(actor.id()).isPresent()).isEqualTo(failRevocation);
        assertAudit("ADMIN_PASSWORD_CHANGE", actor.id(), "member:" + actor.id(), "FAILURE");
        assertSingleLog(output, "ADMIN_PASSWORD_CHANGE", actor.id(), "FAILURE");
        assertNoSecrets(output, jwt, actor);
    }

    @Test
    void businessRejectionsAreAuditedOnceAndKeepExistingCodes() throws Exception {
        Member actor = seed();
        String jwt = jwt(actor.id(), MemberRole.ADMIN);
        assertCode(create(jwt, actor.email()), HttpStatus.CONFLICT, "AUTH_002");
        assertAudit("ADMIN_CREATE", actor.id(), null, "FAILURE");
        assertCode(deactivate(jwt, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "AUTH_033");
        assertAudit("ADMIN_DEACTIVATE", actor.id(), "member:" + Long.MAX_VALUE, "FAILURE");
        assertCode(password(jwt, "wrong-password-2026"), HttpStatus.BAD_REQUEST, "AUTH_032");
        assertAudit("ADMIN_PASSWORD_CHANGE", actor.id(), "member:" + actor.id(), "FAILURE");
    }

    @Test
    void entryRejectionsHaveNoAuditButBusinessForbiddenDoes() throws Exception {
        Member actor = seed();
        String adminJwt = jwt(actor.id(), MemberRole.ADMIN);
        MvcTestResult invalid = mvc.post().uri("/api/v1/admin/admins").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt)
            .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(create("", uniqueEmail())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(create(jwt(actor.id(), MemberRole.STAFF), uniqueEmail())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(audits.findPage(null, actor.id(), null, null, 0, 100)).isEmpty();

        // JWT의 권한 통과와 업무의 현재 DB 역할 검증을 분리한다: 발급 후 역할이 바뀐 토큰.
        members.save(Member.restore(actor.id(), actor.email(), actor.passwordHash(), actor.name(), null, MemberRole.STAFF, actor.version()));
        Member target = seed();
        assertCode(deactivate(adminJwt, target.id()), HttpStatus.FORBIDDEN, "AUTH_046");
        assertAudit("ADMIN_DEACTIVATE", actor.id(), "member:" + target.id(), "FAILURE");
    }

    private void failInvalidation(boolean revocation) {
        if (revocation) {
            tokens.unavailable = true;
        } else {
            cutoffs.failNextWrite();
        }
    }

    private Member seed() {
        String email = uniqueEmail();
        seeds.seed(new AdminSeedCommand(email, PASSWORD, NAME));
        return members.findByEmail(email).orElseThrow();
    }

    private void assertAudit(String action, long actor, String target, String result) {
        List<AdminAuditLog> rows = audits.findPage(action, actor, null, null, 0, 100).getContent();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.actorMemberId()).isEqualTo(actor);
            assertThat(row.target()).isEqualTo(target);
            assertThat(row.result()).isEqualTo(result);
        });
    }

    private static void assertSingleLog(CapturedOutput output, String action, long actor, String result) {
        String prefix = "action=" + action + " actorMemberId=" + actor + " ";
        assertThat(output.getAll().lines().filter(line -> line.contains(prefix)).toList())
            .singleElement().asString().contains("result=" + result).doesNotContain(PASSWORD, NEXT_PASSWORD, NAME);
    }

    private static void assertNoSecrets(CapturedOutput output, String jwt, Member actor) throws Exception {
        assertThat(output.getAll())
            .doesNotContain(actor.email(), NAME, PASSWORD, NEXT_PASSWORD, jwt, SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID());
    }

    private MvcTestResult create(String jwt, String email) {
        return mvc.post().uri("/api/v1/admin/admins").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"%s\"}".formatted(email, PASSWORD, NAME)).exchange();
    }

    private MvcTestResult deactivate(String jwt, long target) {
        return mvc.patch().uri("/api/v1/admin/admins/" + target).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt).exchange();
    }

    private MvcTestResult password(String jwt, String currentPassword) {
        return mvc.patch().uri("/api/v1/admin/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(currentPassword, NEXT_PASSWORD)).exchange();
    }

    private static String jwt(long memberId, MemberRole role) {
        return TestTokens.issuer(Instant.now()).issueAccessToken(memberId, role).tokenValue();
    }

    private static void assertUnavailable(MvcTestResult result) throws Exception {
        assertCode(result, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.dataHeader.resultCode")).isEqualTo(code);
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String uniqueEmail() {
        return "account-audit-" + UUID.randomUUID() + "@example.com";
    }
}
