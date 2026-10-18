package com.commercefoundry.domainlayer.member.domain.model;

import java.util.regex.Pattern;

/**
 * 발급한 refresh 토큰의 모양 — {@code OpaqueTokenGenerator#generate} 가 만드는 base64url 무패딩 43자(32바이트).
 *
 * <p>지문(SHA-256)을 계산하거나 저장소를 부르기 전에 이 모양부터 본다 — 아무 길이의 문자열을 해시 · 조회하지 않게 (#38 보안 메모).
 */
public final class RefreshTokenFormat {

    public static final int LENGTH = 43;

    private static final Pattern BASE64URL_43 = Pattern.compile("[A-Za-z0-9_-]{" + LENGTH + "}");

    private RefreshTokenFormat() {
    }

    /** null · 빈 값 · 다른 길이 · base64url 밖 문자(패딩 {@code =} 포함)는 false. */
    public static boolean isWellFormed(String refreshToken) {
        return refreshToken != null && refreshToken.length() == LENGTH && BASE64URL_43.matcher(refreshToken).matches();
    }
}
