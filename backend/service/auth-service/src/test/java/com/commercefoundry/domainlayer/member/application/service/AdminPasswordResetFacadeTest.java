package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    static Stream<RuntimeException> contentions() {
        return Stream.of(new PessimisticLockingFailureException("busy"), new QueryTimeoutException("busy"), new TransactionTimedOutException("busy"));
    }
}
