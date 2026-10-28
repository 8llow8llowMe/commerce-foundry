package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.*;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialAccountReauthenticationProcessor {
    private final MemberLoginProcessor login;
    private final LoginAttemptCounterPort attempts;
    private final LoginAttemptPolicy policy;
    private final AuthLoginGuardProperties properties;

    // Count before BCrypt. Failed and over-limit attempts never reset or extend the fixed window.
    public void authenticate(long memberId, String password) {
        if (!properties.enabled()) {
            throw new MemberException(MemberErrorCode.SOCIAL_REAUTH_UNAVAILABLE);
        }
        Member member = login.findCustomer(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.SOCIAL_REAUTH_FAILED));
        LoginAttemptOutcome outcome = attempts.record(AttemptKind.LOGIN, member.email(), policy.window());
        if (outcome.exceeds(policy.maxAttempts())) {
            throw new MemberException(MemberErrorCode.SOCIAL_REAUTH_LIMITED);
        }
        boolean acceptable = password != null && password.length() <= 64;
        try {
            login.authenticate(java.util.Optional.of(member), acceptable ? password : "oversized-password-placeholder");
            if (!acceptable) {
                throw new MemberException(MemberErrorCode.SOCIAL_REAUTH_FAILED);
            }
        } catch (MemberException failed) {
            throw new MemberException(MemberErrorCode.SOCIAL_REAUTH_FAILED);
        }
        attempts.reset(AttemptKind.LOGIN, member.email());
    }
}
