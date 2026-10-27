package com.commercefoundry.domainlayer.member.domain.model;

/** 제공자의 subject만 계정 식별자다. 이메일은 신규 가입 정보이며 기존 계정 연결에 쓰지 않는다. */
public record SocialProfile(SocialProvider provider, String subject, String email, boolean usableEmail, String name) {
    public SocialProfile {
        if (provider == null || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("소셜 식별자가 올바르지 않다");
        }
    }

    @Override
    public String toString() {
        return "SocialProfile[provider=" + provider + "]";
    }
}