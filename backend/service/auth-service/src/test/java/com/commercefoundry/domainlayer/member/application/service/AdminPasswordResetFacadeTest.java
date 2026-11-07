package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.properties.AdminPasswordResetProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

class AdminPasswordResetFacadeTest {

    @ParameterizedTest
    @MethodSource("contentions")
    void contentionAfterGrantConsumptionRequiresNewLinkAndRecordsFailure(RuntimeException contention) {
        var reset = mock(AdminPasswordResetProcessor.class);
        var accounts = mock(AdminPasswordResetAccountProcessor.class);
        var passwords = mock(AdminAccountProcessor.class);
        var audit = mock(AdminAuditProcessor.class);
        var grant = new AdminPasswordResetGrant(99L, 0L);
        when(reset.consume("token", "subject")).thenReturn(grant);
        when(passwords.hashPassword("new-password-1")).thenReturn("hash");
        doThrow(contention).when(accounts).reset(grant, "hash");
        var facade = new AdminPasswordResetFacade(reset, accounts, passwords, audit);

        assertThatThrownBy(() -> facade.reset("token", "new-password-1", "subject")).isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_PASSWORD_RESET_BUSY);
        verify(reset).consume("token", "subject");
        verify(audit).adminPasswordResetUsed(99L, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateTokenStoreFailureIsNeutralAndRecordsFailureExactlyOnce(boolean smtpThenDiscardFailure) {
        var tokens = mock(AdminPasswordResetTokenPort.class);
        var mail = mock(AdminPasswordResetMailPort.class);
        var properties = mock(AdminPasswordResetProperties.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.tokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(properties.resetUrl()).thenReturn("https://admin.example.com/reset");
        var reset = new AdminPasswordResetProcessor(tokens, mail, properties);
        var accounts = mock(AdminPasswordResetAccountProcessor.class);
        var passwords = mock(AdminAccountProcessor.class);
        var audit = mock(AdminAuditProcessor.class);
        var recipient = mock(Member.class);
        when(recipient.id()).thenReturn(99L);
        when(recipient.version()).thenReturn(0L);
        when(recipient.email()).thenReturn("reset-target@example.com");
        when(accounts.recipient(1L, 99L)).thenReturn(Optional.of(recipient));
        var unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        if (smtpThenDiscardFailure) {
            doThrow(new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE)).when(mail).send(anyString(), anyString());
            doThrow(unavailable).when(tokens).discard(anyString());
        } else {
            doThrow(unavailable).when(tokens).save(anyString(), any(AdminPasswordResetGrant.class), any(Duration.class));
        }
        var facade = new AdminPasswordResetFacade(reset, accounts, passwords, audit);

        assertThatCode(() -> facade.send(1L, 99L)).doesNotThrowAnyException();

        verify(accounts).recipient(1L, 99L);
        verifyNoMoreInteractions(accounts);
        verify(tokens).save(anyString(), eq(new AdminPasswordResetGrant(99L, 0L)), eq(Duration.ofMinutes(15)));
        if (smtpThenDiscardFailure) {
            verify(mail).send(eq("reset-target@example.com"), anyString());
            verify(tokens).discard(anyString());
        } else {
            verifyNoInteractions(mail);
            verify(tokens, never()).discard(anyString());
        }
        verify(audit).adminPasswordResetLinkSent(1L, 99L, false);
        verifyNoMoreInteractions(audit);
        verifyNoInteractions(passwords);
    }

    @ParameterizedTest
    @ValueSource(longs = {99L, Long.MAX_VALUE})
    void limiterStoreFailureIsPropagatedBeforeRecipientLookupAndAuditedOnce(long target) {
        var reset = mock(AdminPasswordResetProcessor.class);
        var accounts = mock(AdminPasswordResetAccountProcessor.class);
        var passwords = mock(AdminAccountProcessor.class);
        var audit = mock(AdminAuditProcessor.class);
        doThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).when(reset).limitSend(1L, target);
        var facade = new AdminPasswordResetFacade(reset, accounts, passwords, audit);

        assertThatThrownBy(() -> facade.send(1L, target)).isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);

        verify(reset).limitSend(1L, target);
        verifyNoMoreInteractions(reset);
        verifyNoInteractions(accounts, passwords);
        verify(audit).adminPasswordResetLinkSent(1L, target, false);
        verifyNoMoreInteractions(audit);
    }

    static Stream<RuntimeException> contentions() {
        return Stream.of(new PessimisticLockingFailureException("busy"), new QueryTimeoutException("busy"), new TransactionTimedOutException("busy"));
    }
}
