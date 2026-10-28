package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.*;
import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.service.processor.*;
import com.commercefoundry.domainlayer.member.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionTimedOutException;

class SocialAccountWebFacadeTest {
    @Test
    void lockTimeoutAndDeadlockBecomeRetryableConflictOutsideProcessorTransaction() {
        var authorization = mock(SocialAuthorizationProcessor.class);
        var accounts = mock(SocialAccountProcessor.class);
        var profile = new SocialProfile(SocialProvider.KAKAO, "subject", null, false, null);
        var facade = new SocialAccountWebFacade(mock(SocialAccountReauthenticationProcessor.class), authorization, accounts,
            new SocialAccountWebPresenter(), new SocialWebPresenter());
        when(authorization.authenticateLink(10, "kakao", "code", "state", "verifier")).thenReturn(profile);
        for (RuntimeException failure : java.util.List.of(new QueryTimeoutException("timeout"), new TransactionTimedOutException("timeout"),
            new PessimisticLockingFailureException("lock"))) {
            doThrow(failure).when(accounts).link(10, profile);
            assertThatThrownBy(() -> facade.link(10, "kakao", "code", "state", "verifier")).isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
        }
    }
}
