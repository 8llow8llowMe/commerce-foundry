package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAccountsResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAuthorizationResponse;

public interface SocialAccountWebUseCase {
    SocialAccountsResponse list(long memberId);
    SocialAuthorizationResponse authorize(long memberId, String provider, String currentPassword);
    SocialAccountsResponse link(long memberId, String provider, String code, String state, String verifier);
}
