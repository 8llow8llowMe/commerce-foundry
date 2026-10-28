package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.*;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.*;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAccountWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;

@Service
@RequiredArgsConstructor
public class SocialAccountWebFacade implements SocialAccountWebUseCase {
    private final SocialAccountReauthenticationProcessor reauthentication;
    private final SocialAuthorizationProcessor authorization;
    private final SocialAccountProcessor accounts;
    private final SocialAccountWebPresenter presenter;
    private final SocialWebPresenter authorizationPresenter;

    @Override
    public SocialAccountsResponse list(long memberId) { return presenter.toResponse(accounts.list(memberId)); }

    @Override
    public SocialAuthorizationResponse authorize(long memberId, String provider, String currentPassword) {
        reauthentication.authenticate(memberId, currentPassword);
        return authorizationPresenter.toResponse(authorization.authorizeLink(memberId, provider));
    }

    // Redis and provider HTTP finish outside the short transaction in accounts.link.
    @Override
    public SocialAccountsResponse link(long memberId, String provider, String code, String state, String verifier) {
        var profile = authorization.authenticateLink(memberId, provider, code, state, verifier);
        try {
            return presenter.toResponse(accounts.link(memberId, profile));
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException conflict) {
            // The processor proxy has rolled back before translating lock failures.
            throw new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
        }
    }
}
