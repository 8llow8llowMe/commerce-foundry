package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.global.properties.AdminPasswordResetProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminPasswordResetProcessorTest {
    final AdminPasswordResetTokenPort tokens = mock(AdminPasswordResetTokenPort.class);
    final AdminPasswordResetMailPort mail = mock(AdminPasswordResetMailPort.class);
    final AdminPasswordResetProcessor processor = new AdminPasswordResetProcessor(tokens, mail,
        new AdminPasswordResetProperties(true, "https://admin.example.com/reset", "smtp.example.com", 587,
            "fixture-user", "fixture-password", "no-reply@example.com", Duration.ofMinutes(15)));

    @Test
    void sendLimitsBothActorAndTargetBeforeRecipientLookup() {
        processor.limitSend(7, 9);
        verify(tokens).limit("send-global", 100, Duration.ofMinutes(15));
        verify(tokens).limit("send-actor:7", 10, Duration.ofMinutes(15));
        verify(tokens).limit("send-target:9", 3, Duration.ofMinutes(15));
    }

    @Test
    void exhaustedClientCannotBlockAnotherClientAndRejectionDoesNotConsume() {
        String blocked = "a".repeat(22);
        String allowed = "b".repeat(22);
        String token = "t".repeat(43);
        doThrow(new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_LIMITED)).when(tokens)
            .limit("use-client:" + blocked, 10, Duration.ofMinutes(1));
        assertThatThrownBy(() -> processor.consume(token, blocked)).isInstanceOf(MemberException.class);
        verify(tokens, never()).consume(anyString());
        AdminPasswordResetGrant grant = new AdminPasswordResetGrant(7, 2);
        when(tokens.consume(AdminPasswordResetProcessor.fingerprint(token))).thenReturn(Optional.of(grant));
        assertThat(processor.consume(token, allowed)).isEqualTo(grant);
        verify(tokens).limit("use-client:" + allowed, 10, Duration.ofMinutes(1));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "bad-subject", "../../untrusted", "aaaaaaaaaaaaaaaaaaaaaaa"})
    void missingOrInvalidClientSubjectSkipsLimitAndStillRequiresAStoredToken(String subject) {
        String token = "t".repeat(43);
        when(tokens.consume(AdminPasswordResetProcessor.fingerprint(token))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> processor.consume(token, subject)).isInstanceOfSatisfying(MemberException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_PASSWORD_RESET_INVALID));
        verify(tokens, never()).limit(anyString(), anyInt(), any());
        verify(tokens).consume(AdminPasswordResetProcessor.fingerprint(token));
    }

    @Test
    void malformedTokenWithoutClientSubjectDoesNotReachStoreOrBcrypt() {
        assertThatThrownBy(() -> processor.consume("malformed", null)).isInstanceOfSatisfying(MemberException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_PASSWORD_RESET_INVALID));
        verify(tokens, never()).consume(anyString());
        verify(tokens, never()).limit(anyString(), anyInt(), any());
    }
}
