package com.commercefoundry.domainlayer.member.application.info;

/**
 * 관리자 로그인 1단계가 OTP 등록 관리자에게 내주는 2단계 챌린지 (#238 PR-B). access 토큰 대신 준다.
 *
 * @param challengeToken 챌린지 원문(base64url 43자). 응답에만 싣고 저장 · 로그에 남기지 않는다 — 저장소에는 해시만 있다
 * @param expiresIn      챌린지의 수명(초)
 */
public record AdminLoginChallengeInfo(String challengeToken, long expiresIn) {

    /** 챌린지 원문을 가린다. */
    @Override
    public String toString() {
        return "AdminLoginChallengeInfo[challengeToken=****, expiresIn=" + expiresIn + "]";
    }
}
