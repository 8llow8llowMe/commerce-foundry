package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.SocialProfile;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;

public interface SocialProviderPort {
    void requireEnabled(SocialProvider provider);
    String authorizeUrl(SocialProvider provider, String state);
    SocialProfile authenticate(SocialProvider provider, String code, String state);
}