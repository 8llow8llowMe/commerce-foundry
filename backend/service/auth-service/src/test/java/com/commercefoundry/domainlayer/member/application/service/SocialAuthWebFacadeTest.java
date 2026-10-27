package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.*;
import com.commercefoundry.domainlayer.member.application.command.SocialLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.service.processor.*;
import com.commercefoundry.domainlayer.member.domain.model.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SocialAuthWebFacadeTest {
    private final SocialAuthorizationProcessor authorization = mock(SocialAuthorizationProcessor.class);
    private final SocialMemberProcessor members = mock(SocialMemberProcessor.class);
    private final AuthTokenProcessor tokens = mock(AuthTokenProcessor.class);
    private final SocialAuthWebFacade facade = new SocialAuthWebFacade(authorization, members, tokens, new AuthWebPresenter(), new SocialWebPresenter());
    private final SocialProfile profile = new SocialProfile(SocialProvider.KAKAO, "subject", null, false, null);
    private final SocialLoginCommand command = new SocialLoginCommand("code", "state", "verifier", null);
    private final Member customer = Member.signUp(42, "customer@example.com", "hash", "고객", null);

    @Test
    void existingIdentityIgnoresMissingEmailAndUsesExistingTokenFlow() {
        when(authorization.authenticate("kakao", "code", "state", "verifier")).thenReturn(profile);
        when(members.find(profile)).thenReturn(Optional.of(customer));
        when(tokens.issueForLogin(customer)).thenReturn(new AuthTokenInfo("access", 60, "refresh", 120));
        assertThat(facade.login("kakao", command).accessToken()).isEqualTo("access");
        verify(members, never()).hashRandomPassword();
        verify(members, never()).register(any(), any(), any());
    }

    @Test
    void newIdentityRegistersThenIssuesExistingTokenResponse() {
        var agreements = new SignUpAgreements(true, true, false);
        var newCommand = new SocialLoginCommand("code", "state", "verifier", agreements);
        var newProfile = new SocialProfile(SocialProvider.KAKAO, "new-subject", "customer@example.com", true, "고객");
        when(authorization.authenticate("kakao", "code", "state", "verifier")).thenReturn(newProfile);
        when(members.find(newProfile)).thenReturn(Optional.empty());
        when(members.hashRandomPassword()).thenReturn("random-hash");
        when(members.register(newProfile, agreements, "random-hash")).thenReturn(customer);
        when(tokens.issueForLogin(customer)).thenReturn(new AuthTokenInfo("access", 60, "refresh", 120));
        var response = facade.login("kakao", newCommand);
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        var order = inOrder(members, tokens);
        order.verify(members).find(newProfile);
        order.verify(members).hashRandomPassword();
        order.verify(members).register(newProfile, agreements, "random-hash");
        order.verify(tokens).issueForLogin(customer);
    }

    @Test
    void identityConflictRequeriesAfterFailedRegistration() {
        when(authorization.authenticate("kakao", "code", "state", "verifier")).thenReturn(profile);
        when(members.find(profile)).thenReturn(Optional.empty(), Optional.of(customer));
        when(members.hashRandomPassword()).thenReturn("hash");
        when(members.register(profile, null, "hash")).thenThrow(new MemberException(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT));
        when(tokens.issueForLogin(customer)).thenReturn(new AuthTokenInfo("access", 60, "refresh", 120));
        facade.login("kakao", command);
        var order = inOrder(members, tokens);
        order.verify(members).find(profile);
        order.verify(members).hashRandomPassword();
        order.verify(members).register(profile, null, "hash");
        order.verify(members).find(profile);
        order.verify(tokens).issueForLogin(customer);
    }

    @Test
    void emailConflictNeverLinksAnotherIdentity() {
        when(authorization.authenticate("kakao", "code", "state", "verifier")).thenReturn(profile);
        when(members.find(profile)).thenReturn(Optional.empty());
        when(members.hashRandomPassword()).thenReturn("hash");
        when(members.register(profile, null, "hash")).thenThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));
        assertThatThrownBy(() -> facade.login("kakao", command)).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
        verifyNoInteractions(tokens);
    }
}