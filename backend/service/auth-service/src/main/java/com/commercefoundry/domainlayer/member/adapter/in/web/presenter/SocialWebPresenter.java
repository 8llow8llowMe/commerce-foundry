package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAuthorizationResponse;
import com.commercefoundry.domainlayer.member.application.info.SocialAuthorizationInfo;
import org.springframework.stereotype.Component;

@Component
public class SocialWebPresenter {
    public SocialAuthorizationResponse toResponse(SocialAuthorizationInfo info) {
        return new SocialAuthorizationResponse(info.authorizeUrl(), info.state(), info.verifier());
    }
}