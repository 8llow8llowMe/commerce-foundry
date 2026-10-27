package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAuthorizationResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AuthWebPresenter;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.SocialWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.SocialLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.SocialMemberProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.SocialAuthorizationProcessor;
import com.commercefoundry.domainlayer.member.domain.model.SocialProfile;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialAuthWebFacade implements SocialAuthWebUseCase {
    private final SocialAuthorizationProcessor authorization;
    private final SocialMemberProcessor members;
    private final AuthTokenProcessor tokens;
    private final AuthWebPresenter tokenPresenter;
    private final SocialWebPresenter socialPresenter;

    @Override
    public SocialAuthorizationResponse authorize(String provider) {
        return socialPresenter.toResponse(authorization.authorize(provider));
    }

    /** provider HTTP · Redis · BCrypt는 트랜잭션 밖, 조회와 등록만 Processor 트랜잭션으로 좁힌다. */
    @Override
    public AuthTokenResponse login(String provider, SocialLoginCommand command) {
        SocialProfile profile = authorization.authenticate(provider, command.code(), command.state(), command.verifier());
        Member member = members.find(profile).orElseGet(() -> register(profile, command));
        return tokenPresenter.toTokenResponse(tokens.issueForLogin(member));
    }

    private Member register(SocialProfile profile, SocialLoginCommand command) {
        try {
            return members.register(profile, command.agreements(), members.hashRandomPassword());
        } catch (MemberException conflict) {
            if (conflict.getErrorCode() != MemberErrorCode.DUPLICATE_EMAIL
                && conflict.getErrorCode() != MemberErrorCode.SOCIAL_IDENTITY_CONFLICT) {
                throw conflict;
            }
            // 실패한 등록 트랜잭션이 프록시 밖에서 끝난 뒤 새 트랜잭션으로 동일 subject만 재조회한다.
            return members.find(profile).orElseThrow(() -> conflict);
        }
    }
}