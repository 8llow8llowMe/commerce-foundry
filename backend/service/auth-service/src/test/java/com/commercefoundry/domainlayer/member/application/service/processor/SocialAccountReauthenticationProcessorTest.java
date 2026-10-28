package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.*;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SocialAccountReauthenticationProcessorTest {
    private final MemberLoginProcessor login = mock(MemberLoginProcessor.class);
    private final LoginAttemptCounterPort attempts = mock(LoginAttemptCounterPort.class);
    private final LoginAttemptPolicy policy = new LoginAttemptPolicy(5, Duration.ofMinutes(15));
    private final Member member = Member.signUp(10, "customer@example.com", "hash", "Customer", null);

    private SocialAccountReauthenticationProcessor processor(boolean enabled) {
        return new SocialAccountReauthenticationProcessor(login, attempts, policy, new AuthLoginGuardProperties(enabled, 5, policy.window(),
            enabled ? java.util.Base64.getEncoder().encodeToString(new byte[32]) : null));
    }

    @Test
    void countPrecedesPasswordAndResetOnlyFollowsSuccess() {
        when(login.findCustomer(10)).thenReturn(Optional.of(member));
        when(attempts.record(AttemptKind.LOGIN, member.email(), policy.window())).thenReturn(new LoginAttemptOutcome(5, Duration.ofSeconds(30)));
        when(login.authenticate(Optional.of(member), "password")).thenReturn(member);
        processor(true).authenticate(10, "password");
        var order = inOrder(login, attempts);
        order.verify(attempts).record(AttemptKind.LOGIN, member.email(), policy.window());
        order.verify(login).authenticate(Optional.of(member), "password");
        order.verify(attempts).reset(AttemptKind.LOGIN, member.email());
    }

    @Test
    void wrongAndOversizedPasswordNeverReset() {
        when(login.findCustomer(10)).thenReturn(Optional.of(member));
        when(attempts.record(any(), anyString(), any())).thenReturn(new LoginAttemptOutcome(1, Duration.ofMinutes(15)));
        when(login.authenticate(any(), anyString())).thenThrow(new MemberException(MemberErrorCode.LOGIN_FAILED));
        for (String password : java.util.List.of("wrong", "x".repeat(65))) {
            assertThatThrownBy(() -> processor(true).authenticate(10, password)).isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_REAUTH_FAILED);
        }
        verify(login, never()).authenticate(any(), eq("x".repeat(65)));
        verify(attempts, never()).reset(any(), anyString());
    }

    @Test
    void overLimitDoesNotRunBcryptOrReset() {
        when(login.findCustomer(10)).thenReturn(Optional.of(member));
        when(attempts.record(any(), anyString(), any())).thenReturn(new LoginAttemptOutcome(6, Duration.ofSeconds(30)));
        assertThatThrownBy(() -> processor(true).authenticate(10, "password")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_REAUTH_LIMITED);
        verify(login, never()).authenticate(any(), anyString());
        verify(attempts, never()).reset(any(), anyString());
    }

    @Test
    void disabledGuardAndStoreOutageFailClosed() {
        assertThatThrownBy(() -> processor(false).authenticate(10, "password")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_REAUTH_UNAVAILABLE);
        verifyNoInteractions(login, attempts);
        when(login.findCustomer(10)).thenReturn(Optional.of(member));
        when(attempts.record(any(), anyString(), any())).thenThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE));
        assertThatThrownBy(() -> processor(true).authenticate(10, "password")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        verify(login, never()).authenticate(any(), anyString());
    }
}
