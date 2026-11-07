package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.AdjustableClock;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    @AfterEach
    void clear() {
        store.values.clear();
        mailbox.link = null;
        mailbox.fail = false;
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
    void expiredForgedAndMalformedLinksShareOneError() throws Exception {
        Member owner = seed();
        assertThat(send(owner, owner.id())).hasStatusOk();
        String token = mailbox.token();
        clock.advance(Duration.ofMinutes(16));
        code(reset(token), HttpStatus.BAD_REQUEST, "AUTH_061");
        code(reset("a".repeat(43)), HttpStatus.BAD_REQUEST, "AUTH_061");
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
    void cutoffFailureCommitsPasswordAndBurnsLinkWithoutRetryRepair() throws Exception {
        Member owner = seed();
        send(owner, owner.id());
        String token = mailbox.token();
        cutoffs.failNextWrite();
        code(reset(token), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(passwords.matches(NEXT, members.findById(owner.id()).orElseThrow().passwordHash())).isTrue();
        assertThat(cutoffs.findCutoff(owner.id())).isEmpty();
        code(reset(token), HttpStatus.BAD_REQUEST, "AUTH_061");
        assertThat(cutoffs.findCutoff(owner.id())).isEmpty();
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

    @Test
    void publicResetValidatesPasswordBeforeConsumingLink() throws Exception {
        Member owner = seed();
        send(owner, owner.id());
        String token = mailbox.token();
        assertThat(mvc.post().uri(RESET).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\"}").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(reset(token)).hasStatusOk();
    }

    @Test
    void mailOutageKeepsGenericResponseAndDiscardsTheGrant() {
        Member owner = seed();
        mailbox.fail = true;
        assertThat(send(owner, owner.id())).hasStatusOk();
        assertThat(store.values).isEmpty();
        assertThat(send(owner, Long.MAX_VALUE)).hasStatusOk();
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
        return mvc.post().uri(RESET).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEXT + "\"}").exchange();
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
        @Override public void send(String recipient, String link) {
            if (fail) { throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE); }
            this.link = link;
        }
        String token() { return link.substring(link.indexOf("#token=") + 7); }
    }

    static class ResetStore implements AdminPasswordResetTokenPort {
        final Map<String, Entry> values = new HashMap<>();
        final AdjustableClock clock;
        ResetStore(AdjustableClock clock) { this.clock = clock; }
        @Override public synchronized void save(String fingerprint, AdminPasswordResetGrant grant, Duration ttl) {
            values.put(fingerprint, new Entry(grant, clock.instant().plus(ttl)));
        }
        @Override public synchronized Optional<AdminPasswordResetGrant> consume(String fingerprint) {
            Entry entry = values.remove(fingerprint);
            return entry == null || !clock.instant().isBefore(entry.expires()) ? Optional.empty() : Optional.of(entry.grant());
        }
        @Override public synchronized void discard(String fingerprint) { values.remove(fingerprint); }
        @Override public void limit(String dimension, int maximum, Duration window) { }
        record Entry(AdminPasswordResetGrant grant, Instant expires) { }
    }
}
