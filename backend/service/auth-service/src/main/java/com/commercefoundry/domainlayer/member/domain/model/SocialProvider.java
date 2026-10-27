package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Locale;

public enum SocialProvider {
    KAKAO, NAVER;

    public static SocialProvider parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("지원하지 않는 소셜 제공자");
        }
    }
}