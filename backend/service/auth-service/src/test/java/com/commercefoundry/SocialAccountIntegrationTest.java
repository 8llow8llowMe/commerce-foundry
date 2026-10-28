package com.commercefoundry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.commercefoundry.domainlayer.member.application.command.SocialLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.*;
import com.commercefoundry.domainlayer.member.application.service.SocialAccountWebFacade;
import com.commercefoundry.domainlayer.member.application.service.SocialAuthWebFacade;
import com.commercefoundry.domainlayer.member.application.service.processor.*;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberAgreementRepository;
import com.commercefoundry.domainlayer.member.domain.model.*;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class SocialAccountIntegrationTest {
    @Autowired SocialAccountProcessor accounts;
    @Autowired SocialAccountWebFacade facade;
    @Autowired SocialAuthWebFacade socialLogin;
    @Autowired MemberLoginProcessor passwordLogin;
    @Autowired MemberRepositoryPort members;
    @Autowired MemberRepository memberRows;
    @Autowired MemberAgreementRepository agreements;
    @Autowired SocialIdentityRepositoryPort identities;
    @Autowired PasswordEncoder encoder;
    @MockitoBean SocialProviderPort provider;
    @MockitoBean SocialStateStorePort states;
    @MockitoBean LoginAttemptCounterPort attempts;
    @MockitoBean RefreshTokenStorePort refresh;

    @Test
    void linkFreshProofRetryThenPasswordAndSocialLoginKeepSameMemberWithoutChangingProfileOrAgreements() {
        Member member = customer();
        long memberCount = memberRows.count();
        long agreementCount = agreements.count();
        String state = "s".repeat(43), verifier = "v".repeat(43);
        String freshState = "f".repeat(43);
        SocialProfile profile = profile(unique());
        when(attempts.record(eq(AttemptKind.LOGIN), eq(member.email()), any())).thenReturn(new LoginAttemptOutcome(1, Duration.ofMinutes(15)));
        when(states.saveLink(anyString(), eq(member.id()), eq(SocialProvider.KAKAO), anyString(), any())).thenReturn(true);
        when(provider.authorizeUrl(eq(SocialProvider.KAKAO), anyString())).thenReturn("https://example.com");
        assertThat(facade.authorize(member.id(), "kakao", "password123").state()).hasSize(43);
        when(states.consumeLink(anyString(), eq(member.id()), eq(SocialProvider.KAKAO), anyString())).thenReturn(true, true, false);
        when(provider.authenticate(SocialProvider.KAKAO, "code", state)).thenReturn(profile);
        when(provider.authenticate(SocialProvider.KAKAO, "fresh-code", freshState)).thenReturn(profile);
        assertThat(facade.link(member.id(), "kakao", "fresh-code", freshState, verifier).providers()).containsExactly("kakao");
        assertThat(facade.link(member.id(), "kakao", "code", state, verifier).providers()).containsExactly("kakao");
        assertThatThrownBy(() -> facade.link(member.id(), "kakao", "code", state, verifier)).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_STATE_INVALID);
        verifyNoInteractions(refresh);
        when(states.consume(anyString(), eq(SocialProvider.KAKAO), anyString())).thenReturn(true);
        socialLogin.login("kakao", new SocialLoginCommand("code", state, verifier, null));
        verify(refresh).saveNewFamily(anyString(), eq(member.id()), anyString(), any(), any());
        assertThat(passwordLogin.authenticate(passwordLogin.findByEmail(member.email()), "password123").id()).isEqualTo(member.id());
        assertThat(members.findById(member.id())).contains(member);
        assertThat(memberRows.count()).isEqualTo(memberCount);
        assertThat(agreements.count()).isEqualTo(agreementCount);
        assertThat(identities.findProviders(member.id())).containsExactly(SocialProvider.KAKAO);
    }

    @Test
    void wrongPasswordAndOtherMemberProofNeverReachProvider() {
        Member member = customer();
        when(attempts.record(eq(AttemptKind.LOGIN), eq(member.email()), any())).thenReturn(new LoginAttemptOutcome(1, Duration.ofMinutes(15)));
        assertThatThrownBy(() -> facade.authorize(member.id(), "kakao", "wrong")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_REAUTH_FAILED);
        assertThatThrownBy(() -> facade.link(member.id(), "kakao", "code", "s".repeat(43), "v".repeat(43))).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_STATE_INVALID);
        verify(provider, never()).authenticate(any(), any(), any());
        assertThat(identities.findProviders(member.id())).isEmpty();
    }

    @Test
    void sameMemberConcurrentSamePairIsIdempotentAndDifferentSubjectConflicts() throws Exception {
        Member member = customer();
        SocialProfile profile = profile(unique());
        assertThat(race(member.id(), profile, member.id(), profile)).containsExactlyInAnyOrder(true, true);
        assertThat(identities.findProviders(member.id())).containsExactly(SocialProvider.KAKAO);
        assertThatThrownBy(() -> accounts.link(member.id(), profile(unique()))).satisfies(this::assertConflict);
    }

    @Test
    void crossMemberConcurrentSubjectAndSameMemberConcurrentProviderHaveSingleWinner() throws Exception {
        Member first = customer(), second = customer();
        SocialProfile profile = profile(unique());
        assertThat(race(first.id(), profile, second.id(), profile)).containsExactlyInAnyOrder(true, false);
        assertThat(identities.findMemberId(profile.provider(), profile.subject()).orElseThrow()).isIn(first.id(), second.id());
        Member third = customer();
        assertThat(race(third.id(), profile(unique()), third.id(), profile(unique()))).containsExactlyInAnyOrder(true, false);
        assertThat(identities.findProviders(third.id())).containsExactly(SocialProvider.KAKAO);
    }

    private List<Boolean> race(long firstId, SocialProfile first, long secondId, SocialProfile second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = pool.submit(() -> after(start, firstId, first));
            Future<Boolean> two = pool.submit(() -> after(start, secondId, second));
            start.countDown();
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }

    private boolean after(CountDownLatch start, long id, SocialProfile profile) throws Exception {
        start.await();
        try { accounts.link(id, profile); return true; }
        catch (RuntimeException conflict) { assertConflict(conflict); return false; }
    }

    private void assertConflict(Throwable conflict) {
        if (conflict instanceof MemberException member) {
            assertThat(member.getErrorCode()).isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
            return;
        }
        // Production constraint-name translation is verified against PostgreSQL in SocialIdentityPersistenceTest.
        assertThat(conflict).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Throwable cause = ((org.springframework.dao.DataIntegrityViolationException) conflict).getMostSpecificCause();
        assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23505");
        assertThat(cause.getMessage().toLowerCase(java.util.Locale.ROOT)).contains("uk_social_identity_");
    }

    private Member customer() {
        return members.save(Member.signUp(System.nanoTime(), unique() + "@example.com", encoder.encode("password123"), "Customer", null));
    }

    private SocialProfile profile(String subject) {
        return new SocialProfile(SocialProvider.KAKAO, subject, "ignored@example.com", false, "Ignored");
    }

    private String unique() { return UUID.randomUUID().toString(); }
}
