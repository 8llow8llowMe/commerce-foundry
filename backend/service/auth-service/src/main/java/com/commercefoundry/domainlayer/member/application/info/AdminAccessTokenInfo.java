package com.commercefoundry.domainlayer.member.application.info;

/**
 * 관리자 로그인으로 내주는 access 토큰 (#6). refresh 토큰은 없다 — 관리자 세션은 access 수명까지다.
 *
 * @param accessToken          access 토큰(JWT, role=ADMIN). 로그에 남기지 않는다
 * @param accessTokenExpiresIn access 토큰의 남은 수명(초)
 */
public record AdminAccessTokenInfo(String accessToken, long accessTokenExpiresIn) {

    /** 토큰 원문을 가린다. */
    @Override
    public String toString() {
        return "AdminAccessTokenInfo[accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn + "]";
    }
}
