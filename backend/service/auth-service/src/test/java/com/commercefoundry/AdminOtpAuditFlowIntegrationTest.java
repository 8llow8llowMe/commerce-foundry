package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpRecoveryCodeRepository;
import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpEnrollmentStorePort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Base32;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.Totp;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.commercefoundry.support.InMemoryLoginAttemptStore;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 JWT·보안 체인·OTP 트랜잭션·감사 로그/DB. Redis 포트만 메모리와 장애 주입으로 대체한다. 테스트 자체에는 트랜잭션이 없다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:otp-audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS auth")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AdminOtpAuditFlowIntegrationTest {

    private static final String OTP = "/api/v1/admin/me/otp";
    private static final String PASSWORD = "otp-audit-password-2026";
    private static final String EXCEPTION_SECRET = "exception-message-sentinel";
    @Autowired private MockMvcTester mvc;
    @Autowired private AdminSeedUseCase seeds;
    @Autowired private MemberRepositoryPort members;
    @Autowired private AdminOtpProcessor otp;
    @Autowired private AdminOtpRepositoryPort credentials;
    @Autowired private AdminAuditLogRepositoryPort audits;
    @Autowired private AdjustableClock clock;
    @Autowired private InMemoryLoginAttemptStore attempts;
    @Autowired private InMemoryAdminAccessCutoffStore cutoffs;
    @MockitoBean private AdminOtpEnrollmentStorePort enrollment;
    @Autowired private AdminAuditLogRepository auditRepository;
    @MockitoSpyBean private AdminOtpRecoveryCodeRepository recoveryRepository;
    private final Map<Long, byte[]> pending = new HashMap<>();
    private MethodInterceptor auditCommitFailure;

    private record Operator(Member member, String jwt) {
        long id() { return member.id(); }
    }

    @AfterEach
    void resetStores() {
        if (auditCommitFailure != null) {
            ((Advised) auditRepository).removeAdvice(auditCommitFailure);
            auditCommitFailure = null;
        }
        attempts.unavailable = false;
        attempts.clear();
        cutoffs.clear();
        clock.reset();
    }

    private void enrollmentStore() {
        doAnswer(call -> {
            pending.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(enrollment).save(anyLong(), any(byte[].class), any(Duration.class));
        doAnswer(call -> Optional.ofNullable(pending.get(call.getArgument(0)))).when(enrollment).find(anyLong());
        doAnswer(call -> { pending.remove(call.getArgument(0)); return null; }).when(enrollment).delete(anyLong());
    }

    @Test
    void selfHttpActionsCommitAndRecordExactlyOneRowAndLog(CapturedOutput output) throws Exception {
        enrollmentStore();
        Operator actor = operator();
        MvcTestResult start = post(OTP + "/enrollment", actor, null);
        assertThat(start).hasStatusOk();
        noStore(start);
        String secret = JsonPath.read(body(start), "$.dataBody.secret");
        String uri = JsonPath.read(body(start), "$.dataBody.otpauthUri");
        assertThat(secret).isNotBlank();
        assertNoAudit(actor, output);
        String code = Totp.code(Base32.decode(secret), Totp.timeStep(clock.instant()));
        MvcTestResult confirmed = post(OTP, actor, code);
        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        noStore(confirmed);
        List<String> firstCodes = codes(confirmed);
        assertThat(firstCodes).hasSize(8);
        assertThat(otp.status(actor.id()).enrolled()).isTrue();
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_ENROLL", "SUCCESS");
        MvcTestResult regenerated = post(OTP + "/recovery-codes", actor, firstCodes.getFirst());
        assertThat(regenerated).hasStatusOk();
        noStore(regenerated);
        List<String> nextCodes = codes(regenerated);
        assertThat(nextCodes).hasSize(8).doesNotContainAnyElementsOf(firstCodes);
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_RECOVERY_REGENERATE", "SUCCESS");
        assertThat(post(OTP + "/deactivation", actor, nextCodes.getFirst())).hasStatusOk();
        assertThat(otp.status(actor.id()).enrolled()).isFalse();
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_DISABLE", "SUCCESS");
        assertSecretsAbsent(output, actor, secret, uri, code);
        for (String recovery : firstCodes) assertThat(output.getAll()).doesNotContain(recovery);
        for (String recovery : nextCodes) assertThat(output.getAll()).doesNotContain(recovery);
    }

    @Test
    void peerCommitCutoffAndJwtActorHaveOneAudit(CapturedOutput output) throws Exception {
        Operator actor = operator();
        Operator target = operator();
        List<String> actorCodes = enrollDirectly(actor);
        enrollDirectly(target);
        long previous = credentials.findByMemberId(target.id()).orElseThrow().id();
        MvcTestResult recovered = post(peerPath(target), actor, actorCodes.getFirst());
        assertThat(recovered).hasStatusOk();
        noStore(recovered);
        assertThat(codes(recovered)).hasSize(8);
        assertThat(credentials.findByMemberId(target.id()).orElseThrow().id()).isNotEqualTo(previous);
        assertThat(cutoffs.findCutoff(target.id())).isPresent();
        assertAudit(output, actor, target.id(), "ADMIN_OTP_PEER_RECOVERY", "SUCCESS");
        assertSecretsAbsent(output, actor, actorCodes.getFirst());
        for (String recovery : codes(recovered)) assertThat(output.getAll()).doesNotContain(recovery);
    }

    @Test
    void businessFailuresHaveExactlyOneFailureAudit(CapturedOutput output) throws Exception {
        Operator confirm = operator();
        assertCode(post(OTP, confirm, "123456"), HttpStatus.BAD_REQUEST, "AUTH_038");
        assertAudit(output, confirm, confirm.id(), "ADMIN_OTP_ENROLL", "FAILURE");
        Operator disable = operator();
        assertCode(post(OTP + "/deactivation", disable, "123456"), HttpStatus.NOT_FOUND, "AUTH_036");
        assertAudit(output, disable, disable.id(), "ADMIN_OTP_DISABLE", "FAILURE");
        Operator regenerate = operator();
        assertCode(post(OTP + "/recovery-codes", regenerate, "123456"), HttpStatus.NOT_FOUND, "AUTH_036");
        assertAudit(output, regenerate, regenerate.id(), "ADMIN_OTP_RECOVERY_REGENERATE", "FAILURE");
        Operator peer = operator();
        assertCode(post(peerPath(peer), peer, "123456"), HttpStatus.BAD_REQUEST, "AUTH_045");
        assertAudit(output, peer, peer.id(), "ADMIN_OTP_PEER_RECOVERY", "FAILURE");
    }

    @Test
    void admissionAndHttpRejectionsHaveNoAuditButLaterRead503Does(CapturedOutput output) throws Exception {
        Operator actor = operator();
        assertThat(mvc.post().uri(OTP).contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri(OTP).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.jwt())
            .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        String customer = TestTokens.issuer(Instant.now()).issueAccessToken(actor.id(), MemberRole.CUSTOMER).tokenValue();
        assertThat(mvc.post().uri(OTP).header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
            .contentType(MediaType.APPLICATION_JSON).content(request("123456")).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        attempts.unavailable = true;
        assertCode(post(OTP, actor, "123456"), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        attempts.unavailable = false;
        for (int i = 0; i < 5; i++) attempts.record(AttemptKind.ADMIN_OTP, String.valueOf(actor.id()), Duration.ofMinutes(15));
        assertCode(post(OTP, actor, "123456"), HttpStatus.TOO_MANY_REQUESTS, "AUTH_043");
        assertNoAudit(actor, output);
        attempts.clear();
        doThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).when(enrollment).find(actor.id());
        assertCode(post(OTP, actor, "123456"), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_ENROLL", "FAILURE");
    }

    @Test
    void actualProcessorsSwallowKnownDiscardAndResetFailures(CapturedOutput output) throws Exception {
        enrollmentStore();
        Operator actor = operator();
        assertThat(post(OTP + "/enrollment", actor, null)).hasStatusOk();
        String code = Totp.code(pending.get(actor.id()), Totp.timeStep(clock.instant()));
        doThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).when(enrollment).delete(actor.id());
        doAnswer(call -> {
            attempts.unavailable = true; // admission 이후에 장애: 실제 LoginGuardProcessor의 reset best-effort를 탄다.
            return Optional.of(pending.get(actor.id()));
        }).when(enrollment).find(actor.id());
        MvcTestResult result = post(OTP, actor, code);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        noStore(result);
        assertThat(codes(result)).hasSize(8);
        assertThat(otp.status(actor.id()).enrolled()).isTrue();
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_ENROLL", "SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditSaveOrCommitFailureKeepsBusinessCommitAndResponse(boolean commit, CapturedOutput output) throws Exception {
        enrollmentStore();
        Operator actor = operator();
        post(OTP + "/enrollment", actor, null);
        String code = Totp.code(pending.get(actor.id()), Totp.timeStep(clock.instant()));
        failAudit(commit);
        MvcTestResult result = post(OTP, actor, code);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        noStore(result);
        assertThat(codes(result)).hasSize(8);
        assertThat(otp.status(actor.id()).enrolled()).isTrue();
        assertThat(audits.findPage("ADMIN_OTP_ENROLL", actor.id(), null, null, 0, 100)).isEmpty();
        assertSingleLog(output, actor, actor.id(), "ADMIN_OTP_ENROLL", "SUCCESS");
        assertSecretsAbsent(output, actor, EXCEPTION_SECRET, "cause-sentinel", code);
    }

    @Test
    void businessRollbackPreservesOldRecoveryCodesAndRecordsFailure(CapturedOutput output) throws Exception {
        Operator actor = operator();
        List<String> old = enrollDirectly(actor);
        List<String> oldHashes = credentials.findUnusedRecoveryCodes(actor.id()).stream().map(row -> row.codeHash()).toList();
        doThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).when(recoveryRepository).saveAllAndFlush(any());
        assertCode(post(OTP + "/recovery-codes", actor, old.getFirst()), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(credentials.findUnusedRecoveryCodes(actor.id()).stream().map(row -> row.codeHash()).toList()).containsExactlyElementsOf(oldHashes);
        assertAudit(output, actor, actor.id(), "ADMIN_OTP_RECOVERY_REGENERATE", "FAILURE");
        assertSecretsAbsent(output, actor, old.getFirst());
        for (String hash : oldHashes) assertThat(output.getAll()).doesNotContain(hash);
    }

    @Test
    void peerAuditAndCutoffFailuresStillCommitAndKeepSuccessLog(CapturedOutput output) throws Exception {
        Operator actor = operator();
        Operator target = operator();
        List<String> actorCodes = enrollDirectly(actor);
        enrollDirectly(target);
        long previous = credentials.findByMemberId(target.id()).orElseThrow().id();
        failAudit(true);
        cutoffs.failNextWrite();
        assertCode(post(peerPath(target), actor, actorCodes.getFirst()), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(credentials.findByMemberId(target.id()).orElseThrow().id()).isNotEqualTo(previous);
        assertThat(otp.status(actor.id()).remainingRecoveryCodes()).isEqualTo(7);
        assertThat(cutoffs.findCutoff(target.id())).isEmpty();
        assertThat(audits.findPage("ADMIN_OTP_PEER_RECOVERY", actor.id(), null, null, 0, 100)).isEmpty();
        assertSingleLog(output, actor, target.id(), "ADMIN_OTP_PEER_RECOVERY", "SUCCESS");
        assertSecretsAbsent(output, actor, EXCEPTION_SECRET, "cause-sentinel", actorCodes.getFirst());
    }

    private void failAudit(boolean commit) {
        RuntimeException failure = new IllegalStateException(EXCEPTION_SECRET, new IllegalArgumentException("cause-sentinel"));
        auditCommitFailure = invocation -> {
            if (!invocation.getMethod().getName().equals("saveAndFlush")) {
                return invocation.proceed();
            }
            if (!commit) {
                throw failure;
            }
            Object saved = invocation.proceed();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw failure; }
            });
            return saved;
        };
        ((Advised) auditRepository).addAdvice(0, auditCommitFailure);
    }

    private Operator operator() {
        String email = "otp-audit-" + UUID.randomUUID() + "@example.com";
        seeds.seed(new AdminSeedCommand(email, PASSWORD, "OTP감사센티넬"));
        Member member = members.findByEmail(email).orElseThrow();
        return new Operator(member, TestTokens.issuer(Instant.now()).issueAccessToken(member.id(), MemberRole.ADMIN).tokenValue());
    }

    private List<String> enrollDirectly(Operator actor) {
        byte[] secret = new byte[Totp.SECRET_BYTES];
        new java.security.SecureRandom().nextBytes(secret);
        var issued = otp.issueRecoveryCodes();
        otp.confirm(actor.id(), secret, Totp.timeStep(clock.instant()) - 2, clock.instant(), issued.hashes());
        return issued.codes();
    }

    private MvcTestResult post(String path, Operator actor, String code) {
        var request = mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.jwt());
        return code == null ? request.exchange() : request.contentType(MediaType.APPLICATION_JSON).content(request(code)).exchange();
    }

    private static String request(String code) { return "{\"currentPassword\":\"%s\",\"code\":\"%s\"}".formatted(PASSWORD, code); }
    private static String peerPath(Operator target) { return "/api/v1/admin/admins/" + target.id() + "/otp/recovery"; }
    private static String body(MvcTestResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    private static List<String> codes(MvcTestResult result) throws Exception { return JsonPath.read(body(result), "$.dataBody.recoveryCodes"); }
    private static void noStore(MvcTestResult result) { assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store"); }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.dataHeader.resultCode")).isEqualTo(code);
    }

    private void assertAudit(CapturedOutput output, Operator actor, long target, String action, String result) {
        assertThat(audits.findPage(action, actor.id(), null, null, 0, 100).getContent()).singleElement().satisfies(row -> {
            assertThat(row.actorMemberId()).isEqualTo(actor.id());
            assertThat(row.target()).isEqualTo("member:" + target);
            assertThat(row.result()).isEqualTo(result);
        });
        assertSingleLog(output, actor, target, action, result);
    }

    private static void assertSingleLog(CapturedOutput output, Operator actor, long target, String action, String result) {
        assertThat(output.getAll().lines().filter(line -> line.contains("action=" + action + " actorMemberId=" + actor.id() + " ")).toList())
            .singleElement().asString().contains("target=member:" + target + " result=" + result);
    }

    private void assertNoAudit(Operator actor, CapturedOutput output) {
        assertThat(audits.findPage(null, actor.id(), null, null, 0, 100)).isEmpty();
        assertThat(output.getAll()).doesNotContain("actorMemberId=" + actor.id() + " ");
    }

    private static void assertSecretsAbsent(CapturedOutput output, Operator actor, String... secrets) throws Exception {
        assertThat(actor.member().passwordHash()).startsWith("$2");
        String jti = SignedJWT.parse(actor.jwt()).getJWTClaimsSet().getJWTID();
        assertThat(jti).isNotBlank();
        assertThat(output.getAll()).doesNotContain(PASSWORD, "OTP감사센티넬", actor.member().email(), actor.member().passwordHash(), actor.jwt(), jti);
        assertThat(output.getAll()).doesNotContain(secrets);
    }
}
