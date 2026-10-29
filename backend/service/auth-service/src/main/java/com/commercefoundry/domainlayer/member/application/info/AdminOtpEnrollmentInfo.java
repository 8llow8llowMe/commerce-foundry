package com.commercefoundry.domainlayer.member.application.info;

import java.time.Instant;

/**
 * 시작한 OTP 등록 (#238 PR-A2). 비밀은 인증 앱에 넣을 때 한 번만 내려준다.
 *
 * @param secret     Base32(대문자 · 패딩 없음) 비밀. 로그에 남기지 않는다
 * @param otpauthUri 인증 앱 등록 URI(QR 은 FE 가 그린다). 비밀이 들어 있다
 * @param expiresAt  이 등록을 확인해야 하는 기한
 */
public record AdminOtpEnrollmentInfo(String secret, String otpauthUri, Instant expiresAt) {

    /** 비밀 · URI 를 가린다. */
    @Override
    public String toString() {
        return "AdminOtpEnrollmentInfo[secret=****, otpauthUri=****, expiresAt=" + expiresAt + "]";
    }
}
