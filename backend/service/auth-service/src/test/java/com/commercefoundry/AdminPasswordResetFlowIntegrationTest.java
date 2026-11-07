package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "auth.admin-password-reset.enabled=true",
    "auth.admin-password-reset.reset-url=https://admin.example.com/reset",
    "auth.admin-password-reset.smtp-host=smtp.example.com",
    "auth.admin-password-reset.smtp-username=test-user",
    "auth.admin-password-reset.smtp-password=test-fixture-only",
    "auth.admin-password-reset.sender=no-reply@example.com",
    "spring.datasource.url=jdbc:h2:mem:password-reset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS auth"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({InMemoryAuthStoresConfig.class, AdminPasswordResetFlowIntegrationTest.Stores.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminPasswordResetFlowIntegrationTest {
    private static final String OLD = "reset-old-password-1";
    private static final String NEXT = "reset-new-password-2";
    private static final String RESET = "/api/v1/auth/admin/password-reset";
    @Autowired MockMvcTester mvc;
    @Autowired AdminSeedUseCase seeds;
    @Autowired MemberRepositoryPort members;
    @Autowired PasswordEncoder passwords;
    @Autowired ResetStore store;
    @Autowired Mailbox mailbox;
    @Autowired AdjustableClock clock;
    @Autowired InMemoryAdminAccessCutoffStore cutoffs;
    @Autowired AdminAccessCutoffProcessor cutoffProcessor;
    @Autowired AdminPasswordResetAccountProcessor resetAccounts;
    @Autowired MemberLoginProcessor logins;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void clear() {
        store.clear();
        mailbox.link = null;
        mailbox.fail = false;
        mailbox.attempts = 0;
        clock.reset();
        cutoffs.clear();
    }

    @Test
    void successfulResetRejectsOldPasswordOldAccessAndSecondUse() throws Exception {
        Member owner = seed();
        Member target = seed();
        Instant issuedAt = clock.instant();
        assertThat(send(owner, target.id())).hasStatusOk();
        String token = mailbox.token();
        assertThat(mailbox.link).startsWith("https://admin.example.com/reset#token=");
        assertThat(store.values.keySet()).allSatisfy(key -> assertThat(key).hasSize(64).doesNotContain(token));
        assertThat(reset(token)).hasStatusOk();
        Member changed = members.findById(target.id()).orElseThrow();
        assertThat(passwords.matches(OLD, changed.passwordHash())).isFalse();
        assertThat(passwords.matches(NEXT, changed.passwordHash())).isTrue();
        assertThat(cutoffProcessor.issuedBeforeCutoff(target.id(), issuedAt)).isTrue();
        code(reset(token), HttpStatus.BAD_REQUEST, "AUTH_061");
    }

    @Test
    void expiredForgedAndReusedValidFormatLinksShareOneErrorWithoutTokenEcho() throws Exception {
        Member owner = seed();
        assertThat(send(owner, owner.id())).hasStatusOk();
        String reused = mailbox.token();
        assertThat(reset(reused)).hasStatusOk();
        MvcTestResult replay = reset(reused);
        assertThat(send(owner, owner.id())).hasStatusOk();
        String expired = mailbox.token();
        clock.advance(Duration.ofMinutes(16));
        MvcTestResult expiry = reset(expired);
        String forged = "a".repeat(43);
        MvcTestResult forgery = reset(forged);
        for (MvcTestResult result : new MvcTestResult[] {replay, expiry, forgery}) {
            code(result, HttpStatus.BAD_REQUEST, "AUTH_061");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(reused, expired, forged);
            assertThat(JsonPath.<Object>read(result.getResponse().getContentAsString(), "$.dataHeader"))
                .isEqualTo(JsonPath.<Object>read(replay.getResponse().getContentAsString(), "$.dataHeader"));
        }
        code(reset("malformed"), HttpStatus.BAD_REQUEST, "AUTH_061");
    }

    @Test
    void aSecondOutstandingLinkIsInvalidAfterAnotherLinkChangesPassword() throws Exception {
        Member owner = seed();
        send(owner, owner.id());
        String first = mailbox.token();
        send(owner, owner.id());
        String second = mailbox.token();
        assertThat(reset(first)).hasStatusOk();
        code(reset(second), HttpStatus.BAD_REQUEST, "AUTH_061");
    }

    @Test
    void cutoffFailureRollsBackPasswordAndBurnsLinkWithoutRetryRepair() throws Exception {
        Member owner = seed();
        send(owner, owner.id());
        String token = mailbox.token();
        cutoffs.failNextWrite();
        code(reset(token), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(passwords.matches(OLD, members.findById(owner.id()).orElseThrow().passwordHash())).isTrue();
        assertThat(members.findById(owner.id()).orElseThrow().version()).isEqualTo(owner.version());
        assertThat(cutoffs.findCutoff(owner.id())).isEmpty();
        code(reset(token), HttpStatus.BAD_REQUEST, "AUTH_061");
        assertThat(cutoffs.findCutoff(owner.id())).isEmpty();
    }

    @Test
    void resetBeforeLoginRevalidationRejectsOldPasswordSnapshot() throws Exception {
        Member owner = seed();
        Member authenticated = logins.authenticateAdmin(Optional.of(owner), OLD);
        send(owner, owner.id());
        assertThat(reset(mailbox.token())).hasStatusOk();

        assertThatThrownBy(() -> logins.revalidateAdmin(authenticated)).isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_LOGIN_FAILED);
    }

    @Test
    void loginValidatedBeforeResetKeepsCutoffIatWhenSigningFinishesLater() throws Exception {
        Member owner = seed();
        Instant loginStartedAt = clock.instant();
        Member authenticated = logins.revalidateAdmin(logins.authenticateAdmin(Optional.of(owner), OLD));
        send(owner, owner.id());
        assertThat(reset(mailbox.token())).hasStatusOk();
        clock.advance(Duration.ofSeconds(3));

        var issued = TestTokens.issuer(clock.instant()).issueAccessToken(authenticated.id(), authenticated.role(), loginStartedAt);
        Instant actualIat = SignedJWT.parse(issued.tokenValue()).getJWTClaimsSet().getIssueTime().toInstant();
        assertThat(actualIat).isEqualTo(loginStartedAt.truncatedTo(ChronoUnit.SECONDS));
        assertThat(cutoffProcessor.issuedBeforeCutoff(owner.id(), actualIat)).isTrue();
    }

    @Test
    void databaseCommitFailureDoesNotCompensateSuccessfulCutoff() {
        Member owner = seed();
        String hash = passwords.encode(NEXT);
        Instant oldAccessIat = clock.instant();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            resetAccounts.reset(new AdminPasswordResetGrant(owner.id(), owner.version()), hash);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("simulated commit failure"); }
            });
        })).isInstanceOf(IllegalStateException.class);

        assertThat(passwords.matches(OLD, members.findById(owner.id()).orElseThrow().passwordHash())).isTrue();
        assertThat(cutoffProcessor.issuedBeforeCutoff(owner.id(), oldAccessIat)).isTrue();
    }

    @Test
    void absentCustomerAndInactiveTargetsReturnSameSuccessWithoutMail() throws Exception {
        Member owner = seed();
        Member customer = members.save(Member.signUp(78901234, "reset-customer@example.com", passwords.encode(OLD), "고객", null));
        Member inactive = seed();
        members.save(inactive.deactivate(Instant.now()));
        assertThat(send(owner, Long.MAX_VALUE)).hasStatusOk();
        assertThat(send(owner, customer.id())).hasStatusOk();
        assertThat(send(owner, inactive.id())).hasStatusOk();
        assertThat(mailbox.link).isNull();
    }

    @Test
    void sendChecksTokenPermissionAndCurrentDatabaseRepresentativeRole() throws Exception {
        Member owner = seed();
        String uri = "/api/v1/admin/admins/" + owner.id() + "/password-reset-link";
        assertThat(mvc.post().uri(uri).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(owner.id(), MemberRole.CUSTOMER)).exchange())
            .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(owner.id(), MemberRole.MANAGER)).exchange())
            .hasStatus(HttpStatus.FORBIDDEN);
        members.save(owner.changeRole(MemberRole.STAFF));
        code(send(owner, owner.id()), HttpStatus.FORBIDDEN, "AUTH_046");
        assertThat(mailbox.link).isNull();
    }

    @ParameterizedTest
    @CsvSource({"Abcdefgh1,AUTH_141", "\ud55c\uae00abcdefg1,AUTH_142", "abcdefg 12,AUTH_142"})
    void publicResetRejectsInvalidPasswordBeforeConsumingLink(String password, String expectedCode) throws Exception {
        assertInvalidPasswordPreservesLink(password, expectedCode);
    }

    @Test
    void publicResetRejects65CharacterPasswordBeforeConsumingLink() throws Exception {
        assertInvalidPasswordPreservesLink("a".repeat(64) + "1", "AUTH_141");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateStoreFailuresReturnSameEmptyUncacheableSuccessAsAbsentTarget(boolean smtpThenDiscardFailure) throws Exception {
        Member owner = seed();
        MvcTestResult absent = send(owner, Long.MAX_VALUE);
        mailbox.fail = smtpThenDiscardFailure;
        store.failSave = !smtpThenDiscardFailure;
        store.failDiscard = smtpThenDiscardFailure;
        MvcTestResult failed = send(owner, owner.id());
        for (MvcTestResult result : new MvcTestResult[] {absent, failed}) {
            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(JsonPath.<Object>read(result.getResponse().getContentAsString(), "$.dataBody")).isNull();
        }
        assertThat(failed.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString());
        assertThat(mailbox.link).isNull();
        assertThat(mailbox.attempts).isEqualTo(smtpThenDiscardFailure ? 1 : 0);
        assertThat(store.values).hasSize(smtpThenDiscardFailure ? 1 : 0);
        assertThat(store.discardAttempts).isEqualTo(smtpThenDiscardFailure ? 1 : 0);
    }

    @Test
    void limiterOutageReturnsIdentical503ForExistingAndAbsentTargetsWithoutMail() throws Exception {
        Member owner = seed();
        store.failLimit = true;
        MvcTestResult existing = send(owner, owner.id());
        MvcTestResult absent = send(owner, Long.MAX_VALUE);
        code(existing, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        code(absent, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(existing.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString());
        assertThat(mailbox.attempts).isZero();
        assertThat(store.values).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendTargetFourthAttemptIsLimitedEvenForAbsentTarget(boolean absent) throws Exception {
        Member owner = seed();
        long target = absent ? Long.MAX_VALUE : owner.id();
        for (int attempt = 0; attempt < 3; attempt++) { assertThat(send(owner, target)).hasStatusOk(); }
        code(send(owner, target), HttpStatus.TOO_MANY_REQUESTS, "AUTH_062");
        assertThat(mailbox.attempts).isEqualTo(absent ? 0 : 3);
        assertThat(send(owner, absent ? owner.id() : Long.MAX_VALUE)).hasStatusOk();
        clock.advance(Duration.ofMinutes(15));
        assertThat(send(owner, target)).hasStatusOk();
    }

    @Test
    void useClientEleventhAttemptIsLimitedWithoutBlockingAnotherClientOrConsumingLink() throws Exception {
        Member owner = seed();
        assertThat(send(owner, owner.id())).hasStatusOk();
        String token = mailbox.token();
        String subject = "A".repeat(22);
        for (int attempt = 0; attempt < 10; attempt++) {
            code(reset("a".repeat(43), NEXT, subject), HttpStatus.BAD_REQUEST, "AUTH_061");
        }
        code(reset(token, NEXT, subject), HttpStatus.TOO_MANY_REQUESTS, "AUTH_062");
        assertThat(store.values).hasSize(1);
        assertThat(store.consumeAttempts).isEqualTo(10);
        assertThat(reset(token, NEXT, "B".repeat(22))).hasStatusOk();
        clock.advance(Duration.ofMinutes(1));
        code(reset("a".repeat(43), NEXT, subject), HttpStatus.BAD_REQUEST, "AUTH_061");
    }

    @Test
    void mailOutageKeepsGenericResponseAndDiscardsTheGrant() {
        Member owner = seed();
        mailbox.fail = true;
        assertThat(send(owner, owner.id())).hasStatusOk();
        assertThat(store.values).isEmpty();
        assertThat(send(owner, Long.MAX_VALUE)).hasStatusOk();
    }

    private void assertInvalidPasswordPreservesLink(String password, String expectedCode) throws Exception {
        Member owner = seed();
        assertThat(send(owner, owner.id())).hasStatusOk();
        String token = mailbox.token();
        Map<String, ResetStore.Entry> before = Map.copyOf(store.values);
        code(reset(token, password, "A".repeat(22)), HttpStatus.BAD_REQUEST, expectedCode);
        assertThat(store.values).isEqualTo(before);
        assertThat(store.consumeAttempts).isZero();
        assertThat(store.limits).doesNotContainKey("use-client:" + "A".repeat(22));
        assertThat(reset(token)).hasStatusOk();
    }

    private Member seed() {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        seeds.seed(new AdminSeedCommand(email, OLD, "운영자"));
        return members.findByEmail(email).orElseThrow();
    }

    private MvcTestResult send(Member actor, long target) {
        return mvc.post().uri("/api/v1/admin/admins/" + target + "/password-reset-link")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor.id(), MemberRole.ADMIN)).exchange();
    }

    private MvcTestResult reset(String token) {
        return reset(token, NEXT, "");
    }

    private MvcTestResult reset(String token, String password, String subject) {
        return mvc.post().uri(RESET).contentType(MediaType.APPLICATION_JSON).header("X-Client-Subject", subject)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}").exchange();
    }

    private static String bearer(long id, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(id, role).tokenValue();
    }

    private static void code(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.dataHeader.resultCode")).isEqualTo(code);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stores {
        @Bean @Primary ResetStore resetStore(AdjustableClock clock) { return new ResetStore(clock); }
        @Bean @Primary Mailbox mailbox() { return new Mailbox(); }
    }

    static class Mailbox implements AdminPasswordResetMailPort {
        String link;
        boolean fail;
        int attempts;
        @Override public void send(String recipient, String link) {
            attempts++;
            if (fail) { throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE); }
            this.link = link;
        }
        String token() { return link.substring(link.indexOf("#token=") + 7); }
    }

    static class ResetStore implements AdminPasswordResetTokenPort {
        final Map<String, Entry> values = new HashMap<>();
        final Map<String, Window> limits = new HashMap<>();
        final AdjustableClock clock;
        boolean failSave;
        boolean failDiscard;
        boolean failLimit;
        int consumeAttempts;
        int discardAttempts;
        ResetStore(AdjustableClock clock) { this.clock = clock; }
        @Override public synchronized void save(String fingerprint, AdminPasswordResetGrant grant, Duration ttl) {
            if (failSave) { throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE); }
            values.put(fingerprint, new Entry(grant, clock.instant().plus(ttl)));
        }
        @Override public synchronized Optional<AdminPasswordResetGrant> consume(String fingerprint) {
            consumeAttempts++;
            Entry entry = values.remove(fingerprint);
            return entry == null || !clock.instant().isBefore(entry.expires()) ? Optional.empty() : Optional.of(entry.grant());
        }
        @Override public synchronized void discard(String fingerprint) {
            discardAttempts++;
            if (failDiscard) { throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE); }
            values.remove(fingerprint);
        }
        @Override public synchronized void limit(String dimension, int maximum, Duration window) {
            if (failLimit) { throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE); }
            Instant now = clock.instant();
            Window current = limits.get(dimension);
            if (current == null || !now.isBefore(current.expires())) { current = new Window(0, now.plus(window)); }
            Window next = new Window(current.count() + 1, current.expires());
            limits.put(dimension, next);
            if (next.count() > maximum) { throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_LIMITED); }
        }
        synchronized void clear() {
            values.clear();
            limits.clear();
            failSave = false;
            failDiscard = false;
            failLimit = false;
            consumeAttempts = 0;
            discardAttempts = 0;
        }
        record Window(int count, Instant expires) { }
        record Entry(AdminPasswordResetGrant grant, Instant expires) { }
    }
}
