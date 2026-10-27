package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAuthorizationResponse;
import com.commercefoundry.domainlayer.member.application.command.SocialLoginCommand;

public interface SocialAuthWebUseCase {
    SocialAuthorizationResponse authorize(String provider);
    AuthTokenResponse login(String provider, SocialLoginCommand command);
}