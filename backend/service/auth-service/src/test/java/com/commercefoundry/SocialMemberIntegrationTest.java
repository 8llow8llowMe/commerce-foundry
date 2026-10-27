package com.commercefoundry;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.SocialIdentityRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberAgreementRepository;
import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.service.processor.SocialMemberProcessor;
import com.commercefoundry.domainlayer.member.domain.model.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SocialMemberIntegrationTest {
    @Autowired SocialMemberProcessor processor;
    @Autowired MemberRepositoryPort members;
    @Autowired SocialIdentityRepository identities;
    @Autowired MemberAgreementRepository agreements;
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuits;

    @Test
    void newIdentityAndThreeAgreementsCommitTogetherExistingIdentitySurvivesEmailChange() {
        var profile = profile(unique(), unique() + "@example.com");
        var member = processor.register(profile, new SignUpAgreements(true, true, false), processor.hashRandomPassword());
        assertThat(member.isCustomer()).isTrue();
        assertThat(member.passwordHash()).startsWith("$2");
        assertThat(identities.findByProviderAndSubject(profile.provider(), profile.subject())).hasValueSatisfying(identity ->
            assertThat(identity.getMemberId()).isEqualTo(member.id()));
        assertThat(agreements.findAll()).filteredOn(agreement -> agreement.getMemberId().equals(member.id())).hasSize(3);
        assertThat(processor.find(new SocialProfile(profile.provider(), profile.subject(), null, false, null))).contains(member);
    }

    @Test
    void missingAgreementsAndUntrustedEmailNeverCreateMember() {
        var profile = profile(unique(), unique() + "@example.com");
        assertThatThrownBy(() -> processor.register(profile, null, "hash")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_AGREEMENTS_REQUIRED);
        assertThat(members.findByEmail(profile.email())).isEmpty();
        assertThatThrownBy(() -> processor.register(new SocialProfile(SocialProvider.KAKAO, unique(), profile.email(), false, "고객"),
            new SignUpAgreements(true, true, false), "hash")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
    }

    @Test
    void existingCustomerOrAdminEmailNeverLinks() {
        for (boolean admin : new boolean[]{false, true}) {
            String email = unique() + "@example.com";
            Member existing = admin ? Member.admin(System.nanoTime(), email, "hash", "관리자") : Member.signUp(System.nanoTime(), email, "hash", "고객", null);
            members.save(existing);
            var profile = profile(unique(), email);
            assertThatThrownBy(() -> processor.register(profile, new SignUpAgreements(true, true, false), "hash"))
                .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
            assertThat(processor.find(profile)).isEmpty();
        }
    }

    @Test
    void duplicateIdentityRollsBackNewMemberAndAgreements() {
        var first = profile(unique(), unique() + "@example.com");
        processor.register(first, new SignUpAgreements(true, true, false), "hash");
        var second = profile(first.subject(), unique() + "@example.com");
        long before = agreements.count();
        assertThatThrownBy(() -> processor.register(second, new SignUpAgreements(true, true, false), "hash")).satisfies(this::assertIdentityConflict);
        assertThat(members.findByEmail(second.email())).isEmpty();
        assertThat(agreements.count()).isEqualTo(before);
    }

    @Test
    void concurrentSameIdentityCreatesExactlyOneAccountAndNoOrphan() throws Exception {
        String subject = unique();
        var first = profile(subject, unique() + "@example.com");
        var second = profile(subject, unique() + "@example.com");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(() -> registerAfter(start, first));
            Future<Boolean> two = executor.submit(() -> registerAfter(start, second));
            start.countDown();
            assertThat(java.util.List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        long existing = java.util.stream.Stream.of(first.email(), second.email()).filter(email -> members.findByEmail(email).isPresent()).count();
        assertThat(existing).isEqualTo(1);
        assertThat(identities.findByProviderAndSubject(SocialProvider.KAKAO, subject)).isPresent();
    }

    @Test
    void providerCircuitsLoadConfiguredClientRejectionExclusion() {
        var kakao = circuits.circuitBreaker("kakao");
        var naver = circuits.circuitBreaker("naver");
        assertThat(kakao).isNotSameAs(naver);
        assertThat(kakao.getCircuitBreakerConfig().getIgnoreExceptionPredicate().test(
            new com.commercefoundry.domainlayer.member.adapter.out.client.social.SocialProviderHttpAdapter.Rejected())).isTrue();
        assertThat(kakao.getCircuitBreakerConfig().getIgnoreExceptionPredicate().test(new IllegalStateException())).isFalse();
    }

    private boolean registerAfter(CountDownLatch start, SocialProfile profile) throws Exception {
        start.await();
        try {
            processor.register(profile, new SignUpAgreements(true, true, false), "hash");
            return true;
        } catch (RuntimeException conflict) {
            assertIdentityConflict(conflict);
            return false;
        }
    }

    private void assertIdentityConflict(Throwable error) {
        if (error instanceof MemberException member) {
            assertThat(member.getErrorCode()).isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
            return;
        }
        assertThat(error).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Throwable cause = ((org.springframework.dao.DataIntegrityViolationException) error).getMostSpecificCause();
        assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23505");
        assertThat(cause.getMessage().toLowerCase(java.util.Locale.ROOT)).contains("uk_social_identity_provider_subject");
    }

    private SocialProfile profile(String subject, String email) {
        return new SocialProfile(SocialProvider.KAKAO, subject, email, true, "고객");
    }

    private String unique() {
        return UUID.randomUUID().toString();
    }
}
