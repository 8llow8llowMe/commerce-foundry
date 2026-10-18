package com.commercefoundry.domainlayer.member.application.info;

/**
 * 로그인 · 재발급으로 내주는 토큰 한 쌍.
 *
 * @param accessToken           access 토큰(JWT). 로그에 남기지 않는다
 * @param accessTokenExpiresIn  access 토큰의 남은 수명(초)
 * @param refreshToken          refresh 토큰 원문(불투명 base64url 43자). 저장소에는 지문만 있다. 로그에 남기지 않는다
 * @param refreshTokenExpiresIn refresh 토큰의 남은 수명(초)
 */
public record AuthTokenInfo(String accessToken, long accessTokenExpiresIn, String refreshToken, long refreshTokenExpiresIn) {

    /** 토큰 원문을 가린다. */
    @Override
    public String toString() {
        return "AuthTokenInfo[accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn
            + ", refreshToken=****, refreshTokenExpiresIn=" + refreshTokenExpiresIn + "]";
    }
}
