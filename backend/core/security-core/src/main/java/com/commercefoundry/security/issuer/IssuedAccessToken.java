package com.commercefoundry.security.issuer;

import java.time.Instant;

/**
 * 발급한 access 토큰.
 *
 * @param tokenValue 응답으로 내보낼 JWT 문자열. 로그에 남기지 않는다
 * @param tokenId    {@code jti}. 로그아웃 블랙리스트(#4)의 키
 * @param expiresAt  {@code exp} (초 단위로 잘린 값)
 */
public record IssuedAccessToken(
    String tokenValue,
    String tokenId,
    Instant expiresAt
) {

    /** 토큰 원문이 로그로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "IssuedAccessToken[tokenValue=****, tokenId=" + tokenId + ", expiresAt=" + expiresAt + "]";
    }
}
