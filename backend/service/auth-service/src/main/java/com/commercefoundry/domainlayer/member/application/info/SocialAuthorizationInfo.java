package com.commercefoundry.domainlayer.member.application.info;

public record SocialAuthorizationInfo(String authorizeUrl, String state, String verifier) {
    @Override
    public String toString() {
        return "SocialAuthorizationInfo[redacted]";
    }
}